package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyStagingOwnershipTest {
    @TempDir Path temporary;
    private LegacyServerContext owner;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @AfterEach
    void close() {
        if (owner != null) LegacyServerRegistry.close(owner.server());
    }

    @Test
    void wrongServerRejectedBeforeReadingWorldChunkOrDatabase() {
        SchematicDatabase database = mock(SchematicDatabase.class);
        MinecraftServer server = mock(MinecraftServer.class);
        StagingAreaManager manager = manager(server, database);
        ServerLevel world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        LevelChunk chunk = mock(LevelChunk.class);
        BlockPos position = new BlockPos(0, 64, 0);
        clearInvocations(database);
        manager.scheduleContainerScan(position, world);
        manager.onContainerRemoved(position, world);
        assertFalse(manager.isInAnyContainerArea(position, world));
        manager.scanChunkForContainerAreas(chunk, world);
        manager.processDirtyContainers();
        verifyNoInteractions(database, chunk);
        verify(world, never()).dimension();
        verify(world, never()).getBlockEntity(any(BlockPos.class));
        verify(server, never()).execute(any(Runnable.class));
    }

    @Test
    void closingRejectsLateWorldAndDatabaseAccess() {
        SchematicDatabase database = mock(SchematicDatabase.class);
        MinecraftServer server = mock(MinecraftServer.class);
        StagingAreaManager manager = manager(server, database);
        ServerLevel world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        LevelChunk chunk = mock(LevelChunk.class);
        owner.stop();
        clearInvocations(database);
        manager.scheduleContainerScan(BlockPos.ZERO, world);
        manager.onContainerRemoved(BlockPos.ZERO, world);
        manager.scanChunkForContainerAreas(chunk, world);
        manager.rescanStagingArea(1);
        manager.rescanWarehouseAndMarkChunks(1);
        manager.processDirtyContainers();
        verifyNoInteractions(database, chunk);
        verify(world, never()).dimension();
        verify(server, never()).execute(any(Runnable.class));
    }

    @Test
    void managerCannotRebindToAnotherServerOrAfterClose() {
        SchematicDatabase database = mock(SchematicDatabase.class);
        MinecraftServer server = mock(MinecraftServer.class);
        StagingAreaManager manager = manager(server, database);
        assertSame(owner, manager.owner());
        assertThrows(IllegalStateException.class, () -> manager.setServer(mock(MinecraftServer.class)));
        manager.close();
        assertThrows(IllegalStateException.class, () -> manager.setServer(server));
    }

    private StagingAreaManager manager(MinecraftServer server, SchematicDatabase database) {
        StagingAreaManager manager = new StagingAreaManager(database);
        owner = new LegacyServerContext(server, temporary.resolve(UUID.randomUUID().toString()), temporary,
                database, mock(DatabaseQueryService.class), mock(LitematicaParser.class),
                mock(CollaborationManager.class), manager, System::nanoTime);
        LegacyServerRegistry.install(owner);
        return manager;
    }
}
