package net.syncmaterial.syncmaterial.server.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectProgressView;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectProgressServiceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String PROJECT = "project";

    @Test
    void buildsCompleteSnapshotAndProjectsOneViewer() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        tracker.connected(ALICE);
        tracker.connected(BOB);
        tracker.update(ALICE, PROJECT, 1, 64);
        tracker.update(BOB, PROJECT, 1, 10);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectState state = state(
                project(5,
                        material(1, "minecraft:stone", 100),
                        material(2, "minecraft:dirt", 10)),
                List.of(claim(1, ALICE, "Alice"), claim(1, BOB, "Bob")),
                inventory(7, true, false, Map.of("minecraft:stone", 40L, "minecraft:dirt", 10L),
                        Map.of("minecraft:stone", 20L), List.of()));

        ProjectProgressSnapshot snapshot = service.build(state, 42, true);

        assertEquals(PROJECT, snapshot.projectId());
        assertEquals("Project", snapshot.projectName());
        assertEquals(5, snapshot.catalogRevision());
        assertEquals(7, snapshot.inventoryRevision());
        assertEquals(42, snapshot.publicationSequence());
        assertTrue(snapshot.areaCurrent());
        assertTrue(snapshot.areaKnown());
        assertFalse(snapshot.areaOverlap());
        assertTrue(snapshot.preparationKnown());
        assertEquals(List.of(1, 2), snapshot.materials().stream()
                .map(ProjectProgressMaterial::materialId).toList());

        ProjectProgressMaterial stone = snapshot.material(1).orElseThrow();
        assertEquals(100, stone.required());
        assertEquals(40, stone.staging());
        assertEquals(20, stone.warehouse());
        assertEquals(2, stone.participants().size());
        assertEquals(64, held(stone, ALICE));
        assertEquals(10, held(stone, BOB));

        ProjectProgressView bobView = snapshot.viewFor(new PlayerIdentity(BOB, "Bob"));
        ProjectProgressMaterialView bobStone = bobView.material(1).orElseThrow();
        assertTrue(bobStone.claimed());
        assertEquals(10, bobStone.myObserved());
        assertEquals(64, bobStone.otherObserved());
        assertEquals(0, bobStone.teamMissing());
        assertEquals(20, bobStone.pickupMissing());
        assertFalse(bobStone.stagingReady());
        assertTrue(bobStone.teamCollected());
        assertTrue(bobStone.preparationKnown());

        ProjectProgressMaterialView bobDirt = bobView.material(2).orElseThrow();
        assertFalse(bobDirt.claimed());
        assertEquals(0, bobDirt.myObserved());
        assertEquals(0, bobDirt.teamMissing());
        assertTrue(bobDirt.stagingReady());
        assertTrue(bobDirt.teamCollected());
    }

    @Test
    void multiplePlayersOnOneMaterialSumIntoTeamMissing() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.connected(BOB);
        tracker.update(ALICE, PROJECT, 1, 64);
        tracker.update(BOB, PROJECT, 1, 32);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Alice"), claim(1, BOB, "Bob")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        ProjectProgressMaterialView alice = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertEquals(64, alice.myObserved());
        assertEquals(32, alice.otherObserved());
        assertEquals(4, alice.teamMissing());
        assertEquals(0, alice.pickupMissing());
        assertFalse(alice.teamCollected());
    }

    @Test
    void offlineClaimStaysListedWithZeroButCanStillProject() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 1, 32);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Alice"), claim(1, CAROL, "Carol")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        ProjectProgressMaterial stone = snapshot.material(1).orElseThrow();
        assertEquals(2, stone.participants().size());
        assertEquals(0, held(stone, CAROL));

        ProjectProgressMaterialView carol = snapshot.viewFor(new PlayerIdentity(CAROL, "Carol"))
                .material(1).orElseThrow();
        assertTrue(carol.claimed());
        assertEquals(0, carol.myObserved());
        assertEquals(32, carol.otherObserved());
        assertEquals(68, carol.teamMissing());
    }

    @Test
    void duplicateUuidWithDifferentNamesCountsOnce() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 1, 64);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Alice"), claim(1, ALICE, "Alicia")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        ProjectProgressMaterial stone = snapshot.material(1).orElseThrow();
        assertEquals(1, stone.participants().size());
        assertEquals(64, stone.participants().get(0).effectiveHeld());
        ProjectProgressMaterialView view = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertTrue(view.claimed());
        assertEquals(64, view.myObserved());
        assertEquals(0, view.otherObserved());
    }

    @Test
    void identicalNamesWithDifferentUuidsRemainDistinct() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.connected(BOB);
        tracker.update(ALICE, PROJECT, 1, 10);
        tracker.update(BOB, PROJECT, 1, 20);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Steve"), claim(1, BOB, "Steve")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        assertEquals(2, snapshot.material(1).orElseThrow().participants().size());
        ProjectProgressMaterialView bob = snapshot.viewFor(new PlayerIdentity(BOB, "Steve"))
                .material(1).orElseThrow();
        assertTrue(bob.claimed());
        assertEquals(20, bob.myObserved());
        assertEquals(10, bob.otherObserved());

        ProjectProgressMaterialView carol = snapshot.viewFor(new PlayerIdentity(CAROL, "Steve"))
                .material(1).orElseThrow();
        assertFalse(carol.claimed());
        assertEquals(30, carol.otherObserved());
    }

    @Test
    void legacyNameOnlyClaimGetsZeroAndViewerByUuidMayAdoptIt() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 1, 64);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, null, "Legacy")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        ProjectProgressMaterial stone = snapshot.material(1).orElseThrow();
        assertEquals(1, stone.participants().size());
        assertFalse(stone.participants().get(0).hasUuid());
        assertEquals(0, stone.participants().get(0).effectiveHeld());

        ProjectProgressMaterialView view = snapshot.viewFor(new PlayerIdentity(BOB, "Legacy"))
                .material(1).orElseThrow();
        assertTrue(view.claimed());
        assertEquals(0, view.myObserved());
        assertEquals(100, view.teamMissing());
        assertFalse(view.teamCollected());
    }

    @Test
    void expiredAndDisconnectedObservationsDoNotCount() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 1, 64);
        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectState state = state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Alice")),
                inventory(1, true, false, Map.of(), Map.of(), List.of()));

        clock.set(Duration.ofSeconds(10).toNanos());
        ProjectProgressMaterial expired = service.build(state, 1, true).material(1).orElseThrow();
        assertEquals(0, held(expired, ALICE));
        assertEquals(100, service.build(state, 2, true)
                .viewFor(new PlayerIdentity(ALICE, "Alice")).material(1).orElseThrow().teamMissing());

        clock.set(0);
        tracker.update(ALICE, PROJECT, 1, 64);
        tracker.disconnected(ALICE);
        ProjectProgressMaterial disconnected = service.build(state, 3, true).material(1).orElseThrow();
        assertEquals(0, held(disconnected, ALICE));
    }

    @Test
    void unknownAreaKeepsLastKnownCountsButBlocksCompletion() {
        AreaInventoryWarning warning = new AreaInventoryWarning(
                AreaKind.STAGING_AREA, 1, "Main", AreaInventoryWarning.STATUS_UNKNOWN);
        AreaInventoryView area = new AreaInventoryView(
                Map.of("minecraft:stone", 100L), Map.of(), List.of(warning), false, false, 9);

        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(), area), 1, true);

        assertFalse(snapshot.areaKnown());
        assertFalse(snapshot.preparationKnown());
        ProjectProgressMaterialView view = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertEquals(100, view.staging());
        assertEquals(0, view.teamMissing());
        assertFalse(view.stagingReady());
        assertFalse(view.teamCollected());
        assertFalse(view.preparationKnown());
        assertEquals(warning, snapshot.areaWarnings().get(0));
    }

    @Test
    void staleAreaCurrentFlagBlocksCompletionButDeduplicatedOverlapDoesNot() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectState state = state(
                project(1, material(1, "minecraft:stone", 10)),
                List.of(),
                inventory(4, true, false, Map.of("minecraft:stone", 10L), Map.of(), List.of()));

        ProjectProgressMaterialView notCurrent = service.build(state, 1, false)
                .viewFor(new PlayerIdentity(ALICE, "Alice")).material(1).orElseThrow();
        assertFalse(notCurrent.preparationKnown());
        assertFalse(notCurrent.stagingReady());
        assertFalse(notCurrent.teamCollected());

        ProjectState overlapping = state(
                project(1, material(1, "minecraft:stone", 10)),
                List.of(),
                inventory(4, true, true, Map.of("minecraft:stone", 10L), Map.of(), List.of()));
        ProjectProgressView overlapView = service.build(overlapping, 2, true)
                .viewFor(new PlayerIdentity(ALICE, "Alice"));
        ProjectProgressMaterialView overlap = overlapView.material(1).orElseThrow();
        assertTrue(overlapView.areaOverlap());
        assertTrue(overlap.preparationKnown());
        assertTrue(overlap.stagingReady());
        assertTrue(overlap.teamCollected());
    }

    @Test
    void overflowSaturatesRatherThanWrapping() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        tracker.connected(ALICE);
        tracker.connected(BOB);
        tracker.update(ALICE, PROJECT, 1, Long.MAX_VALUE);
        tracker.update(BOB, PROJECT, 1, Long.MAX_VALUE);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1,
                        material(1, "minecraft:stone", Long.MAX_VALUE),
                        material(2, "minecraft:dirt", Long.MAX_VALUE)),
                List.of(claim(1, ALICE, "Alice"), claim(1, BOB, "Bob")),
                inventory(Long.MAX_VALUE, true, false,
                        Map.of("minecraft:dirt", 1L), Map.of("minecraft:dirt", Long.MAX_VALUE), List.of())),
                Long.MAX_VALUE, true);

        ProjectProgressMaterialView alice = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(1).orElseThrow();
        assertEquals(Long.MAX_VALUE, alice.myObserved());
        assertEquals(Long.MAX_VALUE, alice.otherObserved());
        assertEquals(Long.MAX_VALUE, alice.teamObserved());
        assertEquals(0, alice.teamMissing());
        assertEquals(0, alice.pickupMissing());
        assertTrue(alice.teamCollected());

        ProjectProgressMaterialView dirt = snapshot.viewFor(new PlayerIdentity(ALICE, "Alice"))
                .material(2).orElseThrow();
        assertEquals(Long.MAX_VALUE, dirt.required());
        assertEquals(1, dirt.staging());
        assertEquals(Long.MAX_VALUE, dirt.warehouse());
        assertEquals(Long.MAX_VALUE - 1, dirt.pickupMissing());
        assertTrue(dirt.teamCollected());
    }

    @Test
    void acceptsPreTakenSampleAndRejectsMismatchedOrNegativeInputs() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 1, 64);
        ProjectState state = state(
                project(1, material(1, "minecraft:stone", 100)),
                List.of(claim(1, ALICE, "Alice")),
                inventory(1, true, false, Map.of(), Map.of(), List.of()));
        ProjectProgressService service = new ProjectProgressService(tracker);

        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT, state.claims());
        ProjectProgressSnapshot snapshot = service.build(state, sample, 8, true);
        assertEquals(8, snapshot.publicationSequence());
        assertEquals(64, held(snapshot.material(1).orElseThrow(), ALICE));

        OnlineInventorySnapshot otherProject = tracker.snapshot("other", state.claims());
        assertThrows(IllegalArgumentException.class, () -> service.build(state, otherProject, 8, true));
        assertThrows(IllegalArgumentException.class, () -> service.build(state, -1, true));
    }

    @Test
    void claimsForUnknownMaterialsDoNotFabricateCatalogRows() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        tracker.connected(ALICE);
        tracker.update(ALICE, PROJECT, 99, 64);

        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1, material(1, "minecraft:stone", 1)),
                List.of(claim(99, ALICE, "Alice")),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        assertEquals(1, snapshot.materials().size());
        assertEquals(0, snapshot.material(1).orElseThrow().participants().size());
    }

    @Test
    void materialsAreSortedByStableId() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        ProjectProgressService service = new ProjectProgressService(tracker);
        ProjectProgressSnapshot snapshot = service.build(state(
                project(1,
                        material(2, "minecraft:dirt", 1),
                        material(1, "minecraft:stone", 1)),
                List.of(),
                inventory(1, true, false, Map.of(), Map.of(), List.of())), 1, true);

        assertEquals(List.of(1, 2), snapshot.materials().stream()
                .map(ProjectProgressMaterial::materialId).toList());
    }

    private static OnlineInventoryTracker tracker(AtomicLong clock) {
        return new OnlineInventoryTracker(clock::get, Duration.ofSeconds(10));
    }

    private static ProjectState state(ProjectSnapshot project, List<ClaimSnapshot> claims,
                                      AreaInventoryView inventory) {
        return new ProjectState(project, claims, inventory);
    }

    private static ProjectSnapshot project(long revision, MaterialSnapshot... materials) {
        return new ProjectSnapshot(PROJECT, "Project", "hash",
                new PlayerIdentity(ALICE, "Alice"), true, List.of(), List.of(materials), revision);
    }

    private static MaterialSnapshot material(int id, String itemId, long required) {
        return new MaterialSnapshot(id, itemId, required);
    }

    private static ClaimSnapshot claim(int materialId, UUID uuid, String name) {
        return new ClaimSnapshot(materialId, new PlayerIdentity(uuid, name));
    }

    private static AreaInventoryView inventory(long revision, boolean known, boolean overlap,
                                               Map<String, Long> staging, Map<String, Long> warehouse,
                                               List<AreaInventoryWarning> warnings) {
        return new AreaInventoryView(staging, warehouse, warnings, known, overlap, revision);
    }

    private static long held(ProjectProgressMaterial material, UUID playerId) {
        for (ProgressParticipant participant : material.participants()) {
            if (participant.player().uuid() != null && participant.player().uuid().equals(playerId)) {
                return participant.effectiveHeld();
            }
        }
        return 0L;
    }
}
