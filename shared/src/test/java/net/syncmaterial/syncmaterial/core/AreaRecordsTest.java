package net.syncmaterial.syncmaterial.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation, normalization and defensive-copy tests for the area core records. */
class AreaRecordsTest {
    private static final PlayerIdentity OWNER = new PlayerIdentity(null, "Owner");

    @Test
    void areaBoundsNormalizesReversedCornersAndAnswersContainment() {
        AreaBounds bounds = new AreaBounds(10, 70, 10, 0, 64, 0);
        assertEquals(0, bounds.minX());
        assertEquals(10, bounds.maxX());
        assertEquals(64, bounds.minY());
        assertEquals(70, bounds.maxY());
        assertEquals(0, bounds.minZ());
        assertEquals(10, bounds.maxZ());
        assertTrue(bounds.contains(0, 64, 0));
        assertTrue(bounds.contains(10, 70, 10));
        assertFalse(bounds.contains(11, 64, 10));
        assertFalse(bounds.contains(5, 71, 5));

        AreaBounds touching = new AreaBounds(10, 64, 0, 20, 70, 10);
        AreaBounds disjoint = new AreaBounds(11, 64, 0, 20, 70, 10);
        assertTrue(bounds.intersects(touching), "touching faces overlap");
        assertTrue(touching.intersects(bounds));
        assertFalse(bounds.intersects(disjoint));
    }

    @Test
    void areaBoundsChunkHelpersHandleNegativeBlockCoordinates() {
        AreaBounds bounds = new AreaBounds(-1, 0, -1, 0, 0, 0);
        assertEquals(-1, bounds.minChunkX());
        assertEquals(0, bounds.maxChunkX());
        assertEquals(-1, bounds.minChunkZ());
        assertEquals(0, bounds.maxChunkZ());
        assertEquals(4, bounds.chunkCount());
        assertTrue(bounds.containsChunk(-1, -1));
        assertTrue(bounds.containsChunk(0, 0));
        assertFalse(bounds.containsChunk(1, 0));
        assertEquals(List.of(
                        new ChunkKey("minecraft:overworld", -1, -1),
                        new ChunkKey("minecraft:overworld", -1, 0),
                        new ChunkKey("minecraft:overworld", 0, -1),
                        new ChunkKey("minecraft:overworld", 0, 0)),
                bounds.chunkKeys("minecraft:overworld"));
    }

