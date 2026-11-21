package net.syncmaterial.syncmaterial.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ProjectProgressCodec;
import net.syncmaterial.syncmaterial.network.ProjectProgressFragmenter;
import net.syncmaterial.syncmaterial.network.ProjectViewPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewAssemblerTest {
    private static final ProjectViewScope SCOPE = ProjectViewScope.project("project");
    private static final long CONNECTION_GENERATION = 1L;
    private static final long VIEW_ID = 7L;
    private static final long VIEW_GENERATION = 3L;
    private static final String SNAPSHOT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_SNAPSHOT_ID = "00000000-0000-0000-0000-000000000002";

    private static final ProjectProgressSnapshot LARGE = largeSnapshot(9, "project", 7, 3);
    private static final List<ProjectViewPart> LARGE_PARTS = parts(LARGE, SNAPSHOT_ID,
            SCOPE, VIEW_ID, VIEW_GENERATION);

    @Test
    void emitsOnlyAfterEveryPartAndReturnsTheImmutableCoreSnapshot() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assertTrue(assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID,
                VIEW_GENERATION));
        assertTrue(LARGE_PARTS.size() >= 3, "large fixture must exercise multi-part assembly");

        ProjectViewAssembler.Pending first = assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(1, first.receivedParts());
        assertEquals(LARGE_PARTS.size(), first.partCount());
        assertEquals(1, assembler.pendingCount());

        ProjectViewAssembler.Outcome outcome = null;
        for (int index = 1; index < LARGE_PARTS.size(); index++) {
            outcome = assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(index));
            if (index < LARGE_PARTS.size() - 1) {
                assertInstanceOf(ProjectViewAssembler.Pending.class, outcome);
            }
        }
        ProjectViewAssembler.Complete complete = assertInstanceOf(
                ProjectViewAssembler.Complete.class, outcome);
        assertEquals(LARGE, complete.view().snapshot());
        assertEquals(SNAPSHOT_ID, complete.view().snapshotId());
        assertEquals(VIEW_ID, complete.view().viewId());
        assertEquals(VIEW_GENERATION, complete.view().generation());
        assertEquals(9, complete.view().sequence());
        assertEquals(0, assembler.pendingCount());
        assertEquals(1, assembler.activeViewCount());
    }

    @Test
    void outOfOrderFirstOrLaterPartFailsExplicitly() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        ProjectViewAssembler.Failed first = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(1)));
        assertEquals(ProjectViewAssembler.FailureReason.OUT_OF_ORDER, first.reason());
        assertEquals(0, assembler.pendingCount());

        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        ProjectViewAssembler.Failed later = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(2)));
        assertEquals(ProjectViewAssembler.FailureReason.OUT_OF_ORDER, later.reason());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void identicalDuplicatePartIsIdempotentButDifferentBytesFail() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        ProjectViewAssembler.Pending duplicate = assertInstanceOf(
                ProjectViewAssembler.Pending.class, assembler.accept(connection,
                        CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(1, duplicate.receivedParts());

        byte[] different = Arrays.copyOf(LARGE_PARTS.get(0).fragment(),
                LARGE_PARTS.get(0).fragment().length);
        different[0] ^= 1;
        ProjectViewPart conflicting = packet(SCOPE, VIEW_ID, VIEW_GENERATION,
                LARGE_PARTS.get(0).sequence(), LARGE_PARTS.get(0).catalogRevision(),
                LARGE_PARTS.get(0).inventoryRevision(), SNAPSHOT_ID, 0,
                LARGE_PARTS.get(0).partCount(), LARGE_PARTS.get(0).totalBytes(), different);
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, conflicting));
        assertEquals(ProjectViewAssembler.FailureReason.DUPLICATE_CONFLICT, failed.reason());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void sameSequenceWithDifferentFragmentMetadataDropsTheWholeAssembly() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        ProjectViewPart mismatched = packet(SCOPE, VIEW_ID, VIEW_GENERATION,
                LARGE_PARTS.get(0).sequence(), LARGE_PARTS.get(0).catalogRevision(),
                LARGE_PARTS.get(0).inventoryRevision(), SNAPSHOT_ID, 1,
                LARGE_PARTS.get(0).partCount(), LARGE_PARTS.get(0).totalBytes() + 1,
                LARGE_PARTS.get(1).fragment());
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, mismatched));
        assertEquals(ProjectViewAssembler.FailureReason.INCONSISTENT, failed.reason());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void pendingLimitRejectsTheFifthAssemblyWithoutEviction() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        for (int index = 0; index < ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES; index++) {
            ProjectViewPart part = part(LARGE_PARTS.get(0),
                    String.format("00000000-0000-0000-0000-%012x", 0x100 + index));
            assertInstanceOf(ProjectViewAssembler.Pending.class,
                    assembler.accept(connection, CONNECTION_GENERATION, part));
        }
        assertEquals(ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES, assembler.pendingCount());

        ProjectViewPart fifth = part(LARGE_PARTS.get(0),
                "00000000-0000-0000-0000-0000000001ff");
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, fifth));
        assertEquals(ProjectViewAssembler.FailureReason.TOO_MANY_PENDING, failed.reason());
        assertEquals(ProjectViewLimits.MAX_CONCURRENT_ASSEMBLIES, assembler.pendingCount());
    }

    @Test
    void timeoutIsReportedByAdvanceAndClearsPendingState() {
        ProjectViewAssembler assembler = new ProjectViewAssembler(1_000);
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));

        assertTrue(assembler.advance(999).isEmpty());
        List<ProjectViewAssembler.Failed> expired = assembler.advance(1);
        assertEquals(1, expired.size());
        assertEquals(ProjectViewAssembler.FailureReason.TIMEOUT, expired.get(0).reason());
        assertTrue(expired.get(0).detail().contains("missing"));
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void missingPartsAreExplicitlyFailedAtTimeout() {
        ProjectViewAssembler assembler = new ProjectViewAssembler(100);
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        ProjectViewAssembler.Failed timeout = assembler.advance(100).get(0);
        assertEquals(ProjectViewAssembler.FailureReason.TIMEOUT, timeout.reason());
    }

    @Test
    void explicitOpenCloseLifecycleFencesLateAndNewerParts() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        ProjectViewAssembler.Failed notOpen = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(ProjectViewAssembler.FailureReason.CONNECTION_NOT_OPEN, notOpen.reason());

        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assembler.closeView(connection, CONNECTION_GENERATION, VIEW_ID);
        ProjectViewAssembler.Failed closed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(ProjectViewAssembler.FailureReason.VIEW_NOT_OPEN, closed.reason());

        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID,
                VIEW_GENERATION + 1);
        ProjectViewAssembler.Failed stale = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(ProjectViewAssembler.FailureReason.STALE, stale.reason());

        ProjectViewPart current = packet(SCOPE, VIEW_ID, VIEW_GENERATION + 1, 9, 7, 3,
                OTHER_SNAPSHOT_ID, 0, LARGE_PARTS.get(0).partCount(),
                LARGE_PARTS.get(0).totalBytes(), LARGE_PARTS.get(0).fragment());
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, current));

        assembler.closeConnection(connection, CONNECTION_GENERATION);
        ProjectViewAssembler.Failed connectionClosed = assertInstanceOf(
                ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));
        assertEquals(ProjectViewAssembler.FailureReason.CONNECTION_NOT_OPEN,
                connectionClosed.reason());
        assertEquals(0, assembler.activeConnectionCount());
        assertEquals(0, assembler.activeViewCount());
    }

    @Test
    void olderSnapshotFinishingAfterNewerIsRejectedAsStale() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        ProjectProgressSnapshot newer = smallSnapshot(2, "project", 7, 3);
        ProjectProgressSnapshot older = smallSnapshot(1, "project", 7, 3);
        ProjectViewPart newerPart = parts(newer, OTHER_SNAPSHOT_ID, SCOPE, VIEW_ID,
                VIEW_GENERATION).get(0);
        ProjectViewPart olderPart = parts(older, SNAPSHOT_ID, SCOPE, VIEW_ID,
                VIEW_GENERATION).get(0);

        assertInstanceOf(ProjectViewAssembler.Complete.class,
                assembler.accept(connection, CONNECTION_GENERATION, newerPart));
        ProjectViewAssembler.Failed stale = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, olderPart));
        assertEquals(ProjectViewAssembler.FailureReason.STALE, stale.reason());
    }

    @Test
    void decodedSnapshotFromAnotherProjectIsRejected() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);

        ProjectProgressSnapshot foreign = smallSnapshot(9, "other-project", 7, 3);
        ProjectViewPart part = parts(foreign, SNAPSHOT_ID, SCOPE, VIEW_ID,
                VIEW_GENERATION).get(0);
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, part));
        assertEquals(ProjectViewAssembler.FailureReason.INCONSISTENT, failed.reason());
        assertTrue(failed.detail().contains("project id"));
    }

    @Test
    void equalButDistinctConnectionTokensDoNotShareFencingState() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        EqualToken firstConnection = new EqualToken();
        EqualToken reconnected = new EqualToken();
        assertEquals(firstConnection, reconnected);

        assembler.openView(firstConnection, CONNECTION_GENERATION, SCOPE, VIEW_ID,
                VIEW_GENERATION);
        assembler.closeConnection(firstConnection, CONNECTION_GENERATION);
        assembler.openView(reconnected, CONNECTION_GENERATION + 1, SCOPE, VIEW_ID,
                VIEW_GENERATION);

        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(reconnected, CONNECTION_GENERATION + 1, LARGE_PARTS.get(0)));
        assembler.closeConnection(firstConnection, CONNECTION_GENERATION);
        for (int index = 1; index < LARGE_PARTS.size(); index++) {
            ProjectViewAssembler.Outcome outcome = assembler.accept(reconnected,
                    CONNECTION_GENERATION + 1, LARGE_PARTS.get(index));
            if (index == LARGE_PARTS.size() - 1) {
                assertInstanceOf(ProjectViewAssembler.Complete.class, outcome);
            }
        }
    }

    @Test
    void sameTokenNewerGenerationSupersedesAndOldCloseIsIgnored() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, LARGE_PARTS.get(0)));

        assembler.openView(connection, CONNECTION_GENERATION + 1, SCOPE, VIEW_ID,
                VIEW_GENERATION + 1);
        assertEquals(0, assembler.pendingCount());
        assembler.closeConnection(connection, CONNECTION_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION + 1,
                        packet(SCOPE, VIEW_ID, VIEW_GENERATION + 1, 9, 7, 3,
                                OTHER_SNAPSHOT_ID, 0, LARGE_PARTS.get(0).partCount(),
                                LARGE_PARTS.get(0).totalBytes(),
                                LARGE_PARTS.get(0).fragment())));
    }

    @Test
    void closeAndReopenResetsInstalledSequenceForTheNewViewGeneration() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        ProjectViewPart newer = parts(smallSnapshot(9, "project", 7, 3), OTHER_SNAPSHOT_ID,
                SCOPE, VIEW_ID, VIEW_GENERATION).get(0);
        assertInstanceOf(ProjectViewAssembler.Complete.class,
                assembler.accept(connection, CONNECTION_GENERATION, newer));

        assembler.closeView(connection, CONNECTION_GENERATION, VIEW_ID);
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID,
                VIEW_GENERATION + 1);
        ProjectViewPart olderForNewView = parts(smallSnapshot(1, "project", 7, 3), SNAPSHOT_ID,
                SCOPE, VIEW_ID, VIEW_GENERATION + 1).get(0);
        assertInstanceOf(ProjectViewAssembler.Complete.class,
                assembler.accept(connection, CONNECTION_GENERATION, olderForNewView));
    }

    @Test
    void sameViewGenerationCannotSwitchScopeOrDiscardInstalledSequence() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        assertInstanceOf(ProjectViewAssembler.Complete.class, assembler.accept(connection,
                CONNECTION_GENERATION, parts(smallSnapshot(9, "project", 7, 3), SNAPSHOT_ID,
                        SCOPE, VIEW_ID, VIEW_GENERATION).get(0)));

        assertFalse(assembler.openView(connection, CONNECTION_GENERATION,
                ProjectViewScope.project("other"), VIEW_ID, VIEW_GENERATION));
        assertInstanceOf(ProjectViewAssembler.Complete.class, assembler.accept(connection,
                CONNECTION_GENERATION, parts(smallSnapshot(10, "project", 7, 3), OTHER_SNAPSHOT_ID,
                        SCOPE, VIEW_ID, VIEW_GENERATION).get(0)));
        assertTrue(assembler.openView(connection, CONNECTION_GENERATION,
                ProjectViewScope.project("other"), VIEW_ID, VIEW_GENERATION + 1));
    }

    @Test
    void activeViewAndConnectionStateIsBoundedAndRemovedOnClose() {
        ProjectViewAssembler views = new ProjectViewAssembler();
        Object connection = new Object();
        for (int index = 1; index <= ProjectViewLimits.MAX_ACTIVE_VIEWS; index++) {
            views.openView(connection, CONNECTION_GENERATION, SCOPE, index, 1);
        }
        assertEquals(ProjectViewLimits.MAX_ACTIVE_VIEWS, views.activeViewCount());
        assertThrows(IllegalStateException.class,
                () -> views.openView(connection, CONNECTION_GENERATION, SCOPE,
                        ProjectViewLimits.MAX_ACTIVE_VIEWS + 1, 1));
        assertEquals(ProjectViewLimits.MAX_ACTIVE_VIEWS, views.activeViewCount());
        for (int index = 1; index <= ProjectViewLimits.MAX_ACTIVE_VIEWS; index++) {
            views.closeView(connection, CONNECTION_GENERATION, index);
        }
        assertEquals(0, views.activeViewCount());
        assertEquals(1, views.activeConnectionCount());
        views.closeConnection(connection, CONNECTION_GENERATION);
        assertEquals(0, views.activeConnectionCount());

        ProjectViewAssembler connections = new ProjectViewAssembler();
        for (int index = 0; index < ProjectViewLimits.MAX_ACTIVE_CONNECTIONS; index++) {
            connections.openView(new Object(), index + 1L, SCOPE, VIEW_ID, 1);
        }
        assertEquals(ProjectViewLimits.MAX_ACTIVE_CONNECTIONS,
                connections.activeConnectionCount());
        assertThrows(IllegalStateException.class,
                () -> connections.openView(new Object(), 99L, SCOPE, VIEW_ID, 1));
        assertEquals(ProjectViewLimits.MAX_ACTIVE_CONNECTIONS,
                connections.activeConnectionCount());
    }

    @Test
    void malformedCompletedPayloadNeverEmitsPartialOrFakeSnapshot() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        byte[] garbage = {1, 2, 3};
        ProjectViewPart packet = packet(SCOPE, VIEW_ID, VIEW_GENERATION, 9, 7, 3,
                SNAPSHOT_ID, 0, 1, garbage.length, garbage);
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, packet));
        assertEquals(ProjectViewAssembler.FailureReason.DECODE_FAILED, failed.reason());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void declaredTotalMismatchFailsAfterLastPart() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assembler.openView(connection, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        int totalBytes = ProjectViewLimits.PART_BYTES + 1;
        byte[] firstFragment = new byte[ProjectViewLimits.PART_BYTES / 2];
        byte[] secondFragment = new byte[ProjectViewLimits.PART_BYTES / 2];
        ProjectViewPart first = packet(SCOPE, VIEW_ID, VIEW_GENERATION, 9, 7, 3,
                SNAPSHOT_ID, 0, 2, totalBytes, firstFragment);
        ProjectViewPart second = packet(SCOPE, VIEW_ID, VIEW_GENERATION, 9, 7, 3,
                SNAPSHOT_ID, 1, 2, totalBytes, secondFragment);
        assertInstanceOf(ProjectViewAssembler.Pending.class,
                assembler.accept(connection, CONNECTION_GENERATION, first));
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, second));
        assertEquals(ProjectViewAssembler.FailureReason.OVER_LIMIT, failed.reason());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void nonProgressResultsAndBadArgumentsAreRejected() {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        ProjectViewPart opened = new Part(false, SCOPE, VIEW_ID, VIEW_GENERATION, 9, 7, 3,
                SNAPSHOT_ID, 0, 0, 0, new byte[0]);
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(connection, CONNECTION_GENERATION, opened));
        assertEquals(ProjectViewAssembler.FailureReason.NOT_A_PROGRESS_PART, failed.reason());

        assertThrows(NullPointerException.class,
                () -> assembler.accept(null, CONNECTION_GENERATION, opened));
        assertThrows(IllegalArgumentException.class, () -> assembler.advance(-1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewAssembler(0));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.openView(connection, 0, SCOPE, VIEW_ID, 1));
    }

    @Test
    void partAccessorCopiesCannotMutateAssembledInput() {
        byte[] fragment = {5, 6, 7};
        Part packet = new Part(true, SCOPE, VIEW_ID, VIEW_GENERATION, 9, 7, 3, SNAPSHOT_ID,
                0, 1, 3, fragment);
        fragment[0] = 99;
        assertArrayEquals(new byte[]{5, 6, 7}, packet.fragment());
        byte[] accessed = packet.fragment();
        accessed[1] = 99;
        assertArrayEquals(new byte[]{5, 6, 7}, packet.fragment());
    }

    private static ProjectProgressSnapshot largeSnapshot(long sequence, String projectId,
                                                         long catalogRevision,
                                                         long inventoryRevision) {
        List<ProgressParticipant> participants = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            participants.add(new ProgressParticipant(
                    new PlayerIdentity(new UUID(0, index + 1L), "Player" + index), 1));
        }
        List<ProjectProgressMaterial> materials = new ArrayList<>();
        for (int index = 0; index < ProjectViewLimits.CATALOG_MATERIALS; index++) {
            materials.add(new ProjectProgressMaterial(index + 1,
                    String.format("minecraft:item_%04d", index), 100, 10, 5, participants));
        }
        return new ProjectProgressSnapshot(projectId, "Project", catalogRevision,
                inventoryRevision, sequence, true, true, false, List.of(), materials);
    }

    private static ProjectProgressSnapshot smallSnapshot(long sequence, String projectId,
                                                         long catalogRevision,
                                                         long inventoryRevision) {
        return new ProjectProgressSnapshot(projectId, "Project", catalogRevision,
                inventoryRevision, sequence, true, true, false, List.of(),
                List.of(new ProjectProgressMaterial(1, "minecraft:stone", 10, 5, 2,
                        List.of(new ProgressParticipant(new PlayerIdentity(null, "Alice"), 0)))));
    }

    private static List<ProjectViewPart> parts(ProjectProgressSnapshot snapshot, String snapshotId,
                                               ProjectViewScope scope, long viewId,
                                               long viewGeneration) {
        byte[] payload = ProjectProgressCodec.encode(snapshot);
        List<ProjectProgressFragmenter.Fragment> fragments = ProjectProgressFragmenter.split(payload);
        List<ProjectViewPart> parts = new ArrayList<>(fragments.size());
        for (ProjectProgressFragmenter.Fragment fragment : fragments) {
            parts.add(new Part(true, scope, viewId, viewGeneration, snapshot.publicationSequence(),
                    snapshot.catalogRevision(), snapshot.inventoryRevision(), snapshotId,
                    fragment.partIndex(), fragment.partCount(), fragment.totalBytes(),
                    fragment.fragment()));
        }
        return List.copyOf(parts);
    }

    private static ProjectViewPart part(ProjectViewPart source, String snapshotId) {
        return packet(source.scope(), source.viewId(), source.generation(), source.sequence(),
                source.catalogRevision(), source.inventoryRevision(), snapshotId,
                source.partIndex(), source.partCount(), source.totalBytes(), source.fragment());
    }

    private static ProjectViewPart packet(ProjectViewScope scope, long viewId, long generation,
                                          long sequence, long catalogRevision,
                                          long inventoryRevision, String snapshotId,
                                          int partIndex, int partCount, int totalBytes,
                                          byte[] fragment) {
        return new Part(true, scope, viewId, generation, sequence, catalogRevision,
                inventoryRevision, snapshotId, partIndex, partCount, totalBytes, fragment);
    }

    private record Part(
            boolean isProgressPart,
            ProjectViewScope scope,
            long viewId,
            long generation,
            long sequence,
            long catalogRevision,
            long inventoryRevision,
            String snapshotId,
            int partIndex,
            int partCount,
            int totalBytes,
            byte[] fragment
    ) implements ProjectViewPart {
        private Part {
            fragment = fragment.clone();
        }

        @Override
        public byte[] fragment() {
            return fragment.clone();
        }
    }

    private static final class EqualToken {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualToken;
        }

        @Override
        public int hashCode() {
            return 42;
        }
    }
}
