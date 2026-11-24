package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void warehouseBroadcastSkipsSameRevisionAndPublishesNextRevision() {
        when(store.warehouseContainers(PROJECT, "minecraft:overworld"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        clearInvocations(store);
        packets.clear();

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        verify(store, never()).warehouseContainers(anyString(), anyString());

        ProjectProgressRead next = progressReadAtRevision(5);
        when(runtime.readProgress(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(next)));
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        verify(store).warehouseContainers(PROJECT, "minecraft:overworld");
        assertEquals(1, packets.stream().filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.WAREHOUSE
                        && "COMPLETE".equals(packet.message())).count());

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        verify(store).warehouseContainers(PROJECT, "minecraft:overworld");
    }

    @SuppressWarnings("unchecked")
    @Test
    void warehouseReadFailureRetriesTheSameRevision() {
        when(store.warehouseContainers(PROJECT, "minecraft:overworld"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read failed")),
                        CompletableFuture.completedFuture(List.of()));
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        packets.clear();

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

        verify(store, times(2)).warehouseContainers(PROJECT, "minecraft:overworld");
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.WAREHOUSE
                && "COMPLETE".equals(result.message())));
    }

    @SuppressWarnings("unchecked")
    @Test
    void olderWarehouseCompletionCannotOverwriteNewerRevision() {
        CompletableFuture<List<net.syncmaterial.syncmaterial.core.ContainerObservation>> first =
                new CompletableFuture<>();
        CompletableFuture<List<net.syncmaterial.syncmaterial.core.ContainerObservation>> second =
                new CompletableFuture<>();
        when(store.warehouseContainers(PROJECT, "minecraft:overworld")).thenReturn(first, second);
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        ProjectProgressRead next = progressReadAtRevision(5);
        when(runtime.readProgress(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(next)));

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        second.complete(List.of());
        packets.clear();
        first.complete(List.of());

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.WAREHOUSE));
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        verify(store, times(2)).warehouseContainers(PROJECT, "minecraft:overworld");
    }

    @SuppressWarnings("unchecked")
    @Test
    void oldConnectionWarehouseCompletionCannotSendAfterReconnect() {
        CompletableFuture<List<net.syncmaterial.syncmaterial.core.ContainerObservation>> first =
                new CompletableFuture<>();
        CompletableFuture<List<net.syncmaterial.syncmaterial.core.ContainerObservation>> second =
                new CompletableFuture<>();
        when(store.warehouseContainers(PROJECT, "minecraft:overworld")).thenReturn(first, second);
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        when(runtime.connection(playerId)).thenReturn(2L);
        receive(command(SCOPE, 100, 8, 4, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        second.complete(List.of());
        packets.clear();

        first.complete(List.of());

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.WAREHOUSE));
    }

    @Test
    void deletedProjectDoesNotCompletePendingWarehouseRead() {
        CompletableFuture<List<net.syncmaterial.syncmaterial.core.ContainerObservation>> pending =
                new CompletableFuture<>();
        when(store.warehouseContainers(PROJECT, "minecraft:overworld")).thenReturn(pending);
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN,
                InterestKind.WAREHOUSE_CONTENTS.bit()));
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        assertEquals(0, views.viewCount());
        packets.clear();

        pending.complete(List.of());

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
    void missingProjectDoesNotCreateLegacySubscription() {
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        for (InterestKind kind : List.of(InterestKind.MATERIALS, InterestKind.TEAM, InterestKind.OUTLINES,
                InterestKind.WAREHOUSE_CONTENTS, InterestKind.AREA_CONFIG)) {
            receive(command(SCOPE, 99 + kind.ordinal(), 7 + kind.ordinal(), 3 + kind.ordinal(),
                    ProjectViewAction.OPEN, kind.bit()));
        }

        verify(runtime, never()).subscribe(any(), any());
        verify(store, never()).warehouseContainers(anyString(), anyString());
        assertEquals(0, views.viewCount());
    }

    @Test
    void presentProjectReadPrecedesLegacySubscription() {
        clearInvocations(runtime);
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));

        var ordered = inOrder(runtime);
        ordered.verify(runtime).readProgress(PROJECT);
        ordered.verify(runtime).subscribe(playerId, PROJECT);
    }

    @Test
    void missingSetDoesNotCreateLegacySubscription() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.AREA_CONFIG.bit()));
        clearInvocations(runtime);
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));

        receive(command(SCOPE, 100, 7, 3, ProjectViewAction.SET_INTERESTS, InterestKind.TEAM.bit()));

        verify(runtime, never()).subscribe(any(), any());
        verify(runtime, never()).unsubscribe(any(), any());
    }

    @Test
    void missingSetDoesNotClearPreexistingLegacySubscription() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        clearInvocations(runtime);
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));

        receive(command(SCOPE, 100, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.AREA_CONFIG.bit()));

        verify(runtime, never()).subscribe(any(), any());
        verify(runtime, never()).unsubscribe(any(), any());
    }

    @Test
    void areaConfigOnlyOpenDoesNotSubscribeAndOnlyIdempotentlyUnsubscribes() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.AREA_CONFIG.bit()));

        verify(runtime, never()).subscribe(any(), any());
        verify(runtime).unsubscribe(playerId, PROJECT);
    }

    @Test
    void materialTeamAndOutlineInterestsAloneKeepTheLegacySubscription() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        verify(runtime, never()).unsubscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 100, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.TEAM.bit() | InterestKind.AREA_CONFIG.bit()));
        verify(runtime, never()).unsubscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 101, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.AREA_CONFIG.bit()));
        verify(runtime).unsubscribe(playerId, PROJECT);
        verify(runtime, never()).subscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 102, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.TEAM.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        verify(runtime, never()).unsubscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 103, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.AREA_CONFIG.bit()));
        verify(runtime).unsubscribe(playerId, PROJECT);
        verify(runtime, never()).subscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 104, 7, 3, ProjectViewAction.SET_INTERESTS,
                InterestKind.OUTLINES.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        verify(runtime, never()).unsubscribe(any(), any());

        clearInvocations(runtime);
        receive(command(SCOPE, 105, 7, 3, ProjectViewAction.CLOSE, 0));
        verify(runtime).unsubscribe(playerId, PROJECT);
        verify(runtime, never()).subscribe(any(), any());
    }

    @Test
    void globalScopeNeverChangesTheProjectLegacySubscription() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        clearInvocations(runtime);

        receive(command(GLOBAL, 100, 8, 4, ProjectViewAction.OPEN,
                InterestKind.AREA_CONFIG.bit() | InterestKind.OUTLINES.bit()));
        receive(command(GLOBAL, 101, 8, 4, ProjectViewAction.SET_INTERESTS,
                InterestKind.AREA_CONFIG.bit()));
        receive(command(GLOBAL, 102, 8, 4, ProjectViewAction.CLOSE, 0));

        verify(runtime, never()).subscribe(any(), any());
        verify(runtime, never()).unsubscribe(any(), any());
    }

    @Test
    void staleConnectionCannotChangeTheCurrentConnectionsLegacySubscription() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.MATERIALS.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        assertEquals(InterestKind.MATERIALS.bit(), views.mask(playerId, 1L, SCOPE, 7, 3));

        when(runtime.connection(playerId)).thenReturn(2L);
        clearInvocations(runtime);
        packets.clear();
        receive(command(SCOPE, 100, 7, 3, ProjectViewAction.SET_INTERESTS, InterestKind.TEAM.bit()));
        receive(command(SCOPE, 101, 7, 3, ProjectViewAction.CLOSE, 0));

        verify(runtime, never()).subscribe(any(), any());
        verify(runtime, never()).unsubscribe(any(), any());
        assertEquals(InterestKind.MATERIALS.bit(), views.mask(playerId, 1L, SCOPE, 7, 3),
                "a stale connection must not edit the view that still belongs to the old generation");

        receive(command(SCOPE, 102, 8, 4, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        verify(runtime).subscribe(playerId, PROJECT);
        assertTrue(views.matches(playerId, 2L, SCOPE, 8, 4));
        assertTrue(views.views(SCOPE, InterestKind.MATERIALS).isEmpty());
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

    @Test
    void deletingProjectNotifiesAndClosesTeamOnlyView() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        assertEquals(1, views.viewCount());
        assertTrue(interests.interests(playerId).stream()
                .anyMatch(interest -> interest.scope().equals(
                        ProjectInterestRegistry.Scope.project(PROJECT))));

        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        clearInvocations(runtime);
        packets.clear();
        recipients.clear();
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

        List<ProjectViewS2CPacket> deleted = packets.stream()
                .filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.DELETED)
                .toList();
        assertEquals(1, deleted.size());
        assertEquals(SCOPE, deleted.getFirst().scope());
        assertEquals(7L, deleted.getFirst().viewId());
        assertEquals(3L, deleted.getFirst().generation());
        assertEquals(0, views.viewCount());
        assertEquals(0, interests.projectCount());
        assertEquals(0, interests.interests(playerId).size());
        verify(runtime, never()).unsubscribe(any(), any());
    }

    @Test
    void deletingProjectNotifiesAndClosesConfigurationOutlineAndWarehouseViews() {
        when(store.warehouseContainers(PROJECT, "minecraft:overworld"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        int mask = InterestKind.AREA_CONFIG.bit() | InterestKind.OUTLINES.bit()
                | InterestKind.WAREHOUSE_CONTENTS.bit();
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, mask));
        assertEquals(1, views.viewCount());
        assertEquals(3, interests.interests(playerId).size());

        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        packets.clear();
        recipients.clear();
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(0, views.viewCount());
        assertEquals(0, interests.projectCount());
        assertEquals(0, interests.interests(playerId).size());
    }

    @Test
    void deletingProjectKeepsSameIdGlobalViewAndInterest() {
        ProjectViewScope sameIdGlobal = ProjectViewScope.globalWarehouse();
        String projectId = sameIdGlobal.id();
        ProjectViewScope projectScope = ProjectViewScope.project(projectId);
        assertNotNull(views.open(playerId, 1L, sameIdGlobal, 8L, 2L, InterestKind.AREA_CONFIG.bit()));
        assertNotNull(views.open(playerId, 1L, projectScope, 7L, 3L, InterestKind.TEAM.bit()));
        assertEquals(2, views.viewCount());
        assertEquals(2, interests.interests(playerId).size());

        when(runtime.readProgress(projectId)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        packets.clear();
        recipients.clear();
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, projectId);

        List<ProjectViewS2CPacket> deleted = packets.stream()
                .filter(ProjectViewS2CPacket.class::isInstance)
                .map(ProjectViewS2CPacket.class::cast)
                .filter(packet -> packet.result() == ProjectViewS2CPacket.Result.DELETED)
                .toList();
        assertEquals(1, deleted.size());
        assertEquals(projectScope, deleted.getFirst().scope());
        assertEquals(1, views.viewCount());
        assertTrue(views.matches(playerId, 1L, sameIdGlobal, 8, 2));
        assertEquals(0, interests.projectCount());
        assertEquals(1, interests.globalScopeCount());
        Set<ProjectInterestRegistry.Interest> remaining = interests.interests(playerId);
        assertEquals(1, remaining.size());
        ProjectInterestRegistry.Interest interest = remaining.iterator().next();
        assertEquals(ProjectInterestRegistry.Scope.global(projectId), interest.scope());
        assertEquals(InterestKind.AREA_CONFIG, interest.kind());
    }

    @Test
    void existingProjectDoesNotSendDeletedToNonMaterialsView() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        packets.clear();
        recipients.clear();
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(1, views.viewCount());
        assertEquals(InterestKind.TEAM.bit(), views.mask(playerId, 1L, SCOPE, 7, 3));
    }

    @Test
    void deletionReadCompletionLeavesReplacementViewToItsOwnExistenceCheck() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        CompletableFuture<Optional<ProjectProgressRead>> initialRead = new CompletableFuture<>();
        CompletableFuture<Optional<ProjectProgressRead>> replacementRead = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(initialRead, replacementRead);
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        receive(command(SCOPE, 100, 8, 4, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        assertTrue(views.matches(playerId, 1L, SCOPE, 8, 4));
        packets.clear();

        initialRead.complete(Optional.empty());

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertTrue(views.matches(playerId, 1L, SCOPE, 8, 4));
        assertEquals(1, interests.projectCount());

        replacementRead.complete(Optional.empty());
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED
                && result.viewId() == 8L && result.generation() == 4L));
        assertEquals(0, views.viewCount());
        assertEquals(0, interests.projectCount());
    }

    @Test
    void deletionReadCompletionDoesNotCloseAReplacedConnection() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        CompletableFuture<Optional<ProjectProgressRead>> pending = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(pending);
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        when(runtime.connection(playerId)).thenReturn(2L);
        assertNotNull(views.open(playerId, 2L, SCOPE, 8L, 4L, InterestKind.TEAM.bit()));
        packets.clear();

        pending.complete(Optional.empty());

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertTrue(views.matches(playerId, 2L, SCOPE, 8, 4));
        assertEquals(1, interests.projectCount());
    }

    @Test
    void nonMaterialsOpenChecksThatTheProjectExists() {
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));

        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));

        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(0, views.viewCount());
        assertEquals(0, interests.projectCount());
    }

    @Test
    void readFailureDoesNotSendProgressErrorToNonMaterialsView() {
        receive(command(SCOPE, 99, 7, 3, ProjectViewAction.OPEN, InterestKind.TEAM.bit()));
        when(runtime.readProgress(PROJECT)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("read failed")));
        packets.clear();

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);

        assertTrue(packets.stream().noneMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && (result.result() == ProjectViewS2CPacket.Result.ERROR
                || result.result() == ProjectViewS2CPacket.Result.DELETED)));
        assertEquals(1, views.viewCount());
    }

    @Test
    void legacyDeletionCompletingFirstKeepsBothDeletionNotifications() {
        CompletableFuture<Optional<ProjectProgressRead>> legacyRead = new CompletableFuture<>();
        CompletableFuture<Optional<ProjectProgressRead>> viewRead = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(legacyRead, viewRead);
        // A real deletion cleanup removes the legacy subscriber; model that instead of
        // pinning a constant set that would hide completion-order bugs.
        AtomicBoolean subscribed = new AtomicBoolean(true);
        when(runtime.subscribers(PROJECT)).thenAnswer(invocation ->
                subscribed.get() ? Set.of(playerId) : Set.<UUID>of());
        doAnswer(invocation -> {
            subscribed.set(false);
            return null;
        }).when(runtime).unsubscribe(any(), any());
        assertNotNull(views.open(playerId, 1L, SCOPE, 7L, 3L, InterestKind.TEAM.bit()));
        packets.clear();
        recipients.clear();

        FoundationNetworkAdapter.broadcast(server, PROJECT);
        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        legacyRead.complete(Optional.empty());

        assertTrue(packets.stream().anyMatch(packet ->
                packet instanceof StagingAreaConfigResponseS2CPacket response
                        && response.action().equals("SCHEMATIC_DELETED")));
        assertTrue(views.matches(playerId, 1L, SCOPE, 7, 3),
                "the protocol-5 view must stay until its own broadcast completes");

        viewRead.complete(Optional.empty());
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(0, views.viewCount());
    }

    @Test
    void projectViewDeletionCompletingFirstKeepsBothDeletionNotifications() {
        CompletableFuture<Optional<ProjectProgressRead>> viewRead = new CompletableFuture<>();
        CompletableFuture<Optional<ProjectProgressRead>> legacyRead = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(viewRead, legacyRead);
        // Same player holds both memberships; whichever cleanup completes first must not
        // starve the other deletion notification.
        AtomicBoolean subscribed = new AtomicBoolean(true);
        when(runtime.subscribers(PROJECT)).thenAnswer(invocation ->
                subscribed.get() ? Set.of(playerId) : Set.<UUID>of());
        doAnswer(invocation -> {
            subscribed.set(false);
            return null;
        }).when(runtime).unsubscribe(any(), any());
        assertNotNull(views.open(playerId, 1L, SCOPE, 7L, 3L, InterestKind.TEAM.bit()));
        packets.clear();
        recipients.clear();

        FoundationProjectViewNetworkAdapter.broadcast(server, runtime, PROJECT);
        FoundationNetworkAdapter.broadcast(server, PROJECT);
        viewRead.complete(Optional.empty());

        assertTrue(packets.stream().anyMatch(packet -> packet instanceof ProjectViewS2CPacket result
                && result.result() == ProjectViewS2CPacket.Result.DELETED));
        assertEquals(0, views.viewCount());

        legacyRead.complete(Optional.empty());
        assertTrue(packets.stream().anyMatch(packet ->
                        packet instanceof StagingAreaConfigResponseS2CPacket response
                                && response.action().equals("SCHEMATIC_DELETED")),
                "项目视图清理不得吞掉已经捕获的旧订阅删除通知");
    }

    @Test
    void legacyBroadcastReadsAndTailCleansOfflineSnapshotsEvenWithoutTokens() {
        UUID offline = UUID.randomUUID();
        UUID reconnected = UUID.randomUUID();
        when(runtime.subscribers(PROJECT)).thenReturn(Set.of(offline, reconnected));
        when(runtime.connection(offline)).thenReturn(0L);
        when(runtime.connection(reconnected)).thenReturn(0L);
        CompletableFuture<Optional<ProjectProgressRead>> pending = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(pending);
        packets.clear();
        recipients.clear();

        FoundationNetworkAdapter.broadcast(server, PROJECT);
        when(runtime.connection(reconnected)).thenReturn(3L);
        pending.complete(Optional.empty());

        verify(runtime).unsubscribe(offline, PROJECT);
        verify(runtime, never()).unsubscribe(reconnected, PROJECT);
    }

    @Test
    void legacyBroadcastDoesNotClearAReconnectForAnInvalidatedToken() {
        when(runtime.subscribers(PROJECT)).thenReturn(Set.of(playerId));
        when(runtime.connection(playerId)).thenReturn(1L);
        CompletableFuture<Optional<ProjectProgressRead>> pending = new CompletableFuture<>();
        when(runtime.readProgress(PROJECT)).thenReturn(pending);
        packets.clear();
        recipients.clear();

        FoundationNetworkAdapter.broadcast(server, PROJECT);
        when(runtime.connection(playerId)).thenReturn(2L);
        pending.complete(Optional.empty());

        verify(runtime, never()).unsubscribe(any(), any());
    }

    private ProjectProgressRead progressReadAtRevision(long revision) {
        var current = read.state();
        var inventory = new AreaInventoryView(current.inventory().staging(), current.inventory().warehouse(),
                current.inventory().freshness(), current.inventory().known(), current.inventory().overlap(), revision);
        return new ProjectProgressRead(new ProjectState(current.project(), current.claims(), inventory),
                read.observations(), read.publicationSequence());
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
