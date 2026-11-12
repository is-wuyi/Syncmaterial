package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyServerContextTest {
    @TempDir Path temporary;
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @AfterEach
    void close() {
        fixtures.forEach(fixture -> LegacyServerRegistry.close(fixture.server));
    }

    @Test
    void ownsOnlyOriginalWorld() {
        Fixture a = fixture();
        Fixture b = fixture();
        assertTrue(a.owner.owns(a.world));
        assertFalse(a.owner.owns(b.world));
        assertFalse(a.owner.owns(null));
        a.owner.stop();
        assertFalse(a.owner.owns(a.world));
    }

    @Test
    void queuedTaskCannotRunAfterStopAndNewServer() {
        Fixture a = fixture();
        AtomicInteger effects = new AtomicInteger();
        a.owner.execute(effects::incrementAndGet);
        assertEquals(1, a.queued.size());
        LegacyServerRegistry.close(a.server);
        Fixture b = fixture();
        a.queued.forEach(Runnable::run);
        assertEquals(0, effects.get());
        assertTrue(b.owner.isCurrent());
        verifyNoInteractions(b.staging);
    }

    @Test
    void connectionReplacementRejectsQueuedPlayerTask() {
        Fixture fixture = fixture();
        AtomicInteger effects = new AtomicInteger();
        fixture.owner.execute(fixture.player, effects::incrementAndGet);
        fixture.player.connection = mock(ServerGamePacketListenerImpl.class);
        fixture.queued.forEach(Runnable::run);
        assertEquals(0, effects.get());
    }

    @Test
    void missingCurrentPlayerRejectsTaskBeforeQueue() {
        Fixture fixture = fixture();
        when(fixture.server.getPlayerList().getPlayer(fixture.player.getUUID())).thenReturn(null);
        fixture.owner.execute(fixture.player, () -> fail("disconnected player task ran"));
        assertTrue(fixture.queued.isEmpty());
    }

    @Test
    void dirtyTickCadenceIsPerContext() {
        Fixture a = fixture();
        Fixture b = fixture();
        for (int tick = 0; tick < 3; tick++) a.owner.tick();
        verify(a.staging, never()).processDirtyContainers();
        a.owner.tick();
        verify(a.staging).processDirtyContainers();
        for (int tick = 0; tick < 8; tick++) b.owner.tick();
        verify(b.staging, times(2)).processDirtyContainers();
        verify(a.staging, times(1)).processDirtyContainers();
    }

    @Test
    void stoppingAndClosingAreIdempotentAndDatabaseIsLast() {
        Fixture fixture = fixture();
        fixture.owner.stop();
        fixture.owner.stop();
        fixture.owner.close();
        fixture.owner.close();
        var order = inOrder(fixture.database, fixture.staging, fixture.parser);
        order.verify(fixture.database).seal();
        order.verify(fixture.staging).close();
        order.verify(fixture.parser).close();
        order.verify(fixture.database).close();
        verify(fixture.database, times(1)).seal();
        verify(fixture.database, times(1)).close();
        assertEquals(LegacyServerContext.State.CLOSED, fixture.owner.state());
    }

    @Test
    void cleanupFailureDoesNotSkipOtherResources() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("staging failure")).when(fixture.staging).close();
        fixture.owner.close();
        verify(fixture.parser).close();
        verify(fixture.database).close();
        assertFalse(fixture.owner.isOpen());
    }

    @Test
    void uniqueCompatibilityContextFailsClosedWhenAmbiguous() {
        Fixture a = fixture();
        assertSame(a.owner, LegacyServerRegistry.onlyOpenContext());
        Fixture b = fixture();
        assertNull(LegacyServerRegistry.onlyOpenContext());
        a.owner.stop();
        assertSame(b.owner, LegacyServerRegistry.onlyOpenContext());
    }

    @Test
    void duplicateInstallClosesOnlyRejectedContext() {
        Fixture fixture = fixture();
        SchematicDatabase otherDb = mock(SchematicDatabase.class);
        LegacyServerContext duplicate = new LegacyServerContext(fixture.server,
                temporary.resolve("duplicate"), temporary, otherDb, mock(DatabaseQueryService.class),
                mock(LitematicaParser.class), mock(CollaborationManager.class),
                mock(StagingAreaManager.class), System::nanoTime);
        assertThrows(IllegalStateException.class, () -> LegacyServerRegistry.install(duplicate));
        assertSame(fixture.owner, LegacyServerRegistry.get(fixture.server));
        assertTrue(fixture.owner.isCurrent());
        verify(otherDb).close();
        verify(fixture.database, never()).close();
    }

    @Test
    void placementCacheIsIndependentAndClearedByStop() {
        Fixture a = fixture();
        Fixture b = fixture();
        a.owner.setPlacementName("same", "A");
        b.owner.setPlacementName("same", "B");
        assertEquals("A", a.owner.placementNames().get("same"));
        assertEquals("B", b.owner.placementNames().get("same"));
        assertThrows(UnsupportedOperationException.class, () -> a.owner.placementNames().put("extra", "no"));
        a.owner.stop();
        a.owner.setPlacementName("later", "invalid");
        assertTrue(a.owner.placementNames().isEmpty());
        assertEquals("B", b.owner.placementNames().get("same"));
    }

    @Test
    void failedInitializationClosesPartialDatabaseWithoutStartingParser() {
        SchematicDatabase database = mock(SchematicDatabase.class);
        doThrow(new IllegalStateException("initialize failed")).when(database).initialize();
        AtomicInteger created = new AtomicInteger();
        MinecraftServer server = mock(MinecraftServer.class);
        assertThrows(IllegalStateException.class, () -> LegacyServerContext.open(server, temporary, temporary,
                database, () -> {
                    created.incrementAndGet();
                    return mock(LitematicaParser.class);
                }));
        assertEquals(0, created.get());
        verify(database).close();
        assertNull(LegacyServerRegistry.get(server));
    }

    @Test
    void parserCreationFailureClosesDatabaseAndDoesNotInstallContext() {
        SchematicDatabase database = mock(SchematicDatabase.class);
        MinecraftServer server = mock(MinecraftServer.class);
        assertThrows(IllegalStateException.class, () -> LegacyServerContext.open(server, temporary, temporary,
                database, () -> { throw new IllegalStateException("parser failed"); }));
        verify(database).initialize();
        verify(database).close();
        assertNull(LegacyServerRegistry.get(server));
    }

    @Test
    void publicationChangedDuringWarehouseScanIsRejected() {
        Fixture fixture = fixture();
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(fixture.query.schematicExists("project")).thenReturn(true);
        when(fixture.staging.getStagingAreas("project")).thenReturn(List.of());
        when(fixture.staging.getWarehousesForSchematic("project")).thenReturn(List.of(
                new StagingAreaManager.Warehouse(1, "warehouse", "minecraft:overworld", 0, 0, 0, 0, 0, 0)));
        doAnswer(ignored -> {
            current.set(false);
            return null;
        }).when(fixture.staging).rescanWarehouseAndMarkChunks(1);
        fixture.owner.schematicChanged("project", current::get);
        fixture.queued.forEach(Runnable::run);
        verify(fixture.staging).rescanWarehouseAndMarkChunks(1);
        verify(fixture.staging, never()).broadcastUpdate(anyString());
        verify(fixture.collaboration, never()).getAllMaterialIds(anyString());
    }

    @Test
    void removalPredicateRejectsLateCacheAndSubscriptionMutation() {
        Fixture fixture = fixture();
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.owner.setPlacementName("project", "current");
        fixture.owner.subscriptions().subscribeMaterials(fixture.player, "project");
        fixture.owner.schematicRemoved("project", current::get);
        current.set(false);
        fixture.queued.forEach(Runnable::run);
        assertEquals("current", fixture.owner.placementNames().get("project"));
        assertEquals(java.util.Set.of("project"), fixture.owner.subscriptions().projects(fixture.player));
        verify(fixture.staging, never()).forgetProject(anyString());
    }

    @Test
    void concurrentCloseReportsClosedOnlyAfterDatabaseRelease() throws Exception {
        Fixture fixture = fixture();
        java.util.concurrent.CountDownLatch closingDatabase = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseDatabase = new java.util.concurrent.CountDownLatch(1);
        doAnswer(ignored -> {
            closingDatabase.countDown();
            if (!releaseDatabase.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("database release blocked");
            return null;
        }).when(fixture.database).close();
        Thread first = new Thread(fixture.owner::close);
        Thread second = new Thread(fixture.owner::close);
        first.start();
        try {
            assertTrue(closingDatabase.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(LegacyServerContext.State.STOPPING, fixture.owner.state());
            assertFalse(fixture.owner.whenClosed().isDone());
            second.start();
            assertFalse(fixture.owner.whenClosed().isDone());
            releaseDatabase.countDown();
            fixture.owner.whenClosed().get(5, java.util.concurrent.TimeUnit.SECONDS);
            first.join(5000);
            second.join(5000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertEquals(LegacyServerContext.State.CLOSED, fixture.owner.state());
            verify(fixture.database, times(1)).close();
        } finally {
            releaseDatabase.countDown();
            first.join(5000);
            if (second.getState() != Thread.State.NEW) second.join(5000);
        }
    }

    @Test
    void installingSameContextTwiceIsIdempotent() {
        Fixture fixture = fixture();
        LegacyServerRegistry.install(fixture.owner);
        assertSame(fixture.owner, LegacyServerRegistry.get(fixture.server));
        assertTrue(fixture.owner.isCurrent());
        verify(fixture.database, never()).seal();
        verify(fixture.database, never()).close();
    }

    private Fixture fixture() {
        Fixture fixture = new Fixture();
        fixtures.add(fixture);
        LegacyServerRegistry.install(fixture.owner);
        clearInvocations(fixture.staging, fixture.collaboration);
        return fixture;
    }

    private final class Fixture {
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel world = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final SchematicDatabase database = mock(SchematicDatabase.class);
        private final DatabaseQueryService query = mock(DatabaseQueryService.class);
        private final LitematicaParser parser = mock(LitematicaParser.class);
        private final CollaborationManager collaboration = mock(CollaborationManager.class);
        private final StagingAreaManager staging = mock(StagingAreaManager.class);
        private final List<Runnable> queued = new ArrayList<>();
        private final AtomicLong clock = new AtomicLong();
        private final LegacyServerContext owner;

        private Fixture() {
            UUID id = UUID.randomUUID();
            when(world.getServer()).thenReturn(server);
            when(player.level()).thenReturn(world);
            when(player.getUUID()).thenReturn(id);
            when(player.getName()).thenReturn(Component.literal("Player"));
            player.connection = mock(ServerGamePacketListenerImpl.class);
            PlayerList players = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayer(id)).thenReturn(player);
            when(players.getPlayers()).thenReturn(List.of(player));
            when(parser.parseAsync(anyString())).thenReturn(new CompletableFuture<>());
            when(staging.getAllWarehouses()).thenReturn(List.of());
            doAnswer(invocation -> {
                queued.add(invocation.getArgument(0));
                return null;
            }).when(server).execute(any(Runnable.class));
            owner = new LegacyServerContext(server, temporary.resolve(id.toString()), temporary,
                    database, query, parser, collaboration, staging, clock::get);
        }
    }
}
