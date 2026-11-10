package net.syncmaterial.syncmaterial.server.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectState;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-schema migration tests. The v1 fixture is produced by the current store and
 * then stripped back to version 1 (no shadow DDL), so this file cannot silently
 * drift from {@link StoreSchema}'s actual table definitions.
 */
class SchemaMigrationTest {
    private static final String STONE = "minecraft:stone";
    private static final PlayerIdentity ALICE =
            new PlayerIdentity(UUID.nameUUIDFromBytes("Alice".getBytes(StandardCharsets.UTF_8)), "Alice");

    @TempDir
    Path directory;

    @Test
    void v1MigratesToCurrentPreservesIdsClaimsAndKeepsAConsistentBackup() throws Exception {
        Path db = directory.resolve("runtime-v2.sqlite");
        int originalMaterialId = createV1Fixture(db);

        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            assertEquals(StoreSchema.SCHEMA_VERSION, readVersion(db));
            ProjectState state = await(store.snapshot("p1")).orElseThrow();
            assertEquals("unversioned", state.project().interpretationId());
            assertEquals(originalMaterialId, state.project().materials().get(0).id());
            assertEquals(10, state.project().materials().get(0).required());
            assertEquals(1, state.claims().size());
            assertEquals("Alice", state.claims().get(0).player().name());
        }

        Path backups = directory.resolve("migration-backups");
        assertTrue(Files.isDirectory(backups), "migration must snapshot before altering v1");
        List<Path> backupDatabases;
        try (var entries = Files.walk(backups)) {
            backupDatabases = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("runtime-v2.sqlite"))
                    .toList();
        }
        assertEquals(1, backupDatabases.size());
        assertBackupIsV1WithClaim(backupDatabases.get(0));

        // Reopening a migrated store must be stable and must not create another backup.
        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            ProjectState state = await(store.snapshot("p1")).orElseThrow();
            assertEquals(originalMaterialId, state.project().materials().get(0).id());
            assertEquals(1, state.claims().size());
            assertEquals(StoreSchema.SCHEMA_VERSION, readVersion(db));
        }
        try (var entries = Files.walk(backups)) {
            assertEquals(1, entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("runtime-v2.sqlite"))
                    .count());
        }
    }

    @Test
    void failedV1ToV2MigrationRollsBackColumnAndUserVersion() throws Exception {
        Path db = directory.resolve("rollback.sqlite");
        createV1Fixture(db);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThrows(IllegalStateException.class,
                    () -> StoreSchema.migrateSchema(connection, () -> {
                        throw new IllegalStateException("injected migration failure");
                    }));

            assertEquals(1, StoreSchema.readUserVersion(connection));
            assertFalse(hasColumn(connection, "projects", "interpretation_id"));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM projects")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }

            // The same connection can retry and commit normally.
            StoreSchema.migrateSchema(connection);
            assertEquals(StoreSchema.SCHEMA_VERSION, StoreSchema.readUserVersion(connection));
            assertTrue(hasColumn(connection, "projects", "interpretation_id"));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT interpretation_id FROM projects WHERE id = 'p1'")) {
                assertTrue(resultSet.next());
                assertEquals("unversioned", resultSet.getString(1));
            }
        }
    }

    @Test
    void migrationHelperRefusesHigherVersionInsteadOfDowngrading() throws Exception {
        Path db = directory.resolve("future.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY)");
            statement.execute("PRAGMA user_version=" + (StoreSchema.SCHEMA_VERSION + 1));
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> StoreSchema.migrateSchema(connection));
            assertTrue(failure.getMessage().contains("refusing to migrate schema"));
            assertEquals(StoreSchema.SCHEMA_VERSION + 1, StoreSchema.readUserVersion(connection));
        }
    }

    @Test
    void backupSeesWalOnlySchemaAndRowsWithoutChangingSourceFiles() throws Exception {
        Path db = directory.resolve("wal-only.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement statement = writer.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA wal_autocheckpoint=0");
                statement.execute("CREATE TABLE wal_probe (id TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("INSERT INTO wal_probe VALUES ('p1', 'only-in-wal')");
                statement.execute("PRAGMA user_version=1");
            }
            Path wal = directory.resolve("wal-only.sqlite-wal");
            assertTrue(Files.exists(wal), "fixture must keep data outside the main DB file");
            assertTrue(Files.size(wal) > 0);

            assertEquals(1, StoreSchema.readUserVersionReadOnly(db),
                    "read-only config connection must see the WAL user_version");

            byte[] dbBefore = digest(db);
            byte[] walBefore = digest(wal);
            Path backup = StoreSchema.backupBeforeMigration(db, 1);

            assertArrayEquals(dbBefore, digest(db), "backup must not modify the source DB");
            assertArrayEquals(walBefore, digest(wal), "backup must not modify the source WAL");

            SQLiteConfig readOnly = new SQLiteConfig();
            readOnly.setReadOnly(true);
            try (Connection connection = readOnly.createConnection("jdbc:sqlite:" + backup);
                 Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                }
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT value FROM wal_probe WHERE id = 'p1'")) {
                    assertTrue(resultSet.next());
                    assertEquals("only-in-wal", resultSet.getString(1));
                }
            }
        }
    }

    private int createV1Fixture(Path db) throws Exception {
        int materialId;
        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 10))));
            ProjectSnapshot project = await(store.findProject("p1")).orElseThrow();
            materialId = project.materials().get(0).id();
            assertTrue(await(store.join("p1", materialId, ALICE)));
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE projects DROP COLUMN interpretation_id");
            statement.execute("PRAGMA user_version=1");
        }
        return materialId;
    }

    private static void assertBackupIsV1WithClaim(Path backup) throws Exception {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = readOnly.createConnection("jdbc:sqlite:" + backup);
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT p.id, m.item_id, c.material_id "
                            + "FROM projects p "
                            + "JOIN materials m ON m.project_id = p.id "
                            + "JOIN material_claims c ON c.project_id = p.id AND c.material_id = m.id")) {
                assertTrue(resultSet.next(), "backup must contain project, material and claim rows");
                assertEquals("p1", resultSet.getString(1));
                assertEquals(STONE, resultSet.getString(2));
                assertTrue(resultSet.getInt(3) > 0);
            }
        }
    }

    private static int readVersion(Path db) throws Exception {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = readOnly.createConnection("jdbc:sqlite:" + db)) {
            return StoreSchema.readUserVersion(connection);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString(2))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static byte[] digest(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static SchematicMetadata metadata(String id, String hash) {
        return new SchematicMetadata(id, "Project " + id,
                Path.of("/schematics", id + ".litematic"), hash,
                new PlayerIdentity(null, "Owner"));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
