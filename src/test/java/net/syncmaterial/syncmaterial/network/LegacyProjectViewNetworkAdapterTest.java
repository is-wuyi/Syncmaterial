package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.syncmaterial.syncmaterial.client.ClientProjectViewStore;
import net.syncmaterial.syncmaterial.client.ProjectViewAssembler;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Legacy protocol-5 adapter repair tests: main tracker/packets/client-store acceptance,
 * global views, interest-added content, per-container warehouse COMPLETE, CLOSE cleanup,
 * canonical configuration, OPEN-only views, trusted fresh observations, overlap unknown,
 * scan incompleteness and dimension re-read.
 */
class LegacyProjectViewNetworkAdapterTest {
    private static final String PROJECT = "p1";
    private static final long VIEW = 7;
    private static final long GENERATION = 1;

    @TempDir
    Path temporary;

    private MockedStatic<ServerPlayNetworking> networking;
    private MockedStatic<LegacyServerRegistry> registry;
    private LegacyNetworkTestSupport support;
    private SchematicDatabase database;
    private CollaborationManager collaboration;
    private StagingAreaManager staging;
    private final List<CustomPacketPayload> sent = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() throws Exception {
        support = new LegacyNetworkTestSupport("Player1");
        database = new SchematicDatabase();
        database.initialize(temporary.resolve("legacy.sqlite").toString());
        collaboration = new CollaborationManager(database);
        staging = new StagingAreaManager(database);
        collaboration.setStagingAreaManager(staging);
        staging.loadWarehousesFromDb();

        when(support.owner.database()).thenReturn(database);
        when(support.owner.queryService()).thenReturn(new DatabaseQueryService(database));
        when(support.owner.collaboration()).thenReturn(collaboration);
        when(support.owner.staging()).thenReturn(staging);
        when(support.level.dimension()).thenReturn(Level.OVERWORLD);

        insertProject(PROJECT, "Project One", "Owner", true);
        insertMaterial(1, PROJECT, "minecraft:stone", 100);
        insertClaim(1, PROJECT, "Player1");

        registry = mockStatic(LegacyServerRegistry.class);
        registry.when(() -> LegacyServerRegistry.get(support.server))
                .thenAnswer(invocation -> support.installed.get());

        networking = mockStatic(ServerPlayNetworking.class);
        networking.when(() -> ServerPlayNetworking.send(any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == support.player) {
                sent.add(invocation.getArgument(1));
            }
            return null;
        });

