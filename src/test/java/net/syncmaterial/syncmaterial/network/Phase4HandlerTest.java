package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * Phase4Handler 权限分支与响应测试。
 * 通过 mockStatic 拦截 SyncMaterial.getSharedDatabase / ServerPlayNetworking.send，
 * 直接调用包私有 handler，验证：非 owner 拒绝、空参数拒绝、未知操作兜底、成功路径的数据库副作用。
 */
class Phase4HandlerTest {

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
        server = mockServer();
        player = mockPlayer("Operator1");

        syncMaterialMock = mockStatic(SyncMaterial.class);
        syncMaterialMock.when(SyncMaterial::getSharedDatabase).thenReturn(db);

        networkingMock = mockStatic(ServerPlayNetworking.class);
        networkingMock.when(() -> ServerPlayNetworking.send(any(), any())).thenAnswer(inv -> null);

        ModNetworkHandler.initializeServices(mock(DatabaseQueryService.class), cm);
    }

    @AfterEach
    void tearDown() {
        syncMaterialMock.close();
        networkingMock.close();
    }

    private MinecraftServer mockServer() {
        MinecraftServer s = mock(MinecraftServer.class);
        // server.execute(runnable) 立即同步执行，模拟主线程调度
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        }).when(s).execute(any(Runnable.class));
        return s;
    }

    private ServerPlayerEntity mockPlayer(String name) {
        ServerPlayerEntity p = mock(ServerPlayerEntity.class);
        when(p.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), name));
        return p;
    }

    private OwnerActionResponseS2CPacket captureOwnerActionResponse() {
        ArgumentCaptor<OwnerActionResponseS2CPacket> captor =
            ArgumentCaptor.forClass(OwnerActionResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        return captor.getValue();
    }

    private void allowSelfClaimByDefault() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(true);
    }

    // ========== OwnerAction: 权限拒绝矩阵 ==========

    @Test
    void ownerAction_transferByNonMainOwner_rejected() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "TRANSFER", "NewOwner"), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("只有主负责人才能转让", resp.message());
        verify(db, never()).transferOwnership(any(), any());
    }

    @Test
    void ownerAction_transferEmptyTarget_rejected() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "TRANSFER", "  "), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("目标玩家不能为空", resp.message());
        verify(db, never()).transferOwnership(any(), any());
    }

    @Test
    void ownerAction_addDeputyByNonMainOwner_rejected() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "ADD_DEPUTY", "Deputy1"), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("只有主负责人才能添加副负责人", resp.message());
        verify(db, never()).addDeputyOwner(any(), any());
    }

    @Test
    void ownerAction_removeDeputyByNonMainOwner_rejected() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "REMOVE_DEPUTY", "Deputy1"), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("只有主负责人才能移除副负责人", resp.message());
        verify(db, never()).removeDeputyOwner(any(), any());
    }

    @Test
    void ownerAction_toggleSelfClaimByNonOwner_rejected() throws Exception {
        allowSelfClaimByDefault();
        when(db.isOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "TOGGLE_SELF_CLAIM", ""), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("没有权限", resp.message());
        verify(db, never()).setAllowSelfClaim(any(), anyBoolean());
    }

    @Test
    void ownerAction_unknownAction_returnsOperationFailed() throws Exception {
        allowSelfClaimByDefault();

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "DESTROY_EVERYTHING", ""), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertTrue(resp.message().startsWith("操作失败"), "未知操作应返回操作失败: " + resp.message());
    }

    @Test
    void ownerAction_nullAction_returnsOperationFailed() throws Exception {
        allowSelfClaimByDefault();

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", null, ""), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertTrue(resp.message().startsWith("操作失败"));
    }

    // ========== OwnerAction: 成功路径 ==========

    @Test
    void ownerAction_transferByMainOwner_success() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(true);
        when(db.getUploadedBy("s1")).thenReturn("NewOwner");
        when(db.getDeputyOwners("s1")).thenReturn(List.of());

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "TRANSFER", "NewOwner"), player, server);

        verify(db).transferOwnership("s1", "NewOwner");
        var resp = captureOwnerActionResponse();
        assertTrue(resp.success());
        assertTrue(resp.message().contains("NewOwner"));
        assertEquals("NewOwner", resp.ownerName());
    }

    @Test
    void ownerAction_toggleSelfClaimByOwner_flipsState() throws Exception {
        allowSelfClaimByDefault();  // 当前 true → 开关应设为 false
        when(db.isOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "TOGGLE_SELF_CLAIM", ""), player, server);

        verify(db).setAllowSelfClaim("s1", false);
        var resp = captureOwnerActionResponse();
        assertTrue(resp.success());
    }

    @Test
    void ownerAction_addDeputyByMainOwner_success() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(true);
        when(db.getUploadedBy("s1")).thenReturn("Operator1");
        when(db.getDeputyOwners("s1")).thenReturn(List.of("Deputy1"));

        Phase4Handler.handleOwnerAction(
            new OwnerActionC2SPacket("s1", "ADD_DEPUTY", "Deputy1"), player, server);

        verify(db).addDeputyOwner("s1", "Deputy1");
        var resp = captureOwnerActionResponse();
        assertTrue(resp.success());
        assertTrue(resp.deputyOwners().contains("Deputy1"));
    }

    // ========== BatchAssign ==========

    @Test
    void batchAssign_byNonOwner_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleBatchAssign(
            new BatchAssignC2SPacket("s1", List.of(1), List.of("A")), player, server);

        ArgumentCaptor<BatchAssignResponseS2CPacket> captor =
            ArgumentCaptor.forClass(BatchAssignResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("没有权限，只有负责人才能分配", captor.getValue().message());
        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
    }

    @Test
    void batchAssign_emptyMaterials_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleBatchAssign(
            new BatchAssignC2SPacket("s1", List.of(), List.of("A")), player, server);

        ArgumentCaptor<BatchAssignResponseS2CPacket> captor =
            ArgumentCaptor.forClass(BatchAssignResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("请选择至少一个材料", captor.getValue().message());
    }

    @Test
    void batchAssign_emptyPlayers_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleBatchAssign(
            new BatchAssignC2SPacket("s1", List.of(1), List.of()), player, server);

        ArgumentCaptor<BatchAssignResponseS2CPacket> captor =
            ArgumentCaptor.forClass(BatchAssignResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("请选择至少一个玩家", captor.getValue().message());
    }

    @Test
    void batchAssign_byOwner_assignsAllCombinations() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(true);
        when(cm.joinCollaboration(any(), anyInt(), any())).thenReturn(true);

        Phase4Handler.handleBatchAssign(
            new BatchAssignC2SPacket("s1", List.of(1, 2, 3), List.of("A", "B")), player, server);

        // 3 材料 × 2 玩家 = 6 次分配
        verify(cm, times(6)).joinCollaboration(eq("s1"), anyInt(), any());
        verify(cm).joinCollaboration("s1", 1, "A");
        verify(cm).joinCollaboration("s1", 3, "B");

        ArgumentCaptor<BatchAssignResponseS2CPacket> captor =
            ArgumentCaptor.forClass(BatchAssignResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertTrue(captor.getValue().success());
        assertTrue(captor.getValue().message().contains("6"));
    }

    // ========== KickFromMaterial ==========

    @Test
    void kick_byNonOwner_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleKickFromMaterial(
            new KickFromMaterialC2SPacket("s1", List.of(1), "Victim"), player, server);

        ArgumentCaptor<KickFromMaterialResponseS2CPacket> captor =
            ArgumentCaptor.forClass(KickFromMaterialResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("没有权限", captor.getValue().message());
        verify(cm, never()).leaveCollaboration(any(), anyInt(), any());
    }

    @Test
    void kick_emptyTarget_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleKickFromMaterial(
            new KickFromMaterialC2SPacket("s1", List.of(1), " "), player, server);

        ArgumentCaptor<KickFromMaterialResponseS2CPacket> captor =
            ArgumentCaptor.forClass(KickFromMaterialResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("目标玩家不能为空", captor.getValue().message());
    }

    @Test
    void kick_byOwner_removesFromAllMaterials() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(true);
        when(cm.leaveCollaboration(any(), anyInt(), any())).thenReturn(true);

        Phase4Handler.handleKickFromMaterial(
            new KickFromMaterialC2SPacket("s1", List.of(1, 2), "Victim"), player, server);

        verify(cm, times(2)).leaveCollaboration(eq("s1"), anyInt(), eq("Victim"));
        ArgumentCaptor<KickFromMaterialResponseS2CPacket> captor =
            ArgumentCaptor.forClass(KickFromMaterialResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertTrue(captor.getValue().success());
    }

    // ========== Rescan（handleRescanStagingArea） ==========

    @Test
    void rescan_invalidSchematicId_rejected() {
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.player()).thenReturn(player);
        when(context.server()).thenReturn(server);

        ModNetworkHandler.handleRescanStagingArea(
            new RescanStagingAreaC2SPacket(""), context);

        ArgumentCaptor<RescanStagingAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(RescanStagingAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("无效的原理图ID", captor.getValue().message());
    }

    @Test
    void rescan_noStagingAreas_rejected() {
        var manager = mock(net.syncmaterial.syncmaterial.server.StagingAreaManager.class);
        syncMaterialMock.when(SyncMaterial::getServerStagingAreaManager).thenReturn(manager);
        when(manager.getStagingAreas("s1")).thenReturn(List.of());
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.player()).thenReturn(player);
        when(context.server()).thenReturn(server);

        ModNetworkHandler.handleRescanStagingArea(
            new RescanStagingAreaC2SPacket("s1"), context);

        ArgumentCaptor<RescanStagingAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(RescanStagingAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("没有找到备货区", captor.getValue().message());
    }
}
