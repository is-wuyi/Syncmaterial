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
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
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
 * Real-schema migration tests. Historic v1/v2 fixtures are produced by the current store
 * and then stripped back with {@link HistoricSchemaFixtures#revertToV2(Connection)} (no shadow
 * guessed DDL), so this file cannot silently drift from the actual production table
 * definitions.
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
    void failedV1ToCurrentMigrationRollsBackColumnTablesAndUserVersion() throws Exception {
        Path db = directory.resolve("rollback.sqlite");
        createV1Fixture(db);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThrows(IllegalStateException.class,
                    () -> StoreSchema.migrateSchema(connection, () -> {
                        throw new IllegalStateException("injected migration failure");
                    }));

            assertEquals(1, StoreSchema.readUserVersion(connection));
            assertFalse(hasColumn(connection, "projects", "interpretation_id"));
            assertFalse(hasColumn(connection, "areas", "revision"));
            assertFalse(hasColumn(connection, "areas", "geometry_revision"));
            assertFalse(tableExists(connection, "area_chunks"));
            assertFalse(tableExists(connection, "store_state"));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM projects")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }

            // The same connection can retry and commit normally.
            StoreSchema.migrateSchema(connection);
            assertEquals(StoreSchema.SCHEMA_VERSION, StoreSchema.readUserVersion(connection));
            assertTrue(hasColumn(connection, "projects", "interpretation_id"));
            assertTrue(hasColumn(connection, "areas", "revision"));
            assertTrue(hasColumn(connection, "areas", "geometry_revision"));
            assertTrue(tableExists(connection, "area_chunks"));
            assertTrue(tableExists(connection, "store_state"));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT interpretation_id FROM projects WHERE id = 'p1'")) {
                assertTrue(resultSet.next());
                assertEquals("unversioned", resultSet.getString(1));
            }
        }
    }

    @Test
    void v2MigratesToCurrentPreservingAreasReferencesAndBacksUpRealV2Schema() throws Exception {
        Path db = directory.resolve("v2.sqlite");
        createV2Fixture(db);

        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            assertEquals(StoreSchema.SCHEMA_VERSION, readVersion(db));
            var catalog = await(store.areaCatalog());
            assertEquals(2, catalog.areas().size());
            AreaDefinition staging = catalog.areas().stream()
                    .filter(area -> area.kind() == AreaKind.STAGING_AREA)
                    .findFirst().orElseThrow();
            AreaDefinition warehouse = catalog.areas().stream()
                    .filter(area -> area.kind() == AreaKind.WAREHOUSE)
                    .findFirst().orElseThrow();
            assertEquals(0, staging.revision());
            assertEquals(0, staging.geometryRevision());
            assertEquals(0, warehouse.revision());
            assertEquals(0, warehouse.geometryRevision());
            assertEquals(Set.of(warehouse.id()), catalog.referencedWarehouseIds("p1"));
            assertEquals("minecraft:overworld", staging.world());

            ProjectState state = await(store.snapshot("p1")).orElseThrow();
            assertEquals(1, state.claims().size());
            assertEquals("Alice", state.claims().get(0).player().name());
            assertFalse(state.inventory().known(), "migrated area has no coverage yet");
        }

        Path backups = directory.resolve("migration-backups");
        assertTrue(Files.isDirectory(backups), "v2 store must be backed up before migration");
        List<Path> backupDatabases;
        try (var entries = Files.walk(backups)) {
            backupDatabases = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("v2.sqlite"))
                    .toList();
        }
        assertEquals(1, backupDatabases.size());
        assertBackupIsV2(backupDatabases.get(0));

        // Reopening a migrated store is stable and does not create another backup.
        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            assertEquals(2, await(store.areaCatalog()).areas().size());
        }
        try (var entries = Files.walk(backups)) {
            assertEquals(1, entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("v2.sqlite"))
                    .count());
        }
    }

    @Test
    void failedV2ToCurrentMigrationRollsBackTablesColumnsAndPreservesRows() throws Exception {
        Path db = directory.resolve("v2-rollback.sqlite");
        createV2Fixture(db);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            assertThrows(IllegalStateException.class,
                    () -> StoreSchema.migrateSchema(connection, () -> {
                        throw new IllegalStateException("injected v2 migration failure");
                    }));

            assertEquals(2, StoreSchema.readUserVersion(connection));
            for (String table : HistoricSchemaFixtures.V3_TABLES) {
                assertFalse(tableExists(connection, table), "rolled back table: " + table);
            }
            for (String column : HistoricSchemaFixtures.V3_AREA_COLUMNS) {
                assertFalse(hasColumn(connection, "areas", column), "rolled back column: " + column);
            }
            try (Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM areas")) {
                    assertTrue(resultSet.next());
                    assertEquals(2, resultSet.getInt(1));
                }
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM warehouse_refs")) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                }
            }

            StoreSchema.migrateSchema(connection);
            assertEquals(StoreSchema.SCHEMA_VERSION, StoreSchema.readUserVersion(connection));
            for (String table : HistoricSchemaFixtures.V3_TABLES) {
                assertTrue(tableExists(connection, table), "migrated table: " + table);
            }
            for (String column : HistoricSchemaFixtures.V3_AREA_COLUMNS) {
                assertTrue(hasColumn(connection, "areas", column), "migrated column: " + column);
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
            HistoricSchemaFixtures.revertToV2(connection);
            statement.execute("ALTER TABLE projects DROP COLUMN interpretation_id");
            statement.execute("PRAGMA user_version=1");
        }
        return materialId;
    }

    private void createV2Fixture(Path db) throws Exception {
        try (SqliteProjectStore store = SqliteProjectStore.open(db)) {
            await(store.ingest(metadata("p1", "h1"), List.of(new MaterialCount(STONE, 10))));
            int materialId = await(store.findProject("p1")).orElseThrow().materials().get(0).id();
            assertTrue(await(store.join("p1", materialId, ALICE)));
            await(store.addArea("p1", AreaKind.STAGING_AREA, new AreaSpec(
                    "Staging", "minecraft:overworld", new AreaBounds(0, 0, 0, 15, 15, 15))));
            AreaDefinition warehouse = await(store.addArea(null, AreaKind.WAREHOUSE, new AreaSpec(
                    "Global", "minecraft:overworld", new AreaBounds(32, 0, 0, 47, 15, 15))));
            assertTrue(await(store.addWarehouseReference("p1", warehouse.id())));
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            HistoricSchemaFixtures.revertToV2(connection);
        }
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

    private static void assertBackupIsV2(Path backup) throws Exception {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = readOnly.createConnection("jdbc:sqlite:" + backup);
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt(1));
            }
            assertEquals(2, count(statement, "areas"));
            assertEquals(1, count(statement, "warehouse_refs"));
            assertTrue(hasColumn(connection, "projects", "interpretation_id"));
            assertFalse(hasColumn(connection, "areas", "revision"));
            assertFalse(hasColumn(connection, "areas", "geometry_revision"));
            assertFalse(tableExists(connection, "store_state"));
            assertFalse(tableExists(connection, "area_chunks"));
        }
    }

    private static int count(Statement statement, String table) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
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

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
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
