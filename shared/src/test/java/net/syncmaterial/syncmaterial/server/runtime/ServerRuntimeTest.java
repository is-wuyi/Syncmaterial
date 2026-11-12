package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void newRuntimeDoesNotOpenOrModifyTheLegacyDatabase() throws Exception {
        Path game = Files.createDirectory(directory.resolve("game"));
        byte[] legacy = {9, 8, 7, 6};
        Files.write(game.resolve("syncmaterial.db"), legacy);
        Path world = directory.resolve("world");
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            runtime.ingest(metadata("project", "hash")).get(5, TimeUnit.SECONDS);
            assertTrue(Files.exists(world.resolve("syncmaterial/runtime-v2.sqlite")));
            assertArrayEquals(legacy, Files.readAllBytes(game.resolve("syncmaterial.db")));
            assertEquals(1, runtime.store().listProjects().get().size());
        }
        assertArrayEquals(legacy, Files.readAllBytes(game.resolve("syncmaterial.db")));
    }

    @Test
    void closeAndReopenHaveIndependentRuntimeAndInventoryState() throws Exception {
        Path world = directory.resolve("world");
        Path game = Files.createDirectory(directory.resolve("game"));
        UUID epoch;
        UUID player = UUID.randomUUID();
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            epoch = runtime.epoch();
            runtime.connected(player);
            runtime.subscribe(player, "project");
            assertEquals(1, runtime.subscribers("project").size());
        }
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            assertFalse(epoch.equals(runtime.epoch()));
            assertTrue(runtime.subscribers("project").isEmpty());
            assertEquals(0, runtime.inventoryCount(player, "project", 1));
        }
    }

    @Test
    void runtimeSnapshotFreshnessTracksLatestHashAndNotifiesOnFailure() throws Exception {
        Path world = directory.resolve("world-freshness");
        Path game = Files.createDirectory(directory.resolve("game-freshness"));
        AtomicBoolean fail = new AtomicBoolean();
        CopyOnWriteArrayList<String> changed = new CopyOnWriteArrayList<>();
        MaterialDecoder decoder = new MaterialDecoder() {
            @Override
            public List<MaterialCount> decode(Path path) {
                if (fail.get()) throw new IllegalArgumentException("broken schematic");
                int count = path.getFileName().toString().startsWith("new") ? 64 : 32;
                return List.of(new MaterialCount("minecraft:stone", count));
            }

            @Override
            public String interpretationId() {
                return "int-a";
            }
        };

        try (ServerRuntime runtime = ServerRuntime.open(world, game, decoder, changed::add)) {
            runtime.ingest(metadata("project", "old")).get(5, TimeUnit.SECONDS);
            var oldSnapshot = runtime.store().findProject("project").get().orElseThrow();
            assertTrue(runtime.isMaterialSnapshotCurrent(oldSnapshot));
            runtime.tick();
            assertTrue(changed.remove("project"));

            fail.set(true);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> runtime.ingest(metadata("project", "new")).get(5, TimeUnit.SECONDS));
            assertFalse(runtime.isMaterialSnapshotCurrent(oldSnapshot));
            assertEquals("old", runtime.store().findProject("project").get().orElseThrow().hash());
            runtime.tick();
            assertTrue(changed.remove("project"), "failed ingest must enqueue projectChanged");

            fail.set(false);
            runtime.ingest(metadata("project", "new")).get(5, TimeUnit.SECONDS);
            var newSnapshot = runtime.store().findProject("project").get().orElseThrow();
            assertEquals("new", newSnapshot.hash());
            assertTrue(runtime.isMaterialSnapshotCurrent(newSnapshot));
            runtime.tick();
            assertTrue(changed.remove("project"));
        }
    }

    @Test
    void runtimeFreezesInterpretationAndDrivesHookLifecycle() throws Exception {
        Path world = directory.resolve("world-hook");
        Path game = Files.createDirectory(directory.resolve("game-hook"));
        MutableDecoder decoder = new MutableDecoder("mc:100/decoder:1/materials:1");
        RecordingHook hook = new RecordingHook();

        try (ServerRuntime runtime = ServerRuntime.open(world, game, decoder, id -> {}, hook)) {
            assertEquals(1, hook.attaches.get());
            assertEquals("mc:100/decoder:1/materials:1", runtime.interpretationId());

            // A decoder must not be able to change semantics under a live runtime.
            decoder.interpretation = "mc:200/decoder:2/materials:1";
            runtime.ingest(metadata("project", "hash")).get(5, TimeUnit.SECONDS);
            assertEquals("mc:100/decoder:1/materials:1", runtime.interpretationId());
            assertEquals("mc:100/decoder:1/materials:1",
                    runtime.store().findProject("project").get().orElseThrow().interpretationId());

            for (int i = 0; i < 19; i++) {
                runtime.tick();
            }
            assertEquals(0, hook.ticks.get());
            runtime.tick();
            assertEquals(1, hook.ticks.get());
        }
        assertEquals(1, hook.closes.get());
    }

    @Test
    void attachFailureClosesCreatedResourcesAndReleasesDatabaseLease() throws Exception {
        Path world = directory.resolve("world-attach-failure");
        Path game = Files.createDirectory(directory.resolve("game-attach-failure"));
        AtomicInteger closes = new AtomicInteger();
        LifecycleHook hook = new LifecycleHook() {
            @Override
            public void attach(ServerRuntime runtime) {
                throw new IllegalStateException("attach failed");
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ServerRuntime.open(world, game,
                        path -> List.of(new MaterialCount("minecraft:stone", 1)), id -> {}, hook));
        assertEquals("attach failed", failure.getMessage());
        assertEquals(1, closes.get(), "failed attach must still close the hook once");

        // The store/path lease and executors were created before attach: a subsequent
        // open must succeed instead of reporting a leaked writer.
        try (SqliteProjectStore store = SqliteProjectStore.open(
                world.resolve("syncmaterial/runtime-v2.sqlite"))) {
            assertTrue(store.listProjects().get().isEmpty());
        }
    }

    @Test
    void hookCloseFailureDoesNotSkipStoreCleanup() throws Exception {
        Path world = directory.resolve("world-hook-close-failure");
        Path game = Files.createDirectory(directory.resolve("game-hook-close-failure"));
        AtomicInteger closes = new AtomicInteger();
        LifecycleHook hook = new LifecycleHook() {
            @Override
            public void attach(ServerRuntime runtime) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                throw new IllegalStateException("close failed");
            }
        };

        ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 1)), id -> {}, hook);
        runtime.close();
        assertEquals(1, closes.get());

        // Store must still be closed and its path lease released.
        try (SqliteProjectStore store = SqliteProjectStore.open(
                world.resolve("syncmaterial/runtime-v2.sqlite"))) {
            assertTrue(store.listProjects().get().isEmpty());
        }
    }

    @Test
    void runtimeTickDrainsAreaConfigurationAndCloseCompletesPendingRequests() throws Exception {
        Path game = Files.createDirectory(directory.resolve("game-area-lifecycle"));
        ServerRuntime runtime = ServerRuntime.open(directory.resolve("world-area-lifecycle"), game,
                path -> List.of(new MaterialCount("minecraft:stone", 1)), id -> {});
        try {
            var refresh = runtime.areas().refreshConfiguration();
            runtime.store().areaCatalog().get(5, TimeUnit.SECONDS);
            assertFalse(refresh.isDone(), "storage completion must be applied on the runtime tick");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!refresh.isDone() && System.nanoTime() < deadline) {
                runtime.tick();
                Thread.yield();
            }
            refresh.get(5, TimeUnit.SECONDS);
            assertTrue(runtime.areas().configuration().isPresent());

            var pending = runtime.areas().refreshConfiguration();
            runtime.store().areaCatalog().get(5, TimeUnit.SECONDS);
            assertFalse(pending.isDone());
            runtime.close();
            assertTrue(pending.isCompletedExceptionally(), "runtime close must cancel pending scanner requests");
            assertFalse(runtime.isOpen());
        } finally {
            runtime.close();
        }
    }

    private static final class MutableDecoder implements MaterialDecoder {
        private String interpretation;

        private MutableDecoder(String interpretation) {
            this.interpretation = interpretation;
        }

        @Override
        public List<MaterialCount> decode(Path schematicPath) {
            return List.of(new MaterialCount("minecraft:stone", 1));
        }

        @Override
        public String interpretationId() {
            return interpretation;
        }
    }

    private static final class RecordingHook implements LifecycleHook {
        private final AtomicInteger attaches = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void attach(ServerRuntime runtime) {
            attaches.incrementAndGet();
        }

        @Override
        public void tick() {
            ticks.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Project", directory.resolve(hash + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }
}
