package net.syncmaterial.syncmaterial.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDatabaseBackupTest {
    @TempDir
    Path directory;

    @Test
    void preservesDatabaseAndSidecarsWithoutOpeningOrChangingThem() throws Exception {
        Path database = directory.resolve("syncmaterial.db");
        byte[] bytes = {1, 2, 3, 4};
        Files.write(database, bytes);
        Files.write(directory.resolve("syncmaterial.db-wal"), new byte[]{5, 6});
        Files.write(directory.resolve("syncmaterial.db-shm"), new byte[]{7});
        Path backup = LegacyDatabaseBackup.preserve(database, directory.resolve("backups"));
        assertNotEquals(database.getParent(), backup);
        assertArrayEquals(bytes, Files.readAllBytes(database));
        assertArrayEquals(bytes, Files.readAllBytes(backup.resolve("syncmaterial.db")));
        assertArrayEquals(new byte[]{5, 6}, Files.readAllBytes(backup.resolve("syncmaterial.db-wal")));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(backup.resolve("syncmaterial.db-shm")));
        assertTrue(Files.exists(directory.resolve("syncmaterial.db-wal")));
    }

    @Test
    void missingLegacyDatabaseDoesNotCreateBackupDirectory() throws Exception {
        Path root = directory.resolve("backups");
        assertNull(LegacyDatabaseBackup.preserve(directory.resolve("syncmaterial.db"), root));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(root));
    }
}
