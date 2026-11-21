package net.syncmaterial.syncmaterial.client;

import java.util.Set;
import net.syncmaterial.syncmaterial.client.ProjectInterestLeases.Change;
import net.syncmaterial.syncmaterial.client.ProjectInterestLeases.Reason;
import net.syncmaterial.syncmaterial.core.InterestKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInterestLeasesTest {
    @Test
    void activationRetainsMaterialsAndTeamProjectData() {
        var leases = new ProjectInterestLeases();
        Change change = leases.activate("project-a", 1);

        assertTrue(change.changed());
        assertEquals(Reason.ACTIVATED, change.reason());
        assertNull(change.previousProjectId());
        assertEquals(0, change.previousGeneration());
        assertEquals(0, change.previousMask());
        assertEquals("project-a", change.projectId());
        assertEquals(1, change.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, change.desiredMask());
        assertTrue(change.desires(InterestKind.MATERIALS));
        assertTrue(change.desires(InterestKind.TEAM));
        assertFalse(change.desires(InterestKind.AREA_CONFIG));
        assertFalse(change.desires(InterestKind.OUTLINES));
        assertFalse(change.desires(InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(ProjectInterestLeases.BASE_MASK, change.addedMask());
        assertEquals(0, change.removedMask());
        assertEquals(Set.of(InterestKind.MATERIALS, InterestKind.TEAM),
                change.desiredKinds());
    }

    @Test
    void duplicateActivationIsIdempotent() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        Change duplicate = leases.activate("project-a", 1);

        assertFalse(duplicate.changed());
        assertEquals(Reason.NONE, duplicate.reason());
        assertEquals("project-a", duplicate.previousProjectId());
        assertEquals(1, duplicate.previousGeneration());
        assertEquals("project-a", duplicate.projectId());
        assertEquals(1, duplicate.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, duplicate.desiredMask());
        assertEquals(0, duplicate.addedMask());
        assertEquals(0, duplicate.removedMask());
    }

    @Test
    void closingGuiKeepsProjectAndHudBaseInterest() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        Change opened = leases.openGui("project-a", 1);
        assertTrue(opened.changed());
        assertTrue(opened.desires(InterestKind.AREA_CONFIG));
        assertEquals(InterestKind.AREA_CONFIG.bit(), opened.addedMask());
        assertEquals("project-a", opened.previousProjectId());
        assertEquals(1, opened.previousGeneration());
        assertEquals(ProjectInterestLeases.BASE_MASK, opened.previousMask());
        assertTrue(leases.isGuiOpen());

        Change closed = leases.closeGui("project-a", 1);
        assertTrue(closed.changed());
        assertEquals(InterestKind.AREA_CONFIG.bit(), closed.removedMask());
        assertFalse(leases.isGuiOpen());
        assertTrue(leases.isActive());
        assertEquals("project-a", leases.projectId());
        assertEquals(1, leases.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());
        assertTrue(leases.desiredKinds().contains(InterestKind.MATERIALS));
        assertTrue(leases.desiredKinds().contains(InterestKind.TEAM));

        Change duplicateClose = leases.closeGui("project-a", 1);
        assertFalse(duplicateClose.changed());
        assertEquals(Reason.NONE, duplicateClose.reason());
        assertEquals(ProjectInterestLeases.BASE_MASK, duplicateClose.desiredMask());
    }

    @Test
    void guiAndEditorMergeAreaConfigWhileHudIsOnlyDisplay() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);

        Change editor = leases.setEditor("project-a", 1, true);
        assertFalse(editor.changed());
        assertEquals(Reason.NONE, editor.reason());
        assertTrue(leases.isEditorOpen());
        assertTrue(editor.desires(InterestKind.AREA_CONFIG));

        Change guiClosed = leases.closeGui("project-a", 1);
        assertFalse(guiClosed.changed());
        assertFalse(leases.isGuiOpen());
        assertTrue(guiClosed.desires(InterestKind.AREA_CONFIG));
        assertTrue(guiClosed.desires(InterestKind.MATERIALS));
        assertTrue(guiClosed.desires(InterestKind.TEAM));

        Change editorClosed = leases.setEditor("project-a", 1, false);
        assertTrue(editorClosed.changed());
        assertFalse(editorClosed.desires(InterestKind.AREA_CONFIG));
        assertEquals(InterestKind.AREA_CONFIG.bit(), editorClosed.removedMask());
        assertEquals(ProjectInterestLeases.BASE_MASK, editorClosed.desiredMask());
    }

    @Test
    void pickupOutlinesAndEditorLeasesAreIndependent() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        Change pickup = leases.setPickup("project-a", 1, true);
        assertTrue(pickup.changed());
        assertTrue(pickup.desires(InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(InterestKind.WAREHOUSE_CONTENTS.bit(), pickup.addedMask());
        assertTrue(leases.isPickupActive());

        Change outlines = leases.setOutlines("project-a", 1, true);
        assertTrue(outlines.changed());
        assertTrue(outlines.desires(InterestKind.OUTLINES));
        assertEquals(InterestKind.OUTLINES.bit(), outlines.addedMask());
        assertTrue(leases.isOutlinesActive());

        Change editor = leases.setEditor("project-a", 1, true);
        assertTrue(editor.changed());
        assertTrue(editor.desires(InterestKind.AREA_CONFIG));
        assertEquals(InterestKind.AREA_CONFIG.bit(), editor.addedMask());
        assertTrue(leases.isEditorOpen());
        assertEquals(InterestKind.ALL_MASK, leases.desiredMask());

        Change pickupOff = leases.setPickup("project-a", 1, false);
        assertTrue(pickupOff.changed());
        assertFalse(pickupOff.desires(InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(InterestKind.WAREHOUSE_CONTENTS.bit(), pickupOff.removedMask());
        assertTrue(pickupOff.desires(InterestKind.OUTLINES));
        assertTrue(pickupOff.desires(InterestKind.AREA_CONFIG));
        assertTrue(pickupOff.desires(InterestKind.MATERIALS));
        assertTrue(pickupOff.desires(InterestKind.TEAM));

        assertFalse(leases.setOutlines("project-a", 1, true).changed());
        assertFalse(leases.setEditor("project-a", 1, true).changed());
        assertTrue(leases.setOutlines("project-a", 1, false).changed());
        assertTrue(leases.setEditor("project-a", 1, false).changed());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());
    }

    @Test
    void hudVisibilityIsNotALeaseAndCannotEndProjectInterest() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        // The renderer owns the HUD visibility flag; this state machine exposes no HUD
        // lease, so showing or hiding the HUD cannot release project data.
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());
        assertEquals(Set.of(InterestKind.MATERIALS, InterestKind.TEAM),
                leases.desiredKinds());

        Change notOpen = leases.closeGui("project-a", 1);
        assertFalse(notOpen.changed());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());
    }

    @Test
    void staleGenerationCannotChangeOrCloseTheCurrentView() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 2);
        leases.setPickup("project-a", 2, true);

        Change staleGui = leases.openGui("project-a", 1);
        assertFalse(staleGui.changed());
        assertFalse(leases.isGuiOpen());

        Change stalePickup = leases.setPickup("project-a", 1, false);
        assertFalse(stalePickup.changed());
        assertTrue(leases.isPickupActive());

        Change staleEditor = leases.setEditor("project-a", 1, true);
        assertFalse(staleEditor.changed());
        assertFalse(leases.isEditorOpen());

        Change staleClose = leases.closeProject("project-a", 1);
        assertFalse(staleClose.changed());
        assertTrue(leases.isActive());
        assertEquals("project-a", leases.projectId());
        assertEquals(2, leases.generation());
        assertTrue(leases.desiredKinds().contains(InterestKind.WAREHOUSE_CONTENTS));

        assertTrue(leases.openGui("project-a", 2).changed());
        assertTrue(leases.closeProject("project-a", 2).changed());
        assertFalse(leases.isActive());
    }

    @Test
    void staleSameGenerationOperationsFromAnotherProjectAreIgnored() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);
        leases.setOutlines("project-a", 1, true);
        leases.setEditor("project-a", 1, true);

        Change switched = leases.activate("project-b", 1);
        assertEquals(Reason.SWITCHED, switched.reason());
        assertEquals("project-a", switched.previousProjectId());
        assertEquals(1, switched.previousGeneration());
        assertEquals(InterestKind.ALL_MASK, switched.previousMask());
        assertEquals("project-b", switched.projectId());
        assertEquals(1, switched.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, switched.desiredMask());
        assertEquals(0, switched.addedMask());
        assertEquals(InterestKind.ALL_MASK & ~ProjectInterestLeases.BASE_MASK,
                switched.removedMask());

        // A delayed operation from A carries the same numeric generation as B, but the
        // project id is part of the fencing key.
        assertFalse(leases.openGui("project-a", 1).changed());
        assertFalse(leases.isGuiOpen());
        assertFalse(leases.setPickup("project-a", 1, true).changed());
        assertFalse(leases.isPickupActive());
        assertFalse(leases.setOutlines("project-a", 1, true).changed());
        assertFalse(leases.isOutlinesActive());
        assertFalse(leases.setEditor("project-a", 1, true).changed());
        assertFalse(leases.isEditorOpen());
        assertFalse(leases.closeGui("project-a", 1).changed());
        assertFalse(leases.closeProject("project-a", 1).changed());
        assertFalse(leases.projectDeleted("project-a", 1).changed());

        assertTrue(leases.isActive());
        assertEquals("project-b", leases.projectId());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());

        assertTrue(leases.openGui("project-b", 1).changed());
        assertTrue(leases.setPickup("project-b", 1, true).changed());
        assertFalse(leases.closeGui("project-a", 1).changed());
        assertFalse(leases.setPickup("project-a", 1, false).changed());
        assertTrue(leases.isGuiOpen());
        assertTrue(leases.isPickupActive());
    }

    @Test
    void projectSwitchClosesEveryConsumerLeaseOfTheOldProject() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);
        leases.setOutlines("project-a", 1, true);
        leases.setEditor("project-a", 1, true);

        Change switched = leases.activate("project-b", 2);

        assertTrue(switched.changed());
        assertEquals(Reason.SWITCHED, switched.reason());
        assertEquals("project-a", switched.previousProjectId());
        assertEquals(1, switched.previousGeneration());
        assertEquals(InterestKind.ALL_MASK, switched.previousMask());
        assertEquals("project-b", switched.projectId());
        assertEquals(2, switched.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, switched.desiredMask());
        assertEquals(0, switched.addedMask());
        assertEquals(InterestKind.ALL_MASK & ~ProjectInterestLeases.BASE_MASK,
                switched.removedMask());
        assertFalse(leases.isGuiOpen());
        assertFalse(leases.isPickupActive());
        assertFalse(leases.isOutlinesActive());
        assertFalse(leases.isEditorOpen());

        // Delayed operations from the old project/generation cannot leak into B.
        assertFalse(leases.closeGui("project-a", 1).changed());
        assertFalse(leases.setPickup("project-a", 1, false).changed());
        assertFalse(leases.setOutlines("project-a", 1, false).changed());
        assertFalse(leases.setEditor("project-a", 1, false).changed());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());

        Change reopened = leases.openGui("project-b", 2);
        assertTrue(reopened.changed());
        assertTrue(reopened.desires(InterestKind.AREA_CONFIG));
    }

    @Test
    void newGenerationOfSameProjectResetsConsumerLeases() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);
        leases.setOutlines("project-a", 1, true);
        leases.setEditor("project-a", 1, true);

        Change renewed = leases.activate("project-a", 2);

        assertTrue(renewed.changed());
        assertEquals(Reason.GENERATION_CHANGED, renewed.reason());
        assertEquals("project-a", renewed.previousProjectId());
        assertEquals(1, renewed.previousGeneration());
        assertEquals(InterestKind.ALL_MASK, renewed.previousMask());
        assertEquals("project-a", renewed.projectId());
        assertEquals(2, renewed.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, renewed.desiredMask());
        assertFalse(leases.isGuiOpen());
        assertFalse(leases.isPickupActive());
        assertFalse(leases.isOutlinesActive());
        assertFalse(leases.isEditorOpen());

        Change staleClose = leases.closeProject("project-a", 1);
        assertFalse(staleClose.changed());
        assertTrue(leases.isActive());
        assertTrue(leases.openGui("project-a", 2).changed());
    }

    @Test
    void projectDeletionClosesOnlyTheActiveProjectAndGeneration() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);

        Change unrelated = leases.projectDeleted("project-b", 1);
        assertFalse(unrelated.changed());
        assertTrue(leases.isActive());
        assertTrue(leases.isGuiOpen());
        assertTrue(leases.isPickupActive());

        Change wrongGeneration = leases.projectDeleted("project-a", 2);
        assertFalse(wrongGeneration.changed());
        assertTrue(leases.isActive());

        Change deleted = leases.projectDeleted("project-a", 1);
        assertTrue(deleted.changed());
        assertEquals(Reason.PROJECT_DELETED, deleted.reason());
        assertEquals("project-a", deleted.previousProjectId());
        assertEquals(1, deleted.previousGeneration());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit()
                | InterestKind.WAREHOUSE_CONTENTS.bit(), deleted.previousMask());
        assertEquals(0, deleted.desiredMask());
        assertNull(deleted.projectId());
        assertEquals(0, deleted.generation());
        assertEquals(deleted.previousMask(), deleted.removedMask());
        assertFalse(leases.isActive());
        assertFalse(leases.isGuiOpen());
        assertFalse(leases.isPickupActive());

        assertFalse(leases.projectDeleted("project-a", 1).changed());
        assertFalse(leases.openGui("project-a", 1).changed());
        assertEquals(0, leases.desiredMask());
    }

    @Test
    void staleDeletionCannotClearARecreatedOrSwitchedView() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.activate("project-b", 1);

        // Old project deletion after a switch is unrelated to the active view.
        assertFalse(leases.projectDeleted("project-a", 1).changed());
        assertTrue(leases.isActive());
        assertEquals("project-b", leases.projectId());

        // Old generation deletion for the same project id is also fenced.
        leases.activate("project-b", 2);
        assertFalse(leases.projectDeleted("project-b", 1).changed());
        assertTrue(leases.isActive());
        assertEquals(2, leases.generation());
        assertTrue(leases.projectDeleted("project-b", 2).changed());
        assertFalse(leases.isActive());
    }

    @Test
    void dimensionChangeDropsWorldContentAdvancesGenerationAndRejectsOldCallbacks() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);
        leases.setOutlines("project-a", 1, true);
        leases.setEditor("project-a", 1, true);
        assertTrue(leases.desiredKinds().contains(InterestKind.WAREHOUSE_CONTENTS));

        Change changed = leases.dimensionChanged("project-a", 2);

        assertTrue(changed.changed());
        assertEquals(Reason.DIMENSION_CHANGED, changed.reason());
        assertEquals("project-a", changed.previousProjectId());
        assertEquals(1, changed.previousGeneration());
        assertEquals(InterestKind.ALL_MASK, changed.previousMask());
        assertEquals("project-a", changed.projectId());
        assertEquals(2, changed.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, changed.desiredMask());
        assertFalse(changed.desires(InterestKind.WAREHOUSE_CONTENTS));
        assertFalse(changed.desires(InterestKind.OUTLINES));
        assertFalse(changed.desires(InterestKind.AREA_CONFIG));
        assertTrue(changed.desires(InterestKind.MATERIALS));
        assertTrue(changed.desires(InterestKind.TEAM));
        assertEquals(0, changed.addedMask());
        assertEquals(InterestKind.ALL_MASK & ~ProjectInterestLeases.BASE_MASK,
                changed.removedMask());
        assertTrue(leases.isActive());
        assertEquals("project-a", leases.projectId());
        assertEquals(2, leases.generation());
        assertFalse(leases.isGuiOpen());
        assertFalse(leases.isPickupActive());
        assertFalse(leases.isOutlinesActive());
        assertFalse(leases.isEditorOpen());

        // A callback from the old dimension uses the old generation and cannot restore
        // GUI, pickup, outline, or editor content.
        assertFalse(leases.openGui("project-a", 1).changed());
        assertFalse(leases.setPickup("project-a", 1, true).changed());
        assertFalse(leases.setOutlines("project-a", 1, true).changed());
        assertFalse(leases.setEditor("project-a", 1, true).changed());
        assertFalse(leases.dimensionChanged("project-a", 2).changed());
        assertEquals(ProjectInterestLeases.BASE_MASK, leases.desiredMask());

        // The new generation can reopen world content explicitly.
        Change pickupAgain = leases.setPickup("project-a", 2, true);
        assertTrue(pickupAgain.changed());
        assertTrue(pickupAgain.desires(InterestKind.WAREHOUSE_CONTENTS));
    }

    @Test
    void dimensionChangeStillAdvancesGenerationWithoutWorldContent() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        Change changed = leases.dimensionChanged("project-a", 2);

        assertTrue(changed.changed());
        assertEquals(Reason.DIMENSION_CHANGED, changed.reason());
        assertEquals("project-a", changed.previousProjectId());
        assertEquals(1, changed.previousGeneration());
        assertEquals(ProjectInterestLeases.BASE_MASK, changed.previousMask());
        assertEquals("project-a", changed.projectId());
        assertEquals(2, changed.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, changed.desiredMask());
        assertEquals(0, changed.addedMask());
        assertEquals(0, changed.removedMask());

        assertFalse(leases.openGui("project-a", 1).changed());
        assertTrue(leases.openGui("project-a", 2).changed());
    }

    @Test
    void dimensionChangeRejectsStaleOrMismatchedGenerationsAndProjects() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 5);

        assertFalse(leases.dimensionChanged("project-a", 4).changed());
        assertFalse(leases.dimensionChanged("project-a", 5).changed());
        assertFalse(leases.dimensionChanged("project-b", 6).changed());
        assertTrue(leases.isActive());
        assertEquals("project-a", leases.projectId());
        assertEquals(5, leases.generation());

        assertTrue(leases.dimensionChanged("project-a", 6).changed());
        assertEquals(6, leases.generation());
    }

    @Test
    void disconnectClosesEverythingKeepsPreviousScopeAndIsIdempotent() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);
        leases.openGui("project-a", 1);
        leases.setPickup("project-a", 1, true);

        Change disconnected = leases.disconnected();

        assertTrue(disconnected.changed());
        assertEquals(Reason.DISCONNECTED, disconnected.reason());
        assertEquals("project-a", disconnected.previousProjectId());
        assertEquals(1, disconnected.previousGeneration());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit()
                | InterestKind.WAREHOUSE_CONTENTS.bit(), disconnected.previousMask());
        assertEquals(0, disconnected.desiredMask());
        assertNull(disconnected.projectId());
        assertEquals(0, disconnected.generation());
        assertEquals(disconnected.previousMask(), disconnected.removedMask());
        assertFalse(leases.isActive());

        assertFalse(leases.disconnected().changed());
        assertFalse(leases.closeProject("project-a", 1).changed());
        assertEquals(0, leases.desiredMask());

        Change restarted = leases.activate("project-b", 2);
        assertTrue(restarted.changed());
        assertEquals(ProjectInterestLeases.BASE_MASK, restarted.desiredMask());
        assertTrue(leases.openGui("project-b", 2).changed());
    }

    @Test
    void consumerOperationsWithoutActiveProjectAreIgnored() {
        var leases = new ProjectInterestLeases();

        assertFalse(leases.openGui("project-a", 1).changed());
        assertFalse(leases.closeGui("project-a", 1).changed());
        assertFalse(leases.setPickup("project-a", 1, true).changed());
        assertFalse(leases.setOutlines("project-a", 1, true).changed());
        assertFalse(leases.setEditor("project-a", 1, true).changed());
        assertFalse(leases.closeProject("project-a", 1).changed());
        assertFalse(leases.projectDeleted("project-a", 1).changed());
        assertFalse(leases.dimensionChanged("project-a", 2).changed());
        assertFalse(leases.disconnected().changed());
        assertEquals(0, leases.desiredMask());
        assertEquals(Set.of(), leases.desiredKinds());
        assertFalse(leases.isActive());
    }

    @Test
    void changeReportsDesiredMaskAndDiffAcrossConsumers() {
        var leases = new ProjectInterestLeases();

        Change activated = leases.activate("project-a", 1);
        assertNull(activated.previousProjectId());
        assertEquals(ProjectInterestLeases.BASE_MASK, activated.desiredMask());
        assertEquals(ProjectInterestLeases.BASE_MASK, activated.addedMask());
        assertEquals(0, activated.removedMask());

        Change gui = leases.openGui("project-a", 1);
        assertEquals(ProjectInterestLeases.BASE_MASK, gui.previousMask());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit(),
                gui.desiredMask());
        assertEquals(InterestKind.AREA_CONFIG.bit(), gui.addedMask());
        assertEquals(0, gui.removedMask());

        Change pickup = leases.setPickup("project-a", 1, true);
        assertEquals(InterestKind.WAREHOUSE_CONTENTS.bit(), pickup.addedMask());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit()
                | InterestKind.WAREHOUSE_CONTENTS.bit(), pickup.desiredMask());

        Change guiClosed = leases.closeGui("project-a", 1);
        assertEquals(InterestKind.AREA_CONFIG.bit(), guiClosed.removedMask());
        assertEquals(0, guiClosed.addedMask());

        Change closed = leases.closeProject("project-a", 1);
        assertEquals(0, closed.desiredMask());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.WAREHOUSE_CONTENTS.bit(),
                closed.removedMask());
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.WAREHOUSE_CONTENTS.bit(),
                closed.previousMask());
        assertEquals("project-a", closed.previousProjectId());
        assertEquals(1, closed.previousGeneration());
        assertEquals(0, closed.addedMask());
        assertEquals(Reason.CLOSED, closed.reason());
    }

    @Test
    void projectSwitchWithSameMaskStillReportsTransitionAndPreviousScope() {
        var leases = new ProjectInterestLeases();
        leases.activate("project-a", 1);

        Change switched = leases.activate("project-b", 1);

        assertTrue(switched.changed());
        assertEquals(Reason.SWITCHED, switched.reason());
        assertEquals("project-a", switched.previousProjectId());
        assertEquals(1, switched.previousGeneration());
        assertEquals(ProjectInterestLeases.BASE_MASK, switched.previousMask());
        assertEquals("project-b", switched.projectId());
        assertEquals(1, switched.generation());
        assertEquals(ProjectInterestLeases.BASE_MASK, switched.desiredMask());
        assertEquals(0, switched.addedMask());
        assertEquals(0, switched.removedMask());
    }

    @Test
    void invalidProjectIdsAndGenerationsAreRejected() {
        var leases = new ProjectInterestLeases();

        assertThrows(NullPointerException.class, () -> leases.activate(null, 1));
        assertThrows(IllegalArgumentException.class, () -> leases.activate("", 1));
        assertThrows(IllegalArgumentException.class, () -> leases.activate(" ", 1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.activate("x".repeat(101), 1));
        assertThrows(IllegalArgumentException.class, () -> leases.activate("project-a", 0));
        assertThrows(IllegalArgumentException.class, () -> leases.activate("project-a", -1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.openGui("project-a", 0));
        assertThrows(NullPointerException.class, () -> leases.openGui(null, 1));
        assertThrows(IllegalArgumentException.class, () -> leases.openGui("", 1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.closeGui("project-a", -1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.setPickup("project-a", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> leases.setOutlines("project-a", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> leases.setEditor("project-a", 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> leases.closeProject("project-a", 0));
        assertThrows(NullPointerException.class, () -> leases.projectDeleted(null, 1));
        assertThrows(IllegalArgumentException.class, () -> leases.projectDeleted("", 1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.projectDeleted("x".repeat(101), 1));
        assertThrows(IllegalArgumentException.class,
                () -> leases.projectDeleted("project-a", 0));
        assertThrows(IllegalArgumentException.class,
                () -> leases.dimensionChanged("project-a", 0));
        assertThrows(NullPointerException.class, () -> leases.dimensionChanged(null, 1));
    }

    @Test
    void desiredKindSnapshotsAreImmutable() {
        var leases = new ProjectInterestLeases();
        Change change = leases.activate("project-a", 1);

        Set<InterestKind> fromChange = change.desiredKinds();
        Set<InterestKind> fromState = leases.desiredKinds();
        Set<InterestKind> fromPrevious = change.previousKinds();
        assertThrows(UnsupportedOperationException.class, () -> fromChange.clear());
        assertThrows(UnsupportedOperationException.class, () -> fromState.clear());
        assertThrows(UnsupportedOperationException.class, () -> fromPrevious.clear());
        assertThrows(NullPointerException.class, () -> change.desires(null));
    }

    @Test
    void changeRejectsUnknownOrUnboundedMasksAndScopes() {
        int base = ProjectInterestLeases.BASE_MASK;
        assertThrows(IllegalArgumentException.class,
                () -> new Change(true, null, 0, 0, "project-a", 1, 1 << 5, 0, 0,
                        Reason.CONSUMER_CHANGED));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(true, null, 0, 0, "project-a", 1, -1, 0, 0,
                        Reason.CONSUMER_CHANGED));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, "project-a", 1, base, "project-a", 1, base,
                        1 << 7, 0, Reason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, "project-a", 1, base, "project-a", 1, base,
                        0, -1, Reason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, "project-a", 1, 1 << 7, "project-a", 1, base,
                        0, 0, Reason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, null, 1, 0, null, 0, 0, 0, 0, Reason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, "project-a", 0, 0, null, 0, 0, 0, 0, Reason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new Change(false, null, 0, 0, "project-a", 0, 0, 0, 0, Reason.NONE));
        assertThrows(NullPointerException.class,
                () -> new Change(false, null, 0, 0, null, 0, 0, 0, 0, null));
    }
}
