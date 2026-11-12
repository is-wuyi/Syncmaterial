package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.area.AreaSnapshotSource;
import net.syncmaterial.syncmaterial.server.area.ChunkScanCursor;
import net.syncmaterial.syncmaterial.server.area.ChunkScanFilter;
import net.syncmaterial.syncmaterial.server.area.ChunkScanSlice;
import net.syncmaterial.syncmaterial.server.area.ScanBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AreaRuntimeIntegrationTest {
    private static final String PROJECT = "area-runtime";
    private static final String WORLD = "minecraft:overworld";
    private static final String STONE = "minecraft:stone";
    private static final ChunkKey CHUNK = new ChunkKey(WORLD, 0, 0);
    private static final ContainerKey CONTAINER = new ContainerKey(WORLD, 1, 64, 1);

    @TempDir
    Path root;

    @Test
    void runtimePersistsDedupedSourcesAndIncrementalChangesInTheRealStore() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            FixtureSource source = new FixtureSource();
            source.observations = List.of(observation(32));
            runtime.areas().attachSource(source);
            runtime.store().addArea(PROJECT, AreaKind.STAGING_AREA, spec("Staging")).get(5, TimeUnit.SECONDS);
            var warehouse = runtime.store().addArea(null, AreaKind.WAREHOUSE, spec("Warehouse")).get(5, TimeUnit.SECONDS);
            runtime.store().addWarehouseReference(PROJECT, warehouse.id()).get(5, TimeUnit.SECONDS);
            refresh(runtime);
            drain(runtime, () -> ready(runtime, 32));
            var view = runtime.store().snapshot(PROJECT).get(5, TimeUnit.SECONDS).orElseThrow().inventory();
            assertTrue(view.overlap());
            assertEquals(32L, view.staging().get(STONE));
            assertEquals(0L, view.warehouse().getOrDefault(STONE, 0L));

            source.observations = List.of(observation(48));
            runtime.areas().containerChanged(CONTAINER);
            assertFalse(runtime.areas().isInventoryCurrent(PROJECT, view));
            drain(runtime, () -> ready(runtime, 48));

            source.observations = List.of();
            runtime.areas().containerRemoved(CONTAINER);
            drain(runtime, () -> ready(runtime, 0));
            assertTrue(runtime.store().warehouseContainers(PROJECT, WORLD).get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void unloadPreservesLastKnownAndReloadReplacesRatherThanAdds() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            FixtureSource source = new FixtureSource();
            source.observations = List.of(observation(32));
            runtime.areas().attachSource(source);
            runtime.store().addArea(PROJECT, AreaKind.STAGING_AREA, spec("Staging")).get(5, TimeUnit.SECONDS);
            refresh(runtime);
            drain(runtime, () -> ready(runtime, 32));
            source.loaded = false;
            runtime.areas().chunkUnloaded(CHUNK);
            runtime.store().areaScanStore().inventoryRevision().get(5, TimeUnit.SECONDS);
            var stale = runtime.store().snapshot(PROJECT).get(5, TimeUnit.SECONDS).orElseThrow().inventory();
            assertFalse(stale.known());
            assertEquals(32L, stale.staging().get(STONE));
            assertFalse(stale.freshness().isEmpty());

            source.loaded = true;
            source.observations = List.of(observation(16));
            runtime.areas().chunkLoaded(CHUNK);
            drain(runtime, () -> ready(runtime, 16));
            assertEquals(16L, runtime.store().snapshot(PROJECT).get(5, TimeUnit.SECONDS)
                    .orElseThrow().inventory().staging().get(STONE));
        }
    }

    @Test
    void readyStateIsPublishedAfterTheFinalWatermarkClears() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            FixtureSource source = new FixtureSource();
            source.observations = List.of(observation(32));
            runtime.areas().attachSource(source);
            runtime.store().addArea(PROJECT, AreaKind.STAGING_AREA, spec("Staging")).get(5, TimeUnit.SECONDS);
            refresh(runtime);
            drain(runtime, () -> ready(runtime, 32));
            int notifications = changedProjects.size();
            source.observations = List.of(observation(48));
            runtime.areas().containerChanged(CONTAINER);
            drain(runtime, () -> ready(runtime, 48));
            var last = changedProjects.subList(notifications, changedProjects.size());
            assertTrue(last.stream().anyMatch(value -> value.endsWith(":ready")),
                    "a persisted update must publish again after its final pending watermark is released");
        }
    }

    private final java.util.List<String> changedProjects = new java.util.ArrayList<>();

    private ServerRuntime runtime() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ServerRuntime> active = new java.util.concurrent.atomic.AtomicReference<>();
        ServerRuntime runtime = ServerRuntime.open(root.resolve("world"), root,
                path -> List.of(new MaterialCount(STONE, 128)), id -> {
                    ServerRuntime current = active.get();
                    var view = current.store().snapshot(id).join().orElseThrow().inventory();
                    changedProjects.add(id + (current.areas().isInventoryCurrent(id, view) ? ":ready" : ":pending"));
                });
        active.set(runtime);
        runtime.store().ingest(new SchematicMetadata(PROJECT, "Project", Path.of("fixture.litematic"), "hash",
                new PlayerIdentity(UUID.randomUUID(), "Owner")), List.of(new MaterialCount(STONE, 128)),
                runtime.interpretationId()).get(5, TimeUnit.SECONDS);
        return runtime;
    }

    private static AreaSpec spec(String name) {
        return new AreaSpec(name, WORLD, new AreaBounds(0, 64, 0, 15, 64, 15));
    }

    private static ContainerObservation observation(long count) {
        return new ContainerObservation(CONTAINER, Map.of(STONE, count), "");
    }

    private static void refresh(ServerRuntime runtime) throws Exception {
        var pending = runtime.areas().refreshConfiguration();
        drain(runtime, pending::isDone);
        pending.get(5, TimeUnit.SECONDS);
    }

    private static boolean ready(ServerRuntime runtime, long count) {
        var view = runtime.store().snapshot(PROJECT).join().orElseThrow().inventory();
        return view.known() && view.staging().getOrDefault(STONE, 0L) == count
                && runtime.areas().isInventoryCurrent(PROJECT, view);
    }

    private static void drain(ServerRuntime runtime, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            runtime.tick();
            if (done.getAsBoolean()) return;
            runtime.store().areaCatalog().get(5, TimeUnit.SECONDS);
            Thread.yield();
        }
        fail("Area runtime did not settle through its real tick/store lifecycle");
    }

    private static final class FixtureSource implements AreaSnapshotSource {
        private boolean loaded = true;
        private List<ContainerObservation> observations = List.of();

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public Set<ChunkKey> loadedChunks() { return loaded ? Set.of(CHUNK) : Set.of(); }

        @Override
        public boolean isChunkLoaded(ChunkKey chunk) { return loaded && CHUNK.equals(chunk); }

        @Override
        public ChunkScanSlice scanChunk(ChunkKey chunk, ChunkScanFilter filter, ChunkScanCursor cursor, ScanBudget budget) {
            if (!isChunkLoaded(chunk)) return ChunkScanSlice.notLoaded();
            var selected = observations.stream().filter(value -> filter.accepts(value.key().x(), value.key().y(), value.key().z())).toList();
            return ChunkScanSlice.complete(selected, selected.size(), selected.size());
        }

        @Override
        public ContainerReadResult readContainers(ChunkKey chunk, ChunkScanFilter filter, Set<ContainerKey> keys, ScanBudget budget) {
            if (!isChunkLoaded(chunk)) return ContainerReadResult.notLoaded();
            var selected = observations.stream().filter(value -> keys.contains(value.key())).toList();
            return new ContainerReadResult(selected, true, false, selected.size(), selected.size());
        }
    }
}
