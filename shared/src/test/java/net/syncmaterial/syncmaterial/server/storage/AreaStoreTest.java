package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stable area CRUD, projection, staging priority and warehouse container tests. */
class AreaStoreTest {
    private static final String WORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @TempDir
    Path tempDir;

    private SqliteProjectStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = SqliteProjectStore.open(tempDir.resolve("runtime-v2.sqlite"));
        await(store.areaScanStore().beginEpoch());
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ========== CRUD and catalog ==========

    @Test
    void areaCatalogIsImmutableAndResolvesProjectsAndReferences() throws Exception {
        project("p1");
        project("p2");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Global", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p2", warehouse.id())));

        AreaCatalog catalog = await(store.areaCatalog());
        assertEquals(2, catalog.areas().size());
        assertEquals(Set.of(warehouse.id()), catalog.referencedWarehouseIds("p2"));
        assertEquals(List.of(staging.id()), catalog.stagingAreas("p1").stream()
                .map(AreaDefinition::id).toList());
        assertEquals(List.of(warehouse.id()), catalog.referencedWarehouses("p2").stream()
                .map(AreaDefinition::id).toList());
        assertEquals("Project p1", catalog.projectNames().get("p1"));
        assertEquals(0, staging.revision());
        assertEquals(0, staging.geometryRevision());
        assertTrue(catalog.revision() > 0);

        assertThrows(UnsupportedOperationException.class,
                () -> catalog.areas().add(staging));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.warehouseReferences().get("p2").add(staging.id()));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.projectNames().put("p3", "Project p3"));

        assertEquals(Set.of(staging.id(), warehouse.id()), catalog.areasCovering(
                        new ChunkKey(WORLD, 0, 0)).stream().map(AreaDefinition::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(staging.id(), warehouse.id()), catalog.areasCovering(
                        new ContainerKey(WORLD, 1, 1, 1)).stream().map(AreaDefinition::id)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void addAreaValidatesProjectScopeKindAndUniqueNames() throws Exception {
        project("p1");
        project("p2");

        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        assertEquals("p1", staging.projectId());

        assertInstanceOf(IllegalArgumentException.class, failureOf(
                store.addArea(null, AreaKind.STAGING_AREA, spec("Bad", 0, 0, 15, 15))));
        assertInstanceOf(IllegalArgumentException.class, failureOf(
                store.addArea("p1", AreaKind.WAREHOUSE, spec("ProjectWarehouse", 0, 0, 15, 15))));
        assertInstanceOf(StorageException.class, failureOf(
                store.addArea("missing", AreaKind.STAGING_AREA, spec("Missing", 0, 0, 15, 15))));
        assertInstanceOf(StorageException.class, failureOf(
                store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15))));

        assertTrue(await(store.addArea("p2", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15))).id() > 0);
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Staging", 0, 0, 15, 15)));
        assertTrue(warehouse.isGlobalWarehouse());
        assertInstanceOf(StorageException.class, failureOf(
                store.addArea(null, AreaKind.WAREHOUSE, spec("Staging", 0, 0, 15, 15))));
    }

    @Test
    void renameAndReversedCornerUpdateKeepCoverageWhileGeometryChangeInvalidates() throws Exception {
        project("p1");
        AreaDefinition area = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        scan(area, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));

        ProjectState scanned = snapshot("p1");
        assertTrue(scanned.inventory().known());
        assertEquals(Map.of(STONE, 5L), scanned.inventory().staging());
        assertTrue(scanned.inventory().freshness().isEmpty());

        AreaDefinition renamed = await(store.renameArea("p1", area.id(), "Renamed"));
        assertEquals("Renamed", renamed.name());
        assertEquals(1, renamed.revision());
        assertEquals(0, renamed.geometryRevision());
        ProjectState afterRename = snapshot("p1");
        assertEquals(5L, afterRename.inventory().staging().get(STONE));
        assertTrue(afterRename.inventory().known());

        AreaDefinition reversed = await(store.updateArea("p1", AreaKind.STAGING_AREA, area.id(),
                new AreaSpec("Renamed", WORLD, new AreaBounds(15, 15, 15, 0, 0, 0))));
        assertEquals(2, reversed.revision());
        assertEquals(0, reversed.geometryRevision(), "same normalized shape is not a geometry change");
        assertEquals(5L, snapshot("p1").inventory().staging().get(STONE));

        AreaDefinition moved = await(store.updateArea("p1", AreaKind.STAGING_AREA, area.id(),
                spec("Renamed", 32, 0, 47, 15)));
        assertEquals(3, moved.revision());
        assertEquals(1, moved.geometryRevision());
        ProjectState movedState = snapshot("p1");
        assertFalse(movedState.inventory().known());
        assertTrue(movedState.inventory().staging().isEmpty());
        assertTrue(hasWarning(movedState.inventory(), area.id(), AreaInventoryWarning.STATUS_UNKNOWN));

        scan(moved, new ChunkKey(WORLD, 2, 0), container(33, 1, STONE, 7));
        ProjectState rescanned = snapshot("p1");
        assertTrue(rescanned.inventory().known());
        assertEquals(Map.of(STONE, 7L), rescanned.inventory().staging());
    }

    @Test
    void updateRemoveAndRenameRejectUnknownMismatchedAreaIdsAndKinds() throws Exception {
        project("p1");
        project("p2");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaDefinition other = await(store.addArea("p2", AreaKind.STAGING_AREA, spec("Other", 0, 0, 15, 15)));
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Global", 0, 0, 15, 15)));

        assertInstanceOf(StorageException.class, failureOf(
                store.updateArea("p1", AreaKind.STAGING_AREA, 9999, spec("Missing", 0, 0, 15, 15))));
        assertInstanceOf(StorageException.class, failureOf(
                store.updateArea("p1", AreaKind.STAGING_AREA, other.id(), spec("Wrong", 0, 0, 15, 15))));
        assertInstanceOf(StorageException.class, failureOf(
                store.updateArea("p1", AreaKind.WAREHOUSE, staging.id(), spec("Wrong", 0, 0, 15, 15))));
        assertInstanceOf(StorageException.class, failureOf(
                store.updateArea("p1", AreaKind.WAREHOUSE, warehouse.id(), spec("Wrong", 0, 0, 15, 15))));
        assertEquals("Global Renamed", await(store.updateArea(null, AreaKind.WAREHOUSE, warehouse.id(),
                spec("Global Renamed", 0, 0, 15, 15))).name());

        assertInstanceOf(StorageException.class, failureOf(
                store.renameArea("p1", other.id(), "Wrong")));
        assertInstanceOf(StorageException.class, failureOf(
                store.renameArea("p1", warehouse.id(), "Wrong")));
        assertInstanceOf(StorageException.class, failureOf(
                store.renameArea("p1", other.id(), "Other Renamed")));
        assertEquals("Other Renamed", await(store.renameArea("p2", other.id(), "Other Renamed")).name());

        assertInstanceOf(StorageException.class, failureOf(
                store.removeArea("p2", AreaKind.STAGING_AREA, staging.id())));
        assertInstanceOf(StorageException.class, failureOf(
                store.removeArea("p1", AreaKind.WAREHOUSE, staging.id())));
        assertInstanceOf(StorageException.class, failureOf(
                store.removeArea("p1", AreaKind.WAREHOUSE, warehouse.id())));
        assertTrue(await(store.removeArea("p1", AreaKind.STAGING_AREA, staging.id())));
        assertInstanceOf(StorageException.class, failureOf(
                store.removeArea("p1", AreaKind.STAGING_AREA, staging.id())));
        assertTrue(await(store.removeArea(null, AreaKind.WAREHOUSE, warehouse.id())));
    }

    @Test
    void clearStagingAreasRemovesOnlyStagingAndKeepsReferences() throws Exception {
        project("p1");
        AreaDefinition first = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("One", 0, 0, 15, 15)));
        await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Two", 16, 0, 31, 15)));
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Global", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));

        assertEquals(2, await(store.clearStagingAreas("p1")));
        AreaCatalog catalog = await(store.areaCatalog());
        assertTrue(catalog.stagingAreas("p1").isEmpty());
        assertEquals(Set.of(warehouse.id()), catalog.referencedWarehouseIds("p1"));
        assertEquals(0, await(store.clearStagingAreas("p1")));
        assertInstanceOf(StorageException.class, failureOf(store.clearStagingAreas("missing")));
    }

    @Test
    void warehouseReferencesValidateTargetsAndOnlyEffectiveChangesBumpRevision() throws Exception {
        project("p1");
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Global", 0, 0, 15, 15)));
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));

        assertInstanceOf(StorageException.class, failureOf(
                store.addWarehouseReference("missing", warehouse.id())));
        assertInstanceOf(StorageException.class, failureOf(
                store.addWarehouseReference("p1", 9999)));
        assertInstanceOf(StorageException.class, failureOf(
                store.addWarehouseReference("p1", staging.id())));

        long revision = await(store.areaCatalog()).revision();
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        assertEquals(revision + 1, await(store.areaCatalog()).revision());
        assertFalse(await(store.addWarehouseReference("p1", warehouse.id())));
        assertEquals(revision + 1, await(store.areaCatalog()).revision());

        assertInstanceOf(StorageException.class, failureOf(
                store.removeWarehouseReference("p1", 9999)));
        assertInstanceOf(StorageException.class, failureOf(
                store.removeWarehouseReference("p1", staging.id())));
        assertTrue(await(store.removeWarehouseReference("p1", warehouse.id())));
        assertEquals(revision + 2, await(store.areaCatalog()).revision());
        assertFalse(await(store.removeWarehouseReference("p1", warehouse.id())));
        assertEquals(revision + 2, await(store.areaCatalog()).revision());
    }

    @Test
    void projectDeleteCascadesProjectAreasAndReferencesButKeepsGlobalWarehouse() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Global", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));

        await(store.removeProject("p1"));

        AreaCatalog catalog = await(store.areaCatalog());
        assertTrue(catalog.findArea(staging.id()).isEmpty());
        assertTrue(catalog.findArea(warehouse.id()).isPresent());
        assertTrue(catalog.referencedWarehouseIds("p1").isEmpty());
        assertFalse(catalog.projectNames().containsKey("p1"));
    }

    // ========== Projection and warehouse containers ==========

    @Test
    void emptyConfigurationIsKnownAndEmptyButUnseenAreaIsUnknown() throws Exception {
        project("p1");
        AreaInventoryView empty = snapshot("p1").inventory();
        assertTrue(empty.known());
        assertTrue(empty.freshness().isEmpty());
        assertTrue(empty.staging().isEmpty());

        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaInventoryView unseen = snapshot("p1").inventory();
        assertFalse(unseen.known());
        assertTrue(unseen.staging().isEmpty());
        assertTrue(hasWarning(unseen, staging.id(), AreaInventoryWarning.STATUS_UNKNOWN));
        assertFalse(unseen.overlap());
    }

    @Test
    void stagingPriorityCountsOneSourceOnceAndOverlapIsIndependentOfFreshness() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Warehouse", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));

        scan(staging, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));
        scan(warehouse, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));

        AreaInventoryView view = snapshot("p1").inventory();
        assertTrue(view.known(), "both areas are fully covered");
        assertTrue(view.overlap(), "overlap warning is independent of freshness");
        assertEquals(Map.of(STONE, 5L), view.staging());
        assertTrue(view.warehouse().isEmpty(), "staged source must not count again as warehouse");
    }

    @Test
    void cachedWarehouseSourceIsHiddenByNewStagingGeometryBeforeStagingScan() throws Exception {
        project("p1");
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Warehouse", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        scan(warehouse, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));

        AreaInventoryView warehouseOnly = snapshot("p1").inventory();
        assertTrue(warehouseOnly.known());
        assertEquals(Map.of(STONE, 5L), warehouseOnly.warehouse());
        assertEquals(1, await(store.warehouseContainers("p1", WORLD)).size());

        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaInventoryView shadowed = snapshot("p1").inventory();
        assertFalse(shadowed.known(), "unscanned staging must not be ready");
        assertTrue(shadowed.staging().isEmpty());
        assertTrue(shadowed.warehouse().isEmpty(),
                "physically staged source is excluded from warehouse even before the staging scan");
        assertTrue(hasWarning(shadowed, staging.id(), AreaInventoryWarning.STATUS_UNKNOWN));
        assertTrue(await(store.warehouseContainers("p1", WORLD)).isEmpty());
        assertTrue(shadowed.overlap());

        scan(staging, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));
        AreaInventoryView staged = snapshot("p1").inventory();
        assertTrue(staged.known());
        assertEquals(Map.of(STONE, 5L), staged.staging());
        assertTrue(staged.warehouse().isEmpty());
    }

    @Test
    void stagingPriorityDoesNotLeakAcrossProjects() throws Exception {
        project("p1");
        project("p2");
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Warehouse", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        assertTrue(await(store.addWarehouseReference("p2", warehouse.id())));
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));

        scan(warehouse, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));
        scan(staging, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));

        AreaInventoryView p1 = snapshot("p1").inventory();
        AreaInventoryView p2 = snapshot("p2").inventory();
        assertEquals(Map.of(STONE, 5L), p1.staging());
        assertTrue(p1.warehouse().isEmpty());
        assertEquals(Map.of(STONE, 5L), p2.warehouse(),
                "another project's staging must not reserve a shared warehouse source");
        assertTrue(p2.staging().isEmpty());
    }

    @Test
    void warehouseContainersUseOnlyReferencedWarehousesRequestedWorldAndExcludeStaged() throws Exception {
        project("p1");
        AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE,
                new AreaSpec("Warehouse", WORLD, new AreaBounds(0, 0, 0, 31, 15, 15))));
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        AreaDefinition netherWarehouse = await(store.addArea(null, AreaKind.WAREHOUSE,
                new AreaSpec("Nether", NETHER, new AreaBounds(0, 0, 0, 15, 15, 15))));
        AreaDefinition unreferenced = await(store.addArea(null, AreaKind.WAREHOUSE, spec("Unreferenced", 0, 0, 15, 15)));
        assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        assertTrue(await(store.addWarehouseReference("p1", netherWarehouse.id())));

        scan(warehouse, new ChunkKey(WORLD, 0, 0), container(2, 2, DIRT, 7));
        scan(warehouse, new ChunkKey(WORLD, 1, 0), container(18, 2, STONE, 5));
        scan(staging, new ChunkKey(WORLD, 0, 0), container(2, 2, DIRT, 7));
        scan(netherWarehouse, new ChunkKey(NETHER, 0, 0), container(NETHER, 1, 1, STONE, 9));
        scan(unreferenced, new ChunkKey(WORLD, 0, 0), container(3, 3, STONE, 11));

        List<ContainerObservation> containers = await(store.warehouseContainers("p1", WORLD));
        assertEquals(1, containers.size());
        assertEquals(new ContainerKey(WORLD, 18, 1, 2), containers.get(0).key());
        assertEquals(Map.of(STONE, 5L), containers.get(0).counts());
        assertTrue(containers.get(0).warning().isEmpty());

        assertEquals(1, await(store.warehouseContainers("p1", NETHER)).size());
        assertTrue(await(store.warehouseContainers("p1", "minecraft:the_end")).isEmpty());
        assertInstanceOf(StorageException.class, failureOf(
                store.warehouseContainers("missing", WORLD)));
    }

    @Test
    void unloadedChunkKeepsLastKnownCountsButNeverStaysReady() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        ChunkKey chunk = new ChunkKey(WORLD, 0, 0);
        scan(staging, chunk, container(1, 1, STONE, 5));
        assertTrue(snapshot("p1").inventory().known());

        assertEquals(1, await(store.areaScanStore().markChunkStale(chunk)));

        ProjectState stale = snapshot("p1");
        assertFalse(stale.inventory().known());
        assertEquals(Map.of(STONE, 5L), stale.inventory().staging(), "last-known counts are retained");
        assertTrue(hasWarning(stale.inventory(), staging.id(), AreaInventoryWarning.STATUS_STALE));

        scan(staging, chunk, container(1, 1, STONE, 5));
        assertTrue(snapshot("p1").inventory().known());
    }

    @Test
    void destroyedSourceRemovedExactlyWhenLoadedChunkRescanned() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        ChunkKey chunk = new ChunkKey(WORLD, 0, 0);
        scan(staging, chunk, container(1, 1, STONE, 5), container(2, 2, DIRT, 7));
        assertEquals(Map.of(STONE, 5L, DIRT, 7L), snapshot("p1").inventory().staging());

        await(store.areaScanStore().invalidateChunk(chunk));
        scan(staging, chunk, container(1, 1, STONE, 3));

        AreaInventoryView view = snapshot("p1").inventory();
        assertTrue(view.known());
        assertEquals(Map.of(STONE, 3L), view.staging(), "destroyed dirt source must be gone");
        assertTrue(await(store.warehouseContainers("p1", WORLD)).isEmpty());
    }

    @Test
    void opaqueContainerWarnsAndContributesNoCounts() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        ChunkKey chunk = new ChunkKey(WORLD, 0, 0);
        scan(staging, chunk, new ContainerObservation(
                new ContainerKey(WORLD, 1, 1, 1), Map.of(STONE, 9L), "loot table"));

        AreaInventoryView opaque = snapshot("p1").inventory();
        assertFalse(opaque.known());
        assertTrue(opaque.staging().isEmpty(), "opaque counts must not be presented as real");
        assertTrue(hasWarning(opaque, staging.id(), AreaInventoryWarning.STATUS_OPAQUE));

        await(store.areaScanStore().invalidateChunk(chunk));
        scan(staging, chunk, container(1, 1, STONE, 3));
        AreaInventoryView observed = snapshot("p1").inventory();
        assertTrue(observed.known());
        assertEquals(Map.of(STONE, 3L), observed.staging());
        assertTrue(observed.freshness().isEmpty());
    }

    @Test
    void projectionCountSumUsesCheckedMath() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        scan(staging, new ChunkKey(WORLD, 0, 0),
                container(1, 1, STONE, Long.MAX_VALUE),
                container(2, 2, STONE, 1));

        assertInstanceOf(ArithmeticException.class, failureOf(store.snapshot("p1")));
    }

    @Test
    void snapshotCarriesProjectClaimsAndInventoryFromOneRevision() throws Exception {
        project("p1");
        int materialId = await(store.findProject("p1")).orElseThrow().materials().get(0).id();
        PlayerIdentity alice = new PlayerIdentity(
                UUID.nameUUIDFromBytes("Alice".getBytes(StandardCharsets.UTF_8)), "Alice");
        assertTrue(await(store.join("p1", materialId, alice)));
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        scan(staging, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 2));

        ProjectState state = snapshot("p1");
        assertEquals(1, state.claims().size());
        assertEquals(materialId, state.claims().get(0).materialId());
        assertEquals(Map.of(STONE, 2L), state.inventory().staging());
        assertEquals(await(store.areaScanStore().inventoryRevision()), state.inventory().revision());
    }

    @Test
    void catalogRevisionIsConfigOnlyWhileInventoryRevisionTracksObservations() throws Exception {
        project("p1");
        AreaDefinition staging = await(store.addArea("p1", AreaKind.STAGING_AREA, spec("Staging", 0, 0, 15, 15)));
        long catalogRevision = await(store.areaCatalog()).revision();
        long inventoryRevision = await(store.areaScanStore().inventoryRevision());

        scan(staging, new ChunkKey(WORLD, 0, 0), container(1, 1, STONE, 5));
        assertEquals(catalogRevision, await(store.areaCatalog()).revision());
        assertTrue(await(store.areaScanStore().inventoryRevision()) > inventoryRevision);

        long beforeEpoch = await(store.areaScanStore().inventoryRevision());
        await(store.areaScanStore().beginEpoch());
        assertEquals(catalogRevision, await(store.areaCatalog()).revision());
        assertTrue(await(store.areaScanStore().inventoryRevision()) > beforeEpoch);
    }

    // ========== Helpers ==========

    private static AreaSpec spec(String name, int x1, int z1, int x2, int z2) {
        return new AreaSpec(name, WORLD, new AreaBounds(x1, 0, z1, x2, 15, z2));
    }

    private static ContainerObservation container(int x, int z, String itemId, long count) {
        return new ContainerObservation(new ContainerKey(WORLD, x, 1, z), Map.of(itemId, count), "");
    }

    private static ContainerObservation container(String world, int x, int z, String itemId, long count) {
        return new ContainerObservation(new ContainerKey(world, x, 1, z), Map.of(itemId, count), "");
    }

    private static boolean hasWarning(AreaInventoryView view, int areaId, String status) {
        return view.freshness().stream().anyMatch(warning -> warning.areaId() == areaId
                && warning.status().equals(status));
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
        Optional<AreaScanStore.ChunkToken> token = await(store.areaScanStore()
                .beginChunkScan(area.id(), area.geometryRevision(), chunk));
        assertTrue(token.isPresent(), "scan must be accepted for current area geometry");
        assertTrue(await(store.areaScanStore().applyChunkScan(token.get(), List.of(observations))));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
