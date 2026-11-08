package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void newRuntimeDoesNotOpenOrModifyTheLegacyDatabase() throws Exception {
        Path game = Files.createDirectory(directory.resolve("game"));
        byte[] legacy = {9, 8, 7, 6};
        Files.write(game.resolve("syncmaterial.db"), legacy);
        Path world = directory.resolve("world");
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            runtime.ingest(metadata("project", "hash")).get(5, TimeUnit.SECONDS);
            assertTrue(Files.exists(world.resolve("syncmaterial/runtime-v2.sqlite")));
            assertArrayEquals(legacy, Files.readAllBytes(game.resolve("syncmaterial.db")));
            assertEquals(1, runtime.store().listProjects().get().size());
        }
        assertArrayEquals(legacy, Files.readAllBytes(game.resolve("syncmaterial.db")));
    }

    @Test
    void closeAndReopenHaveIndependentRuntimeAndInventoryState() throws Exception {
        Path world = directory.resolve("world");
        Path game = Files.createDirectory(directory.resolve("game"));
        UUID epoch;
        UUID player = UUID.randomUUID();
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            epoch = runtime.epoch();
            runtime.connected(player);
            runtime.subscribe(player, "project");
            assertEquals(1, runtime.subscribers("project").size());
        }
        try (ServerRuntime runtime = ServerRuntime.open(world, game,
                path -> List.of(new MaterialCount("minecraft:stone", 64)), id -> {})) {
            assertFalse(epoch.equals(runtime.epoch()));
            assertTrue(runtime.subscribers("project").isEmpty());
            assertEquals(0, runtime.inventoryCount(player, "project", 1));
        }
    }

    private SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Project", directory.resolve(hash + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }
}