        support.handshake();
    }

    @AfterEach
    void tearDown() {
        if (networking != null) networking.close();
        if (registry != null) registry.close();
        if (staging != null) staging.close();
        if (collaboration != null) collaboration.close();
        if (database != null) database.close();
        if (support != null) support.close();
    }

    // ------------------------------------------------------------------
    // Global views
    // ------------------------------------------------------------------

    @Test
    void globalOpenSetAndCloseUseTypedScopeAndCanonicalConfig() throws Exception {
        database.executeUpdate("INSERT INTO warehouses "
                + "(id, name, world, x1, y1, z1, x2, y2, z2) "
                + "VALUES (1, 'Warehouse', 'minecraft:overworld', 0, 0, 0, 4, 4, 4)");
        staging.loadWarehousesFromDb();
        ProjectViewScope global = ProjectViewScope.globalWarehouse();
        ClientProjectViewStore store = new ClientProjectViewStore();
        assertTrue(store.open(global, VIEW, GENERATION));

        handle(new ProjectViewCommandC2SPacket(global, 1, VIEW, GENERATION,
                ProjectViewAction.OPEN, null, 0, 0,
                InterestKind.AREA_CONFIG.bit() | InterestKind.OUTLINES.bit(),
                Optional.empty(), List.of()));

        assertEquals(ProjectViewS2CPacket.Result.OPENED, first(ProjectViewS2CPacket.class).result());
        List<ProjectViewS2CPacket> configs = packets(ProjectViewS2CPacket.Result.CONFIG);
        assertEquals(1, configs.size());
        assertEquals("LIST_WAREHOUSES", configs.getFirst().configuration().orElseThrow().action());
        assertEquals(AreaKind.WAREHOUSE,
                configs.getFirst().configuration().orElseThrow().areas().getFirst().kind());
        feedStructured(store, configs);
        assertTrue(store.configuration("LIST_WAREHOUSES").orElseThrow().size() == 1);
        assertEquals(1, support.views.viewCount());

        sent.clear();
        handle(new ProjectViewCommandC2SPacket(global, 2, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0, InterestKind.AREA_CONFIG.bit(),
                Optional.empty(), List.of()));
        ProjectViewS2CPacket updated = last(ProjectViewS2CPacket.Result.OPERATION);
        assertTrue(updated.success());
        assertTrue(updated.changed());
        assertEquals(Set.of(InterestKind.AREA_CONFIG), mask(global));

        sent.clear();
        handle(new ProjectViewCommandC2SPacket(global, 3, VIEW, GENERATION,
                ProjectViewAction.CLOSE, null, 0, 0, 0, Optional.empty(), List.of()));
        assertTrue(last(ProjectViewS2CPacket.Result.OPERATION).success());
        assertEquals(0, support.views.viewCount());
        assertEquals(0, support.interests.interestCount());
        assertTrue(packets(ProjectViewS2CPacket.Result.PROGRESS_PART).isEmpty(),
                "global views never fabricate project progress");
    }

    // ------------------------------------------------------------------
    // Interest-added content
    // ------------------------------------------------------------------

    @Test
    void openDeliversFullMaskAndSetDeliversOnlyAddedInterests() throws Exception {
        seedWarehouseWithTwoContainers();
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        int base = InterestKind.TEAM.bit() | InterestKind.AREA_CONFIG.bit()
                | InterestKind.OUTLINES.bit() | InterestKind.WAREHOUSE_CONTENTS.bit();
        ClientProjectViewStore store = new ClientProjectViewStore();
        assertTrue(store.open(scope, VIEW, GENERATION));

        handle(open(PROJECT, 1, VIEW, GENERATION, base));

        assertTrue(packets(ProjectViewS2CPacket.Result.PROGRESS_PART).isEmpty());
        assertEquals(1, packets(TeamResponseS2CPacket.class).size(), "TEAM interest delivers team state");
        List<ProjectViewS2CPacket> configs = packets(ProjectViewS2CPacket.Result.CONFIG);
        assertTrue(configs.stream().anyMatch(packet -> "LIST".equals(
                packet.configuration().orElseThrow().action())));
        assertTrue(configs.stream().anyMatch(packet -> "LIST_WAREHOUSE_REFS".equals(
                packet.configuration().orElseThrow().action())));
        feedStructured(store, configs);
        feedWarehouse(store, packets(ProjectViewS2CPacket.Result.WAREHOUSE));
        assertTrue(store.configuration("LIST").isPresent());
        assertFalse(store.warehouse().isEmpty());

        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0,
                base | InterestKind.MATERIALS.bit(), Optional.empty(), List.of()));
        assertFalse(packets(ProjectViewS2CPacket.Result.PROGRESS_PART).isEmpty(),
                "adding MATERIALS must deliver authoritative progress");
        assertTrue(packets(ProjectViewS2CPacket.Result.CONFIG).isEmpty(),
                "unchanged interests must not be re-sent");
        assertTrue(packets(ProjectViewS2CPacket.Result.WAREHOUSE).isEmpty(),
                "unchanged pickup interest must not be re-sent");
        installProgress(store, PROJECT, VIEW, GENERATION);
    }

    @Test
    void warehouseContentIsOneObservationPerContainerWithSequenceAndComplete() throws Exception {
        List<int[]> positions = seedWarehouseWithTwoContainers();
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        ClientProjectViewStore store = new ClientProjectViewStore();
        assertTrue(store.open(scope, VIEW, GENERATION));
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.WAREHOUSE_CONTENTS.bit()));

        List<ProjectViewS2CPacket> packets = packets(ProjectViewS2CPacket.Result.WAREHOUSE);
        List<ProjectViewS2CPacket> observations = packets.stream()
                .filter(packet -> packet.warehouse().isPresent()).toList();
        assertEquals(positions.size(), observations.size(),
                "every physical container is a separate observation");
        long sequence = observations.getFirst().sequence();
        for (ProjectViewS2CPacket observation : observations) {
            assertEquals(sequence, observation.sequence());
            assertEquals("minecraft:overworld", observation.warehouse().orElseThrow().key().world());
            assertFalse(observation.warehouse().orElseThrow().counts().isEmpty(),
                    "legacy item presence must survive the protocol 5 projection");
            assertTrue(observation.warehouse().orElseThrow().counts().values().stream()
                            .allMatch(count -> count == 0),
                    "legacy cannot truthfully allocate aggregate counts to a physical box");
            assertFalse(observation.warehouse().orElseThrow().warning().isBlank(),
                    "opaque container must stay explicit");
        }
        assertTrue(positions.stream().anyMatch(position ->
                position[0] != observations.getFirst().warehouse().orElseThrow().key().x()));
        ProjectViewS2CPacket complete = packets.getLast();
        assertEquals("COMPLETE", complete.message());
        assertTrue(complete.warehouse().isEmpty());
        assertEquals(sequence, complete.sequence());

        feedWarehouse(store, packets);
        assertEquals(positions.size(), store.warehouse().size());
        assertEquals("minecraft:overworld", store.warehouseWorld());
    }

    // ------------------------------------------------------------------
    // CLOSE and OPEN semantics
    // ------------------------------------------------------------------

    @Test
    void closeClearsWholeScopeWhileGuiCloseIsOnlySetInterests() throws Exception {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.ALL_MASK));
        assertFalse(support.subscriptions.projects(support.player).isEmpty());

        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.CLOSE, null, 0, 0, 0, Optional.empty(), List.of()));
        assertEquals(0, support.views.viewCount());
        assertEquals(0, support.interests.interestCount());
        assertEquals(Set.of(), support.subscriptions.projects(support.player));

        sent.clear();
        handle(open(PROJECT, 3, VIEW, GENERATION, InterestKind.ALL_MASK));
        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 4, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0,
                InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit(),
                Optional.empty(), List.of()));
        assertEquals(Set.of(InterestKind.MATERIALS, InterestKind.TEAM), mask(scope));
        assertTrue(support.views.matches(support.player.getUUID(), connectionGeneration(),
                scope, VIEW, GENERATION), "client-side GUI close is a SET_INTERESTS, not server CLOSE");
        assertEquals(Set.of(PROJECT), support.subscriptions.projects(support.player));
    }

    @Test
    void onlyOpenCreatesViewsAndFailedOpenLeavesNoState() throws Exception {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(new ProjectViewCommandC2SPacket(scope, 1, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0, InterestKind.MATERIALS.bit(),
                Optional.empty(), List.of()));
        assertEquals(ProjectViewS2CPacket.Result.ERROR, last(ProjectViewS2CPacket.Result.ERROR).result());
        assertEquals(0, support.views.viewCount());
        assertEquals(0, support.interests.interestCount());

        sent.clear();
        handle(open("missing", 2, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        assertEquals(ProjectViewS2CPacket.Result.ERROR, last(ProjectViewS2CPacket.Result.ERROR).result());
        assertEquals(0, support.views.viewCount());
        assertEquals(0, support.interests.interestCount());

        sent.clear();
        handle(open(PROJECT, 4, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        assertEquals(ProjectViewS2CPacket.Result.OPENED, first(ProjectViewS2CPacket.class).result());
        assertEquals(1, support.views.viewCount());
    }

    // ------------------------------------------------------------------
    // Canonical configuration
    // ------------------------------------------------------------------

    @Test
    void mutationResponseUsesCanonicalListActionAndBroadcastsToOtherView() throws Exception {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.AREA_CONFIG.bit()));
        long otherView = 8;
        handle(open(PROJECT, 2, otherView, GENERATION, InterestKind.AREA_CONFIG.bit()));
        sent.clear();

        handle(new ProjectViewCommandC2SPacket(scope, 3, VIEW, GENERATION,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0,
                Optional.of(area("Staging", 0, 0, 0, 1, 1, 1, Optional.empty())), List.of()));

        List<ProjectViewS2CPacket> configs = packets(ProjectViewS2CPacket.Result.CONFIG);
        ProjectViewS2CPacket response = configs.stream()
                .filter(packet -> packet.requestId() == 3).findFirst().orElseThrow();
        assertEquals("LIST", response.configuration().orElseThrow().action(),
                "mutation response must use the canonical list action");
        assertEquals(1, response.configuration().orElseThrow().areas().size());
        assertTrue(configs.stream().anyMatch(packet -> packet.requestId() == 0
                && "LIST".equals(packet.configuration().orElseThrow().action())),
                "config broadcast uses a canonical list action with requestId=0");
    }

    @Test
    void emptyListIsSuccessWhileReadErrorIsError() throws Exception {
        insertProject("empty", "Empty", "Owner", true);
        ProjectViewScope scope = ProjectViewScope.project("empty");
        handle(open("empty", 1, VIEW, GENERATION, InterestKind.AREA_CONFIG.bit()));
        sent.clear();

        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 0, Optional.empty(), List.of()));
        ProjectViewS2CPacket empty = last(ProjectViewS2CPacket.Result.CONFIG);
        assertTrue(empty.success());
        assertTrue(empty.configuration().orElseThrow().areas().isEmpty());

        database.executeUpdate("DROP TABLE staging_areas");
        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 3, VIEW, GENERATION,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 0, Optional.empty(), List.of()));
        assertFalse(last(ProjectViewS2CPacket.Result.ERROR).success());
    }

    // ------------------------------------------------------------------
    // Trusted/fresh progress
    // ------------------------------------------------------------------

    @Test
    void progressResolvesOnlineClaimNamesAndCountsOnlyConnectionFreshObservations() throws Exception {
        insertClaim(1, PROJECT, "OfflinePlayer");
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        ProjectProgressSnapshot before = assemble(PROJECT, VIEW, GENERATION);
        var online = participant(before, "Player1");
        var offline = participant(before, "OfflinePlayer");
        assertTrue(online.player().hasUuid(), "online claim name resolves to trusted UUID");
        assertEquals(support.player.getUUID(), online.player().uuid());
        assertEquals(0L, online.effectiveHeld());
        assertFalse(offline.player().hasUuid());
        assertEquals(0L, offline.effectiveHeld());

        sent.clear();
        ModNetworkHandler.handleContainerUpdate(support.owner,
                new InventoryUpdateC2SPacket(PROJECT, 1, 64), support.player, support.server);
        support.projectViews.flush();
        ProjectProgressSnapshot fresh = assemble(PROJECT, VIEW, GENERATION);
        assertEquals(64L, participant(fresh, "Player1").effectiveHeld());
        assertEquals(0L, participant(fresh, "OfflinePlayer").effectiveHeld());

        support.inventoryClock.addAndGet(Duration.ofSeconds(11).toNanos());
        support.onlineInventories.expire();
        sent.clear();
        support.projectViews.markDirty(PROJECT);
        support.projectViews.flush();
        ProjectProgressSnapshot expired = assemble(PROJECT, VIEW, GENERATION);
        assertEquals(0L, participant(expired, "Player1").effectiveHeld(),
                "expired observations never count");
    }

    @Test
    void heartbeatArrivedBeforeRejoinCannotResurrectAnOldObservation() throws Exception {
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        support.owner.markClaimEpoch("Player1", PROJECT, 1);

        sent.clear();
        ModNetworkHandler.handleContainerUpdate(support.owner,
                new InventoryUpdateC2SPacket(PROJECT, 1, 64), support.player, support.server, 0L);
        support.projectViews.markDirty(PROJECT);
        support.projectViews.flush();
        ProjectProgressSnapshot stale = assemble(PROJECT, VIEW, GENERATION);
        assertEquals(0L, participant(stale, "Player1").effectiveHeld(),
                "a heartbeat received before rejoin must not resurrect");

        sent.clear();
        ModNetworkHandler.handleContainerUpdate(support.owner,
                new InventoryUpdateC2SPacket(PROJECT, 1, 64), support.player, support.server,
                Long.MAX_VALUE);
        support.projectViews.flush();
        ProjectProgressSnapshot current = assemble(PROJECT, VIEW, GENERATION);
        assertEquals(64L, participant(current, "Player1").effectiveHeld());
    }

    @Test
    void overlappingPhysicalSourcesStayUnknownInsteadOfClaimingCompletion() throws Exception {
        insertStagingArea(1, PROJECT, "A", 0, 0, 0, 15, 15, 15);
        insertStagingArea(2, PROJECT, "B", 0, 0, 0, 15, 15, 15);
        insertStagingInventory(1, "minecraft:stone", 60);
        insertStagingInventory(2, "minecraft:stone", 60);
        insertContainer("staging_area", 1, 4, 64, 4, "minecraft:stone");
        insertContainer("staging_area", 2, 4, 64, 4, "minecraft:stone");

        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        ProjectProgressSnapshot snapshot = assemble(PROJECT, VIEW, GENERATION);
        assertTrue(snapshot.areaOverlap());
        assertFalse(snapshot.areaKnown());
        assertFalse(snapshot.preparationKnown());
        assertFalse(snapshot.areaWarnings().isEmpty());
        var view = snapshot.viewFor(new net.syncmaterial.syncmaterial.core.PlayerIdentity(
                support.player.getUUID(), "Player1"));
        assertFalse(view.material(1).orElseThrow().teamCollected());
        assertFalse(view.material(1).orElseThrow().stagingReady());
    }

    // ------------------------------------------------------------------
    // Scans and dimension
    // ------------------------------------------------------------------

    @Test
    void incompleteScanPreservesContainerDetailsAndMarksCurrentFalse() throws Exception {
        database.executeUpdate("INSERT INTO warehouses "
                + "(id, name, world, x1, y1, z1, x2, y2, z2) "
                + "VALUES (1, 'Warehouse', 'minecraft:overworld', 0, 0, 0, 15, 15, 15)");
        insertContainer("warehouse", 1, 4, 64, 4, "minecraft:stone");
        staging.loadWarehousesFromDb();

        MinecraftServer scanServer = mock(MinecraftServer.class);
        ServerLevel scanWorld = mock(ServerLevel.class);
        net.minecraft.server.level.ServerChunkCache chunks =
                mock(net.minecraft.server.level.ServerChunkCache.class);
        when(scanWorld.getServer()).thenReturn(scanServer);
        when(scanServer.getLevel(any())).thenReturn(scanWorld);
        when(scanWorld.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(any(Integer.class), any(Integer.class), any(Boolean.class)))
                .thenReturn(null);
        staging.setServer(scanServer);
        staging.loadWarehousesFromDb();

        assertFalse(staging.rescanWarehouseExact(1));
        assertFalse(staging.isWarehouseCurrent(1));
        assertEquals(1, rowCount(
                "SELECT COUNT(*) FROM container_inventory WHERE area_id = 1 AND area_type = 'warehouse'"),
                "unloaded chunks must not wipe existing container details");
    }

    @Test
    void warehouseContentNeverClaimsEmptyWhenFactsAreIncompleteOrUnattributable() throws Exception {
        database.executeUpdate("INSERT INTO warehouses "
                + "(id, name, world, x1, y1, z1, x2, y2, z2) "
                + "VALUES (1, 'Warehouse', 'minecraft:overworld', 0, 0, 0, 15, 15, 15)");
        database.executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) "
                + "VALUES (?, 1)", PROJECT);
        database.executeUpdate("INSERT INTO warehouse_inventory "
                + "(warehouse_id, item_id, count) VALUES (1, 'minecraft:stone', 64)");
        staging.loadWarehousesFromDb();

        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.WAREHOUSE_CONTENTS.bit()));
        assertFalse(packets(ProjectViewS2CPacket.Result.WAREHOUSE).stream()
                        .anyMatch(packet -> "COMPLETE".equals(packet.message())),
                "an unconfirmed warehouse must not be installed as an empty authoritative set");
        assertFalse(packets(ProjectViewS2CPacket.Result.ERROR).isEmpty());

        sent.clear();
        markWarehouseCurrent(1);
        handle(open(PROJECT, 2, VIEW, GENERATION, InterestKind.WAREHOUSE_CONTENTS.bit()));
        assertFalse(packets(ProjectViewS2CPacket.Result.WAREHOUSE).stream()
                        .anyMatch(packet -> "COMPLETE".equals(packet.message())),
                "nonzero aggregate without container facts cannot be claimed as empty");
        assertFalse(packets(ProjectViewS2CPacket.Result.ERROR).isEmpty());
    }

    @Test
    void dimensionChangeResendsWarehouseContentAndCompleteBoundary() throws Exception {
        seedWarehouseWithTwoContainers();
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        ClientProjectViewStore store = new ClientProjectViewStore();
        assertTrue(store.open(scope, VIEW, GENERATION));
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.WAREHOUSE_CONTENTS.bit()));
        List<ProjectViewS2CPacket> first = packets(ProjectViewS2CPacket.Result.WAREHOUSE);
        long firstSequence = first.getFirst().sequence();
        feedWarehouse(store, first);
        assertFalse(store.warehouse().isEmpty());

        sent.clear();
        when(support.level.dimension()).thenReturn(Level.NETHER);
        support.projectViews.flush();

        List<ProjectViewS2CPacket> refreshed = packets(ProjectViewS2CPacket.Result.WAREHOUSE);
        assertFalse(refreshed.isEmpty());
        ProjectViewS2CPacket complete = refreshed.getLast();
        assertEquals("COMPLETE", complete.message());
        assertTrue(complete.sequence() > firstSequence);
        feedWarehouse(store, refreshed);
        assertTrue(store.warehouse().isEmpty(), "new dimension replaces old dimension content");
        assertEquals("", store.warehouseWorld());
    }

    // ------------------------------------------------------------------
    // Lifecycle edges
    // ------------------------------------------------------------------

    @Test
    void overlongLegacyProjectIdDeletionDoesNotThrow() {
        String longId = "x".repeat(200);
        support.subscriptions.subscribeMaterials(support.player, longId);
        assertDoesNotThrow(() -> support.projectViews.projectDeleted(longId));
        assertEquals(Set.of(), support.subscriptions.projects(support.player));
    }

    @Test
    void idempotentOpenAlwaysResyncsLegacySubscription() throws Exception {
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        assertEquals(Set.of(PROJECT), support.subscriptions.projects(support.player));
        support.subscriptions.unsubscribeMaterials(support.player, PROJECT);

        sent.clear();
        handle(open(PROJECT, 2, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        assertEquals(Set.of(PROJECT), support.subscriptions.projects(support.player),
                "idempotent OPEN re-syncs the legacy routing surface");
    }

    @Test
    void zeroMaskAndNewConnectionClearOldInterests() throws Exception {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.ALL_MASK));
        long oldGeneration = connectionGeneration();
        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0, 0, Optional.empty(), List.of()));
        assertEquals(0, support.interests.interestCount(), "zero mask removes every kind");
        assertEquals(1, support.views.viewCount(), "the view identity remains until CLOSE");

        support.player.connection = mock(ServerGamePacketListenerImpl.class);
        support.handshake();
        long newGeneration = connectionGeneration();
        assertTrue(newGeneration > oldGeneration);
        handle(open(PROJECT, 3, 9, 1, InterestKind.MATERIALS.bit()));
        assertTrue(support.interests.interests(support.player.getUUID(), oldGeneration).isEmpty());
        assertEquals(Set.of(InterestKind.MATERIALS), mask(scope));
    }

    // ------------------------------------------------------------------
    // Receiver fences
    // ------------------------------------------------------------------

    @Test
    void receiverGatesProtocolFiveAndDispatchesToOwningContext() {
        var handler = registerProjectViewReceiver();
        ProtocolHandshake.recordHandshake(support.server, support.player, 4, "test");
        sent.clear();
        handler.receive(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()), context());
        assertTrue(sent.isEmpty());
        assertEquals(0, support.views.viewCount());

        support.handshake();
        handler.receive(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()), context());
        assertEquals(ProjectViewS2CPacket.Result.OPENED,
                first(ProjectViewS2CPacket.class).result());
        assertEquals(1, support.views.viewCount());
    }

    @Test
    void queuedCommandCannotRunAfterContextReplacement() {
        var handler = registerProjectViewReceiver();
        support.deferOwnerExecution.set(true);
        handler.receive(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()), context());
        LegacyServerContext replacement = mock(LegacyServerContext.class);
        support.closeOwnerAndInstall(replacement);
        support.flushQueued();
        assertTrue(sent.isEmpty());
        assertEquals(0, support.views.viewCount());
    }

    @Test
    void queuedCommandCannotRunAfterConnectionReplacement() {
        var handler = registerProjectViewReceiver();
        support.deferOwnerExecution.set(true);
        handler.receive(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()), context());
        support.player.connection = mock(ServerGamePacketListenerImpl.class);
        support.flushQueued();
        assertTrue(sent.isEmpty());
        assertEquals(0, support.views.viewCount());
    }

    @Test
    void setInterestsDeliversNewlyAddedTeamConfigAndPickupContent() throws Exception {
        seedWarehouseWithTwoContainers();
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        sent.clear();

        int added = InterestKind.TEAM.bit() | InterestKind.AREA_CONFIG.bit()
                | InterestKind.OUTLINES.bit() | InterestKind.WAREHOUSE_CONTENTS.bit();
        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.SET_INTERESTS, null, 0, 0,
                InterestKind.MATERIALS.bit() | added, Optional.empty(), List.of()));

        assertEquals(1, packets(TeamResponseS2CPacket.class).size());
        assertTrue(packets(ProjectViewS2CPacket.Result.CONFIG).stream()
                .anyMatch(packet -> "LIST".equals(
                        packet.configuration().orElseThrow().action())));
        assertTrue(packets(ProjectViewS2CPacket.Result.WAREHOUSE).stream()
                .anyMatch(packet -> "COMPLETE".equals(packet.message())));
        assertTrue(packets(ProjectViewS2CPacket.Result.PROGRESS_PART).isEmpty(),
                "SET only re-sends newly added interest content");
    }

    @Test
    void lateDisconnectForOldConnectionDoesNotEraseNewView() throws Exception {
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        Object oldConnection = support.player.connection;
        support.player.connection = mock(ServerGamePacketListenerImpl.class);
        support.handshake();
        long newGeneration = connectionGeneration();
        handle(open(PROJECT, 2, 9, 1, InterestKind.MATERIALS.bit()));

        support.projectViews.disconnected(support.player, oldConnection);

        assertTrue(support.views.matches(support.player.getUUID(), newGeneration,
                ProjectViewScope.project(PROJECT), 9, 1),
                "a late old-connection cleanup must not erase the new view");
        support.projectViews.disconnected(support.player, support.player.connection);
        assertEquals(0, support.views.viewCount());
    }

    @Test
    void queuedDisconnectUsesTheCapturedConnectionAndCannotEraseANewerView() throws Exception {
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        support.deferOwnerExecution.set(true);
        ModNetworkHandler.onPlayerDisconnect(support.player);

        support.player.connection = mock(ServerGamePacketListenerImpl.class);
        support.handshake();
        long newGeneration = connectionGeneration();
        handle(open(PROJECT, 2, 9, 1, InterestKind.MATERIALS.bit()));
        support.flushQueued();

        assertTrue(support.views.matches(support.player.getUUID(), newGeneration,
                ProjectViewScope.project(PROJECT), 9, 1),
                "a queued old disconnect must not erase the replacement connection's view");
        assertFalse(support.interests.interests(support.player.getUUID(), newGeneration).isEmpty());
    }

    @Test
    void newGenerationWithZeroMaskClearsPreviousInterests() throws Exception {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.ALL_MASK));
        long oldGeneration = connectionGeneration();
        support.player.connection = mock(ServerGamePacketListenerImpl.class);
        support.handshake();
        long newGeneration = connectionGeneration();

        handle(open(PROJECT, 2, 9, 1, 0));

        assertTrue(support.interests.interests(support.player.getUUID(), oldGeneration).isEmpty());
        assertEquals(0, support.interests.interestCount(),
                "a new generation zero mask must not inherit old interests");
        assertTrue(support.views.matches(support.player.getUUID(), newGeneration, scope, 9, 1));
    }

    @Test
    void joinRecordsOnlyTheTargetObservationAndRejectsCrossProjectReports() throws Exception {
        insertMaterial(2, PROJECT, "minecraft:dirt", 20);
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.JOIN, null, 2, 0, 0, Optional.empty(),
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(2, 7),
                        new ProjectViewCommandC2SPacket.InventoryCount(1, 64))));

        ProjectViewS2CPacket result = last(ProjectViewS2CPacket.Result.OPERATION);
        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals(7, rowCount("SELECT count FROM player_inventories "
                + "WHERE schematic_id = 'p1' AND material_id = 2 AND player_name = 'Player1'"));
        assertEquals(0, rowCount("SELECT count FROM player_inventories "
                + "WHERE schematic_id = 'p1' AND material_id = 1 AND player_name = 'Player1'"),
                "a different reported material must not receive a new observation");

        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 3, VIEW, GENERATION,
                ProjectViewAction.JOIN, null, 2, 0, 0, Optional.empty(),
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(2, 9),
                        new ProjectViewCommandC2SPacket.InventoryCount(99, 1))));
        assertFalse(last(ProjectViewS2CPacket.Result.OPERATION).success());
        assertEquals(7, rowCount("SELECT count FROM player_inventories "
                + "WHERE schematic_id = 'p1' AND material_id = 2 AND player_name = 'Player1'"),
                "cross-project input is rejected before any target update");
    }

    @Test
    void scopedLeaveRemovesOnlyTheTargetParticipationAndObservation() throws Exception {
        insertMaterial(2, PROJECT, "minecraft:dirt", 20);
        insertClaim(2, PROJECT, "Player1");
        collaboration.updatePlayerInventory("Player1", PROJECT, 1, 32);
        collaboration.updatePlayerInventory("Player1", PROJECT, 2, 7);
        support.owner.trackInventory(support.player, PROJECT, 1, 32);
        support.owner.trackInventory(support.player, PROJECT, 2, 7);
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        sent.clear();

        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.LEAVE, null, 1, 0, 0, Optional.empty(), List.of()));

        ProjectViewS2CPacket result = last(ProjectViewS2CPacket.Result.OPERATION);
        assertEquals(2, result.requestId());
        assertEquals(ProjectViewAction.LEAVE, result.action());
        assertTrue(result.success());
        assertTrue(result.changed());
        assertFalse(collaboration.isCollaborating(PROJECT, 1, "Player1"));
        assertTrue(collaboration.isCollaborating(PROJECT, 2, "Player1"));
        assertEquals(0, support.onlineInventories.count(support.player.getUUID(), PROJECT, 1));
        assertEquals(7, support.onlineInventories.count(support.player.getUUID(), PROJECT, 2));
        ProjectProgressSnapshot progress = assemble(PROJECT, VIEW, GENERATION);
        assertTrue(progress.material(1).orElseThrow().participants().isEmpty());
        assertEquals(7, progress.material(2).orElseThrow().participants().getFirst().effectiveHeld());

        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 3, VIEW, GENERATION,
                ProjectViewAction.LEAVE, null, 1, 0, 0, Optional.empty(), List.of()));
        assertTrue(last(ProjectViewS2CPacket.Result.OPERATION).success());
        assertFalse(last(ProjectViewS2CPacket.Result.OPERATION).changed());
    }

    @Test
    void oldViewLeaveCannotRemoveAnExistingParticipation() {
        ProjectViewScope scope = ProjectViewScope.project(PROJECT);
        handle(open(PROJECT, 1, VIEW, GENERATION + 1, InterestKind.MATERIALS.bit()));
        sent.clear();

        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.LEAVE, null, 1, 0, 0, Optional.empty(), List.of()));

        assertTrue(collaboration.isCollaborating(PROJECT, 1, "Player1"));
        ProjectViewS2CPacket rejected = last(ProjectViewS2CPacket.Result.ERROR);
        assertEquals(2, rejected.requestId());
        assertEquals(GENERATION, rejected.generation());
        assertEquals(ProjectViewAction.LEAVE, rejected.action());
        assertFalse(rejected.success());
        assertTrue(packets(ProjectViewS2CPacket.Result.OPERATION).isEmpty());
        assertTrue(packets(ProjectViewS2CPacket.Result.PROGRESS_PART).isEmpty());
    }

    @Test
    void rescanWithNoConfiguredAreasIsAnEmptySuccessNotAnError() throws Exception {
        insertProject("empty", "Empty", "Owner", true);
        ProjectViewScope scope = ProjectViewScope.project("empty");
        handle(open("empty", 1, VIEW, GENERATION, InterestKind.AREA_CONFIG.bit()));
        sent.clear();
        handle(new ProjectViewCommandC2SPacket(scope, 2, VIEW, GENERATION,
                ProjectViewAction.RESCAN, null, 0, 0, 0, Optional.empty(), List.of()));

        ProjectViewS2CPacket result = last(ProjectViewS2CPacket.Result.OPERATION);
        assertTrue(result.success());
        assertEquals("项目没有配置区域", result.message());
        assertTrue(packets(ProjectViewS2CPacket.Result.ERROR).isEmpty());
    }

    @Test
    void disconnectWithClearedHandlerCleansTheOldViewInventoryAndHandshake() {
        handle(open(PROJECT, 1, VIEW, GENERATION, InterestKind.MATERIALS.bit()));
        support.owner.trackInventory(support.player, PROJECT, 1, 64);
        support.player.connection = null;

        ModNetworkHandler.onPlayerDisconnect(support.player);

        assertEquals(0, support.views.viewCount());
        assertEquals(0, support.onlineInventories.count(support.player.getUUID(), PROJECT, 1));
        assertEquals(0, ProtocolHandshake.getVersion(support.player));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ServerPlayNetworking.PlayPayloadHandler<ProjectViewCommandC2SPacket>
            registerProjectViewReceiver() {
        var handlers = new java.util.HashMap<CustomPacketPayload.Type<?>,
                ServerPlayNetworking.PlayPayloadHandler<?>>();
        networking.when(() -> ServerPlayNetworking.registerGlobalReceiver(any(), any()))
                .thenAnswer(invocation -> {
                    handlers.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                });
        ModNetworkHandler.registerReceiversForTests();
        @SuppressWarnings("unchecked")
        var handler = (ServerPlayNetworking.PlayPayloadHandler<ProjectViewCommandC2SPacket>)
                handlers.get(ProjectViewCommandC2SPacket.ID);
        assertNotNull(handler);
        return handler;
    }

    private ServerPlayNetworking.Context context() {
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(support.server);
        when(context.player()).thenReturn(support.player);
        return context;
    }

    private void handle(ProjectViewCommandC2SPacket command) {
        support.projectViews.handle(command, support.player, support.player.connection,
                support.server);
    }

    private long connectionGeneration() {
        return support.teamRequests.connection(support.player.getUUID(), support.player.connection);
    }

    private Set<InterestKind> mask(ProjectViewScope scope) {
        Set<InterestKind> kinds = EnumSet.noneOf(InterestKind.class);
        for (var view : support.views.views(scope, InterestKind.MATERIALS)) kinds.add(InterestKind.MATERIALS);
        for (var view : support.views.views(scope, InterestKind.TEAM)) kinds.add(InterestKind.TEAM);
        for (var view : support.views.views(scope, InterestKind.AREA_CONFIG)) kinds.add(InterestKind.AREA_CONFIG);
        for (var view : support.views.views(scope, InterestKind.OUTLINES)) kinds.add(InterestKind.OUTLINES);
        for (var view : support.views.views(scope, InterestKind.WAREHOUSE_CONTENTS)) kinds.add(InterestKind.WAREHOUSE_CONTENTS);
        return kinds;
    }

    private ProjectProgressSnapshot assemble(String projectId, long viewId, long generation) {
        return assembler(projectId, viewId, generation).view().snapshot();
    }

    private void installProgress(ClientProjectViewStore store, String projectId, long viewId,
                                 long generation) {
        assertTrue(store.install(assembler(projectId, viewId, generation).view()));
    }

    private ProjectViewAssembler.Complete assembler(String projectId, long viewId,
                                                     long generation) {
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        long connectionGeneration = connectionGeneration();
        assertTrue(assembler.openView(support.player.connection, connectionGeneration,
                ProjectViewScope.project(projectId), viewId, generation));
        ProjectViewAssembler.Outcome outcome = null;
        for (ProjectViewS2CPacket part : packets(ProjectViewS2CPacket.Result.PROGRESS_PART)) {
            outcome = assembler.accept(support.player.connection, connectionGeneration, part);
        }
        return assertInstanceOf(ProjectViewAssembler.Complete.class, outcome);
    }

    private static ProgressParticipant participant(ProjectProgressSnapshot snapshot, String name) {
        return snapshot.material(1).orElseThrow().participants().stream()
                .filter(entry -> entry.player().name().equals(name))
                .findFirst().orElseThrow();
    }

    private void feedStructured(ClientProjectViewStore store, List<ProjectViewS2CPacket> packets) {
        for (ProjectViewS2CPacket packet : packets) {
            if (packet.result() == ProjectViewS2CPacket.Result.CONFIG) {
                assertTrue(store.configuration(packet.scope(), packet.viewId(), packet.generation(),
                        packet.sequence(), packet.configuration().orElseThrow().action(),
                        packet.configuration().orElseThrow().areas()));
            }
        }
    }

    private void feedWarehouse(ClientProjectViewStore store, List<ProjectViewS2CPacket> packets) {
        for (ProjectViewS2CPacket packet : packets) {
            if (packet.result() != ProjectViewS2CPacket.Result.WAREHOUSE) continue;
            boolean complete = packet.warehouse().isEmpty() && "COMPLETE".equals(packet.message());
            assertTrue(store.warehouse(packet.scope(), packet.viewId(), packet.generation(),
                    packet.sequence(), packet.warehouse(), complete));
        }
    }

    private List<ProjectViewS2CPacket> packets(ProjectViewS2CPacket.Result result) {
        List<ProjectViewS2CPacket> packets = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (payload instanceof ProjectViewS2CPacket packet && packet.result() == result) {
                packets.add(packet);
            }
        }
        return packets;
    }

    private <T extends CustomPacketPayload> List<T> packets(Class<T> type) {
        List<T> packets = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (type.isInstance(payload)) packets.add(type.cast(payload));
        }
        return packets;
    }

    private <T extends CustomPacketPayload> T first(Class<T> type) {
        return packets(type).getFirst();
    }

    private ProjectViewS2CPacket last(ProjectViewS2CPacket.Result result) {
        List<ProjectViewS2CPacket> packets = packets(result);
        assertFalse(packets.isEmpty(), "未发送结果 " + result);
        return packets.getLast();
    }

    private static ProjectViewCommandC2SPacket open(String projectId, long requestId, long viewId,
                                                     long generation, int mask) {
        return new ProjectViewCommandC2SPacket(ProjectViewScope.project(projectId), requestId,
                viewId, generation, ProjectViewAction.OPEN, null, 0, 0, mask,
                Optional.empty(), List.of());
    }

    private static StagingAreaConfigC2SPacket.AreaData area(String name, int x1, int y1, int z1,
                                                             int x2, int y2, int z2,
                                                             Optional<String> world) {
        return new StagingAreaConfigC2SPacket.AreaData(name, x1, y1, z1, x2, y2, z2, world);
    }

    private void insertProject(String id, String name, String uploadedBy, boolean selfClaim)
            throws SQLException {
        database.executeUpdate("INSERT INTO schematics "
                        + "(id, name, file_path, uploaded_by, allow_self_claim) VALUES (?, ?, ?, ?, ?)",
                id, name, "test/" + id + ".litematic", uploadedBy, selfClaim ? 1 : 0);
    }

    private void insertMaterial(int id, String projectId, String itemId, long count)
            throws SQLException {
        database.executeUpdate("INSERT INTO material_entries "
                + "(id, schematic_id, item_id, count) VALUES (?, ?, ?, ?)",
                id, projectId, itemId, count);
    }

    private void insertClaim(int materialId, String projectId, String playerName)
            throws SQLException {
        database.executeUpdate("INSERT INTO claims "
                        + "(schematic_id, material_id, player_name, status) "
                        + "VALUES (?, ?, ?, 'active')",
                projectId, materialId, playerName);
    }

    private void insertStagingArea(int id, String projectId, String name, int x1, int y1, int z1,
                                   int x2, int y2, int z2) throws SQLException {
        database.executeUpdate("INSERT INTO staging_areas "
                        + "(id, schematic_id, world, name, x1, y1, z1, x2, y2, z2) "
                        + "VALUES (?, ?, 'minecraft:overworld', ?, ?, ?, ?, ?, ?, ?)",
                id, projectId, name, x1, y1, z1, x2, y2, z2);
    }

    private void insertStagingInventory(int areaId, String itemId, long count) throws SQLException {
        database.executeUpdate("INSERT INTO staging_area_inventory "
                + "(staging_area_id, item_id, count) VALUES (?, ?, ?)", areaId, itemId, count);
    }

    private void insertContainer(String type, int areaId, int x, int y, int z, String itemId)
            throws SQLException {
        database.executeUpdate("INSERT INTO container_inventory "
                        + "(area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                areaId, type, x, y, z, itemId);
    }

    private List<int[]> seedWarehouseWithTwoContainers() throws Exception {
        database.executeUpdate("INSERT INTO warehouses "
                + "(id, name, world, x1, y1, z1, x2, y2, z2) "
                + "VALUES (1, 'Warehouse', 'minecraft:overworld', 0, 0, 0, 15, 15, 15)");
        database.executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) "
                + "VALUES (?, 1)", PROJECT);
        insertContainer("warehouse", 1, 2, 64, 2, "minecraft:stone");
        insertContainer("warehouse", 1, 3, 64, 3, "minecraft:diamond");
        staging.loadWarehousesFromDb();
        markWarehouseCurrent(1);
        return List.of(new int[]{2, 64, 2}, new int[]{3, 64, 3});
    }

    @SuppressWarnings("unchecked")
    private void markWarehouseCurrent(int id) throws Exception {
        java.lang.reflect.Field initialized = StagingAreaManager.class
                .getDeclaredField("initializedAreas");
        initialized.setAccessible(true);
        ((Set<String>) initialized.get(staging)).add("W:" + id);
        java.lang.reflect.Field incomplete = StagingAreaManager.class
                .getDeclaredField("incompleteScans");
        incomplete.setAccessible(true);
        ((Set<String>) incomplete.get(staging)).remove("W:" + id);
    }

    private long rowCount(String sql) throws SQLException {
        try (var rows = database.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }
}
