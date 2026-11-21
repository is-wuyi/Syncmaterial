package net.syncmaterial.syncmaterial.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectProgressModelTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void viewMatchesUuidEvenWhenTheDisplayNameChanged() {
        ProjectProgressSnapshot snapshot = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 100, 40, 20,
                        participant(ALICE, "CurrentName", 64),
                        participant(BOB, "Builder", 10))));

        ProjectProgressView mine = snapshot.viewFor(new PlayerIdentity(ALICE, "OldName"));

        ProjectProgressMaterialView stone = mine.material(1).orElseThrow();
        assertTrue(stone.claimed());
        assertEquals(64, stone.myObserved());
        assertEquals(10, stone.otherObserved());
        assertEquals(0, stone.teamMissing());
        assertEquals(0, stone.pickupMissing());
        assertFalse(stone.stagingReady());
        assertTrue(stone.teamCollected());
        assertTrue(mine.preparationKnown());

        ProjectProgressView stranger = snapshot.viewFor(new PlayerIdentity(CAROL, "Stranger"));
        ProjectProgressMaterialView strangerStone = stranger.material(1).orElseThrow();
        assertFalse(strangerStone.claimed());
        assertEquals(0, strangerStone.myObserved());
        assertEquals(74, strangerStone.otherObserved());
        assertEquals(0, strangerStone.teamMissing());
        assertEquals(20, strangerStone.pickupMissing());
    }

    @Test
    void identicalNamesWithDifferentUuidsNeverBleedIntoEachOther() {
        ProjectProgressSnapshot snapshot = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 100, 0, 0,
                        participant(ALICE, "Steve", 10),
                        participant(BOB, "Steve", 20))));

        ProjectProgressMaterialView bobView = snapshot.viewFor(new PlayerIdentity(BOB, "Steve"))
                .material(1).orElseThrow();
        assertTrue(bobView.claimed());
        assertEquals(20, bobView.myObserved());
        assertEquals(10, bobView.otherObserved());

        ProjectProgressMaterialView renamedCarol = snapshot.viewFor(new PlayerIdentity(CAROL, "Steve"))
                .material(1).orElseThrow();
        assertFalse(renamedCarol.claimed());
        assertEquals(0, renamedCarol.myObserved());
        assertEquals(30, renamedCarol.otherObserved());
    }

    @Test
    void legacyNameOnlyClaimCanBeAdoptedByUuidViewerOnlyWhenUnambiguous() {
        ProjectProgressSnapshot snapshot = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 100, 0, 0,
                        participant(null, "Legacy", 0),
                        participant(BOB, "Builder", 5))));

        ProjectProgressMaterialView viewer = snapshot.viewFor(new PlayerIdentity(ALICE, "Legacy"))
                .material(1).orElseThrow();
        assertTrue(viewer.claimed());
        assertEquals(0, viewer.myObserved());
        assertEquals(5, viewer.otherObserved());

        ProjectProgressMaterialView legacyViewer = snapshot.viewFor(new PlayerIdentity(null, "legacy"))
                .material(1).orElseThrow();
        assertTrue(legacyViewer.claimed());

        ProjectProgressMaterialView resolvedNameOnly = snapshot.viewFor(new PlayerIdentity(null, "Builder"))
                .material(1).orElseThrow();
        assertFalse(resolvedNameOnly.claimed());
    }

    @Test
    void legacyNameOnlyClaimIsNotAdoptedWhenAnotherResolvedPlayerOwnsTheName() {
        ProjectProgressSnapshot snapshot = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 100, 0, 0,
                        participant(null, "Steve", 0),
                        participant(BOB, "Steve", 20))));

        ProjectProgressMaterialView viewer = snapshot.viewFor(new PlayerIdentity(ALICE, "Steve"))
                .material(1).orElseThrow();
        assertFalse(viewer.claimed());
        assertEquals(0, viewer.myObserved());
        assertEquals(20, viewer.otherObserved());
    }

    @Test
    void completedProjectionsRequireCurrentKnownAreaDataAndKeepOverlapSeparate() {
        ProjectProgressSnapshot ready = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 10, 10, 0,
                        participant(ALICE, "Alice", 0))));
        ProjectProgressMaterialView readyStone = ready.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertTrue(readyStone.preparationKnown());
        assertTrue(readyStone.stagingReady());
        assertTrue(readyStone.teamCollected());

        ProjectProgressSnapshot stale = snapshot(
                area(true, false), false,
                List.of(material(1, "minecraft:stone", 10, 10, 0,
                        participant(ALICE, "Alice", 0))));
        ProjectProgressMaterialView staleStone = stale.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertFalse(staleStone.preparationKnown());
        assertFalse(staleStone.stagingReady());
        assertFalse(staleStone.teamCollected());
        assertEquals(0, staleStone.teamMissing());

        ProjectProgressSnapshot unknown = snapshot(
                AreaInventoryView.unknown(), false,
                List.of(material(1, "minecraft:stone", 0, 0, 0,
                        participant(ALICE, "Alice", 0))));
        ProjectProgressMaterialView unknownStone = unknown.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertFalse(unknownStone.preparationKnown());
        assertFalse(unknownStone.teamCollected());

        ProjectProgressSnapshot overlap = snapshot(
                area(true, true), true,
                List.of(material(1, "minecraft:stone", 10, 10, 0,
                        participant(ALICE, "Alice", 0))));
        ProjectProgressView overlapView = overlap.viewFor(new PlayerIdentity(ALICE, "Alice"));
        ProjectProgressMaterialView overlapStone = overlapView.material(1).orElseThrow();
        assertTrue(overlapView.areaOverlap());
        assertTrue(overlapStone.preparationKnown());
        assertTrue(overlapStone.stagingReady());
        assertTrue(overlapStone.teamCollected());
    }

    @Test
    void viewerProjectionCarriesCumulativeSaturationEvenWhenViewerObservedNothing() {
        ProjectProgressSnapshot snapshot = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", Long.MAX_VALUE, 0, 0,
                        participant(ALICE, "Alice", Long.MAX_VALUE),
                        participant(BOB, "Bob", 1))));

        ProjectProgressMaterialView stranger = snapshot.viewFor(new PlayerIdentity(CAROL, "Carol"))
                .material(1).orElseThrow();
        assertFalse(stranger.claimed());
        assertEquals(0, stranger.myObserved());
        assertEquals(Long.MAX_VALUE, stranger.otherObserved());
        assertEquals(Long.MAX_VALUE, stranger.teamObserved());
        assertTrue(stranger.teamObservedSaturated(),
                "two other participants sum to Long.MAX_VALUE + 1 even though my side is zero");

        ProjectProgressMaterialView saturatingViewer = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertEquals(Long.MAX_VALUE, saturatingViewer.myObserved());
        assertEquals(1, saturatingViewer.otherObserved());
        assertTrue(saturatingViewer.teamObservedSaturated());

        ProjectProgressSnapshot exact = snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", Long.MAX_VALUE, 0, 0,
                        participant(ALICE, "Alice", Long.MAX_VALUE))));
        ProjectProgressMaterialView exactMax = exact.viewFor(new PlayerIdentity(BOB, "Bob"))
                .material(1).orElseThrow();
        assertEquals(Long.MAX_VALUE, exactMax.otherObserved());
        assertFalse(exactMax.teamObservedSaturated(),
                "one participant at exactly Long.MAX_VALUE is an exact total, not a bound");
    }

    @Test
    void recordsAreImmutableAndRejectDuplicateOrNegativeFacts() {
        List<ProgressParticipant> participants = new ArrayList<>();
        participants.add(participant(ALICE, "Alice", 5));
        ProjectProgressMaterial material = material(1, "minecraft:stone", 10, 0, 0, participants);
        participants.clear();
        assertEquals(1, material.participants().size());

        List<ProjectProgressMaterial> materials = new ArrayList<>();
        materials.add(material(2, "minecraft:dirt", 1, 0, 0));
        materials.add(material(1, "minecraft:stone", 1, 0, 0));
        ProjectProgressSnapshot snapshot = snapshot(area(true, false), true, materials);
        materials.clear();
        assertEquals(List.of(1, 2), snapshot.materials().stream()
                .map(ProjectProgressMaterial::materialId).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.materials().add(material(3, "minecraft:sand", 1, 0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.material(1).orElseThrow().participants().clear());

        ProjectProgressView view = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"));
        assertThrows(UnsupportedOperationException.class, () -> view.materials().clear());

        assertThrows(IllegalArgumentException.class, () -> snapshot(
                area(true, false), true,
                List.of(material(1, "minecraft:stone", 1, 0, 0),
                        material(1, "minecraft:dirt", 1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> material(1, "minecraft:stone", -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> material(1, "minecraft:stone", 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> material(1, "minecraft:stone", 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> participant(ALICE, "Alice", -1));
        assertThrows(IllegalArgumentException.class, () -> participant(null, "Legacy", 1));
        assertThrows(IllegalArgumentException.class, () -> material(1, "minecraft:stone", 1, 0, 0,
                participant(ALICE, "Alice", 1),
                participant(ALICE, "RenamedAlice", 1)));
    }

    @Test
    void snapshotRejectsKnownAreaViewCarryingWarnings() {
        AreaInventoryWarning warning = new AreaInventoryWarning(
                AreaKind.STAGING_AREA, 1, "Main", AreaInventoryWarning.STATUS_STALE);
        AreaInventoryView inconsistent = new AreaInventoryView(
                Map.of(), Map.of(), List.of(warning), false, false, 7);
        assertEquals(1, inconsistent.freshness().size());
        // A known view with warnings is rejected by AreaInventoryView itself; the
        // snapshot enforced the same invariant for its own copies.
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "Project", 1, 7, 1, true, true, false, List.of(warning), List.of()));
    }

    @Test
    void viewCannotClaimPreparationWithoutConsistentAreaFlags() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressView(
                "p", "Project", 1, 2, 3, false, true, false, List.of(), true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterialView(
                1, "minecraft:stone", 1, 0, 0, false, 0, 0, 1, 0, true, false, false));
    }

    private static AreaInventoryView area(boolean known, boolean overlap) {
        return new AreaInventoryView(Map.of("minecraft:stone", 40L), Map.of(), List.of(),
                known, overlap, 7);
    }

    private static ProjectProgressSnapshot snapshot(AreaInventoryView area, boolean current,
                                                    List<ProjectProgressMaterial> materials) {
        return new ProjectProgressSnapshot("p", "Project", 3, area.revision(), 9,
                current, area.known(), area.overlap(), area.freshness(), materials);
    }

    private static ProjectProgressMaterial material(int id, String itemId, long required,
                                                    long staging, long warehouse,
                                                    ProgressParticipant... participants) {
        return material(id, itemId, required, staging, warehouse, List.of(participants));
    }

    private static ProjectProgressMaterial material(int id, String itemId, long required,
                                                    long staging, long warehouse,
                                                    List<ProgressParticipant> participants) {
        return new ProjectProgressMaterial(id, itemId, required, staging, warehouse, participants);
    }

    private static ProgressParticipant participant(UUID uuid, String name, long held) {
        return new ProgressParticipant(new PlayerIdentity(uuid, name), held);
    }
}
