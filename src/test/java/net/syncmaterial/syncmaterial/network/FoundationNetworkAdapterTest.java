package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.server.runtime.MaterialCatalogTracker;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoundationNetworkAdapterTest {
    private static final String PROJECT = "foundation-network";
    private final UUID playerId = UUID.randomUUID();
    private final List<CustomPacketPayload> packets = new ArrayList<>();
    private final Map<CustomPacketPayload.Type<?>, ServerPlayNetworking.PlayPayloadHandler<?>> handlers = new HashMap<>();
    private MockedStatic<ServerPlayNetworking> networking;
    private MockedStatic<ServerRuntimeRegistry> registry;
    private ServerPlayer player;
    private MinecraftServer server;
    private ServerRuntime runtime;
    private SqliteProjectStore store;
    private ServerPlayNetworking.Context context;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getName()).thenReturn(Component.literal("Player"));
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        store = mock(SqliteProjectStore.class);
        runtime = mock(ServerRuntime.class);
        when(runtime.store()).thenReturn(store);
        when(runtime.isOpen()).thenReturn(true);
        when(runtime.connection(playerId)).thenReturn(1L);
        when(runtime.isMaterialSnapshotCurrent(any(ProjectSnapshot.class))).thenReturn(true);
        when(runtime.catalogs()).thenReturn(new MaterialCatalogTracker());
        when(runtime.subscribers(PROJECT)).thenReturn(Set.of(playerId));
        context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(server);
        when(context.player()).thenReturn(player);
        registry = mockStatic(ServerRuntimeRegistry.class);
        registry.when(() -> ServerRuntimeRegistry.get(server)).thenReturn(runtime);
        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.registerGlobalReceiver(any(), any())).thenAnswer(invocation -> {
            handlers.put(invocation.getArgument(0), invocation.getArgument(1));
            return true;
        });
        networking.when(() -> ServerPlayNetworking.send(eq(player), any())).thenAnswer(invocation -> {
            packets.add(invocation.getArgument(1));
            return null;
        });
        ProtocolHandshake.recordHandshake(playerId, ProtocolVersion.CURRENT, "test");
        FoundationNetworkAdapter.register();
        snapshot("Original", "hash", 1, 64);
    }

    @AfterEach
    void tearDown() {
        networking.close();
        registry.close();
        ProtocolHandshake.remove(playerId);
    }

    @Test
    void protocolOneGetsNoNewPacketAndNoSpuriousFirstUpdateNotice() {
        ProtocolHandshake.recordHandshake(playerId, 1, "old-client");
        requestMaterials();
        assertTrue(packets.stream().anyMatch(MaterialStatsResponseS2CPacket.class::isInstance));
        assertTrue(packets.stream().noneMatch(MaterialCatalogUpdateS2CPacket.class::isInstance));
        clearInvocations(player);
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        verify(player, never()).sendSystemMessage(any(Component.class));
        snapshot("Renamed", "hash", 1, 64);
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("重新打开")));
        assertTrue(packets.stream().noneMatch(MaterialCatalogUpdateS2CPacket.class::isInstance));
    }

    @Test
    void protocolTwoGetsInitialCatalogAndSameHashRenameInPlace() {
        requestMaterials();
        var initial = packets.stream().filter(MaterialCatalogUpdateS2CPacket.class::isInstance)
                .map(MaterialCatalogUpdateS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals("Original", initial.schematicName());
        packets.clear();
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        assertTrue(packets.stream().noneMatch(MaterialCatalogUpdateS2CPacket.class::isInstance));
        snapshot("Renamed", "hash", 1, 64);
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        var update = packets.stream().filter(MaterialCatalogUpdateS2CPacket.class::isInstance)
                .map(MaterialCatalogUpdateS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals("Renamed", update.schematicName());
        assertTrue(update.revision() > initial.revision());
        assertTrue(packets.stream().noneMatch(MaterialStatsResponseS2CPacket.class::isInstance));
    }

    @Test
    void unknownMaterialFailsWholeRequestRatherThanDroppingRequirements() {
        var project = new ProjectSnapshot(PROJECT, "Unknown", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "missing:item", 64)), 1);
        when(store.snapshot(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(new ProjectState(project, List.of()))));
        requestMaterials();
        assertTrue(packets.isEmpty());
        verify(runtime, never()).subscribe(any(), any());
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("请求失败")));
    }

    @Test
    void oldConnectionQueryCannotMarkReconnectedPlayersCatalogAsSent() {
        var pending = new CompletableFuture<Optional<ProjectState>>();
        when(store.snapshot(PROJECT)).thenReturn(pending);
        requestMaterials();
        when(runtime.connection(playerId)).thenReturn(2L);
        var project = new ProjectSnapshot(PROJECT, "Original", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        pending.complete(Optional.of(new ProjectState(project, List.of())));
        assertTrue(packets.isEmpty());
        assertTrue(runtime.catalogs().changed(playerId, 2L, project).isPresent());
        verify(runtime, never()).subscribe(any(), any());
    }

    @Test
    void staleSnapshotIsRejectedBeforeSendingOrSubscribing() {
        when(runtime.isMaterialSnapshotCurrent(any(ProjectSnapshot.class))).thenReturn(false);
        requestMaterials();
        assertTrue(packets.isEmpty());
        verify(runtime, never()).subscribe(any(), any());
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("统计已过期")));
    }

    @Test
    void staleSnapshotCannotPublishCatalogOrCollaborationStatus() {
        requestMaterials();
        packets.clear();
        when(runtime.isMaterialSnapshotCurrent(any(ProjectSnapshot.class))).thenReturn(false);
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        assertTrue(packets.isEmpty());
    }

    @Test
    void staleSnapshotCannotBeClaimedOrReportInitialInventory() {
        var project = new ProjectSnapshot(PROJECT, "Original", "old-hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        when(store.findProject(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(project)));
        when(runtime.isMaterialSnapshotCurrent(project)).thenReturn(false);
        receive(JoinCollaborationC2SPacket.ID, new JoinCollaborationC2SPacket(PROJECT, 1, Map.of(1, 32)));
        verify(store, never()).join(anyString(), anyInt(), any(PlayerIdentity.class));
        verify(runtime, never()).reportInventory(anyString(), anyInt(), any(PlayerIdentity.class), anyLong());
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("统计已过期")));
        assertTrue(packets.isEmpty());
    }

    @Test
    void currentSnapshotCanBeClaimedAndReportsInitialInventory() {
        var project = new ProjectSnapshot(PROJECT, "Original", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        when(store.findProject(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(project)));
        when(store.join(eq(PROJECT), eq(1), any(PlayerIdentity.class))).thenReturn(CompletableFuture.completedFuture(true));
        when(runtime.reportInventory(eq(PROJECT), eq(1), any(PlayerIdentity.class), eq(32L)))
                .thenReturn(CompletableFuture.completedFuture(null));
        receive(JoinCollaborationC2SPacket.ID, new JoinCollaborationC2SPacket(PROJECT, 1, Map.of(1, 32)));
        verify(store).join(eq(PROJECT), eq(1), any(PlayerIdentity.class));
        verify(runtime).reportInventory(eq(PROJECT), eq(1), any(PlayerIdentity.class), eq(32L));
        verify(runtime).requestBroadcast(PROJECT);
    }

    @Test
    void unimplementedAreaListDoesNotReportSuccess() {
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "LIST", -1, Optional.empty()));
        var response = (StagingAreaConfigResponseS2CPacket) packets.get(0);
        assertFalse(response.success());
        assertTrue(response.message().contains("尚未接入"));
    }

    @Test
    void deletingProjectClearsSubscriptionAndCatalogBaseline() {
        requestMaterials();
        when(store.snapshot(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof StagingAreaConfigResponseS2CPacket response
                && response.action().equals("SCHEMATIC_DELETED")));
        verify(runtime).unsubscribe(playerId, PROJECT);
    }

    private void requestMaterials() {
        receive(MaterialStatsRequestC2SPacket.ID, new MaterialStatsRequestC2SPacket(PROJECT));
    }

    @SuppressWarnings("unchecked")
    private <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, T packet) {
        ((ServerPlayNetworking.PlayPayloadHandler<T>) handlers.get(type)).receive(packet, context);
    }

    private void snapshot(String name, String hash, long revision, long count) {
        var project = new ProjectSnapshot(PROJECT, name, hash, owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", count)), revision);
        when(store.snapshot(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(new ProjectState(project, List.of()))));
    }

    private PlayerIdentity owner() {
        return new PlayerIdentity(playerId, "Player");
    }
}
