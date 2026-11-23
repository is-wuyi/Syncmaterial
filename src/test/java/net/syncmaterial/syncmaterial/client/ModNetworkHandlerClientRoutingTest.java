package net.syncmaterial.syncmaterial.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.network.BatchAssignResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.HelloS2CPacket;
import net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket;
import net.syncmaterial.syncmaterial.network.MaterialStatsResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient;
import net.syncmaterial.syncmaterial.network.OwnerActionResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.TeamCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.TeamResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.WarehouseAreaResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * INIT 阶段按原始 handler 绑定的接收入口合同：
 * 捕获 INIT 实际注册的 receiver，直接喂原 handler A/B 的包，验证旧连接的
 * Hello/材料/团队/旧协议回包不会落到新会话；来源只取捕获的 handler 身份，
 * 不读全局 connection token。
 */
class ModNetworkHandlerClientRoutingTest {
    private static final String PROJECT = "project-a";
    private static final PlayerIdentity ACTOR = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "Actor");

    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private GuiMaterialList gui;
    private ReceiverCapture captureA;
    private ReceiverCapture captureB;
    private Object handlerA;
    private Object handlerB;

    @BeforeAll
    static void bootstrap() {
        // 包类型携带 ItemStack/注册表静态初始化，先做版本与注册表引导
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    /** 捕获一次 INIT 安装的全部 S2C receiver，测试直接按类型喂包。 */
    private static final class ReceiverCapture implements ModNetworkHandlerClient.S2CRegistrar {
        private final ClientPlayNetworking.Context context;
        final Map<CustomPacketPayload.Type<?>, BiConsumer<Object, ClientPlayNetworking.Context>> byType =
                new LinkedHashMap<>();

        ReceiverCapture(ClientPlayNetworking.Context context) {
            this.context = context;
        }

        @Override
        public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type,
                BiConsumer<T, ClientPlayNetworking.Context> receiver) {
            byType.put(type, (payload, ctx) -> receiver.accept(cast(payload), ctx));
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object payload) {
            return (T) payload;
        }

        void receive(CustomPacketPayload.Type<?> type, CustomPacketPayload payload) {
            BiConsumer<Object, ClientPlayNetworking.Context> receiver = byType.get(type);
            assertNotNull(receiver, "该 S2C 类型未按 INIT 注册: " + type);
            receiver.accept(payload, context);
        }
    }

    @BeforeEach
    void setUp() {
        ClientSessionManager.resetForTesting();
        ClientProtocolState.reset();
        TeamManagementClient.resetForTesting();
        TeamManagementClient.setTransportForTesting(sent::add);
        TeamManagementClient.setIdentityProviderForTesting(() -> ACTOR);
        TeamManagementClient.setScreenProviderForTesting(() -> gui);
        // 每个原始连接各捕获一份 INIT 注册的 receiver；client() 的 execute 内联执行，
        // 等价于接收器排队到客户端线程后的真实调度
        handlerA = new Object();
        handlerB = new Object();
        captureA = captureFor(handlerA);
        captureB = captureFor(handlerB);
    }

    @AfterEach
    void tearDown() {
        ClientSessionManager.resetForTesting();
        ClientProtocolState.reset();
        TeamManagementClient.resetForTesting();
    }

    private static ReceiverCapture captureFor(Object handler) {
        ReceiverCapture capture = new ReceiverCapture(inlineContext());
        ModNetworkHandlerClient.installReceivers(handler, capture);
        return capture;
    }

    private static ClientPlayNetworking.Context inlineContext() {
        Minecraft client = mock(Minecraft.class);
        doAnswer(ModNetworkHandlerClientRoutingTest::runInline)
                .when(client).execute(any());
        return new ClientPlayNetworking.Context() {
            @Override public Minecraft client() { return client; }
            @Override public LocalPlayer player() { return null; }
            @Override public PacketSender responseSender() { return null; }
        };
    }

    private static Object runInline(InvocationOnMock invocation) {
        ((Runnable) invocation.getArgument(0)).run();
        return null;
    }

    private static Set<CustomPacketPayload.Type<?>> expectedS2CTypes() {
        return Set.of(
                HelloS2CPacket.ID,
                ProjectViewS2CPacket.ID,
                MaterialStatsResponseS2CPacket.ID,
                MaterialCatalogUpdateS2CPacket.ID,
                CollaborationStatusS2CPacket.ID,
                AreaInventorySnapshotS2CPacket.ID,
                StagingAreaConfigResponseS2CPacket.ID,
                RescanStagingAreaResponseS2CPacket.ID,
                TeamResponseS2CPacket.ID,
                OwnerActionResponseS2CPacket.ID,
                BatchAssignResponseS2CPacket.ID,
                KickFromMaterialResponseS2CPacket.ID,
                PlayerListResponseS2CPacket.ID,
                WarehouseContainerResponseS2CPacket.ID,
                WarehouseAreaResponseS2CPacket.ID);
    }

    private static GuiMaterialList mockGui(String project) {
        GuiMaterialList list = mock(GuiMaterialList.class);
        SyncMaterialList sync = mock(SyncMaterialList.class);
        when(sync.getSchematicId()).thenReturn(project);
        when(list.getSchematicId()).thenReturn(project);
        when(list.getMaterialList()).thenReturn(sync);
        return list;
    }

    private static GuiPlayerSelectDialog mockDialog(GuiMaterialList list, String project) {
        GuiPlayerSelectDialog dialog = mock(GuiPlayerSelectDialog.class);
        when(dialog.getMaterialList()).thenReturn(list);
        when(dialog.getSchematicId()).thenReturn(project);
        return dialog;
    }

    private static TeamState state(String project) {
        return new TeamState(project, ACTOR, List.of(), true);
    }

    private static TeamResponse response(String project, long requestId, TeamAction action,
                                         boolean success) {
        return new TeamResponse(project, requestId, action, 1, success, "", state(project),
                List.of(), 0, 0);
    }

    /** 仅供测试的 direct entry：显式 join 之后才有会话可写。 */
    private static void scopedProtocol(int version) {
        ClientProtocolState.onHandshakeResponse(version, "test", true);
    }

    @Test
    void initRegistersEveryModS2CTypePerConnection() {
        assertEquals(expectedS2CTypes(), captureA.byType.keySet(),
                "INIT 必须为每个原始连接注册本模组全部 S2C");
        assertEquals(expectedS2CTypes(), captureB.byType.keySet());
    }

    @Test
    void staleHelloFromOriginalHandlerCannotChangeTheReplacementSession() {
        TeamManagementClient.onJoin(handlerA);
        captureA.receive(HelloS2CPacket.ID, new HelloS2CPacket(5, "server-a", true));
        assertTrue(ClientProtocolState.isUsable());
        assertEquals(5, ClientProtocolState.getServerProtocolVersion());

        // 快速重连：JOIN 先封闭旧会话再发布新 current
        TeamManagementClient.onJoin(handlerB);
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus());

        captureA.receive(HelloS2CPacket.ID, new HelloS2CPacket(5, "stale", true));
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus(),
                "旧 handler 的 Hello 不得落入新会话");
        assertFalse(ClientProtocolState.isUsable());

        captureB.receive(HelloS2CPacket.ID, new HelloS2CPacket(3, "server-b", true));
        assertTrue(ClientProtocolState.isUsable());
        assertEquals(3, ClientProtocolState.getServerProtocolVersion());
    }

    @Test
    void teamResponseFromOldHandlerIsDroppedAndCannotConsumeNewRequest() {
        TeamManagementClient.onJoin(handlerA);
        scopedProtocol(4);
        gui = mockGui(PROJECT);
        TeamManagementClient.onMaterialListOpened(gui);

        TeamManagementClient.onJoin(handlerB);
        scopedProtocol(4);
        gui = mockGui(PROJECT);
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        long newRequestId = ((TeamCommandC2SPacket) sent.get(sent.size() - 1))
                .command().requestId();
        assertEquals(1, TeamManagementClient.pendingCountForTesting());

        // 旧 handler A 捕获的 receiver 投递回包：必须整包丢弃
        captureA.receive(TeamResponseS2CPacket.ID,
                new TeamResponseS2CPacket(response(PROJECT, newRequestId,
                        TeamAction.TOGGLE_SELF_CLAIM, true)));
        verify(gui, never()).applyTeamState(any(), any());
        assertEquals(1, TeamManagementClient.pendingCountForTesting(),
                "旧 handler 回包不得消费新连接的请求");

        captureB.receive(TeamResponseS2CPacket.ID,
                new TeamResponseS2CPacket(response(PROJECT, newRequestId,
                        TeamAction.TOGGLE_SELF_CLAIM, true)));
        verify(gui).applyTeamState(any(), any());
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void legacyResponseFromOldHandlerCannotMatchNewConnectionWrite() {
        TeamManagementClient.onJoin(handlerA);
        scopedProtocol(3);
        gui = mockGui(PROJECT);
        TeamManagementClient.onMaterialListOpened(gui);

        TeamManagementClient.onJoin(handlerB);
        scopedProtocol(3);
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertEquals(1, sent.size());

        captureA.receive(BatchAssignResponseS2CPacket.ID,
                new BatchAssignResponseS2CPacket(true, "late-ack"));
        captureA.receive(OwnerActionResponseS2CPacket.ID,
                new OwnerActionResponseS2CPacket(true, "late", "Owner", List.of("Owner"), true));
        verify(gui, never()).applyLegacyOwnerState(anyString(), anyList(), anyBoolean());
        verify(gui, never()).onTeamFeedback(anyBoolean(), anyString());
        assertEquals(1, sent.size(), "旧 handler 的迟到回包不得触发新连接发送后续目标");
    }

    @Test
    void materialStatsFromOldHandlerCannotOpenListOnNewConnection() {
        TeamManagementClient.onJoin(handlerA);
        TeamManagementClient.onJoin(handlerB);
        var payload = new MaterialStatsResponseS2CPacket(PROJECT, "name", List.of(),
                false, false, "", List.of(), true);
        try (MockedStatic<SyncMaterialClient> client = mockStatic(SyncMaterialClient.class)) {
            captureA.receive(MaterialStatsResponseS2CPacket.ID, payload);
            client.verify(() -> SyncMaterialClient.onLegacyMaterialStats(any(), any(), anyList(),
                    anyBoolean(), anyBoolean(), anyString(), anyList(), anyBoolean()), never());
            captureB.receive(MaterialStatsResponseS2CPacket.ID, payload);
            client.verify(() -> SyncMaterialClient.onLegacyMaterialStats(any(), any(), anyList(),
                    anyBoolean(), anyBoolean(), anyString(), anyList(), anyBoolean()));
        }
    }

    @Test
    void protocolFiveRejectsLegacyMaterialResponseWithoutOpeningAList() {
        TeamManagementClient.onJoin(handlerA);
        scopedProtocol(5);
        var payload = new MaterialStatsResponseS2CPacket(PROJECT, "name", List.of(),
                false, false, "", List.of(), true);
        captureA.receive(MaterialStatsResponseS2CPacket.ID, payload);
        assertNull(ClientSessionManager.current().orElseThrow().projectCoordinator().materialList());
    }

    @Test
    void legacyWorldPacketsAreAcceptedOnlyForLegacyPeers() {
        TeamManagementClient.onJoin(handlerA);
        scopedProtocol(5);
        var packet = new WarehouseAreaResponseS2CPacket(List.of(
                new StagingAreaConfigResponseS2CPacket.AreaInfo(1, "warehouse",
                        0, 64, 0, 1, 65, 1, "minecraft:overworld")), List.of());
        var renderer = net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance();

        captureA.receive(WarehouseAreaResponseS2CPacket.ID, packet);
        assertTrue(renderer.getWarehouseAreas().isEmpty(), "协议5旧线框不得覆盖关联视图");

        scopedProtocol(3);
        captureA.receive(WarehouseAreaResponseS2CPacket.ID, packet);
        assertEquals(1, renderer.getWarehouseAreas().size(), "旧服务端保留原有兼容入口");
    }

    @Test
    void receiversForUnjoinedHandlerAreDropped() {
        Object orphan = new Object();
        ReceiverCapture capture = captureFor(orphan);

        capture.receive(HelloS2CPacket.ID, new HelloS2CPacket(5, "srv", true));
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus(),
                "未 JOIN 的 handler 不得落地任何状态");

        capture.receive(TeamResponseS2CPacket.ID,
                new TeamResponseS2CPacket(response(PROJECT, 1, TeamAction.TOGGLE_SELF_CLAIM, true)));
        capture.receive(OwnerActionResponseS2CPacket.ID,
                new OwnerActionResponseS2CPacket(true, "ok", "Owner", List.of(), true));
        capture.receive(WarehouseAreaResponseS2CPacket.ID,
                new WarehouseAreaResponseS2CPacket(List.of(), List.of()));
        assertFalse(ClientProtocolState.isUsable());
    }
}
