package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/**
 * ModNetworkHandler C2S 行为测试：所有业务状态显式绑定到
 * {@link LegacyServerContext}，不再通过静态 service setter 或 SyncMaterial getter。
 */
class ModNetworkHandlerBehaviorTest {

    private MockedStatic<ServerPlayNetworking> networkingMock;
    private MockedStatic<LegacyServerRegistry> registryMock;

    private LegacyNetworkTestSupport support;
    private MinecraftServer server;
    private ServerPlayer player;
    private LegacyServerContext owner;
    private net.syncmaterial.syncmaterial.server.SchematicDatabase db;
    private net.syncmaterial.syncmaterial.server.DatabaseQueryService queryService;
    private net.syncmaterial.syncmaterial.server.CollaborationManager cm;
    private StagingAreaManager manager;

    @BeforeAll
    static void setup() {
        LegacyNetworkTestSupport.bootstrap();
    }

    @BeforeEach
    void setUp() {
        support = new LegacyNetworkTestSupport("Player1");
        server = support.server;
        player = support.player;
        owner = support.owner;
        db = support.db;
        queryService = support.queryService;
        cm = support.collaboration;
        manager = support.staging;
        support.handshake();

        networkingMock = mockStatic(ServerPlayNetworking.class);
        registryMock = mockStatic(LegacyServerRegistry.class);
        registryMock.when(() -> LegacyServerRegistry.get(server))
                .thenAnswer(inv -> support.installed.get());
    }

    @AfterEach
    void tearDown() {
        registryMock.close();
        networkingMock.close();
        support.close();
    }

