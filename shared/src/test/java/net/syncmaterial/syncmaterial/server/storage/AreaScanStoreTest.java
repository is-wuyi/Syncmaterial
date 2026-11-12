package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.storage.AreaScanStore.ChunkToken;
import net.syncmaterial.syncmaterial.server.storage.AreaScanStore.CoverageState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scan-store token, fence, canonical consistency, dirty update and invalidation tests. */
class AreaScanStoreTest {
    private static final String WORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @TempDir
    Path tempDir;

    private Path dbPath;
    private SqliteProjectStore store;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("runtime-v2.sqlite");
        store = SqliteProjectStore.open(dbPath);
        await(store.areaScanStore().beginEpoch());
        project("p1");
        project("p2");
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void epochRestartDurablyStalesCoverageAndRejectsOldTokens() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5));

        ChunkToken oldToken = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        long epoch = await(store.areaScanStore().beginEpoch());
        assertEquals(CoverageState.STALE, coverage(area.id()).get(0).state());
        assertFalse(await(store.areaScanStore().applyChunkScan(oldToken, List.of())));

        store.close();
        store = SqliteProjectStore.open(dbPath);
        assertEquals(epoch, await(store.areaScanStore().currentEpoch()));
        assertEquals(CoverageState.STALE, coverage(area.id()).get(0).state(),
                "restart staleness must be durable");
        ProjectState state = snapshot("p1");
        assertFalse(state.inventory().known());
        assertEquals(Map.of(STONE, 5L), state.inventory().staging());

        scan(area, chunk, container(1, 1, STONE, 5));
        assertTrue(snapshot("p1").inventory().known());
    }

    @Test
    void fullChunkScanReplacesAllSourcesAndEmptyResultClears() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5), container(2, 2, DIRT, 7));
        assertEquals(2, containerCount());
        assertEquals(2, linkCount());

        await(store.areaScanStore().invalidateChunk(chunk));
        scan(area, chunk, container(1, 1, STONE, 6));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 6L));
        assertEquals(1, containerCount(), "unobserved canonical source is removed");
        assertEquals(1, linkCount());

        scan(area, chunk);
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of());
        assertEquals(0, containerCount());
        assertEquals(0, linkCount());
    }

    @Test
    void newerBeginRejectsOlderFullScanTokenWithoutPartialWrites() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        ChunkToken first = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        ChunkToken second = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        assertNotEquals(first.generation(), second.generation());

        assertFalse(await(store.areaScanStore().applyChunkScan(first, List.of(container(1, 1, STONE, 5)))));
        assertEquals(0, containerCount());
        assertEquals(0, linkCount());
        assertEquals(CoverageState.SCANNING, coverage(area.id()).get(0).state());

        assertTrue(await(store.areaScanStore().applyChunkScan(second, List.of(container(1, 1, STONE, 3)))));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 3L));
    }

    @Test
    void invalidateKeepsDirtySelectableAndStalesScanningRows() throws Exception {
        AreaDefinition first = staging("First", 0, 0, 15, 15);
        AreaDefinition second = staging("Second", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(first, chunk, container(1, 1, STONE, 5));
        scan(second, chunk, container(1, 1, STONE, 5));

        ChunkToken secondRescan = await(store.areaScanStore()
                .beginChunkScan(second.id(), second.geometryRevision(), chunk)).orElseThrow();
        assertEquals(CoverageState.SCANNING, coverage(second.id()).get(0).state());

        assertEquals(2, await(store.areaScanStore().invalidateChunk(chunk)));
        assertEquals(CoverageState.DIRTY, coverage(first.id()).get(0).state());
        assertEquals(CoverageState.STALE, coverage(second.id()).get(0).state());

        Optional<ChunkToken> firstDirty = await(store.areaScanStore()
                .beginContainerUpdates(first.id(), first.geometryRevision(), chunk));
        assertTrue(firstDirty.isPresent());
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                firstDirty.get(), List.of(container(1, 1, STONE, 8)), List.of())));

        assertTrue(await(store.areaScanStore()
                .beginContainerUpdates(second.id(), second.geometryRevision(), chunk)).isEmpty());
        assertFalse(await(store.areaScanStore().applyChunkScan(secondRescan, List.of())));
        assertEquals(CoverageState.STALE, coverage(second.id()).get(0).state());

        ProjectState state = snapshot("p1");
        assertFalse(state.inventory().known());
        assertEquals(8L, state.inventory().staging().get(STONE));
        assertTrue(state.inventory().freshness().stream().anyMatch(warning ->
                warning.areaId() == second.id()
                        && warning.status().equals(AreaInventoryWarning.STATUS_STALE)));
    }

    @Test
    void oldOverlappingAreaTokenCannotCommitAfterEventAndFreshApply() throws Exception {
        AreaDefinition first = staging("First", 0, 0, 15, 15);
        AreaDefinition second = staging("Second", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(first, chunk, container(1, 1, STONE, 5));
        scan(second, chunk, container(1, 1, STONE, 5));

        ChunkToken staleFirst = await(store.areaScanStore()
                .beginChunkScan(first.id(), first.geometryRevision(), chunk)).orElseThrow();
        ChunkToken staleSecond = await(store.areaScanStore()
                .beginChunkScan(second.id(), second.geometryRevision(), chunk)).orElseThrow();

        await(store.areaScanStore().invalidateChunk(chunk));
        assertFalse(await(store.areaScanStore().applyChunkScan(staleFirst, List.of(container(1, 1, STONE, 7)))));

        scan(first, chunk, container(1, 1, STONE, 7));
        assertFalse(await(store.areaScanStore().applyChunkScan(staleSecond, List.of(container(1, 1, STONE, 3)))));

        ProjectState state = snapshot("p1");
        assertFalse(state.inventory().known(), "second area is still not ready");
        assertEquals(Map.of(STONE, 7L), state.inventory().staging(), "old token must not roll canonical counts back");
    }

    @Test
    void dirtyTokensForTwoAreasShareOneFenceAndApplySequentially() throws Exception {
        AreaDefinition first = staging("First", 0, 0, 15, 15);
        AreaDefinition second = staging("Second", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(first, chunk, container(1, 1, STONE, 5));
        scan(second, chunk, container(1, 1, STONE, 5));

        await(store.areaScanStore().invalidateChunk(chunk));
        ChunkToken firstToken = await(store.areaScanStore()
                .beginContainerUpdates(first.id(), first.geometryRevision(), chunk)).orElseThrow();
        ChunkToken secondToken = await(store.areaScanStore()
                .beginContainerUpdates(second.id(), second.geometryRevision(), chunk)).orElseThrow();
        assertEquals(firstToken.fenceGeneration(), secondToken.fenceGeneration());

        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                secondToken, List.of(container(1, 1, STONE, 11)), List.of())));
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                firstToken, List.of(container(1, 1, STONE, 8)), List.of())));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 11L));
        assertEquals(CoverageState.COMPLETE, coverage(first.id()).get(0).state());
        assertEquals(CoverageState.COMPLETE, coverage(second.id()).get(0).state());

        // A second event gives both areas fresh tokens; an older apply after a newer one must
        // still not overwrite the canonical counts or resurrect a removed source.
        await(store.areaScanStore().invalidateChunk(chunk));
        ChunkToken older = await(store.areaScanStore()
                .beginContainerUpdates(first.id(), first.geometryRevision(), chunk)).orElseThrow();
        ChunkToken newer = await(store.areaScanStore()
                .beginContainerUpdates(second.id(), second.geometryRevision(), chunk)).orElseThrow();
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                newer, List.of(), List.of(new ContainerKey(WORLD, 1, 1, 1)))));
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                older, List.of(container(1, 1, STONE, 99)), List.of())));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of());
        assertEquals(0, containerCount());
        assertEquals(1, tombstoneCount());
    }

    @Test
    void dirtyUpdateDuringActiveFullScanInvalidatesScanAndRequiresFullScan() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5));

        ChunkToken active = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        assertTrue(await(store.areaScanStore()
                .beginContainerUpdates(area.id(), area.geometryRevision(), chunk)).isEmpty());
        assertEquals(CoverageState.STALE, coverage(area.id()).get(0).state());
        assertFalse(await(store.areaScanStore().applyChunkScan(active, List.of(container(1, 1, STONE, 9)))));

        scan(area, chunk, container(1, 1, STONE, 9));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 9L));
    }

    @Test
    void dirtyUpdatesTouchOnlyListedSources() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5), container(2, 2, DIRT, 7), container(3, 3, STONE, 2));

        await(store.areaScanStore().invalidateChunk(chunk));
        ChunkToken token = await(store.areaScanStore()
                .beginContainerUpdates(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                token,
                List.of(container(1, 1, STONE, 8)),
                List.of(new ContainerKey(WORLD, 2, 1, 2)))));

        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 10L));
        assertEquals(2, containerCount(), "updated and untouched sources remain, removed one is gone");
        assertEquals(1, coverage(area.id()).size());
        assertTrue(coverage(area.id()).get(0).generation() > 0);
    }

    @Test
    void applyRejectsWrongWorldChunkAndAreaGeometryWithoutPartialWrites() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        ChunkToken token = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();

        assertInstanceOf(IllegalArgumentException.class, failureOf(store.areaScanStore()
                .applyChunkScan(token, List.of(container(16, 1, STONE, 5)))));
        assertInstanceOf(IllegalArgumentException.class, failureOf(store.areaScanStore()
                .applyChunkScan(token, List.of(container(NETHER, 1, 1, STONE, 5)))));
        assertEquals(CoverageState.SCANNING, coverage(area.id()).get(0).state());
        assertEquals(0, containerCount());
        assertEquals(0, linkCount());

        ContainerObservation outsideHeight = new ContainerObservation(
                new ContainerKey(WORLD, 1, 100, 1), Map.of(STONE, 5L), "");
        assertInstanceOf(IllegalArgumentException.class, failureOf(store.areaScanStore()
                .applyChunkScan(token, List.of(outsideHeight))));
        assertEquals(CoverageState.SCANNING, coverage(area.id()).get(0).state(),
                "failed apply must roll back its state update");
        assertEquals(0, containerCount());
        assertEquals(0, linkCount());

        assertTrue(await(store.areaScanStore().applyChunkScan(token, List.of(container(1, 1, STONE, 4)))));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 4L));

        await(store.areaScanStore().invalidateChunk(chunk));
        ChunkToken dirty = await(store.areaScanStore()
                .beginContainerUpdates(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        assertInstanceOf(IllegalArgumentException.class, failureOf(store.areaScanStore()
                .applyContainerUpdates(dirty, List.of(outsideHeight), List.of())));
        assertEquals(CoverageState.DIRTY, coverage(area.id()).get(0).state());
        ProjectState pending = snapshot("p1");
        assertFalse(pending.inventory().known());
        assertEquals(Map.of(STONE, 4L), pending.inventory().staging());
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                dirty, List.of(container(1, 1, STONE, 6)), List.of())));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 6L));
    }

    @Test
    void markChunkStaleInvalidatesInFlightScanAndKeepsLastKnownData() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5));
        ChunkToken active = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();

        assertEquals(1, await(store.areaScanStore().markChunkStale(chunk)));
        assertEquals(CoverageState.STALE, coverage(area.id()).get(0).state());
        assertFalse(await(store.areaScanStore().applyChunkScan(active, List.of(container(1, 1, STONE, 9)))));
        assertEquals(Map.of(STONE, 5L), snapshot("p1").inventory().staging());
        assertFalse(snapshot("p1").inventory().known());
        assertEquals(0, await(store.areaScanStore().markChunkStale(chunk(9, 9))));
    }

    @Test
    void geometryChangeRemovesCoverageAndRejectsOldTokens() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        scan(area, chunk, container(1, 1, STONE, 5));
        ChunkToken active = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();

        AreaDefinition moved = await(store.updateArea("p1", AreaKind.STAGING_AREA, area.id(),
                new AreaSpec("Staging", WORLD, new AreaBounds(32, 0, 0, 47, 15, 15))));
        assertEquals(1, moved.geometryRevision());
        assertFalse(await(store.areaScanStore().applyChunkScan(active, List.of(container(1, 1, STONE, 9)))));
        assertTrue(coverage(area.id()).isEmpty(), "old geometry coverage is deleted in the CRUD transaction");
        assertFalse(snapshot("p1").inventory().known());

        scan(moved, chunk(2, 0), container(33, 1, STONE, 7));
        AreaInventoryViewAssertions.assertCounts(snapshot("p1"), Map.of(STONE, 7L));
    }

    @Test
    void abandonMarksScanStaleAndBlocksApply() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);
        ChunkToken token = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();

        assertTrue(await(store.areaScanStore().abandonChunkScan(token)));
        assertEquals(CoverageState.STALE, coverage(area.id()).get(0).state());
        assertFalse(await(store.areaScanStore().applyChunkScan(token, List.of())));
        assertFalse(await(store.areaScanStore().abandonChunkScan(token)));
    }

    @Test
    void beginRejectsRemovedMismatchedAndOutOfBoundsAreasWithoutRows() throws Exception {
        AreaDefinition area = staging("Staging", 0, 0, 15, 15);
        ChunkKey chunk = chunk(0, 0);

        assertTrue(await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision() + 1, chunk)).isEmpty());
        assertTrue(await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk(5, 0))).isEmpty());
        assertTrue(await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), new ChunkKey(NETHER, 0, 0))).isEmpty());
        assertTrue(await(store.areaScanStore()
                .beginChunkScan(9999, 0, chunk)).isEmpty());
        assertInstanceOf(IllegalArgumentException.class, failureOf(
                store.areaScanStore().beginChunkScan(0, 0, chunk)));
        assertTrue(coverage(area.id()).isEmpty());

        assertTrue(await(store.removeArea("p1", AreaKind.STAGING_AREA, area.id())));
        assertTrue(await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).isEmpty());
        assertTrue(await(store.areaScanStore().coverage(area.id())).isEmpty());
        assertInstanceOf(IllegalArgumentException.class, failureOf(store.areaScanStore().coverage(0)));
    }

    @Test
    void sharedWarehouseSourceIsConsistentAcrossProjects() throws Exception {
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE,
                new AreaSpec("Warehouse", WORLD, new AreaBounds(0, 0, 0, 15, 15, 15))));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        assertTrue(await(store.addWarehouseReference("p2", warehouse.id())));
        ChunkKey chunk = chunk(0, 0);
        scan(warehouse, chunk, container(1, 1, STONE, 5));

        assertEquals(Map.of(STONE, 5L), snapshot("p1").inventory().warehouse());
        assertEquals(Map.of(STONE, 5L), snapshot("p2").inventory().warehouse());

        await(store.areaScanStore().invalidateChunk(chunk));
        ChunkToken token = await(store.areaScanStore()
                .beginContainerUpdates(warehouse.id(), warehouse.geometryRevision(), chunk)).orElseThrow();
        assertTrue(await(store.areaScanStore().applyContainerUpdates(
                token, List.of(container(1, 1, STONE, 9)), List.of())));
        assertEquals(Map.of(STONE, 9L), snapshot("p1").inventory().warehouse());
        assertEquals(Map.of(STONE, 9L), snapshot("p2").inventory().warehouse());
        assertEquals(1, await(store.warehouseContainers("p1", WORLD)).size());
        assertEquals(1, await(store.warehouseContainers("p2", WORLD)).size());
    }

    @Test
    void closeRejectsScanStoreOperations() throws Exception {
        SqliteProjectStore closed = store;
        store = null;
        closed.close();
        assertInstanceOf(IllegalStateException.class, failureOf(closed.areaScanStore().beginEpoch()));
        assertInstanceOf(IllegalStateException.class,
                failureOf(closed.areaScanStore().coverage(1)));
    }

    // ========== Helpers ==========

    private AreaDefinition staging(String name, int x1, int z1, int x2, int z2) throws Exception {
        return await(store.addArea("p1", AreaKind.STAGING_AREA,
                new AreaSpec(name, WORLD, new AreaBounds(x1, 0, z1, x2, 15, z2))));
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static ContainerObservation container(int x, int z, String itemId, long count) {
        return new ContainerObservation(new ContainerKey(WORLD, x, 1, z), Map.of(itemId, count), "");
    }

    private static ContainerObservation container(String world, int x, int z, String itemId, long count) {
        return new ContainerObservation(new ContainerKey(world, x, 1, z), Map.of(itemId, count), "");
    }

    private List<AreaScanStore.ChunkCoverage> coverage(int areaId) throws Exception {
        return await(store.areaScanStore().coverage(areaId));
    }

    private void project(String projectId) throws Exception {
        await(store.ingest(
                new SchematicMetadata(projectId, "Project " + projectId,
                        Path.of("/schematics", projectId + ".litematic"), "hash-" + projectId,
                        new PlayerIdentity(null, "Owner")),
                List.of(new MaterialCount(STONE, 1))));
    }

    private ProjectState snapshot(String projectId) throws Exception {
        return await(store.snapshot(projectId)).orElseThrow();
    }

    private void scan(AreaDefinition area, ChunkKey chunk, ContainerObservation... observations) throws Exception {
        ChunkToken token = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk)).orElseThrow();
        assertTrue(await(store.areaScanStore().applyChunkScan(token, List.of(observations))));
    }

    private long containerCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM containers");
    }

    private long linkCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM area_chunk_containers");
    }

    private long tombstoneCount() throws Exception {
        return scalar("SELECT COUNT(*) FROM container_tombstones");
    }

    private long scalar(String sql) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }

    private static final class AreaInventoryViewAssertions {
        private AreaInventoryViewAssertions() {
        }

        static void assertCounts(ProjectState state, Map<String, Long> expected) {
            assertTrue(state.inventory().known());
            assertEquals(expected, state.inventory().staging());
            assertTrue(state.inventory().warehouse().isEmpty());
        }
    }
}
