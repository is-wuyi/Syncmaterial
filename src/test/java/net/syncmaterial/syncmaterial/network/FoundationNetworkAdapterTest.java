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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.server.area.AreaScanService;
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
    private AreaScanService areas;
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
        ServerLevel world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.level()).thenReturn(world);
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(playerId)).thenReturn(player);
        when(players.getPlayers()).thenReturn(List.of(player));
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
        areas = mock(AreaScanService.class);
        when(runtime.areas()).thenReturn(areas);
        when(areas.refreshConfiguration()).thenReturn(CompletableFuture.completedFuture(null));
        when(areas.isInventoryCurrent(eq(PROJECT), any(AreaInventoryView.class))).thenReturn(true);
        catalog(List.of());
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
        ProtocolHandshake.recordHandshake(playerId, 2, "protocol-two");
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
    void materialIntentChangedBeforeServerDeliveryRejectsPreviouslyReadSnapshot() {
        List<Runnable> callbacks = new ArrayList<>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        requestMaterials();
        when(runtime.isMaterialSnapshotCurrent(any(ProjectSnapshot.class))).thenReturn(false);
        List.copyOf(callbacks).forEach(Runnable::run);
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
    void areaListReturnsRealConfigurationAndClearableOverlapWarning() {
        var first = area(1, AreaKind.STAGING_AREA, "First", 0, 2);
        var second = area(2, AreaKind.STAGING_AREA, "Second", 1, 3);
        catalog(List.of(first, second));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "LIST", -1, Optional.empty()));
        var response = packets.stream().filter(StagingAreaConfigResponseS2CPacket.class::isInstance)
                .map(StagingAreaConfigResponseS2CPacket.class::cast).findFirst().orElseThrow();
        assertTrue(response.success());
        assertEquals(2, response.areas().size());
        assertEquals(AreaOverlapStatus.MESSAGE_KEY, response.message());
        packets.clear();
        catalog(List.of(first));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "LIST", -1, Optional.empty()));
        response = packets.stream().filter(StagingAreaConfigResponseS2CPacket.class::isInstance)
                .map(StagingAreaConfigResponseS2CPacket.class::cast).findFirst().orElseThrow();
        assertTrue(response.message().isEmpty());
    }

    @Test
    void realEffectiveTotalsAreSentInLegacyAndAuthoritativeViews() {
        inventory(new AreaInventoryView(Map.of("minecraft:stone", 32L), Map.of("minecraft:stone", 16L),
                List.of(), true, true, 1));
        requestMaterials();
        var status = packets.stream().filter(CollaborationStatusS2CPacket.class::isInstance)
                .map(CollaborationStatusS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals(32, status.stagingCount());
        assertEquals(16, status.warehouseCount());
        assertTrue(status.freshnessInfo().isEmpty());
        var authoritative = packets.stream().filter(AreaInventorySnapshotS2CPacket.class::isInstance)
                .map(AreaInventorySnapshotS2CPacket.class::cast).findFirst().orElseThrow();
        assertTrue(authoritative.known());
        assertEquals(List.of(new AreaInventorySnapshotS2CPacket.MaterialInventory(1, 32, 16)), authoritative.materials());
        var catalog = packets.stream().filter(MaterialCatalogUpdateS2CPacket.class::isInstance)
                .map(MaterialCatalogUpdateS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals(catalog.revision(), authoritative.catalogRevision());
        assertTrue(packets.indexOf(authoritative) > packets.indexOf(catalog));
    }

    @Test
    void protocolTwoNeverReceivesNewAreaPacketButTotalsStayDeduped() {
        ProtocolHandshake.recordHandshake(playerId, 2, "protocol-two");
        inventory(new AreaInventoryView(Map.of("minecraft:stone", 32L), Map.of("minecraft:stone", 0L),
                List.of(), true, true, 1));
        requestMaterials();
        assertTrue(packets.stream().noneMatch(AreaInventorySnapshotS2CPacket.class::isInstance));
        var status = packets.stream().filter(CollaborationStatusS2CPacket.class::isInstance)
                .map(CollaborationStatusS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals(32, status.stagingCount());
        assertEquals(0, status.warehouseCount());
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("更新客户端")));
    }

    @Test
    void pendingWorldChangeCannotPublishReadyFromOlderCommittedView() {
        inventory(new AreaInventoryView(Map.of("minecraft:stone", 64L), Map.of(), List.of(), true, false, 1));
        when(areas.isInventoryCurrent(eq(PROJECT), any(AreaInventoryView.class))).thenReturn(false);
        requestMaterials();
        var packet = packets.stream().filter(AreaInventorySnapshotS2CPacket.class::isInstance)
                .map(AreaInventorySnapshotS2CPacket.class::cast).findFirst().orElseThrow();
        assertFalse(packet.known());
        assertFalse(packet.freshnessInfo().isEmpty());
        assertEquals(64, packet.materials().get(0).stagingCount());
    }

    @Test
    void staleAreaRetainsCountsAndWarningsWithoutReadyClaim() {
        inventory(new AreaInventoryView(Map.of("minecraft:stone", 32L), Map.of(),
                List.of(new AreaInventoryWarning(AreaKind.STAGING_AREA, 1, "Area", "区块已卸载，库存可能过期")), false, false, 1));
        requestMaterials();
        var packet = packets.stream().filter(AreaInventorySnapshotS2CPacket.class::isInstance)
                .map(AreaInventorySnapshotS2CPacket.class::cast).findFirst().orElseThrow();
        assertFalse(packet.known());
        assertEquals(32, packet.materials().get(0).stagingCount());
        assertEquals("区块已卸载，库存可能过期", packet.freshnessInfo().get(0).status());
    }

    @Test
    void invalidOrCrossProjectAreaUpdateReportsFailureWithOriginalAction() {
        catalog(List.of(area(1, AreaKind.STAGING_AREA, "Only", 0, 2)));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "UPDATE", 2,
                Optional.of(new StagingAreaConfigC2SPacket.AreaData("Wrong", 0, 0, 0, 1, 1, 1, Optional.empty()))));
        var packet = (StagingAreaConfigResponseS2CPacket) packets.get(0);
        assertFalse(packet.success());
        assertEquals("UPDATE", packet.action());
        verify(store, never()).updateArea(any(), any(), anyInt(), any(AreaSpec.class));
    }

    @Test
    void missingAreaDataDoesNotThrowOrReportSuccess() {
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "ADD", -1, Optional.empty()));
        var packet = (StagingAreaConfigResponseS2CPacket) packets.get(0);
        assertFalse(packet.success());
        assertEquals("ADD", packet.action());
        verify(store, never()).addArea(any(), any(), any(AreaSpec.class));
    }

    @Test
    void unknownProjectAreaListIsExplicitFailure() {
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(new AreaCatalog(List.of(), Map.of(), Map.of(), 1)));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "LIST", -1, Optional.empty()));
        assertFalse(((StagingAreaConfigResponseS2CPacket) packets.get(0)).success());
    }

    @Test
    void globalWarehouseListRemainsOpenWithoutProject() {
        catalog(List.of(area(1, AreaKind.WAREHOUSE, "Global", 0, 1)));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket("", "LIST_WAREHOUSES", -1, Optional.empty()));
        var packet = packets.stream().filter(StagingAreaConfigResponseS2CPacket.class::isInstance)
                .map(StagingAreaConfigResponseS2CPacket.class::cast).findFirst().orElseThrow();
        assertTrue(packet.success());
        assertEquals(1, packet.areas().size());
        assertEquals("", packet.schematicId());
    }

    @Test
    void unhandshakedRequestsReceiveNoAreaOrInventoryPackets() {
        ProtocolHandshake.remove(playerId);
        requestMaterials();
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket("", "LIST_WAREHOUSES", -1, Optional.empty()));
        assertTrue(packets.isEmpty());
        verify(store, never()).snapshot(anyString());
        verify(store, never()).areaCatalog();
    }

    @Test
    void validAreaMutationUsesStoreAndRefreshesConfigurationWithoutOwnerGate() {
        var added = area(1, AreaKind.STAGING_AREA, "New", 0, 1);
        when(store.addArea(eq(PROJECT), eq(AreaKind.STAGING_AREA), any(AreaSpec.class)))
                .thenReturn(CompletableFuture.completedFuture(added));
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(new AreaCatalog(List.of(), Map.of(), Map.of(PROJECT, "Original"), 1)))
                .thenReturn(CompletableFuture.completedFuture(new AreaCatalog(List.of(added), Map.of(), Map.of(PROJECT, "Original"), 2)));
        receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "ADD", -1,
                Optional.of(new StagingAreaConfigC2SPacket.AreaData("New", 0, 0, 0, 1, 2, 1,
                        Optional.of("minecraft:overworld")))));
        verify(store).addArea(eq(PROJECT), eq(AreaKind.STAGING_AREA), any(AreaSpec.class));
        verify(areas).refreshConfiguration();
        assertTrue(packets.stream().anyMatch(packet -> packet instanceof StagingAreaConfigResponseS2CPacket response
                && response.success() && response.action().equals("LIST") && response.areas().size() == 1));
        verify(runtime).requestBroadcast(PROJECT);
    }

    @Test
    void committedAreaChangeStillPublishesAfterTheInitiatorDisconnects() {
        var pending = new CompletableFuture<AreaDefinition>();
        var added = area(1, AreaKind.STAGING_AREA, "New", 0, 1);
        when(store.addArea(eq(PROJECT), eq(AreaKind.STAGING_AREA), any(AreaSpec.class))).thenReturn(pending);
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(new AreaCatalog(List.of(), Map.of(), Map.of(PROJECT, "Original"), 1)))
                .thenReturn(CompletableFuture.completedFuture(new AreaCatalog(List.of(added), Map.of(), Map.of(PROJECT, "Original"), 2)));
        UUID observerId = UUID.randomUUID();
        ServerPlayer observer = mock(ServerPlayer.class);
        observer.connection = mock(ServerGamePacketListenerImpl.class);
        when(observer.getUUID()).thenReturn(observerId);
        when(runtime.connection(observerId)).thenReturn(2L);
        when(runtime.subscribers(PROJECT)).thenReturn(Set.of(observerId));
        when(server.getPlayerList().getPlayer(observerId)).thenReturn(observer);
        when(server.getPlayerList().getPlayers()).thenReturn(List.of(observer));
        ProtocolHandshake.recordHandshake(observerId, ProtocolVersion.CURRENT, "test");
        try {
            receive(StagingAreaConfigC2SPacket.ID, new StagingAreaConfigC2SPacket(PROJECT, "ADD", -1,
                    Optional.of(new StagingAreaConfigC2SPacket.AreaData("New", 0, 0, 0, 1, 2, 1,
                            Optional.of("minecraft:overworld")))));
            when(runtime.connection(playerId)).thenReturn(0L);
            when(server.getPlayerList().getPlayer(playerId)).thenReturn(null);
            ProtocolHandshake.remove(playerId);
            pending.complete(added);
            verify(runtime).requestBroadcast(PROJECT);
            networking.verify(() -> ServerPlayNetworking.send(eq(observer), argThat(packet ->
                    packet instanceof StagingAreaConfigResponseS2CPacket response && response.success()
                            && response.action().equals("LIST") && response.areas().size() == 1)));
            assertTrue(packets.isEmpty(), "the disconnected initiator must not receive its old response");
        } finally {
            ProtocolHandshake.remove(observerId);
        }
    }

    @Test
    void rescanCompletesTruthfullyAndRequestsInventoryBroadcast() {
        when(areas.rescan(PROJECT)).thenReturn(CompletableFuture.completedFuture(
                new AreaScanService.RescanResult(true, "已刷新已加载区块；未加载部分保留缓存")));
        receive(RescanStagingAreaC2SPacket.ID, new RescanStagingAreaC2SPacket(PROJECT));
        var packet = (RescanStagingAreaResponseS2CPacket) packets.get(0);
        assertTrue(packet.success());
        assertTrue(packet.message().contains("未加载"));
        verify(areas).refreshConfiguration();
        verify(runtime).requestBroadcast(PROJECT);
    }

    @Test
    void rescanFailureDoesNotReportSuccess() {
        when(areas.rescan(PROJECT)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("failed")));
        receive(RescanStagingAreaC2SPacket.ID, new RescanStagingAreaC2SPacket(PROJECT));
        assertFalse(((RescanStagingAreaResponseS2CPacket) packets.get(0)).success());
        verify(runtime, never()).requestBroadcast(PROJECT);
    }

    @Test
    void warehouseDetailsFilterDimensionAndDeduplicatePhysicalSources() {
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.level()).thenReturn(overworld);
        var key = new ContainerKey("minecraft:overworld", 1, 64, 1);
        when(store.warehouseContainers(PROJECT, "minecraft:overworld")).thenReturn(CompletableFuture.completedFuture(List.of(
                new ContainerObservation(key, Map.of("minecraft:stone", 32L), ""),
                new ContainerObservation(key, Map.of("minecraft:dirt", 16L), ""),
                new ContainerObservation(new ContainerKey("minecraft:the_nether", 1, 64, 1), Map.of("minecraft:diamond", 8L), ""),
                new ContainerObservation(new ContainerKey("minecraft:overworld", 2, 64, 2), Map.of(), "未打开的战利品容器"))));
        receive(WarehouseContainerRequestC2SPacket.ID, new WarehouseContainerRequestC2SPacket(PROJECT, true));
        var response = (WarehouseContainerResponseS2CPacket) packets.get(0);
        assertEquals(1, response.containers().size());
        assertEquals(Set.of("minecraft:stone", "minecraft:dirt"), Set.copyOf(response.containers().get(0).itemIds()));
        packets.clear();
        receive(WarehouseContainerRequestC2SPacket.ID, new WarehouseContainerRequestC2SPacket(PROJECT, false));
        assertTrue(((WarehouseContainerResponseS2CPacket) packets.get(0)).containers().isEmpty());
    }

    @Test
    void warehouseResultFromPreviousDimensionIsNotDelivered() {
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(player.level()).thenReturn(overworld);
        var pending = new CompletableFuture<List<ContainerObservation>>();
        when(store.warehouseContainers(PROJECT, "minecraft:overworld")).thenReturn(pending);
        receive(WarehouseContainerRequestC2SPacket.ID, new WarehouseContainerRequestC2SPacket(PROJECT, true));
        ServerLevel nether = mock(ServerLevel.class);
        when(nether.dimension()).thenReturn(net.minecraft.world.level.Level.NETHER);
        when(player.level()).thenReturn(nether);
        pending.complete(List.of(new ContainerObservation(new ContainerKey("minecraft:overworld", 1, 64, 1), Map.of("minecraft:stone", 32L), "")));
        assertTrue(packets.isEmpty());
    }

    @Test
    void asynchronousOlderStatusCannotReplaceNewerInventoryDelivery() {
        requestMaterials();
        packets.clear();
        var older = new CompletableFuture<Optional<ProjectState>>();
        when(store.snapshot(PROJECT)).thenReturn(older);
        receive(QueryMaterialStatusC2SPacket.ID, new QueryMaterialStatusC2SPacket(PROJECT));
        inventory(new AreaInventoryView(Map.of("minecraft:stone", 48L), Map.of(), List.of(), true, false, 2));
        receive(QueryMaterialStatusC2SPacket.ID, new QueryMaterialStatusC2SPacket(PROJECT));
        var project = new ProjectSnapshot(PROJECT, "Original", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        int delivered = packets.size();
        older.complete(Optional.of(new ProjectState(project, List.of(),
                new AreaInventoryView(Map.of("minecraft:stone", 16L), Map.of(), List.of(), true, false, 1))));
        assertEquals(delivered, packets.size());
        var latest = packets.stream().filter(AreaInventorySnapshotS2CPacket.class::isInstance)
                .map(AreaInventorySnapshotS2CPacket.class::cast).findFirst().orElseThrow();
        assertEquals(48, latest.materials().get(0).stagingCount());
    }

    @Test
    void closingProjectFencesInFlightStatusDelivery() {
        requestMaterials();
        packets.clear();
        var pending = new CompletableFuture<Optional<ProjectState>>();
        when(store.snapshot(PROJECT)).thenReturn(pending);
        receive(QueryMaterialStatusC2SPacket.ID, new QueryMaterialStatusC2SPacket(PROJECT));
        receive(MaterialListCloseC2SPacket.ID, new MaterialListCloseC2SPacket(PROJECT));
        packets.clear();
        var project = new ProjectSnapshot(PROJECT, "Original", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        pending.complete(Optional.of(new ProjectState(project, List.of(),
                new AreaInventoryView(Map.of("minecraft:stone", 64L), Map.of(), List.of(), true, false, 1))));
        assertTrue(packets.isEmpty());
        verify(runtime).unsubscribe(playerId, PROJECT);
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

    private void inventory(AreaInventoryView inventory) {
        var project = new ProjectSnapshot(PROJECT, "Original", "hash", owner(), true, List.of(),
                List.of(new MaterialSnapshot(1, "minecraft:stone", 64)), 1);
        when(store.snapshot(PROJECT)).thenReturn(CompletableFuture.completedFuture(Optional.of(new ProjectState(project, List.of(), inventory))));
    }

    private void catalog(List<AreaDefinition> definitions) {
        when(store.areaCatalog()).thenReturn(CompletableFuture.completedFuture(
                new AreaCatalog(definitions, Map.of(), Map.of(PROJECT, "Original"), 1)));
    }

    private static AreaDefinition area(int id, AreaKind kind, String name, int first, int last) {
        return new AreaDefinition(id, kind == AreaKind.WAREHOUSE ? null : PROJECT, kind, name,
                "minecraft:overworld", new AreaBounds(first, 0, first, last, 2, last), 1, 1);
    }

    private PlayerIdentity owner() {
        return new PlayerIdentity(playerId, "Player");
    }
}
