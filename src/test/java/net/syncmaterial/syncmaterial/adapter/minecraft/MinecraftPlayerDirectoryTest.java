package net.syncmaterial.syncmaterial.adapter.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftPlayerDirectoryTest {
    @TempDir Path temporary;

    @Test
    void savedCacheIdentityIsReadWithoutAccountLookupOrInventedUuid() throws Exception {
        UUID id = UUID.randomUUID();
        Path cache = write("[{\"name\":\"Known\",\"uuid\":\"" + id + "\"},"
                + "{\"name\":\"Offline\"},{\"name\":\"BadUuid\",\"uuid\":\"not-a-uuid\"}]");
        var players = MinecraftPlayerDirectory.readCache(cache);
        assertEquals(3, players.size());
        assertEquals(id, players.getFirst().uuid());
        assertNull(players.get(1).uuid());
        assertNull(players.get(2).uuid());
    }

    @Test
    void repeatedNamesDoNotCollapseDifferentRealPlayers() throws Exception {
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        var players = MinecraftPlayerDirectory.readCache(write("[{\"name\":\"Same\",\"uuid\":\"" + left
                + "\"},{\"name\":\"Same\",\"uuid\":\"" + right + "\"}]"));
        assertEquals(2, players.size());
        assertNotEquals(players.get(0).uuid(), players.get(1).uuid());
    }

    @Test
    void malformedRootAndTrailingDataFailWithoutPartialDirectory() throws Exception {
        assertThrows(java.io.IOException.class, () -> MinecraftPlayerDirectory.readCache(write("[] []")));
        assertThrows(IllegalStateException.class, () -> MinecraftPlayerDirectory.readCache(write("{}")));
    }

    @Test
    void missingCacheIsEmptyAndDoesNotCreateFile() throws Exception {
        Path missing = temporary.resolve("missing.json");
        assertTrue(MinecraftPlayerDirectory.readCache(missing).isEmpty());
        assertFalse(Files.exists(missing));
    }

    @Test
    void invalidEntryIsSkippedButUsefulOfflineNameSurvives() throws Exception {
        var players = MinecraftPlayerDirectory.readCache(write("[null,5,{\"name\":\"\"},{\"name\":\"Offline\",\"expiresOn\":\"old\"}]"));
        assertEquals(1, players.size());
        assertEquals("Offline", players.getFirst().name());
        assertNull(players.getFirst().uuid());
    }

    private Path write(String content) throws Exception {
        Path cache = temporary.resolve("usercache.json");
        Files.writeString(cache, content);
        return cache;
    }
}