    @Test
    void areaBoundsAcceptsExactly1024ChunksAndRejectsLargerOrOutOfRange() {
        AreaBounds exactly1024 = new AreaBounds(0, 0, 0, 511, 0, 511);
        assertEquals(32L * 32L, exactly1024.chunkCount());

        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(0, 0, 0, 527, 0, 527), "33x33 chunks is 1089, above the cap");
        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(0, 0, 0, 511, 0, 528), "one chunk too deep");
        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(30_000_001, 0, 0, 30_000_001, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(0, 4_097, 0, 0, 4_097, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(Integer.MIN_VALUE, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaBounds(0, 0, 0, 0, Integer.MIN_VALUE, 0));
    }

    @Test
    void chunkAndContainerKeysValidateWorldAndMapPositionsToChunks() {
        ChunkKey chunk = new ChunkKey("minecraft:overworld", -1, -1);
        assertEquals(-1, chunk.x());
        assertEquals(-1, chunk.z());
        assertThrows(IllegalArgumentException.class, () -> new ChunkKey(" ", 0, 0));

        ContainerKey container = new ContainerKey("minecraft:overworld", -1, 64, -17);
        assertEquals(new ChunkKey("minecraft:overworld", -1, -2), container.chunk());
        assertEquals(container, ContainerKey.fromBlock("minecraft:overworld", -1, 64, -17));
        assertEquals(new ChunkKey("minecraft:overworld", 0, 0),
                ChunkKey.fromBlock("minecraft:overworld", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerKey("", 0, 0, 0));
    }

    @Test
    void areaDefinitionEnforcesProjectScopeAndNonNegativeRevisions() {
        AreaBounds bounds = new AreaBounds(0, 0, 0, 15, 15, 15);
        AreaDefinition staging = new AreaDefinition(
                1, "p1", AreaKind.STAGING_AREA, "Staging", "minecraft:overworld", bounds, 2, 1);
        assertEquals("p1", staging.projectId());
        assertFalse(staging.isGlobalWarehouse());

        AreaDefinition warehouse = new AreaDefinition(
                2, null, AreaKind.WAREHOUSE, "Warehouse", "minecraft:overworld", bounds, 0, 0);
        assertTrue(warehouse.isGlobalWarehouse());

        assertThrows(IllegalArgumentException.class, () -> new AreaDefinition(
                3, null, AreaKind.STAGING_AREA, "Bad", "minecraft:overworld", bounds, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AreaDefinition(
                3, "p1", AreaKind.STAGING_AREA, "Bad", "minecraft:overworld", bounds, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AreaDefinition(
                3, "p1", AreaKind.STAGING_AREA, "Bad", "minecraft:overworld", bounds, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new AreaDefinition(
                0, "p1", AreaKind.STAGING_AREA, "Bad", "minecraft:overworld", bounds, 0, 0));
    }

    @Test
    void containerObservationCopiesCountsAndMarksOpaqueSources() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("minecraft:stone", 5L);
        ContainerObservation observation = new ContainerObservation(
                new ContainerKey("minecraft:overworld", 0, 64, 0), counts, "");
        counts.put("minecraft:dirt", 2L);

        assertEquals(Map.of("minecraft:stone", 5L), observation.counts());
        assertFalse(observation.isOpaque());
        assertThrows(UnsupportedOperationException.class,
                () -> observation.counts().put("minecraft:dirt", 1L));

        ContainerObservation opaque = new ContainerObservation(
                new ContainerKey("minecraft:overworld", 0, 64, 0), Map.of(), "loot table");
        assertTrue(opaque.isOpaque());

        assertThrows(IllegalArgumentException.class, () -> new ContainerObservation(
                new ContainerKey("minecraft:overworld", 0, 64, 0),
                Map.of("minecraft:stone", -1L), ""));
        assertThrows(IllegalArgumentException.class, () -> new ContainerObservation(
                new ContainerKey("minecraft:overworld", 0, 64, 0),
                Map.of("stone", 1L), ""));
        assertEquals(Map.of(), ContainerObservation.empty(
                new ContainerKey("minecraft:overworld", 0, 64, 0)).counts());
    }

    @Test
    void areaInventoryViewCopiesAndRejectsContradictoryKnownState() {
        Map<String, Long> staging = new HashMap<>();
        staging.put("minecraft:stone", 3L);
        AreaInventoryView view = new AreaInventoryView(
                staging, Map.of("minecraft:dirt", 2L), List.of(), true, true, 7);
        staging.put("minecraft:dirt", 9L);

        assertEquals(Map.of("minecraft:stone", 3L), view.staging());
        assertEquals(2L, view.warehouse().get("minecraft:dirt"));
        assertTrue(view.known());
        assertTrue(view.overlap());
        assertEquals(7, view.revision());
        assertThrows(UnsupportedOperationException.class,
                () -> view.staging().put("minecraft:dirt", 1L));

        AreaInventoryView unknown = AreaInventoryView.unknown();
        assertFalse(unknown.known());
        assertTrue(unknown.staging().isEmpty());
        assertEquals(0, unknown.revision());

        AreaInventoryWarning warning = new AreaInventoryWarning(
                AreaKind.STAGING_AREA, 1, "Staging", AreaInventoryWarning.STATUS_UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> new AreaInventoryView(
                Map.of(), Map.of(), List.of(warning), true, false, 0));
        AreaInventoryView notReady = new AreaInventoryView(
                Map.of(), Map.of(), List.of(warning), false, false, 1);
        assertTrue(notReady.hasWarnings());
    }

    @Test
    void areaCatalogCopiesCollectionsAndResolvesHelpers() {
        AreaBounds bounds = new AreaBounds(0, 0, 0, 15, 255, 15);
        List<AreaDefinition> areas = new ArrayList<>(List.of(
                new AreaDefinition(1, "p1", AreaKind.STAGING_AREA, "Staging",
                        "minecraft:overworld", bounds, 0, 0),
                new AreaDefinition(2, null, AreaKind.WAREHOUSE, "Global",
                        "minecraft:overworld", bounds, 0, 0),
                new AreaDefinition(3, "p2", AreaKind.STAGING_AREA, "Other",
                        "the_nether", bounds, 0, 0)));
        Map<String, Set<Integer>> references = new HashMap<>();
        Set<Integer> refs = new HashSet<>(Set.of(2));
        references.put("p1", refs);
        Map<String, String> names = new HashMap<>();
        names.put("p1", "Project 1");
        names.put("p2", "Project 2");

        AreaCatalog catalog = new AreaCatalog(areas, references, names, 11);
        areas.clear();
        refs.clear();
        names.clear();

        assertEquals(3, catalog.areas().size());
        assertEquals(Set.of(2), catalog.referencedWarehouseIds("p1"));
        assertEquals(1, catalog.referencedWarehouses("p1").size());
        assertEquals(1, catalog.stagingAreas("p1").size());
        assertEquals("Project 1", catalog.projectNames().get("p1"));
        assertEquals(11, catalog.revision());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.areas().add(catalog.areas().get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.warehouseReferences().get("p1").add(3));

        assertEquals(2, catalog.findArea(2).orElseThrow().id());
        assertTrue(catalog.findArea(99).isEmpty());
        assertEquals(List.of(1, 2), catalog.areasCovering(
                new ChunkKey("minecraft:overworld", 0, 0)).stream().map(AreaDefinition::id).toList());
        assertEquals(List.of(1, 2), catalog.areasCovering(
                new ContainerKey("minecraft:overworld", 1, 64, 1)).stream()
                .map(AreaDefinition::id).toList());

        assertThrows(IllegalArgumentException.class, () -> new AreaCatalog(
                List.of(catalog.areas().get(0), catalog.areas().get(0)),
                Map.of(), Map.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaCatalog(List.of(), Map.of("p1", Set.of(0)), Map.of(), 0));
    }

    @Test
    void projectStateTwoArgumentFixtureStartsWithUnknownInventory() {
        ProjectSnapshot project = new ProjectSnapshot(
                "p1", "Project", "hash", OWNER, true, List.of(), List.of(), 0);
        ProjectState state = new ProjectState(project, List.of());

        assertFalse(state.inventory().known());
        assertEquals(0, state.inventory().revision());
        assertThrows(NullPointerException.class, () -> new ProjectState(project, List.of(), null));
    }
}
