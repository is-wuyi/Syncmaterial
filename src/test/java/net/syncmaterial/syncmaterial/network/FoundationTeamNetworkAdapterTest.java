package net.syncmaterial.syncmaterial.network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.server.runtime.MaterialCatalogTracker;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;
import net.syncmaterial.syncmaterial.server.runtime.TeamManagementService;
import net.syncmaterial.syncmaterial.server.runtime.TeamRequestTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Registry-level acceptance for the additive protocol-4 management receiver.
 *
 * <p>The common fixture uses a real {@link ServerRuntime} and SQLite store; only the
 * Minecraft server/player/network boundary is mocked. The delayed-callback case uses a
 * mocked runtime/service because a pending future is the only deterministic way to
 * observe a connection change between submission and completion.</p>
 */
class FoundationTeamNetworkAdapterTest {
    @TempDir
    Path temporary;

    private final UUID playerId = UUID.randomUUID();
    private final List<CustomPacketPayload> packets = Collections.synchronizedList(new ArrayList<>());
    private final Map<CustomPacketPayload.Type<?>, ServerPlayNetworking.PlayPayloadHandler<?>> handlers =
            new HashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> serverTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private MockedStatic<ServerPlayNetworking> networking;
    private MockedStatic<ServerRuntimeRegistry> registry;
    private ServerRuntime runtime;
    private ServerPlayer player;
    private MinecraftServer server;
    private ServerPlayNetworking.Context context;
    private String projectId;
    private long connection;

