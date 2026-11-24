package net.syncmaterial.syncmaterial.network;

import java.util.UUID;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoundationDeliveryTrackerTest {
    private static final String PROJECT = "project";

    private final FoundationDeliveryTracker tracker = new FoundationDeliveryTracker();
    private final UUID player = UUID.randomUUID();

    @Test
    void olderProjectResponseCannotReplaceNewerDelivery() {
        var older = tracker.beginProject(player, 1, "project", false);
        var newer = tracker.beginProject(player, 1, "project", false);
        assertTrue(tracker.acceptProject(newer));
        assertFalse(tracker.acceptProject(older));
        assertFalse(tracker.acceptProject(newer));
    }

    @Test
    void laterOpeningWinsEvenAcrossDifferentProjects() {
        var older = tracker.beginProject(player, 1, "before", true);
        var newer = tracker.beginProject(player, 1, "after", true);
        assertFalse(tracker.acceptProject(older));
        assertTrue(tracker.acceptProject(newer));
    }

    @Test
    void disconnectedConnectionCannotPublishIntoNewSession() {
        var old = tracker.beginProject(player, 1, "project", false);
        tracker.disconnected(player);
        tracker.beginProject(player, 2, "project", false);
        assertFalse(tracker.acceptProject(old));
        assertFalse(tracker.acceptConfiguration(player, 2, "S:project", -1));
    }

    @Test
    void backgroundUpdateCannotOvertakeInitialOpeningResponse() {
        var opening = tracker.beginProject(player, 1, "project", true);
        var background = tracker.beginProject(player, 1, "project", false);
        assertFalse(tracker.acceptProject(background));
        assertTrue(tracker.acceptProject(opening));
        assertTrue(tracker.acceptProject(tracker.beginProject(player, 1, "project", false)));
    }

    @Test
    void oldConfigurationCannotResetConnectionGeneration() {
        assertTrue(tracker.acceptConfiguration(player, 2, "S:one", 3));
        assertFalse(tracker.acceptConfiguration(player, 1, "S:one", 4));
        assertTrue(tracker.acceptConfiguration(player, 2, "S:one", 4));
    }

    @Test
    void configurationScopesRejectOlderSnapshotsButKeepEqualRevisionRefreshes() {
        assertTrue(tracker.acceptConfiguration(player, 1, "S:one", 3));
        assertFalse(tracker.acceptConfiguration(player, 1, "S:one", 2));
        assertTrue(tracker.acceptConfiguration(player, 1, "S:one", 3));
        assertTrue(tracker.acceptConfiguration(player, 1, "W", 1));
        assertTrue(tracker.acceptConfiguration(player, 1, "S:two", 1));
    }

    @Test
    void closingProjectClearsItsActualConfigurationScopes() {
        assertTrue(tracker.acceptConfiguration(player, 1, "S:LIST:project", 5));
        assertTrue(tracker.acceptConfiguration(player, 1, "S:LIST_WAREHOUSE_REFS:project", 5));
        assertTrue(tracker.acceptConfiguration(player, 1, "S:LIST:other", 5));
        tracker.forgetProject(player, 1, "project");
        assertTrue(tracker.acceptConfiguration(player, 1, "S:LIST:project", 4));
        assertTrue(tracker.acceptConfiguration(player, 1, "S:LIST_WAREHOUSE_REFS:project", 4));
        assertFalse(tracker.acceptConfiguration(player, 1, "S:LIST:other", 4));
    }

    @Test
    void warehouseUnsubscribeInvalidatesPendingHighlights() {
        tracker.subscribeWarehouse(player, 1, "project", true);
        var pending = tracker.beginWarehouse(player, 1, "minecraft:overworld");
        assertTrue(tracker.acceptWarehouse(pending));
        tracker.subscribeWarehouse(player, 1, "project", false);
        assertFalse(tracker.acceptWarehouse(pending));
        assertFalse(tracker.hasWarehouseSubscription(player, 1, "project"));
    }

    @Test
    void warehouseLatestDimensionRequestReplacesOldOne() {
        tracker.subscribeWarehouse(player, 1, "project", true);
        var before = tracker.beginWarehouse(player, 1, "minecraft:overworld");
        var after = tracker.beginWarehouse(player, 1, "minecraft:the_nether");
        assertFalse(tracker.acceptWarehouse(before));
        assertTrue(tracker.acceptWarehouse(after));
    }

    @Test
    void closingProjectRejectsPendingProjectAndWarehouseResults() {
        var pending = tracker.beginProject(player, 1, "project", false);
        tracker.subscribeWarehouse(player, 1, "project", true);
        var highlights = tracker.beginWarehouse(player, 1, "minecraft:overworld");
        tracker.forgetProject(player, 1, "project");
        assertFalse(tracker.acceptProject(pending));
        assertFalse(tracker.acceptWarehouse(highlights));
        assertTrue(tracker.acceptProject(tracker.beginProject(player, 1, "project", true)));
    }

    @Test
    void warehouseRevisionSkipsDeliveredAndPendingDuplicates() {
        var first = tracker.beginWarehouseRevision(view(1, 1, 1), 5, false);
        assertNotNull(first);
        assertTrue(tracker.acceptWarehouseRevision(first));
        assertTrue(tracker.acceptWarehouseRevision(first));
        tracker.completeWarehouseRevision(first);
        assertFalse(tracker.acceptWarehouseRevision(first));
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 5, false));
        var initial = tracker.beginWarehouseRevision(view(1, 1, 1), 5, true);
        assertNotNull(initial);
        assertTrue(tracker.acceptWarehouseRevision(initial));
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 5, true));
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 5, false));
    }

    @Test
    void newerWarehouseRevisionSupersedesPendingAndRejectsOldCompletion() {
        var older = tracker.beginWarehouseRevision(view(1, 1, 1), 4, false);
        assertNotNull(older);
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 3, true));
        var newer = tracker.beginWarehouseRevision(view(1, 1, 1), 5, false);
        assertNotNull(newer);
        assertFalse(tracker.acceptWarehouseRevision(older));
        tracker.failWarehouseRevision(older);
        assertTrue(tracker.acceptWarehouseRevision(newer));
        tracker.completeWarehouseRevision(older);
        assertTrue(tracker.acceptWarehouseRevision(newer));
        tracker.completeWarehouseRevision(newer);
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 4, false));
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 5, false));
    }

    @Test
    void failedWarehouseRevisionCanRetryTheSameRevision() {
        var token = tracker.beginWarehouseRevision(view(1, 1, 1), 3, true);
        assertNotNull(token);
        assertTrue(tracker.acceptWarehouseRevision(token));
        tracker.failWarehouseRevision(token);
        assertFalse(tracker.acceptWarehouseRevision(token));
        tracker.completeWarehouseRevision(token);
        var retry = tracker.beginWarehouseRevision(view(1, 1, 1), 3, false);
        assertNotNull(retry);
        assertTrue(tracker.acceptWarehouseRevision(retry));
        tracker.completeWarehouseRevision(retry);
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 3, false));
    }

    @Test
    void newViewReplacesWarehouseRevisionStateAndInvalidatesOldTokens() {
        var first = tracker.beginWarehouseRevision(view(1, 1, 1), 5, true);
        assertNotNull(first);
        assertTrue(tracker.acceptWarehouseRevision(first));
        tracker.completeWarehouseRevision(first);
        var reopened = tracker.beginWarehouseRevision(view(1, 2, 2), 5, false);
        assertNotNull(reopened);
        assertFalse(tracker.acceptWarehouseRevision(first));
        assertTrue(tracker.acceptWarehouseRevision(reopened));
        tracker.completeWarehouseRevision(reopened);
        assertNull(tracker.beginWarehouseRevision(view(1, 2, 2), 5, false));
        assertNotNull(tracker.beginWarehouseRevision(view(1, 2, 2), 5, true));
        assertNull(tracker.beginWarehouseRevision(view(1, 1, 1), 6, false));
    }

    @Test
    void warehouseRevisionTokensDoNotCrossConnectionsOrDisconnect() {
        var old = tracker.beginWarehouseRevision(view(1, 1, 1), 4, true);
        assertNotNull(old);
        var current = tracker.beginWarehouseRevision(view(2, 1, 1), 4, true);
        assertNotNull(current);
        assertFalse(tracker.acceptWarehouseRevision(old));
        tracker.completeWarehouseRevision(old);
        assertTrue(tracker.acceptWarehouseRevision(current));
        assertNull(tracker.beginWarehouseRevision(view(1, 2, 2), 5, false));
        tracker.disconnected(player);
        assertFalse(tracker.acceptWarehouseRevision(current));
        tracker.completeWarehouseRevision(current);
        assertNotNull(tracker.beginWarehouseRevision(view(2, 1, 1), 4, false));
    }

    @Test
    void forgetProjectClearsOnlyItsWarehouseRevisionState() {
        var projectRevision = tracker.beginWarehouseRevision(view(1, 1, 1), 5, true);
        var otherRevision = tracker.beginWarehouseRevision(view("other", 1, 1, 1), 5, true);
        var projectToken = tracker.beginProject(player, 1, PROJECT, false);
        assertNotNull(projectRevision);
        assertNotNull(otherRevision);
        tracker.forgetProject(player, 1, PROJECT);
        assertFalse(tracker.acceptWarehouseRevision(projectRevision));
        assertFalse(tracker.acceptProject(projectToken));
        assertTrue(tracker.acceptWarehouseRevision(otherRevision));
        assertNotNull(tracker.beginWarehouseRevision(view(1, 1, 1), 5, false));
    }

    @Test
    void warehouseRevisionStateDoesNotConsumeLegacySequences() {
        var pendingProject = tracker.beginProject(player, 1, PROJECT, false);
        tracker.subscribeWarehouse(player, 1, PROJECT, true);
        var pendingWarehouse = tracker.beginWarehouse(player, 1, "minecraft:overworld");
        var revision = tracker.beginWarehouseRevision(view(1, 1, 1), 2, true);
        assertTrue(tracker.acceptProject(pendingProject));
        assertTrue(tracker.acceptWarehouse(pendingWarehouse));
        assertTrue(tracker.acceptWarehouseRevision(revision));
    }

    private ProjectViewTracker.View view(long connection, long viewId, long viewGeneration) {
        return view(PROJECT, connection, viewId, viewGeneration);
    }

    private ProjectViewTracker.View view(String projectId, long connection, long viewId, long viewGeneration) {
        return new ProjectViewTracker.View(player, connection, ProjectViewScope.project(projectId),
                viewId, viewGeneration, 0);
    }
}