    private <T> T lastPacketOfType(Class<T> type) {
        ArgumentCaptor<net.minecraft.network.protocol.common.custom.CustomPacketPayload> captor =
            ArgumentCaptor.forClass(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()),
            atLeastOnce());
        return captor.getAllValues().stream()
            .filter(type::isInstance)
            .map(type::cast)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("未发送 " + type.getSimpleName()));
    }

    // ========== JoinCollaboration: 自行认领门控 ==========

    @Test
    void join_selfClaimDisabledAndNonOwner_silentlyDropped() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(false);

        ModNetworkHandler.handleJoinCollaboration(owner,
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10)), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void join_selfClaimDisabledButOwner_proceeds() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(true);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(owner,
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10, 2, 20)), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
        verify(cm).updatePlayerInventory("Player1", "s1", 1, 10);
        verify(cm).updatePlayerInventory("Player1", "s1", 2, 20);
    }

    @Test
    void join_selfClaimEnabled_anyPlayerCanJoin() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(true);
        when(db.isOwner("s1", "Player1")).thenReturn(false);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(owner,
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
    }

    @Test
    void join_dbError_dropsRequest() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenThrow(new RuntimeException("db down"));

        ModNetworkHandler.handleJoinCollaboration(owner,
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
    }

    // ========== InventoryUpdate: 协作者门控 ==========

    @Test
    void inventoryUpdate_byNonCollaborator_ignored() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(false);

        ModNetworkHandler.handleContainerUpdate(owner,
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void inventoryUpdate_byCollaborator_updatesCount() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleContainerUpdate(owner,
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm).updatePlayerInventory("Player1", "s1", 42, 64);
    }

    // ========== validate* 边界 ==========

    @Test
    void validateSchematicId_boundaries() {
        assertTrue(ModNetworkHandler.validateSchematicId("a"));
        assertTrue(ModNetworkHandler.validateSchematicId("x".repeat(100)), "100 字符应通过");
        assertFalse(ModNetworkHandler.validateSchematicId("x".repeat(101)), "101 字符应拒绝");
    }

    @Test
    void validateSchematicId_nullOrBlank() {
        assertFalse(ModNetworkHandler.validateSchematicId(null));
        assertFalse(ModNetworkHandler.validateSchematicId(""));
        assertFalse(ModNetworkHandler.validateSchematicId("   "));
    }

    @Test
    void validateMaterialId_negativeRejected() {
        assertTrue(ModNetworkHandler.validateMaterialId(0));
        assertFalse(ModNetworkHandler.validateMaterialId(-1));
    }

    @Test
    void validateCount_negativeRejected() {
        assertTrue(ModNetworkHandler.validateCount(0));
        assertFalse(ModNetworkHandler.validateCount(-1));
    }

    @Test
    void validateStagingAction_whitelist() {
        String[] valid = {"LIST", "ADD", "RENAME", "DELETE", "UPDATE", "CLEAR",
            "LIST_WAREHOUSES", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE",
            "ADD_WAREHOUSE_REF", "REMOVE_WAREHOUSE_REF", "LIST_WAREHOUSE_REFS"};
        for (String action : valid) {
            assertTrue(ModNetworkHandler.validateStagingAction(action), "应接受: " + action);
        }
        assertFalse(ModNetworkHandler.validateStagingAction(null));
        assertFalse(ModNetworkHandler.validateStagingAction("lowercase"));
        assertFalse(ModNetworkHandler.validateStagingAction("DROP_TABLE"));
    }

    @Test
    void globalWarehouseAction_onlyExactCrudActionsBypassSchematicValidation() {
        for (String action : List.of(
                "LIST_WAREHOUSES", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE")) {
            assertTrue(ModNetworkHandler.isGlobalWarehouseAction(action),
                action + " 应被识别为全局仓库操作");
        }

        for (String action : List.of(
                "ADD_WAREHOUSE_REF", "REMOVE_WAREHOUSE_REF", "LIST_WAREHOUSE_REFS")) {
            assertFalse(ModNetworkHandler.isGlobalWarehouseAction(action),
                action + " 属于原理图引用操作，不得绕过 schematicId 校验");
        }

        assertFalse(ModNetworkHandler.isGlobalWarehouseAction(null));
        assertFalse(ModNetworkHandler.isGlobalWarehouseAction("ADD_WAREHOUSE_EVIL"),
            "前缀相同但不在精确白名单内的 action 不得放行");
    }

    @Test
    void validateOwnerAction_whitelistAndCaseSensitivity() {
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TRANSFER"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("ADD_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("REMOVE_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TOGGLE_SELF_CLAIM"));
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("transfer"));
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("HACK"));
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction(null));
    }

    // ========== 取货模式订阅（Phase 5） ==========

    @Test
    void warehouseSubscribe_sendsContainerEntriesOfReferencedWarehouses() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of(
            new WarehouseContainerResponseS2CPacket.ContainerEntry(1, 64, 1, List.of("minecraft:stone"))));

        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player);

        ArgumentCaptor<WarehouseContainerResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseContainerResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(1, captor.getValue().containers().size());
        assertEquals("minecraft:stone", captor.getValue().containers().get(0).itemIds().get(0));
    }

    @Test
    void sendToPlayers_requiresCompletedHandshake() {
        ProtocolHandshake.remove(player);

        ModNetworkHandler.sendToPlayers(owner, java.util.Set.of(player),
            new CollaborationStatusS2CPacket("s1", 1, 10, 0, 0, List.of(), List.of()));

        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void warehouseSubscribe_managerMissing_noop() {
        when(owner.staging()).thenReturn(null);

        assertDoesNotThrow(() -> ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player));

        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void warehouseUnsubscribe_stopsPush() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());

        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", false), player);

        ModNetworkHandler.pushWarehouseContainerUpdate(owner);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    @Test
    void warehousePush_sendsUnionOfAllSubscribedSchematics() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getWarehousesForSchematic("s2")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(2, "w2", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());

        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s2", true), player);

        ModNetworkHandler.pushWarehouseContainerUpdate(owner);

        verify(manager, atLeastOnce()).getContainerEntriesForWarehouses(argThat(ids ->
            ids.size() == 2 && ids.contains(1) && ids.contains(2)));
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(3));
    }

    // ========== 断线清理（客户端未主动退订的路径） ==========

    @Test
    void playerDisconnect_clearsWarehouseSubscription() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());

        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.onPlayerDisconnect(player);

        ModNetworkHandler.pushWarehouseContainerUpdate(owner);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    @Test
    void playerDisconnect_clearsMaterialListAndStagingSubscriptions() {
        owner.subscriptions().subscribeMaterials(player, "s1");
        assertTrue(owner.subscriptions().projects(player).contains("s1"));

        ModNetworkHandler.onPlayerDisconnect(player);

        assertFalse(owner.subscriptions().projects(player).contains("s1"),
            "断线后材料列表订阅应被清理");
        verify(manager).unsubscribeAll(player);
    }

    @Test
    void playerDisconnect_nullPlayer_DoesNotThrow() {
        assertDoesNotThrow(() -> ModNetworkHandler.onPlayerDisconnect(null));
    }

    @Test
    void warehousePush_skipsDisconnectedPlayer() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());

        ModNetworkHandler.handleWarehouseContainerRequest(owner,
            new WarehouseContainerRequestC2SPacket("s1", true), player);

        // 模拟连接已失效但条目尚未清理（DISCONNECT 事件与推送竞争的窗口）
        player.connection = null;
        ModNetworkHandler.pushWarehouseContainerUpdate(owner);

        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    // ========== 备货区网络配置入口（ADD） ==========

    @Test
    void stagingAreaConfig_add_rescansImmediatelyAndResponds() {
        var worldKey = net.minecraft.resources.ResourceKey.create(
            //? if >=26.2 {
            net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "overworld"));
            //?} else {
            /*net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
            *///?}
        when(support.level.dimension()).thenReturn(worldKey);
        when(manager.addStagingArea(eq("s1"), eq("minecraft:overworld"), eq("新区域"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(7);
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("s1", "ADD", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新区域", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        verify(manager).rescanStagingArea(7);
        verify(manager).broadcastUpdate("s1");

        ArgumentCaptor<StagingAreaConfigResponseS2CPacket> captor =
            ArgumentCaptor.forClass(StagingAreaConfigResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertTrue(captor.getValue().success());
        assertEquals("备货区已添加", captor.getValue().message());
    }

    @Test
    void stagingAreaConfig_addWithoutAreaData_rejected() {
        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("s1", "ADD", 0,
            java.util.Optional.empty()), player, server);

        verify(manager, never()).addStagingArea(any(), any(), any(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        ArgumentCaptor<StagingAreaConfigResponseS2CPacket> captor =
            ArgumentCaptor.forClass(StagingAreaConfigResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("缺少区域数据", captor.getValue().message());
    }

    // ========== 仓库网络配置入口（创建/修改后立即重扫） ==========

    @Test
    void warehouseAreas_addWarehouse_broadcastsAreaPacket() {
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(9);
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "新仓库", "minecraft:overworld", 0, 64, 0, 10, 70, 10)));

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(1, captor.getValue().warehouses().size());
        assertEquals(9, captor.getValue().warehouses().get(0).areaId());
        assertEquals("minecraft:overworld", captor.getValue().warehouses().get(0).world());
    }

    @Test
    void warehouseAreas_unhandshakedPlayer_receivesNoBroadcast() {
        ProtocolHandshake.remove(player);

        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(9);
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "新仓库", "minecraft:overworld", 0, 64, 0, 10, 70, 10)));

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player),
            any(WarehouseAreaResponseS2CPacket.class)), never());
    }

    @Test
    void warehouseAreas_deleteWarehouse_broadcastsRemainingAreas() {
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of());
        when(manager.getAllWarehouses()).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(owner,
            new StagingAreaConfigC2SPacket("", "DELETE_WAREHOUSE", 9, java.util.Optional.empty()),
            player, server);

        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertTrue(captor.getValue().warehouses().isEmpty());
    }

    @Test
    void warehouseAreas_referencedByOpenSchematic_markedAsReferenced() {
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "被引用仓库", "minecraft:overworld", 0, 64, 0, 5, 70, 5),
            new StagingAreaManager.Warehouse(4, "未引用仓库", "minecraft:overworld", 20, 64, 20, 25, 70, 25)));
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "被引用仓库", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));

        ModNetworkHandler.handleQueryMaterialStatus(owner,
            new QueryMaterialStatusC2SPacket("s1"), player);

        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(2, captor.getValue().warehouses().size(), "全部仓库都要下发");
        assertEquals(List.of(3), captor.getValue().referencedIds());
    }

    @Test
    void warehouseAreas_addReference_rebroadcastsSoHighlightUpdates() {
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "w3", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "w3", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));

        ModNetworkHandler.handleStagingAreaConfig(owner,
            new StagingAreaConfigC2SPacket("s1", "ADD_WAREHOUSE_REF", 3, java.util.Optional.empty()),
            player, server);

        verify(manager).addWarehouseReference("s1", 3);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player),
            any(WarehouseAreaResponseS2CPacket.class)));
    }

    @Test
    void warehouseConfig_add_rescansImmediatelyAndResponds() {
        when(manager.addWarehouse(eq("新仓库"), eq("minecraft:overworld"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(9);

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        verify(manager).rescanWarehouseAndMarkChunks(9);

        var resp = lastPacketOfType(StagingAreaConfigResponseS2CPacket.class);
        assertTrue(resp.success());
        assertEquals("仓库已创建", resp.message());
    }

    @Test
    void warehouseConfig_addFailure_doesNotRescan() {
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(-1);

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "失败仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        verify(manager, never()).rescanWarehouseAndMarkChunks(anyInt());
    }

    @Test
    void warehouseConfig_update_rescansNewBoundsAndBroadcastsReferences() {
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of("s1"));
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "UPDATE_WAREHOUSE", 9,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "修改后的仓库", 10, 64, 10, 20, 70, 20,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        verify(manager).updateWarehouse(eq(9), eq("修改后的仓库"), eq("minecraft:overworld"),
            eq(10), eq(64), eq(10), eq(20), eq(70), eq(20));
        verify(manager).rescanWarehouseAndMarkChunks(9);
        verify(cm).getAllMaterialIds("s1");
    }

    @Test
    void warehouseConfig_update_withoutWorld_passesNullToKeepDimension() {
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of());

        ModNetworkHandler.handleStagingAreaConfig(owner, new StagingAreaConfigC2SPacket("", "UPDATE_WAREHOUSE", 9,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "仓库", 10, 64, 10, 20, 70, 20, java.util.Optional.empty()))),
            player, server);

        verify(manager).updateWarehouse(eq(9), eq("仓库"), isNull(),
            eq(10), eq(64), eq(10), eq(20), eq(70), eq(20));
    }

    @Test
    void warehouseReference_add_broadcastsMaterialStatus() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of());
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(owner,
            new StagingAreaConfigC2SPacket("s1", "ADD_WAREHOUSE_REF", 9, java.util.Optional.empty()),
            player, server);

        verify(manager).addWarehouseReference("s1", 9);
        verify(cm).getAllMaterialIds("s1");
    }

    // ========== 材料清单请求（服务端核心读路径） ==========

    @Test
    void materialStatsRequest_assemblesOwnerInfoAndProgress() throws Exception {
        var entry = new net.syncmaterial.syncmaterial.api.MaterialEntry(
            1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE), 100);
        when(queryService.getMaterials("s1")).thenReturn(List.of(entry));
        var status = new CollaborationStatusS2CPacket("s1", 1, 100, 10, 5,
            List.of(new CollaborationStatusS2CPacket.ParticipantInfo("P1", 20)), List.of());
        when(cm.getCollaborationStatus("s1", 1)).thenReturn(status);
        when(db.isOwner("s1", "Player1")).thenReturn(true);
        when(db.isMainOwner("s1", "Player1")).thenReturn(false);
        when(db.getUploadedBy("s1")).thenReturn("Boss");
        when(db.getDeputyOwners("s1")).thenReturn(List.of("Dep1"));

        ModNetworkHandler.handleMaterialStatsRequest(owner,
            new MaterialStatsRequestC2SPacket("s1"), player, server);

        assertEquals(35, entry.getCountAvailable());
        assertEquals(65, entry.getCountMissing());

        ArgumentCaptor<MaterialStatsResponseS2CPacket> captor =
            ArgumentCaptor.forClass(MaterialStatsResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        var resp = captor.getValue();
        assertEquals(LegacyNetworkTestSupport.DEFAULT_SCHEMATIC_NAME, resp.schematicName());
        assertTrue(resp.isOwner());
        assertFalse(resp.isMainOwner());
        assertEquals("Boss", resp.ownerName());
        assertTrue(resp.deputyOwners().contains("Dep1"));
        assertEquals(1, resp.materials().size());

        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), eq(status)));
    }

    @Test
    void materialStatsRequest_dbMissing_usesDefaults() {
        when(owner.database()).thenReturn(null);
        when(queryService.getMaterials("s1")).thenReturn(List.of());

        ModNetworkHandler.handleMaterialStatsRequest(owner,
            new MaterialStatsRequestC2SPacket("s1"), player, server);

        ArgumentCaptor<MaterialStatsResponseS2CPacket> captor =
            ArgumentCaptor.forClass(MaterialStatsResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        var resp = captor.getValue();
        assertFalse(resp.isOwner(), "数据库缺失时不应误判为 owner");
        assertEquals("", resp.ownerName());
        assertTrue(resp.allowSelfClaim(), "默认应允许自行认领");
    }

    @Test
    void materialStatsRequest_queryFails_sendsEmptyFallback() {
        when(queryService.getMaterials("s1")).thenThrow(new RuntimeException("db down"));

        ModNetworkHandler.handleMaterialStatsRequest(owner,
            new MaterialStatsRequestC2SPacket("s1"), player, server);

        ArgumentCaptor<MaterialStatsResponseS2CPacket> captor =
            ArgumentCaptor.forClass(MaterialStatsResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        var resp = captor.getValue();
        assertTrue(resp.materials().isEmpty());
        assertEquals("", resp.schematicName());
    }

    // ========== 退出协作 ==========

    @Test
    void leaveCollaboration_byParticipant_broadcastsAndSendsStatus() {
        when(cm.leaveCollaboration("s1", 42, "Player1")).thenReturn(true);
        when(cm.getCollaborationStatus("s1", 42)).thenReturn(new CollaborationStatusS2CPacket(
            "s1", 42, 10, 0, 0, List.of(), List.of()));

        ModNetworkHandler.handleLeaveCollaboration(owner,
            new LeaveCollaborationC2SPacket("s1", 42), player, server);

        verify(cm).leaveCollaboration("s1", 42, "Player1");
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), any(CollaborationStatusS2CPacket.class)),
            times(1));
    }

    @Test
    void leaveCollaboration_notParticipating_noSideEffects() {
        when(cm.leaveCollaboration("s1", 42, "Player1")).thenReturn(false);

        ModNetworkHandler.handleLeaveCollaboration(owner,
            new LeaveCollaborationC2SPacket("s1", 42), player, server);

        verify(cm).leaveCollaboration("s1", 42, "Player1");
        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    // ========== 材料列表订阅（打开/关闭界面） ==========

    @Test
    void queryMaterialStatus_subscribesAndSendsEachStatus() {
        var status = new CollaborationStatusS2CPacket("s1", 1, 10, 0, 0, List.of(), List.of());
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of(1, 2));
        when(cm.getCollaborationStatus("s1", 1)).thenReturn(status);
        when(cm.getCollaborationStatus("s1", 2)).thenReturn(null);

        ModNetworkHandler.handleQueryMaterialStatus(owner,
            new QueryMaterialStatusC2SPacket("s1"), player);

        assertTrue(owner.subscriptions().projects(player).contains("s1"),
            "查询状态应把玩家加入订阅列表");
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), eq(status)), times(1));
    }

    @Test
    void materialListClose_unsubscribes() {
        owner.subscriptions().subscribeMaterials(player, "s1");

        ModNetworkHandler.handleMaterialListClose(owner,
            new MaterialListCloseC2SPacket("s1"), player);

        assertFalse(owner.subscriptions().projects(player).contains("s1"),
            "关闭界面应清除订阅");
    }

    // ========== 生命周期：旧上下文/旧连接不得落到新上下文 ==========

    @Test
    void dispatch_oldContextClosedBeforeCallback_usesNoReplacementServices() {
        support.deferOwnerExecution.set(true);
        var replacement = mock(LegacyServerContext.class);

        var callbackOwner = new AtomicReference<LegacyServerContext>();
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(server);
        when(context.player()).thenReturn(player);
        registryMock.when(() -> LegacyServerRegistry.get(server)).thenReturn(owner);

        LegacyNetworkDispatch.dispatch(context, captured -> {
            callbackOwner.set(captured);
            ModNetworkHandler.handleMaterialStatsRequest(captured,
                new MaterialStatsRequestC2SPacket("s1"), player, server);
        });

        // 准入捕获 A 后、回调执行前 A 关闭且 B 接管同一 server。
        support.closeOwnerAndInstall(replacement);
        support.flushQueued();

        assertNull(callbackOwner.get(), "旧上下文的排队回调不得执行");
        verifyNoInteractions(replacement);
        verifyNoInteractions(queryService);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void dispatch_originalConnectionReplacedBeforeCallback_isDropped() {
        support.deferOwnerExecution.set(true);

        var callbackOwner = new AtomicReference<LegacyServerContext>();
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(server);
        when(context.player()).thenReturn(player);
        registryMock.when(() -> LegacyServerRegistry.get(server)).thenReturn(owner);

        LegacyNetworkDispatch.dispatch(context, callbackOwner::set);

        // 同一个上下文仍当前，但玩家已经换了连接（重连）。
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        support.flushQueued();

        assertNull(callbackOwner.get(), "原始连接失效后回调不得执行");
    }

    @Test
    void dispatch_missingOwner_doesNotInvokeCallback() {
        registryMock.when(() -> LegacyServerRegistry.get(server)).thenReturn(null);
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(server);
        when(context.player()).thenReturn(player);

        AtomicReference<LegacyServerContext> seen = new AtomicReference<>();
        LegacyNetworkDispatch.dispatch(context, seen::set);

        assertNull(seen.get());
    }

    @Test
    void directHandlerEntrypoints_rejectAbsentClosedWrongServerAndDisconnectedOwners() {
        var payload = new MaterialStatsRequestC2SPacket("s1");

        ModNetworkHandler.handleMaterialStatsRequest(null, payload, player, server);
        verifyNoInteractions(queryService);

        support.open.set(false);
        ModNetworkHandler.handleMaterialStatsRequest(owner, payload, player, server);
        verifyNoInteractions(queryService);
        support.open.set(true);

        var otherServer = mock(MinecraftServer.class);
        ModNetworkHandler.handleMaterialStatsRequest(owner, payload, player, otherServer);
        verifyNoInteractions(queryService);

        player.connection = null;
        ModNetworkHandler.handleMaterialStatsRequest(owner, payload, player, server);
        verifyNoInteractions(queryService);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void getSubscribedSchematics_readsFromPlayerOwnerContext() {
        owner.subscriptions().subscribeMaterials(player, "s1");

        assertTrue(Phase4Handler.getSubscribedSchematics(player).contains("s1"));
    }

    @Test
    void subscriptions_areIsolatedBetweenContexts() {
        var other = new LegacyNetworkTestSupport("Other");
        try {
            owner.subscriptions().subscribeMaterials(player, "s1");

            assertTrue(owner.subscriptions().projects(player).contains("s1"));
            assertTrue(other.owner.subscriptions().projects(player).isEmpty(),
                "另一个上下文不得看到本上下文的材料订阅");
            assertTrue(other.owner.subscriptions().materialSubscribers("s1").isEmpty());
        } finally {
            other.close();
        }
    }
}
