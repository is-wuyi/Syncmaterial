package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import net.syncmaterial.syncmaterial.client.ProjectViewAssembler;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.server.runtime.CollaborationService;
import net.syncmaterial.syncmaterial.server.runtime.OnlineInventorySnapshot;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry;
import net.syncmaterial.syncmaterial.server.runtime.ProjectProgressRead;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;
import net.syncmaterial.syncmaterial.server.runtime.TeamManagementService;
import net.syncmaterial.syncmaterial.server.runtime.TeamRequestTracker;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoundationProjectViewNetworkAdapterTest {
    private static final String PROJECT = "project";
    private static final ProjectViewScope SCOPE = ProjectViewScope.project(PROJECT);
    private static final ProjectViewScope GLOBAL = ProjectViewScope.globalWarehouse();

    private final UUID playerId = UUID.randomUUID();
    private final List<CustomPacketPayload> packets = new ArrayList<>();
    private final List<ServerPlayer> recipients = new ArrayList<>();
    private final Map<CustomPacketPayload.Type<?>, ServerPlayNetworking.PlayPayloadHandler<?>> handlers = new HashMap<>();
    private MockedStatic<ServerPlayNetworking> networking;
    private MockedStatic<ServerRuntimeRegistry> registry;
    private ServerPlayer player;
    private MinecraftServer server;
    private ServerRuntime runtime;
    private SqliteProjectStore store;
    private CollaborationService collaboration;
    private ProjectViewTracker views;
    private ProjectInterestRegistry interests;
    private ServerPlayNetworking.Context context;
    private ProjectProgressRead read;
    private ProjectProgressSnapshot snapshot;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        player = player(playerId, "Player");
        server = mock(MinecraftServer.class);
        when(player.level().getServer()).thenReturn(server);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        when(players.getPlayers()).thenReturn(new ArrayList<>(List.of(player)));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        runtime = mock(ServerRuntime.class);
        store = mock(SqliteProjectStore.class);
        collaboration = mock(CollaborationService.class);
        interests = new ProjectInterestRegistry();
        views = new ProjectViewTracker(interests);
        when(runtime.store()).thenReturn(store);
        when(runtime.collaboration()).thenReturn(collaboration);
        when(runtime.interests()).thenReturn(interests);
        when(runtime.views()).thenReturn(views);
        when(runtime.catalogs()).thenReturn(new net.syncmaterial.syncmaterial.server.runtime.MaterialCatalogTracker());
        when(runtime.isOpen()).thenReturn(true);
        when(runtime.connection(playerId)).thenReturn(1L);
        when(runtime.nextPublicationSequence()).thenReturn(11L);
        when(runtime.teamRequests()).thenReturn(new TeamRequestTracker());
        TeamManagementService teams = mock(TeamManagementService.class);
        when(runtime.teams()).thenReturn(teams);
        when(teams.state(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                new TeamState(PROJECT, new PlayerIdentity(playerId, "Player"), List.of(), true))));
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(
                new AreaCatalog(List.of(), Map.of(), Map.of(PROJECT, "Project"), 1)));

        var project = new ProjectSnapshot(PROJECT, "Project", "hash",
                new PlayerIdentity(playerId, "Player"), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 3);
        var state = new ProjectState(project, List.of(), new AreaInventoryView(
                Map.of(), Map.of(), List.of(), true, false, 4));
        read = new ProjectProgressRead(state,
                new OnlineInventorySnapshot(PROJECT, 0, Map.of(), Map.of()), 5);
        snapshot = new ProjectProgressSnapshot(PROJECT, "Project", 3, 4, 5,
                true, true, false, List.of(), List.of(new ProjectProgressMaterial(1,
                "minecraft:stone", 64, 16, 8,
                List.of(new ProgressParticipant(new PlayerIdentity(playerId, "Player"), 12)))));
        when(runtime.readProgress(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(read)));
        when(runtime.progressOnServer(read)).thenReturn(Optional.of(snapshot));

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
        networking.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any())).thenAnswer(invocation -> {
            recipients.add(invocation.getArgument(0));
            packets.add(invocation.getArgument(1));
            return null;
        });
        ProtocolHandshake.registerServer(server);
        assertTrue(ProtocolHandshake.recordHandshake(server, player, ProtocolVersion.CURRENT, "test"));
        FoundationProjectViewNetworkAdapter.register();
    }

    @AfterEach
    void tearDown() {
        networking.close();
        registry.close();
        ProtocolHandshake.clear(server);
        views.close();
        interests.close();
    }

    @Test
    void openReturnsCorrelatedAckAndCompleteAtomicProgress() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));

        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.OPENED && result.requestId() == 99));
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        Object connection = new Object();
        assertTrue(assembler.openView(connection, 1, SCOPE, 7, 3));
        List<ProjectViewS2CPacket> fragments = packets.stream()
                .filter(ProjectViewS2CPacket.class::isInstance).map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.PROGRESS_PART).toList();
        assertFalse(fragments.isEmpty());
        ProjectViewAssembler.Outcome outcome = null;
        for (var fragment : fragments) {
            assertEquals(0, fragment.requestId());
            outcome = assembler.accept(connection, 1, fragment);
        }
        assertInstanceOf(ProjectViewAssembler.Complete.class, outcome);
        assertEquals(snapshot, ((ProjectViewAssembler.Complete) outcome).view().snapshot());
        MaterialCatalogUpdateS2CPacket token = packets.stream()
                .filter(MaterialCatalogUpdateS2CPacket.class::isInstance)
                .map(MaterialCatalogUpdateS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals(runtime.catalogs().currentRevision(playerId, PROJECT, 1).orElseThrow(), token.revision());
        assertEquals(3, snapshot.catalogRevision(), "persisted revision remains separate from management token");
    }

    @Test
    void protocolFourCannotOpenAProjectView() {
        ProtocolHandshake.recordHandshake(server, player, 4, "old");
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        assertTrue(packets.isEmpty());
        assertEquals(0, views.viewCount());
    }

    @Test
    void joinUsesAtomicServiceAndReturnsItsRealResult() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        when(collaboration.join(eq(PROJECT), eq(1), any(PlayerIdentity.class), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(new CollaborationOutcome(
                        CollaborationOutcome.Action.JOIN, CollaborationOutcome.Status.APPLIED, PROJECT, 1)));

        receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 7, 3, ProjectViewAction.JOIN,
                null, 1, 0, 0, Optional.empty(),
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, 32L))));

        verify(collaboration).join(eq(PROJECT), eq(1), any(PlayerIdentity.class), eq(Map.of(1, 32L)));
        verify(runtime, never()).reportInventory(anyString(), anyInt(), any(PlayerIdentity.class), anyLong());
        ProjectViewS2CPacket result = packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.OPERATION)
                .findFirst().orElseThrow();
        assertEquals(100, result.requestId());
        assertEquals(ProjectViewAction.JOIN, result.action());
        assertTrue(result.success());
        assertTrue(result.changed());
    }

    @Test
    void leaveReturnsItsRealResultWithoutReportingInventory() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        when(collaboration.leave(eq(PROJECT), eq(1), any(PlayerIdentity.class)))
                .thenReturn(CompletableFuture.completedFuture(new CollaborationOutcome(
                        CollaborationOutcome.Action.LEAVE, CollaborationOutcome.Status.APPLIED, PROJECT, 1)));

        receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 7, 3, ProjectViewAction.LEAVE,
                null, 1, 0, 0, Optional.empty(), List.of()));

        verify(collaboration).leave(eq(PROJECT), eq(1), any(PlayerIdentity.class));
        verify(runtime, never()).reportInventory(anyString(), anyInt(), any(PlayerIdentity.class), anyLong());
        ProjectViewS2CPacket result = packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.OPERATION)
                .findFirst().orElseThrow();
        assertEquals(100, result.requestId());
        assertEquals(ProjectViewAction.LEAVE, result.action());
        assertTrue(result.success());
        assertTrue(result.changed());
    }

    @Test
    void duplicateLeaveSucceedsWithoutClaimingAnotherChange() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        when(collaboration.leave(eq(PROJECT), eq(1), any(PlayerIdentity.class)))
                .thenReturn(CompletableFuture.completedFuture(new CollaborationOutcome(
                        CollaborationOutcome.Action.LEAVE, CollaborationOutcome.Status.UNCHANGED, PROJECT, 1)));

        receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 7, 3, ProjectViewAction.LEAVE,
                null, 1, 0, 0, Optional.empty(), List.of()));

        ProjectViewS2CPacket result = packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.OPERATION)
                .findFirst().orElseThrow();
        assertTrue(result.success());
        assertFalse(result.changed());
    }

    @Test
    void oldGenerationLeaveCannotWriteOrReceiveAnAck() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 7, 2, ProjectViewAction.LEAVE,
                null, 1, 0, 0, Optional.empty(), List.of()));
        verify(collaboration, never()).leave(anyString(), anyInt(), any());
        assertTrue(packets.isEmpty());
    }

    @Test
    void oldGenerationJoinCannotWriteOrReceiveAnAck() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 7, 2, ProjectViewAction.JOIN,
                null, 1, 0, 0, Optional.empty(),
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, 32L))));
        verify(collaboration, never()).join(anyString(), anyInt(), any(), anyMap());
        assertTrue(packets.isEmpty());
    }

    @Test
    void emptyWarehouseCompletesAndReadFailureDoesNotFakeAnEmptySuccess() {
        when(store.warehouseContainers(PROJECT, "minecraft:overworld"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        ProjectViewS2CPacket complete = packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.WAREHOUSE)
                .findFirst().orElseThrow();
        assertEquals("COMPLETE", complete.message());
        assertTrue(complete.warehouse().isEmpty());
        assertEquals(11, complete.sequence());

        packets.clear();
        when(store.warehouseContainers(PROJECT, "minecraft:overworld"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read failed")));
        receive(command(SCOPE, 100, 7, 4, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.ERROR));
        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.WAREHOUSE));
    }

    @Test
    void staleSampleIsInvalidatedButMissingProjectIsDeleted() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        packets.clear();
        when(runtime.progressOnServer(read)).thenReturn(Optional.empty());
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.INVALIDATED));
        assertTrue(views.matches(playerId, 1, SCOPE, 7, 3));

        packets.clear();
        when(runtime.readProgress(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(0, views.viewCount());
    }

    @Test
    void globalWarehouseViewReceivesTypedConfiguration() {
        var warehouse = new AreaDefinition(4, null, AreaKind.WAREHOUSE, "Warehouse",
                "minecraft:overworld", new AreaBounds(0, 0, 0, 15, 15, 15), 1, 0);
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(
                new AreaCatalog(List.of(warehouse), Map.of(), Map.of(), 9)));

        receive(command(GLOBAL, 99, 8, 2, ProjectViewAction.OPEN, InterestKind.AREA_CONFIG.bit()));

        ProjectViewS2CPacket config = packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.CONFIG)
                .findFirst().orElseThrow();
        assertEquals(GLOBAL, config.scope());
        assertEquals(9, config.sequence());
        assertEquals("LIST_WAREHOUSES", config.configuration().orElseThrow().action());
        assertEquals(List.of(warehouse), config.configuration().orElseThrow().areas());
        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.PROGRESS_PART));
    }

    @Test
    void oneBroadcastReadsOnceAndFansOutTheSameSnapshot() {
        UUID observerId = UUID.randomUUID();
        ServerPlayer observer = player(observerId, "Observer");
        when(runtime.connection(observerId)).thenReturn(2L);
        when(server.getPlayerList().getPlayer(observerId)).thenReturn(observer);
        when(server.getPlayerList().getPlayers()).thenReturn(new ArrayList<>(List.of(player, observer)));
        assertTrue(ProtocolHandshake.recordHandshake(server, observer, ProtocolVersion.CURRENT, "test"));
        try {
            receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
            receive(new ProjectViewCommandC2SPacket(SCOPE, 100, 8, 4, ProjectViewAction.OPEN,
                    null, 0, 0, InterestKind.MATERIALS.bit(), Optional.empty(), List.of()), observer);
            packets.clear();
            recipients.clear();
            clearInvocations(runtime);

            FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

            verify(runtime, times(1)).readProgress(PROJECT);
            assertTrue(recipients.contains(player));
            assertTrue(recipients.contains(observer));
            assertEquals(1, packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                    .map(ProjectViewS2CPacket.class::cast)
                    .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.PROGRESS_PART)
                    .map(ProjectViewS2CPacket::snapshotId).distinct().count());
        } finally {
            ProtocolHandshake.remove(observer);
        }
    }

    private ServerPlayer player(UUID id, String name) {
        ServerPlayer result = mock(ServerPlayer.class);
        result.connection = mock(ServerGamePacketListenerImpl.class);
        when(result.getUUID()).thenReturn(id);
        when(result.getName()).thenReturn(Component.literal(name));
        ServerLevel world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        if (server != null) when(world.getServer()).thenReturn(server);
        when(result.level()).thenReturn(world);
        return result;
    }

    private ProjectViewCommandC2SPacket command(ProjectViewScope scope, long requestId,
            long viewId, long generation, ProjectViewAction action, int mask) {
        return new ProjectViewCommandC2SPacket(scope, requestId, viewId, generation,
                action, null, 0, 0, mask, Optional.empty(), List.of());
    }

    private void receive(ProjectViewCommandC2SPacket packet) {
        receive(packet, player);
    }

    @SuppressWarnings("unchecked")
    private void receive(ProjectViewCommandC2SPacket packet, ServerPlayer sender) {
        when(context.player()).thenReturn(sender);
        ((ServerPlayNetworking.PlayPayloadHandler<ProjectViewCommandC2SPacket>)
                handlers.get(ProjectViewCommandC2SPacket.ID)).receive(packet, context);
    }
}
