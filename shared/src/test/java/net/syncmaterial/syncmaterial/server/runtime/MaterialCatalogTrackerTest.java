package net.syncmaterial.syncmaterial.server.runtime;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialCatalogTrackerTest {
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final PlayerIdentity OWNER = new PlayerIdentity(null, "Owner");

    private final MaterialCatalogTracker tracker = new MaterialCatalogTracker();

    @Test
    void sentRecordsBaselineAndAllocatesRuntimeWideMonotonicSequence() {
        long first = tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 7L,
                material(1, "minecraft:stone", 64)));
        assertEquals(1L, first);
        assertTrue(tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 7L,
                material(1, "minecraft:stone", 64))).isEmpty());

        long second = tracker.sent(PLAYER_B, 1L, project("b", "Other", "hash-b", 1L,
                material(2, "minecraft:dirt", 32)));
        long third = tracker.sent(PLAYER_A, 1L, project("b", "Other", "hash-b", 1L,
                material(2, "minecraft:dirt", 32)));
        assertEquals(2L, second);
        assertEquals(3L, third);

        OptionalLong changed = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 7L,
                material(1, "minecraft:stone", 65)));
        assertEquals(4L, changed.getAsLong());
    }

    @Test
    void differentPlayersHaveIndependentBaselines() {
        tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        OptionalLong forB = tracker.changed(PLAYER_B, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertEquals(2L, forB.getAsLong());
        assertTrue(tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64))).isEmpty());
        assertTrue(tracker.changed(PLAYER_B, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64))).isEmpty());
    }

    @Test
    void renameWithSameHashIsAChange() {
        tracker.sent(PLAYER_A, 1L, project("a", "Old name", "hash", 3L,
                material(1, "minecraft:stone", 64)));
        OptionalLong renamed = tracker.changed(PLAYER_A, 1L, project("a", "New name", "hash", 3L,
                material(1, "minecraft:stone", 64)));
        assertEquals(2L, renamed.getAsLong());
        assertTrue(tracker.changed(PLAYER_A, 1L, project("a", "New name", "hash", 3L,
                material(1, "minecraft:stone", 64))).isEmpty());
    }

    @Test
    void revisionOnlyChangeDoesNotNotify() {
        tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 9L,
                material(1, "minecraft:stone", 64)));
        assertTrue(tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64))).isEmpty());
    }

    @Test
    void sameIdRebuildWithNewMaterialIdsNotifiesEvenAtRevisionOne() {
        tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 7L,
                material(1, "minecraft:stone", 64)));
        OptionalLong rebuilt = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(2, "minecraft:stone", 64)));
        assertEquals(2L, rebuilt.getAsLong());

        OptionalLong requirementChanged = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(2, "minecraft:stone", 32)));
        assertEquals(3L, requirementChanged.getAsLong());
    }

    @Test
    void connectionGenerationDoesNotReuseTheBaseline() {
        tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        OptionalLong newConnection = tracker.changed(PLAYER_A, 2L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertEquals(2L, newConnection.getAsLong());
        assertTrue(tracker.changed(PLAYER_A, 2L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64))).isEmpty());
    }

    @Test
    void forgetDisconnectAndClearDropBaselinesWithoutResettingTheSequence() {
        long baseline = tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        tracker.forget(PLAYER_A, "a");
        OptionalLong afterForget = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertEquals(baseline + 1, afterForget.getAsLong());

        tracker.disconnected(PLAYER_A);
        OptionalLong afterDisconnect = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertEquals(baseline + 2, afterDisconnect.getAsLong());

        long beforeClear = tracker.sent(PLAYER_B, 1L, project("b", "Other", "hash-b", 1L,
                material(2, "minecraft:dirt", 1)));
        tracker.clear();
        long afterClear = tracker.sent(PLAYER_B, 1L, project("b", "Other", "hash-b", 1L,
                material(2, "minecraft:dirt", 1)));
        assertEquals(beforeClear + 1, afterClear);
    }

    @Test
    void currentRevisionReturnsTheExactSequenceDeliveredOnThisConnection() {
        long first = tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertEquals(first, tracker.currentRevision(PLAYER_A, "a", 1L).getAsLong());
        assertTrue(tracker.currentRevision(PLAYER_A, "b", 1L).isEmpty());
        assertTrue(tracker.currentRevision(PLAYER_B, "a", 1L).isEmpty());

        long second = tracker.sent(PLAYER_A, 1L, project("b", "Other", "hash-b", 1L,
                material(2, "minecraft:dirt", 1)));
        assertEquals(second, tracker.currentRevision(PLAYER_A, "b", 1L).getAsLong());
        assertEquals(first, tracker.currentRevision(PLAYER_A, "a", 1L).getAsLong());

        long changed = tracker.changed(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 32))).getAsLong();
        assertEquals(changed, tracker.currentRevision(PLAYER_A, "a", 1L).getAsLong());
    }

    @Test
    void currentRevisionRejectsOtherConnectionGenerationAndInvalidInput() {
        long revision = tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        assertTrue(tracker.currentRevision(PLAYER_A, "a", 2L).isEmpty(),
                "新旧连接不能共享目录基线");
        assertTrue(tracker.currentRevision(PLAYER_A, "a", 0L).isEmpty());
        assertTrue(tracker.currentRevision(PLAYER_A, "a", -1L).isEmpty());
        assertThrows(NullPointerException.class, () -> tracker.currentRevision(null, "a", 1L));
        assertThrows(NullPointerException.class, () -> tracker.currentRevision(PLAYER_A, null, 1L));

        tracker.forget(PLAYER_A, "a");
        assertTrue(tracker.currentRevision(PLAYER_A, "a", 1L).isEmpty());
        tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64)));
        tracker.disconnected(PLAYER_A);
        assertTrue(tracker.currentRevision(PLAYER_A, "a", 1L).isEmpty());
        assertEquals(revision + 2, tracker.sent(PLAYER_A, 1L, project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64))));
    }

    @Test
    void nonPositiveConnectionIsRejected() {
        ProjectSnapshot project = project("a", "Project", "hash", 1L,
                material(1, "minecraft:stone", 64));
        assertThrows(IllegalArgumentException.class, () -> tracker.sent(PLAYER_A, 0L, project));
        assertThrows(IllegalArgumentException.class, () -> tracker.changed(PLAYER_A, -1L, project));
    }

    private static ProjectSnapshot project(String id, String name, String hash, long revision,
                                           MaterialSnapshot... materials) {
        return new ProjectSnapshot(id, name, hash, OWNER, true, List.of(), List.of(materials), revision);
    }

    private static MaterialSnapshot material(int id, String itemId, long required) {
        return new MaterialSnapshot(id, itemId, required);
    }
}
