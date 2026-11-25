package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LegacyWarehouseNetworkAdapterTest {
    private LegacyNetworkTestSupport support;
    private LegacyWarehouseNetworkAdapter adapter;
    private MockedStatic<ServerPlayNetworking> networking;
    private final List<CustomPacketPayload> packets = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        LegacyNetworkTestSupport.bootstrap();
    }

    @BeforeEach
    void setUp() {
        support = new LegacyNetworkTestSupport("Player1");
        support.handshake();
        adapter = support.warehouseProjection;
        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.send(any(), any())).thenAnswer(invocation -> {
            packets.add(invocation.getArgument(1));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        networking.close();
        support.close();
    }

    @Test
    void subscribeSendsInitialSnapshotEvenWhenEmpty() {
        when(support.staging.getWarehousesForSchematic("empty")).thenReturn(List.of());

        adapter.subscribe(support.player, "empty");

        assertEquals(1, packets(WarehouseContainerResponseS2CPacket.class).size());
        assertTrue(firstContainers().containers().isEmpty());
        assertTrue(support.subscriptions.warehouseIds(support.player).isEmpty());
    }

    @Test
    void subscribeSendsCurrentUnionAndDimensionToManager() {
        when(support.staging.getWarehousesForSchematic("a")).thenReturn(List.of(warehouse(1)));
        when(support.staging.getWarehousesForSchematic("b")).thenReturn(List.of(warehouse(2)));

        adapter.subscribe(support.player, "a");
        adapter.subscribe(support.player, "b");

        assertEquals(Set.of(1, 2), support.subscriptions.warehouseIds(support.player));
        verify(support.staging).getContainerEntriesForWarehouses(eq(Set.of(1, 2)), eq("minecraft:overworld"));
        assertEquals(2, packets(WarehouseContainerResponseS2CPacket.class).size());
    }

    @Test
    void dirtyPublishSkipsEmptyUnionAndDisconnectedPlayers() {
        adapter.publishContainers();
        assertTrue(packets.isEmpty());

        when(support.staging.getWarehousesForSchematic("a")).thenReturn(List.of(warehouse(1)));
        adapter.subscribe(support.player, "a");
        packets.clear();
        adapter.publishContainers();
        assertEquals(1, packets(WarehouseContainerResponseS2CPacket.class).size());

        support.player.connection = null;
        adapter.publishContainers();
        assertEquals(1, packets(WarehouseContainerResponseS2CPacket.class).size());
    }

    @Test
    void referenceRefreshPreservesOtherProjectsAndClearsFinalUnion() {
        support.subscriptions.subscribeWarehouses(support.player, "a", Set.of(1));
        support.subscriptions.subscribeWarehouses(support.player, "b", Set.of(2));
        when(support.staging.getWarehousesForSchematic("a")).thenReturn(List.of());

        adapter.refreshProjectReferences("a");

        assertEquals(Set.of(2), support.subscriptions.warehouseIds(support.player));
        verify(support.staging).getContainerEntriesForWarehouses(eq(Set.of(2)), anyString());
        when(support.staging.getWarehousesForSchematic("b")).thenReturn(List.of());
        adapter.refreshProjectReferences("b");
        assertTrue(support.subscriptions.warehouseIds(support.player).isEmpty());
        assertTrue(packets(WarehouseContainerResponseS2CPacket.class).getLast().containers().isEmpty());
        assertEquals(Set.of(support.player), support.subscriptions.warehouseSubscribers().keySet());
    }

    @Test
    void wireframeReferencesComeFromMaterialListsNotPickupProjects() {
        support.subscriptions.subscribeMaterials(support.player, "materials");
        support.subscriptions.subscribeWarehouses(support.player, "pickup", Set.of(9));
        when(support.staging.getWarehousesForSchematic("materials")).thenReturn(List.of(warehouse(7)));
        when(support.staging.getWarehousesForSchematic("pickup")).thenReturn(List.of(warehouse(9)));
        when(support.staging.getAllWarehouses()).thenReturn(List.of(warehouse(7), warehouse(9)));

        adapter.sendAreas(support.player);

        WarehouseAreaResponseS2CPacket response = packets(WarehouseAreaResponseS2CPacket.class).getFirst();
        assertEquals(List.of(7), response.referencedIds());
    }

    @Test
    void invalidOwnerCannotSubscribeOrPublish() {
        support.closeOwnerAndInstall(mock(LegacyServerContext.class));

        adapter.subscribe(support.player, "a");
        adapter.publishContainers();
        adapter.refreshProjectReferences("a");
        adapter.sendAreas(support.player);
        adapter.broadcastAreas();

        assertTrue(packets.isEmpty());
        verify(support.staging, never()).getWarehousesForSchematic(anyString());
    }

    private WarehouseContainerResponseS2CPacket firstContainers() {
        return packets(WarehouseContainerResponseS2CPacket.class).getFirst();
    }

    private <T extends CustomPacketPayload> List<T> packets(Class<T> type) {
        return packets.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static StagingAreaManager.Warehouse warehouse(int id) {
        return new StagingAreaManager.Warehouse(id, "w" + id, "minecraft:overworld",
                0, 0, 0, 4, 4, 4);
    }
}
