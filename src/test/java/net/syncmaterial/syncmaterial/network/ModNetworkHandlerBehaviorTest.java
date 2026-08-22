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
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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

    private SchematicDatabase db;
    private CollaborationManager cm;
    private MinecraftServer server;
    private ServerPlayerEntity player;

    @BeforeAll
    static void setup() {
        // mock ServerPlayerEntity 会触发 Entity 静态初始化，需要注册表（与执行顺序无关）
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void setUp() {
        db = mock(SchematicDatabase.class);
        cm = mock(CollaborationManager.class);
        server = mock(MinecraftServer.class);
        player = mock(ServerPlayerEntity.class);
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), "Player1"));
        when(player.getName()).thenReturn(Text.literal("Player1"));

        syncMaterialMock = mockStatic(SyncMaterial.class);
        syncMaterialMock.when(SyncMaterial::getSharedDatabase).thenReturn(db);

        networkingMock = mockStatic(ServerPlayNetworking.class);

        ModNetworkHandler.initializeServices(mock(DatabaseQueryService.class), cm);
    }

    @AfterEach
    void tearDown() {
        // 先取消本测试可能建立的取货订阅（mock 仍可用），避免静态状态泄漏到其他测试类
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", false), player);
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s2", false), player);
        syncMaterialMock.close();
        networkingMock.close();
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

        ModNetworkHandler.handleInventoryUpdate(
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void inventoryUpdate_byCollaborator_updatesCount() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleInventoryUpdate(
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

        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s1", true), player);
        ModNetworkHandler.handleWarehouseContainerRequest(new WarehouseContainerRequestC2SPacket("s2", true), player);

        ModNetworkHandler.pushWarehouseContainerUpdate(manager);

        // 第二次订阅时 handler 已按并集 {1,2} 查询过一次，推送再查一次
        verify(manager, atLeastOnce()).getContainerEntriesForWarehouses(argThat(ids ->
            ids.size() == 2 && ids.contains(1) && ids.contains(2)));
        // 每个原理图订阅各一次 + 推送一次 = 3 次
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), times(3));
    }

    // ========== 备货区网络配置入口（ADD） ==========

    @Test
    void stagingAreaConfig_add_rescansImmediatelyAndResponds() {
        StagingAreaManager manager = mockWarehouseManager();
        // handler 解析 world 时 orElse 参数会立即求值（即使 AreaData 已带 world 也调用）
        var worldKey = net.minecraft.registry.RegistryKey.of(
            net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.of("minecraft", "overworld"));
        var playerWorld = mock(net.minecraft.server.world.ServerWorld.class);
        when(playerWorld.getRegistryKey()).thenReturn(worldKey);
        when(player.getWorld()).thenReturn(playerWorld);
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
}
