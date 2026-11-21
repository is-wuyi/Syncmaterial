package net.syncmaterial.syncmaterial.client;

import java.util.List;
import java.util.Optional;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProjectViewStoreTest {
    private static final ProjectViewScope SCOPE = ProjectViewScope.project("project");
    private static final String SNAPSHOT = "00000000-0000-0000-0000-000000000001";

    @Test
    void progressInstallsAtomicallyAndRejectsOlderSequences() {
        ClientProjectViewStore store = open();
        assertTrue(store.install(assembled(5, "project")));
        assertEquals(5, store.progress().orElseThrow().publicationSequence());
        assertFalse(store.install(assembled(4, "project")));
        assertFalse(store.install(assembled(6, "other")));
        assertEquals(5, store.progress().orElseThrow().publicationSequence());
    }

    @Test
    void configurationRejectsStaleOrConflictingRevisionWithoutDroppingKnownData() {
        ClientProjectViewStore store = open();
        AreaDefinition first = area(1, "first");
        AreaDefinition changed = area(1, "changed");
        assertTrue(store.configuration(SCOPE, 7, 3, 4, "LIST", List.of(first)));
        assertFalse(store.configuration(SCOPE, 7, 3, 3, "LIST", List.of()));
        assertFalse(store.configuration(SCOPE, 7, 3, 4, "LIST", List.of(changed)));
        assertEquals(List.of(first), store.configuration("LIST").orElseThrow());
        assertTrue(store.configuration(SCOPE, 7, 3, 4, "LIST_WAREHOUSE_REFS", List.of()));
        assertTrue(store.configuration("LIST_WAREHOUSE_REFS").orElseThrow().isEmpty());
    }

    @Test
    void warehouseReplacesOnlyAtCompleteAndSeparatesDimensions() {
        ClientProjectViewStore store = open();
        ContainerObservation observation = observation("minecraft:overworld", 1);
        assertFalse(store.hasWarehouseSnapshot());
        assertTrue(store.warehouse(SCOPE, 7, 3, 10, Optional.of(observation), false));
        assertFalse(store.hasWarehouseSnapshot());
        assertTrue(store.warehouse().isEmpty());
        assertEquals(1, store.pendingWarehouseParts());
        assertFalse(store.warehouse(SCOPE, 7, 3, 10,
                Optional.of(observation("minecraft:the_nether", 2)), false));
        assertTrue(store.warehouse(SCOPE, 7, 3, 10, Optional.empty(), true));
        assertTrue(store.hasWarehouseSnapshot());
        assertEquals(List.of(observation), store.warehouse());
        assertEquals("minecraft:overworld", store.warehouseWorld());
        assertFalse(store.warehouse(SCOPE, 7, 3, 9, Optional.of(observation), false));

        assertTrue(store.warehouse(SCOPE, 7, 3, 11, Optional.empty(), true));
        assertTrue(store.warehouse().isEmpty());
        assertEquals("", store.warehouseWorld());
    }

    @Test
    void newerWarehouseSequenceSupersedesIncompleteOlderContent() {
        ClientProjectViewStore store = open();
        ContainerObservation old = observation("minecraft:overworld", 1);
        ContainerObservation current = observation("minecraft:overworld", 2);
        store.warehouse(SCOPE, 7, 3, 10, Optional.of(old), false);
        store.warehouse(SCOPE, 7, 3, 12, Optional.of(current), false);
        assertFalse(store.warehouse(SCOPE, 7, 3, 11, Optional.empty(), true));
        assertTrue(store.warehouse(SCOPE, 7, 3, 12, Optional.empty(), true));
        assertEquals(List.of(current), store.warehouse());
    }

    @Test
    void duplicateCompleteForTheSameSequenceCannotClearInstalledContent() {
        ClientProjectViewStore store = open();
        ContainerObservation observation = observation("minecraft:overworld", 1);
        assertTrue(store.warehouse(SCOPE, 7, 3, 10, Optional.of(observation), false));
        assertTrue(store.warehouse(SCOPE, 7, 3, 10, Optional.empty(), true));
        assertEquals(List.of(observation), store.warehouse());

        assertFalse(store.warehouse(SCOPE, 7, 3, 10, Optional.empty(), true));
        assertEquals(List.of(observation), store.warehouse());
        assertEquals("minecraft:overworld", store.warehouseWorld());
        assertTrue(store.hasWarehouseSnapshot());
    }

    @Test
    void warehousePendingLimitRejectsTheWholeSequenceAndKeepsCompletedContent() {
        ClientProjectViewStore store = open();
        ContainerObservation completed = observation("minecraft:overworld", 9);
        assertTrue(store.warehouse(SCOPE, 7, 3, 9, Optional.of(completed), false));
        assertTrue(store.warehouse(SCOPE, 7, 3, 9, Optional.empty(), true));

        for (int index = 0; index < ClientProjectViewStore.MAX_PENDING_WAREHOUSE_OBSERVATIONS; index++) {
            assertTrue(store.warehouse(SCOPE, 7, 3, 10,
                    Optional.of(observation("minecraft:overworld", index)), false));
        }
        assertFalse(store.warehouse(SCOPE, 7, 3, 10,
                Optional.of(observation("minecraft:overworld", 10_000)), false));
        assertEquals(0, store.pendingWarehouseParts());
        assertFalse(store.warehouse(SCOPE, 7, 3, 10, Optional.empty(), true),
                "an overflowed sequence must not be committed by its COMPLETE");
        assertEquals(List.of(completed), store.warehouse());
        assertEquals("minecraft:overworld", store.warehouseWorld());

        assertTrue(store.warehouse(SCOPE, 7, 3, 11,
                Optional.of(observation("minecraft:overworld", 20_000)), false));
        assertTrue(store.warehouse(SCOPE, 7, 3, 11, Optional.empty(), true));
        assertEquals(1, store.warehouse().size());
    }

    @Test
    void invalidationRetainsLastKnownDataWithoutClaimingItIsCurrent() {
        ClientProjectViewStore store = open();
        store.install(assembled(5, "project"));
        ContainerObservation observation = observation("minecraft:overworld", 1);
        store.warehouse(SCOPE, 7, 3, 10, Optional.of(observation), false);
        store.warehouse(SCOPE, 7, 3, 10, Optional.empty(), true);

        assertTrue(store.invalidated(SCOPE, 7, 3, "stale"));
        assertFalse(store.valid());
        assertEquals(5, store.progress().orElseThrow().publicationSequence());
        assertEquals(1, store.warehouse().size());
        assertEquals("stale", store.lastError());
        assertTrue(store.error(SCOPE, 7, 3, "read failed"));
        assertEquals(1, store.warehouse().size());
        assertEquals("read failed", store.lastError());
    }

    @Test
    void closeAndLatePacketsAreFencedByScopeAndGeneration() {
        ClientProjectViewStore store = open();
        assertFalse(store.close(SCOPE, 7, 2));
        assertTrue(store.close(SCOPE, 7, 3));
        assertFalse(store.install(assembled(6, "project")));
        assertTrue(store.progress().isEmpty());
        assertTrue(store.warehouse().isEmpty());
        assertTrue(store.open(ProjectViewScope.globalWarehouse(), 7, 3));
        assertFalse(store.warehouse(ProjectViewScope.globalWarehouse(), 7, 3, 1,
                Optional.of(observation("minecraft:overworld", 1)), false));
    }

    private static ClientProjectViewStore open() {
        ClientProjectViewStore store = new ClientProjectViewStore();
        assertTrue(store.open(SCOPE, 7, 3));
        return store;
    }

    private static ProjectViewAssembler.AssembledView assembled(long sequence, String project) {
        ProjectProgressSnapshot snapshot = new ProjectProgressSnapshot(project, project,
                1, 1, sequence, true, true, false, List.of(), List.of());
        return new ProjectViewAssembler.AssembledView(SCOPE, 7, 3, sequence, SNAPSHOT,
                1, 1, snapshot);
    }

    private static AreaDefinition area(int id, String name) {
        return new AreaDefinition(id, "project", AreaKind.STAGING_AREA, name,
                "minecraft:overworld", new AreaBounds(0, 0, 0, 15, 15, 15), 1, 0);
    }

    private static ContainerObservation observation(String world, int x) {
        return new ContainerObservation(new ContainerKey(world, x, 64, 1),
                java.util.Map.of("minecraft:stone", 32L), "");
    }
}
