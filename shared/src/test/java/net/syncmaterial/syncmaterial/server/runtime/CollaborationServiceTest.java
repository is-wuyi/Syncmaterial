package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationServiceTest {
    @TempDir
    Path temporary;

    @Test
    void joinWritesClaimAndInitialObservationAtomicallyAndRepeatIsSuccessfulNoChange() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity visitor = player("Visitor");
            int material = project(runtime, "p", player("Owner"));
            runtime.connected(visitor.uuid());

            CollaborationOutcome first = await(runtime.collaboration().join(
                    "p", material, visitor, Map.of(material, 12L)));
            assertEquals(CollaborationOutcome.Status.APPLIED, first.status());
            assertTrue(first.success());
            assertTrue(first.changed());
            assertEquals(12, runtime.inventoryCount(visitor.uuid(), "p", material));
            assertEquals(Map.of(material, 12L), await(runtime.store().inventory("p", visitor)));

            CollaborationOutcome repeated = await(runtime.collaboration().join(
                    "p", material, visitor, Map.of(material, 24L)));
            assertEquals(CollaborationOutcome.Status.UNCHANGED, repeated.status());
            assertTrue(repeated.success());
            assertFalse(repeated.changed());
            assertEquals(24, runtime.inventoryCount(visitor.uuid(), "p", material));
            assertEquals(Map.of(material, 24L), await(runtime.store().inventory("p", visitor)));
            assertEquals(1, await(runtime.store().claims("p")).size());
        }
    }

    @Test
    void leaveIsAnExplicitResultAndRepeatedLeaveIsSuccessfulNoChange() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity visitor = player("Visitor");
            int material = project(runtime, "p", player("Owner"));
            runtime.connected(visitor.uuid());
            await(runtime.collaboration().join("p", material, visitor, Map.of(material, 12L)));

            CollaborationOutcome left = await(runtime.collaboration().leave("p", material, visitor));
            assertEquals(CollaborationOutcome.Status.APPLIED, left.status());
            assertEquals(0, runtime.inventoryCount(visitor.uuid(), "p", material));
            assertTrue(await(runtime.store().claims("p")).isEmpty());
            assertTrue(await(runtime.store().inventory("p", visitor)).isEmpty());

            CollaborationOutcome repeated = await(runtime.collaboration().leave("p", material, visitor));
            assertEquals(CollaborationOutcome.Status.UNCHANGED, repeated.status());
            assertTrue(repeated.success());
        }
    }

    @Test
    void unclaimedReportAndMissingProjectAreRejectedWithoutObservations() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity visitor = player("Visitor");
            int material = project(runtime, "p", player("Owner"));
            runtime.connected(visitor.uuid());

            CollaborationOutcome unclaimed = await(runtime.collaboration().report("p", material, visitor, 64));
            assertEquals(CollaborationOutcome.Status.NOT_CLAIMED, unclaimed.status());
            assertFalse(unclaimed.success());
            assertEquals(0, runtime.inventoryCount(visitor.uuid(), "p", material));
            assertTrue(await(runtime.store().inventory("p", visitor)).isEmpty());

            CollaborationOutcome missing = await(runtime.collaboration().report("missing", material, visitor, 64));
            assertEquals(CollaborationOutcome.Status.PROJECT_MISSING, missing.status());
            assertThrows(IllegalArgumentException.class, () -> runtime.collaboration().report(
                    "p", material, new PlayerIdentity(null, "Legacy"), 1));
        }
    }

    @Test
    void deniedRepeatJoinDoesNotBlockAnExistingParticipantsLaterReport() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity owner = player("Owner");
            PlayerIdentity visitor = player("Visitor");
            int material = project(runtime, "p", owner);
            runtime.connected(owner.uuid());
            runtime.connected(visitor.uuid());
            await(runtime.collaboration().join("p", material, visitor, Map.of(material, 12L)));
            await(runtime.teams().execute(new TeamCommand("p", 1, TeamAction.TOGGLE_SELF_CLAIM,
                    -1, List.of(), List.of()), owner, () -> true));

            CollaborationOutcome denied = await(runtime.collaboration().join(
                    "p", material, visitor, Map.of(material, 20L)));
            assertEquals(CollaborationOutcome.Status.DENIED, denied.status());
            CollaborationOutcome report = await(runtime.collaboration().report("p", material, visitor, 18));
            assertEquals(CollaborationOutcome.Status.APPLIED, report.status());
            assertEquals(18, runtime.inventoryCount(visitor.uuid(), "p", material));
        }
    }

    @Test
    void queuedLeaveRejoinAndReportsEndWithTheLatestObservation() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity visitor = player("Visitor");
            int material = project(runtime, "p", player("Owner"));
            runtime.connected(visitor.uuid());
            await(runtime.collaboration().join("p", material, visitor, Map.of(material, 1L)));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<?> barrier = runtime.store().snapshot("p", state -> {
                entered.countDown();
                awaitLatch(release);
                return state;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            CompletableFuture<CollaborationOutcome> oldReport = runtime.collaboration().report(
                    "p", material, visitor, 64);
            CompletableFuture<CollaborationOutcome> leave = runtime.collaboration().leave("p", material, visitor);
            CompletableFuture<CollaborationOutcome> rejoin = runtime.collaboration().join(
                    "p", material, visitor, Map.of(material, 7L));
            CompletableFuture<CollaborationOutcome> latest = runtime.collaboration().report(
                    "p", material, visitor, 9);
            release.countDown();

            assertEquals(CollaborationOutcome.Status.SUPERSEDED, await(oldReport).status(),
                    "the reserved leave/rejoin transition must fence the older captured report");
            assertEquals(CollaborationOutcome.Status.SUPERSEDED, await(leave).status(),
                    "the newer rejoin intent supersedes the queued leave before admission");
            assertEquals(CollaborationOutcome.Status.UNCHANGED, await(rejoin).status(),
                    "the skipped leave means the existing claim need not be inserted again");
            assertEquals(CollaborationOutcome.Status.APPLIED, await(latest).status());
            await(barrier);
            assertEquals(9, runtime.inventoryCount(visitor.uuid(), "p", material));
            assertEquals(Map.of(material, 9L), await(runtime.store().inventory("p", visitor)));
        }
    }

    @Test
    void kickCannotOvertakeAnEarlierWriterCommitProjection() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            PlayerIdentity owner = player("Owner");
            PlayerIdentity target = player("Target");
            int material = project(runtime, "p", owner);
            OnlineInventoryTracker inventories = new OnlineInventoryTracker();
            inventories.connected(target.uuid());
            CountDownLatch reportCommitted = new CountDownLatch(1);
            CountDownLatch releaseReport = new CountDownLatch(1);
            CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
            AtomicBoolean blockReport = new AtomicBoolean();
            CollaborationService collaboration = new CollaborationService(runtime.store(), inventories,
                    runtime::isMaterialSnapshotCurrent, runtime::isOpen, project -> {
                events.add("report");
                if (blockReport.get()) {
                    reportCommitted.countDown();
                    awaitLatch(releaseReport);
                }
            });
            TeamManagementService teams = new TeamManagementService(runtime.store(), collaboration,
                    Runnable::run, runtime::isMaterialSnapshotCurrent, runtime::isOpen,
                    project -> events.add("kick"), project -> {});
            await(collaboration.join("p", material, target, Map.of(material, 12L)));
            events.clear();
            blockReport.set(true);

            CompletableFuture<CollaborationOutcome> report = collaboration.report("p", material, target, 32);
            assertTrue(reportCommitted.await(5, TimeUnit.SECONDS));
            CompletableFuture<?> kick = teams.execute(new TeamCommand("p", 1, TeamAction.KICK,
                    -1, List.of(material), List.of(target)), owner, () -> true);
            assertFalse(kick.isDone(), "a later kick must not overtake the report's writer-local projection");
            releaseReport.countDown();

            assertEquals(CollaborationOutcome.Status.APPLIED, await(report).status());
            await(kick);
            assertEquals(List.of("report", "kick"), events);
            assertEquals(0, inventories.count(target.uuid(), "p", material));
            assertTrue(await(runtime.store().claims("p")).isEmpty());
            assertTrue(await(runtime.store().inventory("p", target)).isEmpty());
        }
    }

    private ServerRuntime runtime() throws Exception {
        Path root = Files.createTempDirectory(temporary, "collaboration-");
        return ServerRuntime.open(root.resolve("world"), root.resolve("game"),
                path -> List.of(new MaterialCount("minecraft:stone", 64)), ignored -> {});
    }

    private static int project(ServerRuntime runtime, String id, PlayerIdentity owner) throws Exception {
        await(runtime.store().ingest(new SchematicMetadata(id, id, Path.of(id + ".litematic"), "hash", owner),
                List.of(new MaterialCount("minecraft:stone", 64)), runtime.interpretationId()));
        return await(runtime.store().findProject(id)).orElseThrow().materials().getFirst().id();
    }

    private static PlayerIdentity player(String name) {
        return new PlayerIdentity(UUID.randomUUID(), name);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("writer was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("writer was interrupted", interrupted);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
