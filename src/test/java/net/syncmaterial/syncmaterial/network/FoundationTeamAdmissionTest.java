package net.syncmaterial.syncmaterial.network;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Admission, authority and publication regression using real shared team transactions. */
class FoundationTeamAdmissionTest {
    @TempDir Path temporary;
    private static final String PROJECT = "team-admission";
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final List<CustomPacketPayload> sent = new java.util.ArrayList<>();
    private MockedStatic<ServerPlayNetworking> networking;
    private ServerRuntime runtime;
    private MinecraftServer server;
    private ServerPlayer actor;
    private ServerPlayer observer;
    private PlayerIdentity owner;
    private PlayerIdentity target;
    private int material;
    private long revision;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        actor = player(server, "Owner");
        observer = player(server, "Observer");
        when(players.getPlayers()).thenReturn(List.of(actor, observer));
        when(players.getPlayer(actor.getUUID())).thenReturn(actor);
        when(players.getPlayer(observer.getUUID())).thenReturn(observer);
        when(server.getFile("usercache.json")).thenReturn(temporary.resolve("usercache.json"));
        doAnswer(invocation -> { tasks.add(invocation.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
        runtime = ServerRuntime.open(temporary.resolve("world"), temporary.resolve("game"),
                path -> List.of(new MaterialCount("minecraft:stone", 64)), ignored -> {});
        ServerRuntimeRegistry.install(server, runtime);
        owner = new PlayerIdentity(actor.getUUID(), "Owner");
        target = new PlayerIdentity(UUID.randomUUID(), "Target");
        runtime.store().ingest(new SchematicMetadata(PROJECT, PROJECT, Path.of("fixture.litematic"), "hash", owner),
                List.of(new MaterialCount("minecraft:stone", 64)), runtime.interpretationId()).get();
        runtime.store().reconcilePlayers(List.of(target)).get();
        material = runtime.store().findProject(PROJECT).get().orElseThrow().materials().getFirst().id();
        runtime.connected(actor.getUUID());
        runtime.connected(observer.getUUID());
        runtime.subscribe(actor.getUUID(), PROJECT);
        runtime.subscribe(observer.getUUID(), PROJECT);
        revision = runtime.catalogs().sent(actor.getUUID(), runtime.connection(actor.getUUID()),
                runtime.store().findProject(PROJECT).get().orElseThrow());
        ProtocolHandshake.registerServer(server);
        assertTrue(ProtocolHandshake.recordHandshake(server, actor, 4, "test"));
        assertTrue(ProtocolHandshake.recordHandshake(server, observer, 4, "test"));
        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.send(any(), any())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(1)); return null;
        });
    }

    @AfterEach
    void close() {
        if (networking != null) networking.close();
        if (server != null) {
            ProtocolHandshake.clear(server);
            ServerRuntimeRegistry.close(server);
        }
        if (runtime != null) runtime.close();
    }

    @Test
    void lostActorAndMissingHandshakeFailBeforeStoreSubmission() throws Exception {
        runtime.disconnected(actor.getUUID());
        FoundationTeamNetworkAdapter.handle(assign(1, revision), actor, server, true);
        drain();
        assertTrue(runtime.store().claims(PROJECT).get().isEmpty());
        assertTrue(sent.isEmpty());
        runtime.connected(actor.getUUID());
        ProtocolHandshake.remove(actor);
        FoundationTeamNetworkAdapter.handle(toggle(2), actor, server, true);
        drain();
        assertTrue(runtime.store().teamState(PROJECT).get().orElseThrow().allowSelfClaim());
        assertTrue(sent.isEmpty());
    }

    @Test
    void missingAndWrongCatalogReturnKnownFailureWithoutWriting() throws Exception {
        FoundationTeamNetworkAdapter.handle(assign(1, -1), actor, server, true);
        awaitResponse(1);
        var first = response(1);
        assertFalse(first.success());
        assertTrue(first.message().contains("过期"));
        assertNotNull(first.team());
        assertTrue(runtime.store().claims(PROJECT).get().isEmpty());
        FoundationTeamNetworkAdapter.handle(assign(2, revision + 1), actor, server, true);
        awaitResponse(2);
        assertFalse(response(2).success());
        assertTrue(runtime.store().claims(PROJECT).get().isEmpty());
    }

    @Test
    void ambiguousNameNeverAssignsArbitraryUuid() throws Exception {
        var duplicate = new PlayerIdentity(UUID.randomUUID(), target.name());
        runtime.store().reconcilePlayers(List.of(duplicate)).get();
        var request = new TeamCommand(PROJECT, 1, TeamAction.ASSIGN, revision, List.of(material),
                List.of(new PlayerIdentity(null, target.name())));
        FoundationTeamNetworkAdapter.handle(request, actor, server, true);
        awaitResponse(1);
        assertFalse(response(1).success());
        assertTrue(response(1).message().contains("歧义"));
        assertNotNull(response(1).team());
        assertTrue(runtime.store().claims(PROJECT).get().isEmpty());
    }

    @Test
    void scopedDirectoryIncludesOfflineUuidAndTeamQueryIsCorrelated() throws Exception {
        FoundationTeamNetworkAdapter.handle(new TeamCommand(PROJECT, 1, TeamAction.PLAYERS, -1, List.of(), List.of()),
                actor, server, true);
        awaitResponse(1);
        assertTrue(response(1).success());
        assertTrue(response(1).players().stream().anyMatch(entry -> target.uuid().equals(entry.player().uuid()) && !entry.online()));
        FoundationTeamNetworkAdapter.handle(new TeamCommand(PROJECT, 2, TeamAction.TEAM_STATE, -1, List.of(), List.of()),
                actor, server, true);
        awaitResponse(2);
        assertTrue(response(2).team().isMainOwner(owner));
        assertFalse(response(2).team().allowNameAuthority());
    }

    @Test
    void committedRoleBroadcastIsIndependentOfInitiatorAndCatalog() throws Exception {
        var before = runtime.store().findProject(PROJECT).get().orElseThrow();
        assertTrue(runtime.teams().execute(toggle(1), owner, () -> true).get().success());
        runtime.disconnected(actor.getUUID());
        when(server.getPlayerList().getPlayer(actor.getUUID())).thenReturn(null);
        ProtocolHandshake.remove(actor);
        FoundationTeamNetworkAdapter.tick(server, runtime);
        await(() -> sent.stream().anyMatch(packet -> packet instanceof TeamResponseS2CPacket response
                && response.response().requestId() == 0 && !response.response().team().allowSelfClaim()));
        networking.verify(() -> ServerPlayNetworking.send(eq(observer), argThat(packet -> packet instanceof TeamResponseS2CPacket)), times(1));
        networking.verify(() -> ServerPlayNetworking.send(eq(actor), any()), never());
        var after = runtime.store().findProject(PROJECT).get().orElseThrow();
        assertEquals(before.revision(), after.revision());
        assertEquals(before.materials(), after.materials());
        assertTrue(sent.stream().noneMatch(MaterialStatsResponseS2CPacket.class::isInstance));
    }

    @Test
    void oldSubscriberGetsNoticeInsteadOfAnonymousRolePacket() throws Exception {
        ProtocolHandshake.recordHandshake(server, observer, 3, "old");
        runtime.teams().execute(toggle(1), owner, () -> true).get();
        FoundationTeamNetworkAdapter.tick(server, runtime);
        await(() -> tasks.isEmpty() && !sent.isEmpty());
        verify(observer).sendSystemMessage(argThat(message -> message.getString().contains("重新打开")));
        networking.verify(() -> ServerPlayNetworking.send(eq(observer), any()), never());
        assertTrue(sent.stream().noneMatch(OwnerActionResponseS2CPacket.class::isInstance));
    }

    private TeamCommand assign(long requestId, long catalog) {
        return new TeamCommand(PROJECT, requestId, TeamAction.ASSIGN, catalog, List.of(material), List.of(target));
    }

    private static TeamCommand toggle(long requestId) {
        return new TeamCommand(PROJECT, requestId, TeamAction.TOGGLE_SELF_CLAIM, -1, List.of(), List.of());
    }

    private net.syncmaterial.syncmaterial.core.TeamResponse response(long id) {
        return sent.stream().filter(TeamResponseS2CPacket.class::isInstance).map(TeamResponseS2CPacket.class::cast)
                .map(TeamResponseS2CPacket::response).filter(reply -> reply.requestId() == id).findFirst().orElse(null);
    }

    private void awaitResponse(long id) throws Exception {
        await(() -> response(id) != null);
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            drain();
            if (condition.getAsBoolean()) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        fail("Timed out waiting for real team response");
    }

    private void drain() {
        Runnable next;
        while ((next = tasks.poll()) != null) next.run();
    }

    private static ServerPlayer player(MinecraftServer server, String name) {
        var player = mock(ServerPlayer.class);
        var world = mock(ServerLevel.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(Component.literal(name));
        when(player.level()).thenReturn(world);
        when(world.getServer()).thenReturn(server);
        return player;
    }
}