    private final PlayerIdentity owner = new PlayerIdentity(playerId, "RealOwner");
    private final PlayerIdentity target = new PlayerIdentity(UUID.randomUUID(), "RealTarget");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() throws Exception {
        player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getName()).thenReturn(Component.literal("RealOwner"));
        ServerLevel world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.level()).thenReturn(world);
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        when(players.getPlayers()).thenReturn(List.of(player));
        doAnswer(invocation -> {
            serverTasks.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        when(server.getFile("usercache.json")).thenReturn(temporary.resolve("usercache.json"));
        when(world.getServer()).thenReturn(server);
        when(player.level()).thenReturn(world);

        runtime = ServerRuntime.open(temporary.resolve("world"), temporary.resolve("game"),
                path -> List.of(new MaterialCount("minecraft:stone", 64)), ignored -> { });
        runtime.connected(playerId);
        connection = runtime.connection(playerId);
        projectId = "team-network-" + UUID.randomUUID();
        runtime.store().ingest(new SchematicMetadata(projectId, "network", Path.of("network.litematic"),
                "network-hash", owner), List.of(new MaterialCount("minecraft:stone", 64),
                new MaterialCount("minecraft:dirt", 32)), runtime.interpretationId()).join();
        runtime.store().reconcilePlayers(List.of(target)).join();
        runtime.subscribe(playerId, projectId);
        ServerRuntimeRegistry.install(server, runtime);

        context = mock(ServerPlayNetworking.Context.class);
        when(context.player()).thenReturn(player);
        when(context.server()).thenReturn(server);

        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.registerGlobalReceiver(any(), any())).thenAnswer(invocation -> {
            handlers.put(invocation.getArgument(0), invocation.getArgument(1));
            return true;
        });
        networking.when(() -> ServerPlayNetworking.send(eq(player), any())).thenAnswer(invocation -> {
            packets.add(invocation.getArgument(1));
            return null;
        });
        ProtocolHandshake.registerServer(server);
        assertTrue(ProtocolHandshake.recordHandshake(server, player, ProtocolVersion.CURRENT, "test"));
        FoundationTeamNetworkAdapter.register();
        assertTrue(ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT),
                "测试前置：协议 4 必须已握手");
        assertTrue(ModNetworkHandler.validateHandshakedPlayer(player), "测试前置：业务准入必须通过");
        assertTrue(runtime.isOpen(), "测试前置：runtime 必须打开");
        assertEquals(1L, runtime.connection(playerId), "测试前置：连接代次必须存在");
        assertSame(runtime, ServerRuntimeRegistry.get(server), "测试前置：静态 runtime 必须匹配");
        assertNotNull(handlers.get(TeamCommandC2SPacket.ID), "测试前置：团队 receiver 必须注册");
        assertNotNull(handlers.get(OwnerActionC2SPacket.ID), "测试前置：旧版负责人 receiver 必须注册");
        assertSame(server, LegacyNetworkDispatch.serverOf(player), "测试前置：玩家服务端必须匹配");
        assertSame(player, server.getPlayerList().getPlayer(playerId), "测试前置：玩家必须在线");
    }

    @AfterEach
    void tearDown() {
        if (networking != null) networking.close();
        if (registry != null) registry.close();
        if (server != null) {
            ProtocolHandshake.clear(server);
            ServerRuntimeRegistry.close(server);
        }
        if (runtime != null) runtime.close();
    }

    @Test
    void scopedTeamCommandUpdatesTheRealStoreAndSendsCorrelatedResponse() throws Exception {
        ProjectSnapshot snapshot = runtime.store().findProject(projectId).join().orElseThrow();
        int materialId = snapshot.materials().stream()
                .filter(value -> value.itemId().equals("minecraft:stone"))
                .findFirst().orElseThrow().id();
        long revision = runtime.catalogs().sent(playerId, connection, snapshot);
        TeamCommand command = new TeamCommand(projectId, 41, TeamAction.ASSIGN, revision,
                List.of(materialId), List.of(target));

        assertSame(player, FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection),
                "测试前置：当前玩家连接必须可用");
        dispatch(new TeamCommandC2SPacket(command));

        boolean responded = await(() -> awaitResponse(41) != null);
        assertTrue(responded, "协议 4 必须回送请求关联的响应; packets=" + packets.stream()
                .map(packet -> packet.getClass().getSimpleName() + ":"
                        + (packet instanceof TeamResponseS2CPacket response ? response.response() : ""))
                .toList() + "; sequence=" + runtime.teamRequests().currentSequence(projectId));
        TeamResponseS2CPacket response = awaitResponse(41);
        assertTrue(response.response().success(),
                "协议 4 分配失败: " + response.response().message());
        assertTrue(await(() -> runtime.store().claims(projectId).join().stream()
                        .anyMatch(claim -> claim.player().uuid().equals(target.uuid()))),
                "协议 4 分配必须真实写入认领");
        assertEquals(projectId, response.response().projectId());
        assertEquals(TeamAction.ASSIGN, response.response().action());
        assertTrue(response.response().success());
        assertTrue(response.response().sequence() > 0, "作用域回包必须带投递序号");
        assertNotNull(response.response().team(), "成功操作必须携带权威团队状态");
        assertEquals(1, response.response().processedCount(),
                "响应处理数必须来自真实分配尝试");
        assertEquals(1, response.response().changedCount(),
                "响应变更数必须来自真实认领插入");
    }

    @Test
    void duplicateAndStaleScopedRequestIdsDoNotRunOrSendAgain() throws Exception {
        ProjectSnapshot snapshot = runtime.store().findProject(projectId).join().orElseThrow();
        int materialId = snapshot.materials().stream()
                .filter(value -> value.itemId().equals("minecraft:stone"))
                .findFirst().orElseThrow().id();
        long revision = runtime.catalogs().sent(playerId, connection, snapshot);
        TeamCommand command = new TeamCommand(projectId, 7, TeamAction.ASSIGN, revision,
                List.of(materialId), List.of(target));

        dispatch(new TeamCommandC2SPacket(command));
        assertTrue(await(() -> awaitResponse(7) != null), "首个请求必须完成");
        int packetCount = packets.size();

        dispatch(new TeamCommandC2SPacket(command));
        dispatch(new TeamCommandC2SPacket(new TeamCommand(projectId, 6, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of())));
        drainServerTasks();
        assertEquals(packetCount, packets.size(),
                "重复或回退的 requestId 不得执行第二次或发送第二个回包");
        assertEquals(1, runtime.store().claims(projectId).join().size(),
                "重复 requestId 不得产生额外认领");

        dispatch(new TeamCommandC2SPacket(new TeamCommand(projectId, 8, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of())));
        assertTrue(await(() -> awaitResponse(8) != null), "更高 requestId 必须继续受理");
    }

    @Test
    void legacyReceiversExerciseFourManagementActionsAgainstTheRealStore() throws Exception {
        dispatchOwnerAction("ADD_DEPUTY", target.name());
        assertTrue(await(() -> legacyResponse(OwnerActionResponseS2CPacket.class) != null),
                "添加副负责人必须回旧版响应");
        OwnerActionResponseS2CPacket addResponse = legacyResponse(OwnerActionResponseS2CPacket.class);
        assertTrue(addResponse.success(), "旧版添加副负责人失败: " + addResponse.message());
        assertTrue(await(() -> runtime.store().teamState(projectId).join().orElseThrow()
                .deputies().stream().anyMatch(value -> value.uuid().equals(target.uuid()))),
                "旧版添加副负责人必须写入 UUID 角色");

        dispatchOwnerAction("REMOVE_DEPUTY", target.name());
        assertTrue(await(() -> runtime.store().teamState(projectId).join().orElseThrow()
                .deputies().isEmpty()), "旧版移除副负责人必须生效");

        dispatchOwnerAction("TOGGLE_SELF_CLAIM", "");
        assertTrue(await(() -> !runtime.store().teamState(projectId).join().orElseThrow()
                .allowSelfClaim()), "旧版自行认领开关必须生效");

        assertTrue(await(() -> packets.stream()
                        .filter(OwnerActionResponseS2CPacket.class::isInstance).count() >= 3),
                "添加/移除/开关三个旧版响应必须全部到达");
        packets.clear();
        dispatchOwnerAction("TRANSFER", target.name());
        assertTrue(await(() -> runtime.store().teamState(projectId).join().orElseThrow()
                .isMainOwner(target)), "旧版转让必须改变真实主负责人");
        OwnerActionResponseS2CPacket transferResponse = awaitLegacyResponse(OwnerActionResponseS2CPacket.class);
        assertTrue(transferResponse.success());
        assertEquals(target.name(), transferResponse.ownerName());

        // Legacy assign/kick use a fresh project so the original owner still manages it.
        String assignProject = "team-legacy-assign-" + UUID.randomUUID();
        runtime.store().ingest(new SchematicMetadata(assignProject, "legacy-assign",
                        Path.of("legacy-assign.litematic"), "legacy-assign", owner),
                List.of(new MaterialCount("minecraft:stone", 16)), runtime.interpretationId()).join();
        int assignMaterial = runtime.store().findProject(assignProject).join().orElseThrow()
                .materials().getFirst().id();
        dispatch(new BatchAssignC2SPacket(assignProject, List.of(assignMaterial), List.of(target.name())));
        assertTrue(await(() -> runtime.store().claims(assignProject).join().stream()
                        .anyMatch(claim -> claim.player().uuid().equals(target.uuid()))),
                "旧版批量分配必须真实写入认领");
        BatchAssignResponseS2CPacket assignResponse = awaitLegacyResponse(BatchAssignResponseS2CPacket.class);
        assertTrue(assignResponse.success());

        dispatch(new KickFromMaterialC2SPacket(assignProject, List.of(assignMaterial), target.name()));
        assertTrue(await(() -> runtime.store().claims(assignProject).join().isEmpty()),
                "旧版按材料踢出必须清除认领");
        KickFromMaterialResponseS2CPacket kickResponse = awaitLegacyResponse(KickFromMaterialResponseS2CPacket.class);
        assertTrue(kickResponse.success());

        dispatch(new PlayerListRequestC2SPacket(assignProject));
        PlayerListResponseS2CPacket directoryResponse = awaitLegacyResponse(PlayerListResponseS2CPacket.class);
        assertTrue(directoryResponse.players().stream()
                .anyMatch(info -> info.name().equals("RealOwner") && info.online()),
                "旧版目录必须包含在线发起者");
        assertTrue(directoryResponse.players().stream()
                        .anyMatch(info -> info.name().equals(target.name())),
                "旧版目录必须包含已知离线身份");
        runtime.store().removeProject(assignProject).join();
    }

    @Test
    void delayedCallbackAfterConnectionReplacementOrUnhandshakeSendsNothing() throws Exception {
        ServerRuntime mockRuntime = mock(ServerRuntime.class);
        TeamManagementService mockTeams = mock(TeamManagementService.class);
        registry = mockStatic(ServerRuntimeRegistry.class);
        registry.when(() -> ServerRuntimeRegistry.get(server)).thenReturn(mockRuntime);
        TeamRequestTracker tracker = new TeamRequestTracker();
        when(mockRuntime.isOpen()).thenReturn(true);
        when(mockRuntime.connection(playerId)).thenReturn(1L);
        when(mockRuntime.teams()).thenReturn(mockTeams);
        when(mockRuntime.teamRequests()).thenReturn(tracker);
        when(mockRuntime.catalogs()).thenReturn(new MaterialCatalogTracker());
        when(mockRuntime.subscribers(projectId)).thenReturn(java.util.Set.of(playerId));
        registry.when(() -> ServerRuntimeRegistry.get(server)).thenReturn(mockRuntime);

        TeamCommand command = new TeamCommand(projectId, 90, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of());
        CompletableFuture<TeamCommand> resolved = CompletableFuture.completedFuture(command);
        when(mockTeams.resolveTargets(any(), anyList(), any())).thenReturn(resolved);
        CompletableFuture<TeamOutcome> pending = new CompletableFuture<>();
        when(mockTeams.execute(any(), any(), any())).thenReturn(pending);

        FoundationTeamNetworkAdapter.handle(command, player, server, true);
        drainServerTasks();
        tracker.begin(playerId, 1L, 91L); // first request is still in flight
        FoundationTeamNetworkAdapter.handle(new TeamCommand(projectId, 91, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of()), player, server, true);
        drainServerTasks();
        verify(mockTeams, times(1)).execute(any(), any(), any());

        when(mockRuntime.connection(playerId)).thenReturn(2L);
        pending.complete(new TeamOutcome(TeamOutcome.Status.SUCCESS,
                new TeamState(projectId, owner, List.of(), false), 1, 1, java.util.Set.of(), List.of()));
        drainServerTasks();
        assertTrue(packets.stream().noneMatch(TeamResponseS2CPacket.class::isInstance),
                "连接代次已变化，旧回调不得发送");

        CompletableFuture<TeamOutcome> unhandshakePending = new CompletableFuture<>();
        when(mockRuntime.connection(playerId)).thenReturn(3L);
        when(mockTeams.execute(any(), any(), any())).thenReturn(unhandshakePending);
        FoundationTeamNetworkAdapter.handle(new TeamCommand(projectId, 92, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of()), player, server, true);
        drainServerTasks();
        verify(mockTeams, times(2)).execute(any(), any(), any());
        ProtocolHandshake.remove(player);
        unhandshakePending.complete(new TeamOutcome(TeamOutcome.Status.SUCCESS,
                new TeamState(projectId, owner, List.of(), false), 1, 1, java.util.Set.of(), List.of()));
        drainServerTasks();
        assertTrue(packets.stream().noneMatch(TeamResponseS2CPacket.class::isInstance),
                "未握手的旧回调不得发送");
    }

    @Test
    void committedStorageExceptionIsReportedAsSuccessWithoutRetrying() throws Exception {
        ServerRuntime mockRuntime = mock(ServerRuntime.class);
        TeamManagementService mockTeams = mock(TeamManagementService.class);
        registry = mockStatic(ServerRuntimeRegistry.class);
        registry.when(() -> ServerRuntimeRegistry.get(server)).thenReturn(mockRuntime);
        TeamRequestTracker tracker = new TeamRequestTracker();
        when(mockRuntime.isOpen()).thenReturn(true);
        when(mockRuntime.connection(playerId)).thenReturn(1L);
        when(mockRuntime.teams()).thenReturn(mockTeams);
        when(mockRuntime.teamRequests()).thenReturn(tracker);
        when(mockRuntime.catalogs()).thenReturn(new MaterialCatalogTracker());

        TeamCommand command = new TeamCommand(projectId, 77, TeamAction.TOGGLE_SELF_CLAIM,
                -1, List.of(), List.of());
        when(mockTeams.resolveTargets(any(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(command));
        TeamState durableState = new TeamState(projectId, owner, List.of(), false);
        TeamOutcome durable = new TeamOutcome(TeamOutcome.Status.SUCCESS, durableState, 1, 1,
                java.util.Set.of(), List.of());
        when(mockTeams.execute(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(
                new net.syncmaterial.syncmaterial.server.storage.CommittedStorageException(
                        "teamCommand", durable, new IllegalStateException("autoCommit restore failed"))));

        dispatch(new TeamCommandC2SPacket(command));
        assertTrue(await(() -> awaitResponse(77) != null), "已提交故障也必须回送结果");
        TeamResponseS2CPacket response = awaitResponse(77);
        assertTrue(response.response().success(), "已提交故障必须报告成功");
        assertTrue(response.response().message().contains("操作已提交"),
                "已提交故障必须提示不要重复操作: " + response.response().message());
        assertEquals(1, response.response().processedCount());
        assertEquals(1, response.response().changedCount());
        assertEquals(owner.uuid(), response.response().team().owner().uuid(),
                "已提交故障必须携带持久化的权威状态");
        verify(mockTeams, times(1)).execute(any(), any(), any());
    }

    @Test
    void disconnectAfterAdmissionStillQueuesTheRealCommitBroadcast() throws Exception {
        runtime.connected(owner.uuid());
        CompletableFuture<TeamOutcome> pending = runtime.teams().execute(
                new TeamCommand(projectId, 1, TeamAction.TOGGLE_SELF_CLAIM, -1, List.of(), List.of()),
                owner, () -> true);
        runtime.disconnected(owner.uuid());
        TeamOutcome outcome = pending.get(5, TimeUnit.SECONDS);
        assertTrue(outcome.success());
        assertTrue(runtime.takeTeamChanges().contains(projectId),
                "已提交的角色变更必须与发起连接存活状态解耦");
    }

    // ==================================================================
    // Dispatch and polling fixtures
    // ==================================================================

    @SuppressWarnings("unchecked")
    private <T extends CustomPacketPayload> void dispatch(T packet) {
        ServerPlayNetworking.PlayPayloadHandler<T> handler =
                (ServerPlayNetworking.PlayPayloadHandler<T>) handlers.get(packet.type());
        assertNotNull(handler, "未捕获到 receiver: " + packet.type().id());
        handler.receive(packet, context);
    }

    private void dispatchOwnerAction(String action, String targetName) {
        dispatch(new OwnerActionC2SPacket(projectId, action, targetName));
    }

    private TeamResponseS2CPacket awaitResponse(long requestId) {
        synchronized (packets) {
            return packets.stream()
                    .filter(TeamResponseS2CPacket.class::isInstance)
                    .map(TeamResponseS2CPacket.class::cast)
                    .filter(packet -> packet.response().requestId() == requestId)
                    .findFirst()
                    .orElse(null);
        }
    }

    private <T extends CustomPacketPayload> T legacyResponse(Class<T> type) {
        synchronized (packets) {
            return packets.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
        }
    }

    private <T extends CustomPacketPayload> T awaitLegacyResponse(Class<T> type) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            drainServerTasks();
            T response = legacyResponse(type);
            if (response != null) return response;
            Thread.sleep(10);
        }
        fail("等待旧版响应超时: " + type.getSimpleName());
        return null;
    }

    private boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            drainServerTasks();
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private void drainServerTasks() {
        Runnable task;
        while ((task = serverTasks.poll()) != null) {
            task.run();
        }
    }
}
