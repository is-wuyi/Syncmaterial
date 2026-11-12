package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * Phase4Handler 权限分支与响应测试。
 * 业务服务全部来自显式 {@link LegacyServerContext}，不再 mock SyncMaterial 静态 getter。
 */
class Phase4HandlerTest {

    private MockedStatic<ServerPlayNetworking> networkingMock;

    private LegacyNetworkTestSupport support;
    private SchematicDatabase db;
    private CollaborationManager cm;
    private MinecraftServer server;
    private ServerPlayer player;
    private LegacyServerContext owner;

    @BeforeAll
    static void setup() {
        LegacyNetworkTestSupport.bootstrap();
    }

    @BeforeEach
    void setUp() {
        support = new LegacyNetworkTestSupport("Operator1");
        db = support.db;
        cm = support.collaboration;
        server = support.server;
        player = support.player;
        owner = support.owner;
        support.handshake();

        networkingMock = mockStatic(ServerPlayNetworking.class);
        networkingMock.when(() -> ServerPlayNetworking.send(any(), any())).thenAnswer(inv -> null);
    }

    @AfterEach
    void tearDown() {
        networkingMock.close();
        support.close();
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

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "TOGGLE_SELF_CLAIM", ""), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertEquals("没有权限", resp.message());
        verify(db, never()).setAllowSelfClaim(any(), anyBoolean());
    }

    @Test
    void ownerAction_unknownAction_returnsOperationFailed() throws Exception {
        allowSelfClaimByDefault();

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "DESTROY_EVERYTHING", ""), player, server);

        var resp = captureOwnerActionResponse();
        assertFalse(resp.success());
        assertTrue(resp.message().startsWith("操作失败"), "未知操作应返回操作失败: " + resp.message());
    }

    @Test
    void ownerAction_nullAction_returnsOperationFailed() throws Exception {
        allowSelfClaimByDefault();

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "TRANSFER", "NewOwner"), player, server);

        verify(db).transferOwnership("s1", "NewOwner");
        var resp = captureOwnerActionResponse();
        assertTrue(resp.success());
        assertTrue(resp.message().contains("NewOwner"));
        assertEquals("NewOwner", resp.ownerName());
    }

    @Test
    void ownerAction_toggleSelfClaimByOwner_flipsState() throws Exception {
        allowSelfClaimByDefault();
        when(db.isOwner("s1", "Operator1")).thenReturn(true);

        Phase4Handler.handleOwnerAction(owner,
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

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "ADD_DEPUTY", "Deputy1"), player, server);

        verify(db).addDeputyOwner("s1", "Deputy1");
        var resp = captureOwnerActionResponse();
        assertTrue(resp.success());
        assertTrue(resp.deputyOwners().contains("Deputy1"));
    }

    @Test
    void ownerAction_broadcastsToOwnerScopedSubscribers() throws Exception {
        allowSelfClaimByDefault();
        when(db.isMainOwner("s1", "Operator1")).thenReturn(true);
        when(db.getUploadedBy("s1")).thenReturn("Operator1");
        when(db.getDeputyOwners("s1")).thenReturn(List.of());
        owner.subscriptions().subscribeMaterials(player, "s1");

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "ADD_DEPUTY", "Deputy1"), player, server);

        // 订阅者广播 + 操作者兜底发送，都是本上下文连接
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player),
            any(OwnerActionResponseS2CPacket.class)), atLeastOnce());
    }

    // ========== BatchAssign ==========

    @Test
    void batchAssign_byNonOwner_rejected() throws Exception {
        when(db.isOwner("s1", "Operator1")).thenReturn(false);

        Phase4Handler.handleBatchAssign(owner,
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

        Phase4Handler.handleBatchAssign(owner,
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

        Phase4Handler.handleBatchAssign(owner,
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

        Phase4Handler.handleBatchAssign(owner,
            new BatchAssignC2SPacket("s1", List.of(1, 2, 3), List.of("A", "B")), player, server);

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

        Phase4Handler.handleKickFromMaterial(owner,
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

        Phase4Handler.handleKickFromMaterial(owner,
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

        Phase4Handler.handleKickFromMaterial(owner,
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
        ModNetworkHandler.handleRescanStagingArea(owner,
            new RescanStagingAreaC2SPacket(""), player, server);

        ArgumentCaptor<RescanStagingAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(RescanStagingAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("无效的原理图ID", captor.getValue().message());
    }

    @Test
    void rescan_noStagingAreas_rejected() {
        StagingAreaManager manager = support.staging;
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        ModNetworkHandler.handleRescanStagingArea(owner,
            new RescanStagingAreaC2SPacket("s1"), player, server);

        ArgumentCaptor<RescanStagingAreaResponseS2CPacket> captor =
            ArgumentCaptor.forClass(RescanStagingAreaResponseS2CPacket.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()));
        assertFalse(captor.getValue().success());
        assertEquals("没有找到备货区", captor.getValue().message());
    }

    @Test
    void phase4Handler_rejectsClosedOwnerWithoutDatabaseAccess() {
        support.open.set(false);

        Phase4Handler.handleOwnerAction(owner,
            new OwnerActionC2SPacket("s1", "TOGGLE_SELF_CLAIM", ""), player, server);

        verifyNoInteractions(db);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }
}
