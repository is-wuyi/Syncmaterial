package net.syncmaterial.syncmaterial.server.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.runtime.OnlineInventoryTracker;
import net.syncmaterial.syncmaterial.server.runtime.ProjectProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real JDBC reads and one runtime sample; no future callback is used as a writer-thread guarantee. */
class ProjectProgressReadTest {
    private static final String PROJECT = "project";
    private static final String STONE = "minecraft:stone";
    private static final String WORLD = "minecraft:overworld";

    @TempDir
    Path directory;

    @Test
    void snapshotProjectionSamplesInsideTheSameWriterReadBeforeQueuedWrites() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("progress.sqlite"))) {
            PlayerIdentity player = new PlayerIdentity(UUID.randomUUID(), "Alice");
            await(store.ingest(metadata("old", player), List.of(new MaterialCount(STONE, 100))));
            int materialId = await(store.findProject(PROJECT)).orElseThrow().materials().getFirst().id();
            await(store.join(PROJECT, materialId, player));
            await(store.areaScanStore().beginEpoch());
            AreaDefinition area = await(store.addArea(PROJECT, AreaKind.STAGING_AREA,
                    new AreaSpec("Staging", WORLD, new AreaBounds(0, 0, 0, 15, 15, 15))));
            var token = await(store.areaScanStore().beginChunkScan(area.id(), area.geometryRevision(),
                    new ChunkKey(WORLD, 0, 0))).orElseThrow();
            assertTrue(await(store.areaScanStore().applyChunkScan(token, List.of(
                    new ContainerObservation(new ContainerKey(WORLD, 1, 1, 1), Map.of(STONE, 16L), "")))));
            ProjectState before = await(store.snapshot(PROJECT)).orElseThrow();
            ReadProbe probe = await(store.runTestSupport(connection ->
                    new ReadProbe(connection, Thread.currentThread())));

            AtomicInteger clockReads = new AtomicInteger();
            OnlineInventoryTracker inventories = new OnlineInventoryTracker(() -> {
                clockReads.incrementAndGet();
                return 0L;
            }, Duration.ofSeconds(10));
            inventories.connected(player.uuid());
            inventories.update(player.uuid(), PROJECT, materialId, 32);
            clockReads.set(0);
            ProjectProgressService progress = new ProjectProgressService(inventories);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<java.util.Optional<ProjectProgressSnapshot>> read = store.snapshot(PROJECT, state -> {
                probe.assertReading();
                assertEquals(before, state);
                entered.countDown();
                waitFor(release);
                probe.assertReading();
                return progress.build(state, 42, true);
            });
            CompletableFuture<Void> ingest;
            CompletableFuture<Boolean> leave;
            CompletableFuture<String> later;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                ingest = store.ingest(metadata("new", player), List.of(new MaterialCount(STONE, 200)));
                leave = store.leave(PROJECT, materialId, player);
                var rename = store.renameArea(PROJECT, area.id(), "Renamed");
                later = store.snapshot(PROJECT, state -> {
                    probe.assertReading();
                    assertEquals("new", state.project().hash());
                    assertEquals(200, state.project().materials().getFirst().required());
                    assertTrue(state.claims().isEmpty());
                    return state.project().hash();
                }).thenApply(value -> value.orElseThrow());
                assertFalse(ingest.isDone());
                assertFalse(leave.isDone());
                assertFalse(rename.isDone());
                assertFalse(later.isDone());
            } finally {
                release.countDown();
            }

            ProjectProgressSnapshot snapshot = await(read).orElseThrow();
            assertEquals(before.project().revision(), snapshot.catalogRevision());
            assertEquals(before.inventory().revision(), snapshot.inventoryRevision());
            assertEquals(42, snapshot.publicationSequence());
            var material = snapshot.material(materialId).orElseThrow();
            assertEquals(100, material.required());
            assertEquals(16, material.staging());
            assertEquals(1, material.participants().size());
            assertEquals(32, material.participants().getFirst().effectiveHeld());
            assertEquals(1, clockReads.get(), "the complete read must take only one tracker clock sample");
            await(ingest);
            assertTrue(await(leave));
            assertEquals("new", await(later));
        }
    }

    @Test
    void missingProjectNeverCallsTheProjection() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("missing.sqlite"))) {
            AtomicInteger calls = new AtomicInteger();
            assertTrue(await(store.snapshot(PROJECT, state -> {
                calls.incrementAndGet();
                return state;
            })).isEmpty());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void nullProjectionResultFailsClearlyAndTheReadConnectionStaysUsable() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("null.sqlite"))) {
            await(store.ingest(metadata("hash", new PlayerIdentity(null, "Owner")),
                    List.of(new MaterialCount(STONE, 1))));
            Throwable failure = failureOf(store.snapshot(PROJECT, state -> null));
            NullPointerException invalid = assertInstanceOf(NullPointerException.class, failure);
            assertEquals("snapshot projection", invalid.getMessage());
            assertTrue(await(store.snapshot(PROJECT)).isPresent());
        }
    }

    @Test
    void projectionFailureRollsBackAndDoesNotRunAgainOrBlockTheNextWrite() throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(directory.resolve("failure.sqlite"))) {
            PlayerIdentity owner = new PlayerIdentity(null, "Owner");
            await(store.ingest(metadata("old", owner), List.of(new MaterialCount(STONE, 1))));
            ReadProbe probe = await(store.runTestSupport(connection ->
                    new ReadProbe(connection, Thread.currentThread())));
            AtomicInteger calls = new AtomicInteger();
            IllegalStateException expected = new IllegalStateException("sample failed");
            Throwable failure = failureOf(store.snapshot(PROJECT, state -> {
                probe.assertReading();
                calls.incrementAndGet();
                throw expected;
            }));
            assertSame(expected, failure);
            assertEquals(1, calls.get());
            await(store.ingest(metadata("new", owner), List.of(new MaterialCount(STONE, 2))));
            assertEquals("new", await(store.findProject(PROJECT)).orElseThrow().hash());
        }
    }

    private static SchematicMetadata metadata(String hash, PlayerIdentity player) {
        return new SchematicMetadata(PROJECT, "Project", Path.of("/schematics/project.litematic"), hash, player);
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("projection was never released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("projection was interrupted", interrupted);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }

    private record ReadProbe(Connection connection, Thread writer) {
        void assertReading() {
            assertSame(writer, Thread.currentThread());
            try {
                assertFalse(connection.getAutoCommit(), "projection must still be inside the JDBC read transaction");
            } catch (SQLException failure) {
                throw new AssertionError("the read connection is unavailable", failure);
            }
        }
    }
}
