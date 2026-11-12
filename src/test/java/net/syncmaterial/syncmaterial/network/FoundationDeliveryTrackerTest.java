package net.syncmaterial.syncmaterial.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoundationDeliveryTrackerTest {
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
}
