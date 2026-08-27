package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;

/**
 * ModNetworkHandler C2S 行为测试：
 * JoinCollaboration 的自行认领门控、InventoryUpdate 的协作者门控、validate* 边界。
 */
class ModNetworkHandlerBehaviorTest {

    private MockedStatic<SyncMaterial> syncMaterialMock;
    private MockedStatic<ServerPlayNetworking> networkingMock;
    private MockedStatic<net.syncmaterial.syncmaterial.server.PlacementsUtil> placementsMock;

    private SchematicDatabase db;
    private DatabaseQueryService queryService;
    private CollaborationManager cm;
    private MinecraftServer server;
    private ServerPlayer player;

    @BeforeAll
    static void setup() {
        // mock ServerPlayer 会触发 Entity 静态初始化，需要注册表（与执行顺序无关）
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        db = mock(SchematicDatabase.class);
        queryService = mock(DatabaseQueryService.class);
        cm = mock(CollaborationManager.class);
        server = mock(MinecraftServer.class);
        player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Player1"));
        when(player.getName()).thenReturn(Component.literal("Player1"));
        when(player.getUUID()).thenReturn(playerId);
        // 广播出口会跳过未完成版本握手的玩家（视为没装本 mod），测试需先完成握手
        ProtocolHandshake.recordHandshake(playerId, ProtocolVersion.CURRENT, "test");

        syncMaterialMock = mockStatic(SyncMaterial.class);
        syncMaterialMock.when(SyncMaterial::getSharedDatabase).thenReturn(db);

        networkingMock = mockStatic(ServerPlayNetworking.class);

        placementsMock = mockStatic(net.syncmaterial.syncmaterial.server.PlacementsUtil.class);
        placementsMock.when(() -> net.syncmaterial.syncmaterial.server.PlacementsUtil.getDisplayName(any()))
            .thenReturn("测试原理图");

        ModNetworkHandler.initializeServices(queryService, cm);
    }

    @AfterEach
    void tearDown() {
        // 先取消本测试可能建立的取货订阅（mock 仍可用），避免静态状态泄漏到其他测试类
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", false), player);
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s2", false), player);
        Phase4Handler.unsubscribeAllMaterialList(player);
        ProtocolHandshake.remove(player.getUUID());
        syncMaterialMock.close();
        networkingMock.close();
        placementsMock.close();
    }

    // ========== JoinCollaboration: 自行认领门控 ==========

    @Test
    void join_selfClaimDisabledAndNonOwner_silentlyDropped() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(false);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10)), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void join_selfClaimDisabledButOwner_proceeds() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(true);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10, 2, 20)), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
        // 加入成功后，背包上报逐条写入
        verify(cm).updatePlayerInventory("Player1", "s1", 1, 10);
        verify(cm).updatePlayerInventory("Player1", "s1", 2, 20);
    }

    @Test
    void join_selfClaimEnabled_anyPlayerCanJoin() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(true);
        when(db.isOwner("s1", "Player1")).thenReturn(false);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
    }

    @Test
    void join_dbError_dropsRequest() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenThrow(new RuntimeException("db down"));

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
    }

    // ========== InventoryUpdate: 协作者门控 ==========

    @Test
    void inventoryUpdate_byNonCollaborator_ignored() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(false);

        ModNetworkHandler.handleContainerUpdate(
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void inventoryUpdate_byCollaborator_updatesCount() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleContainerUpdate(
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
    void validateOwnerAction_whitelistAndCaseSensitivity() {
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TRANSFER"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("ADD_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("REMOVE_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TOGGLE_SELF_CLAIM"));
        // 白名单区分大小写
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("transfer"));
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("HACK"));
    }

    // ========== 取货模式订阅（Phase 5） ==========

    private StagingAreaManager mockWarehouseManager() {
        StagingAreaManager manager = mock(StagingAreaManager.class);
        syncMaterialMock.when(SyncMaterial::getServerStagingAreaManager).thenReturn(manager);
        return manager;
    }

    /**
     * broadcastWarehouseAreas 会遍历在线玩家，测试里需要可用的 PlayerManager。
     * 同时让 player 通过 sendWarehouseAreas 的存活检查。
     */
    private void stubOnlinePlayer() {
        var playerList = mock(net.minecraft.server.players.PlayerList.class);
        when(playerList.getPlayers()).thenReturn(List.of(player));
        when(server.getPlayerList()).thenReturn(playerList);
        when(player.isAlive()).thenReturn(true);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
    }

    /**
     * 取出发给该玩家的最后一个指定类型封包。
     * ArgumentCaptor 不按类型过滤，仓库操作会同时发出配置响应和区域广播两种包，
     * 直接 getValue() 可能拿到另一种类型。
     */
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

    @Test
    void warehouseSubscribe_sendsContainerEntriesOfReferencedWarehouses() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of(
            new WarehouseContainerResponseS2CPacket.ContainerEntry(1, 64, 1, List.of("minecraft:stone"))));

        ModNetworkHandler.handleWarehouseContainerRequest(
            new WarehouseContainerRequestC2SPacket("s1", true), player);

        ArgumentCaptor<WarehouseContainerResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseContainerResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(1, captor.getValue().containers().size());
        assertEquals("minecraft:stone", captor.getValue().containers().get(0).itemIds().get(0));
    }

    @Test
    void warehouseSubscribe_managerMissing_noop() {
        // getServerStagingAreaManager 默认返回 null（未初始化），订阅应静默跳过
        assertDoesNotThrow(() -> ModNetworkHandler.handleWarehouseContainerRequest(
            new WarehouseContainerRequestC2SPacket("s1", true), player));

        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void warehouseUnsubscribe_stopsPush() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());
        alivePlayer();

        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", false), player);

        // 退订后推送不应再给该玩家发任何包：唯一的 send 来自订阅时那次
        ModNetworkHandler.pushWarehouseContainerUpdate(manager);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    @Test
    void warehousePush_sendsUnionOfAllSubscribedSchematics() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getWarehousesForSchematic("s2")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(2, "w2", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());
        alivePlayer();

        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s2", true), player);

        ModNetworkHandler.pushWarehouseContainerUpdate(manager);

        // 第二次订阅时 handler 已按并集 {1,2} 查询过一次，推送再查一次
        verify(manager, atLeastOnce()).getContainerEntriesForWarehouses(argThat(ids ->
            ids.size() == 2 && ids.contains(1) && ids.contains(2)));
        // 每个原理图订阅各一次 + 推送一次 = 3 次
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(3));
    }

    // ========== 断线清理（客户端未主动退订的路径） ==========

    /** pushWarehouseContainerUpdate 有存活检查，测试推送需让 mock 玩家"在线" */
    private void alivePlayer() {
        when(player.isAlive()).thenReturn(true);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
    }

    @Test
    void playerDisconnect_clearsWarehouseSubscription() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());
        alivePlayer();

        // 玩家订阅取货模式后直接掉线（不发退订包）
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.onPlayerDisconnect(player);

        // 断线后推送不应再给该玩家发包：唯一的 send 来自订阅时那次
        ModNetworkHandler.pushWarehouseContainerUpdate(manager);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    @Test
    void playerDisconnect_clearsMaterialListAndStagingSubscriptions() {
        StagingAreaManager manager = mockWarehouseManager();

        Phase4Handler.subscribeMaterialList(player, "s1");
        assertTrue(Phase4Handler.getMaterialListSubscribers().containsKey("s1"));

        ModNetworkHandler.onPlayerDisconnect(player);

        assertFalse(Phase4Handler.getMaterialListSubscribers().containsKey("s1"),
            "断线后材料列表订阅应被清理");
        verify(manager).unsubscribeAll(player);
    }

    @Test
    void playerDisconnect_nullPlayer_doesNotThrow() {
        assertDoesNotThrow(() -> ModNetworkHandler.onPlayerDisconnect(null));
    }

    @Test
    void warehousePush_skipsDeadPlayer() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(1, "w1", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));
        when(manager.getContainerEntriesForWarehouses(anySet())).thenReturn(List.of());
        alivePlayer();

        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", true), player);

        // 模拟连接已失效但条目尚未清理（DISCONNECT 事件与推送竞争的窗口）
        when(player.isAlive()).thenReturn(false);
        ModNetworkHandler.pushWarehouseContainerUpdate(manager);

        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(1));
    }

    // ========== 备货区网络配置入口（ADD） ==========

    @Test
    void stagingAreaConfig_add_rescansImmediatelyAndResponds() {
        StagingAreaManager manager = mockWarehouseManager();
        // handler 解析 world 时 orElse 参数会立即求值（即使 AreaData 已带 world 也调用）
        var worldKey = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        var playerWorld = mock(net.minecraft.server.level.ServerLevel.class);
        when(playerWorld.dimension()).thenReturn(worldKey);
        when(player.level()).thenReturn(playerWorld);
        when(manager.addStagingArea(eq("s1"), eq("minecraft:overworld"), eq("新区域"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(7);
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("s1", "ADD", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新区域", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        // 圈区时箱子里已有的物品靠创建后的这次同步重扫统计（GUI 添加区域的真实路径）
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
        StagingAreaManager manager = mockWarehouseManager();

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("s1", "ADD", 0,
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
        StagingAreaManager manager = mockWarehouseManager();
        stubOnlinePlayer();
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(9);
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "新仓库", "minecraft:overworld", 0, 64, 0, 10, 70, 10)));

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        // 核心断言：新建仓库后必须广播区域数据，否则客户端没有线框可画
        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(1, captor.getValue().warehouses().size());
        assertEquals(9, captor.getValue().warehouses().get(0).areaId());
        assertEquals("minecraft:overworld", captor.getValue().warehouses().get(0).world());
    }

    @Test
    void warehouseAreas_unhandshakedPlayer_receivesNoBroadcast() {
        // 未完成版本握手 = 没装本 mod（或版本被拒），不该给它推任何包
        ProtocolHandshake.remove(player.getUUID());

        StagingAreaManager manager = mockWarehouseManager();
        stubOnlinePlayer();
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(9);
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "新仓库", "minecraft:overworld", 0, 64, 0, 10, 70, 10)));

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player),
            any(WarehouseAreaResponseS2CPacket.class)), never());
    }

    @Test
    void warehouseAreas_deleteWarehouse_broadcastsRemainingAreas() {
        StagingAreaManager manager = mockWarehouseManager();
        stubOnlinePlayer();
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of());
        when(manager.getAllWarehouses()).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(
            new StagingAreaConfigC2SPacket("", "DELETE_WAREHOUSE", 9, java.util.Optional.empty()),
            player, server);

        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertTrue(captor.getValue().warehouses().isEmpty(),
            "删除后应广播空列表，客户端据此清掉线框");
    }

    @Test
    void warehouseAreas_referencedByOpenSchematic_markedAsReferenced() {
        StagingAreaManager manager = mockWarehouseManager();
        stubOnlinePlayer();
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "被引用仓库", "minecraft:overworld", 0, 64, 0, 5, 70, 5),
            new StagingAreaManager.Warehouse(4, "未引用仓库", "minecraft:overworld", 20, 64, 20, 25, 70, 25)));
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "被引用仓库", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));

        // 打开材料清单会订阅原理图，进而触发仓库区域推送
        ModNetworkHandler.handleQueryMaterialStatus(
            new QueryMaterialStatusC2SPacket("s1"), player);

        ArgumentCaptor<WarehouseAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(WarehouseAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertEquals(2, captor.getValue().warehouses().size(), "全部仓库都要下发");
        assertEquals(List.of(3), captor.getValue().referencedIds(),
            "只有被当前原理图引用的仓库进入高亮集合");
    }

    @Test
    void warehouseAreas_addReference_rebroadcastsSoHighlightUpdates() {
        StagingAreaManager manager = mockWarehouseManager();
        stubOnlinePlayer();
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "w3", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "w3", "minecraft:overworld", 0, 64, 0, 5, 70, 5)));

        ModNetworkHandler.handleStagingAreaConfig(
            new StagingAreaConfigC2SPacket("s1", "ADD_WAREHOUSE_REF", 3, java.util.Optional.empty()),
            player, server);

        verify(manager).addWarehouseReference("s1", 3);
        // 引用集合变化必须重推，否则高亮色停留在旧状态
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player),
            any(WarehouseAreaResponseS2CPacket.class)));
    }

    @Test
    void warehouseConfig_add_rescansImmediatelyAndResponds() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.addWarehouse(eq("新仓库"), eq("minecraft:overworld"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(9);

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "新仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        // 核心回归断言：新建仓库必须立即扫描，不应等到箱子物品发生变化
        verify(manager).rescanWarehouseAndMarkChunks(9);

        var resp = lastPacketOfType(StagingAreaConfigResponseS2CPacket.class);
        assertTrue(resp.success());
        assertEquals("仓库已创建", resp.message());
    }

    @Test
    void warehouseConfig_addFailure_doesNotRescan() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(-1);

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "失败仓库", 0, 64, 0, 10, 70, 10,
                java.util.Optional.of("minecraft:overworld")))),
            player, server);

        verify(manager, never()).rescanWarehouseAndMarkChunks(anyInt());
    }

    @Test
    void warehouseConfig_update_rescansNewBoundsAndBroadcastsReferences() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of("s1"));
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "UPDATE_WAREHOUSE", 9,
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
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getSchematicsReferencingWarehouse(9)).thenReturn(java.util.Set.of());

        ModNetworkHandler.handleStagingAreaConfig(new StagingAreaConfigC2SPacket("", "UPDATE_WAREHOUSE", 9,
            java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                "仓库", 10, 64, 10, 20, 70, 20, java.util.Optional.empty()))),
            player, server);

        // 客户端未提供维度时必须传 null，让数据层保持原维度而不是写成默认值
        verify(manager).updateWarehouse(eq(9), eq("仓库"), isNull(),
            eq(10), eq(64), eq(10), eq(20), eq(70), eq(20));
    }

    @Test
    void warehouseReference_add_broadcastsMaterialStatus() {
        StagingAreaManager manager = mockWarehouseManager();
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of());
        when(cm.getAllMaterialIds("s1")).thenReturn(List.of());

        ModNetworkHandler.handleStagingAreaConfig(
            new StagingAreaConfigC2SPacket("s1", "ADD_WAREHOUSE_REF", 9, java.util.Optional.empty()),
            player, server);

        verify(manager).addWarehouseReference("s1", 9);
        verify(cm).getAllMaterialIds("s1");
    }

    // ========== 材料清单请求（服务端核心读路径） ==========

    @Test
    void materialStatsRequest_assemblesOwnerInfoAndProgress() throws Exception {
        var entry = new net.syncmaterial.syncmaterial.api.MaterialEntry(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE), 100);
        when(queryService.getMaterials("s1")).thenReturn(List.of(entry));
        var status = new CollaborationStatusS2CPacket("s1", 1, 100, 10, 5,
            List.of(new CollaborationStatusS2CPacket.ParticipantInfo("P1", 20)), List.of());
        when(cm.getCollaborationStatus("s1", 1)).thenReturn(status);
        when(db.isOwner("s1", "Player1")).thenReturn(true);
        when(db.isMainOwner("s1", "Player1")).thenReturn(false);
        when(db.getUploadedBy("s1")).thenReturn("Boss");
        when(db.getDeputyOwners("s1")).thenReturn(List.of("Dep1"));

        ModNetworkHandler.handleMaterialStatsRequest(
            new MaterialStatsRequestC2SPacket("s1"), player, server);

        // 进度装配：备货区 10 + 仓库 5 + 背包 20 = 35，缺失 65
        assertEquals(35, entry.getCountAvailable());
        assertEquals(65, entry.getCountMissing());

        ArgumentCaptor<MaterialStatsResponseS2CPacket> captor =
            ArgumentCaptor.forClass(MaterialStatsResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        var resp = captor.getValue();
        assertEquals("测试原理图", resp.schematicName());
        assertTrue(resp.isOwner());
        assertFalse(resp.isMainOwner());
        assertEquals("Boss", resp.ownerName());
        assertTrue(resp.deputyOwners().contains("Dep1"));
        assertEquals(1, resp.materials().size());

        // 有协作状态的材料，状态包也应转发给玩家
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), eq(status)));
    }

    @Test
    void materialStatsRequest_dbMissing_usesDefaults() {
        syncMaterialMock.when(SyncMaterial::getSharedDatabase).thenReturn(null);
        when(queryService.getMaterials("s1")).thenReturn(List.of());

        ModNetworkHandler.handleMaterialStatsRequest(
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

        ModNetworkHandler.handleMaterialStatsRequest(
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

        ModNetworkHandler.handleLeaveCollaboration(
            new LeaveCollaborationC2SPacket("s1", 42), player, server);

        verify(cm).leaveCollaboration("s1", 42, "Player1");
        // 无其他参与者/订阅者时 broadcast 不发包，仅 sendStatusToPlayer 单发一次
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), any(CollaborationStatusS2CPacket.class)),
            times(1));
    }

    @Test
    void leaveCollaboration_notParticipating_noSideEffects() {
        when(cm.leaveCollaboration("s1", 42, "Player1")).thenReturn(false);

        ModNetworkHandler.handleLeaveCollaboration(
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

        ModNetworkHandler.handleQueryMaterialStatus(new QueryMaterialStatusC2SPacket("s1"), player);

        assertTrue(Phase4Handler.getMaterialListSubscribers().getOrDefault("s1", java.util.Set.of()).contains(player),
            "查询状态应把玩家加入订阅列表");
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), eq(status)), times(1));
    }

    @Test
    void materialListClose_unsubscribes() {
        Phase4Handler.subscribeMaterialList(player, "s1");

        ModNetworkHandler.handleMaterialListClose(new MaterialListCloseC2SPacket("s1"), player);

        assertFalse(Phase4Handler.getMaterialListSubscribers().containsKey("s1"),
            "关闭界面应清除订阅");
    }
}
