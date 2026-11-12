package net.syncmaterial.syncmaterial.server.storage;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;

/** Fresh schema, version gate and migration for the Foundation store. */
final class StoreSchema {
    static final int SCHEMA_VERSION = 3;

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private static final List<String> DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS players (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT,
                name TEXT NOT NULL CHECK (name <> '')
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS players_uuid_unique
                ON players(uuid) WHERE uuid IS NOT NULL
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS players_unresolved_name_unique
                ON players(name COLLATE NOCASE) WHERE uuid IS NULL
            """,
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                hash TEXT NOT NULL,
                interpretation_id TEXT NOT NULL DEFAULT 'unversioned',
                owner_player_id INTEGER NOT NULL,
                allow_self_claim INTEGER NOT NULL DEFAULT 1 CHECK (allow_self_claim IN (0, 1)),
                revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (owner_player_id) REFERENCES players(id)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS projects_owner ON projects(owner_player_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS materials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                required_count INTEGER NOT NULL CHECK (required_count >= 0),
                UNIQUE (project_id, item_id),
                UNIQUE (project_id, id),
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS material_claims (
                project_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_id INTEGER NOT NULL,
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, material_id, player_id),
                FOREIGN KEY (project_id, material_id) REFERENCES materials(project_id, id) ON DELETE CASCADE,
                FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS material_claims_player ON material_claims(player_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS player_inventories (
                project_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_id INTEGER NOT NULL,
                count INTEGER NOT NULL CHECK (count >= 0),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, material_id, player_id),
                FOREIGN KEY (project_id, material_id) REFERENCES materials(project_id, id) ON DELETE CASCADE,
                FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS player_inventories_project_player
                ON player_inventories(project_id, player_id)
            """,
            """
            CREATE INDEX IF NOT EXISTS player_inventories_player ON player_inventories(player_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS project_deputies (
                project_id TEXT NOT NULL,
                player_id INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, player_id),
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS project_deputies_player ON project_deputies(player_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS areas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT,
                kind TEXT NOT NULL CHECK (kind IN ('staging_area', 'warehouse')),
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER NOT NULL,
                y1 INTEGER NOT NULL,
                z1 INTEGER NOT NULL,
                x2 INTEGER NOT NULL,
                y2 INTEGER NOT NULL,
                z2 INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
                geometry_revision INTEGER NOT NULL DEFAULT 0 CHECK (geometry_revision >= 0),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK (kind <> 'staging_area' OR project_id IS NOT NULL),
                UNIQUE (project_id, kind, name),
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS areas_global_name_unique
                ON areas(kind, name) WHERE project_id IS NULL
            """,
            """
            CREATE INDEX IF NOT EXISTS areas_project ON areas(project_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS warehouse_refs (
                project_id TEXT NOT NULL,
                area_id INTEGER NOT NULL,
                PRIMARY KEY (project_id, area_id),
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS warehouse_refs_area ON warehouse_refs(area_id)
            """,
            """
            CREATE TABLE IF NOT EXISTS store_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
                inventory_revision INTEGER NOT NULL DEFAULT 0 CHECK (inventory_revision >= 0),
                scan_epoch INTEGER NOT NULL DEFAULT 0 CHECK (scan_epoch >= 0),
                observation_sequence INTEGER NOT NULL DEFAULT 0 CHECK (observation_sequence >= 0)
            )
            """,
            """
            INSERT OR IGNORE INTO store_state
                (id, revision, inventory_revision, scan_epoch, observation_sequence)
                VALUES (1, 0, 0, 0, 0)
            """,
            """
            CREATE TABLE IF NOT EXISTS containers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                warning TEXT NOT NULL DEFAULT '',
                observation_sequence INTEGER NOT NULL DEFAULT 0 CHECK (observation_sequence >= 0),
                updated_at INTEGER NOT NULL,
                UNIQUE (world, x, y, z)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS container_items (
                container_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL CHECK (count >= 0),
                PRIMARY KEY (container_id, item_id),
                FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS container_tombstones (
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                observation_sequence INTEGER NOT NULL CHECK (observation_sequence >= 0),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS area_chunks (
                area_id INTEGER NOT NULL,
                geometry_revision INTEGER NOT NULL,
                world TEXT NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('scanning', 'complete', 'stale', 'dirty')),
                generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                dirty_generation INTEGER NOT NULL DEFAULT 0 CHECK (dirty_generation >= 0),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (area_id, geometry_revision, world, chunk_x, chunk_z),
                FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS area_chunks_world_chunk
                ON area_chunks(world, chunk_x, chunk_z)
            """,
            """
            CREATE TABLE IF NOT EXISTS chunk_generations (
                world TEXT NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                PRIMARY KEY (world, chunk_x, chunk_z)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS area_chunk_containers (
                area_id INTEGER NOT NULL,
                geometry_revision INTEGER NOT NULL,
                world TEXT NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                container_id INTEGER NOT NULL,
                PRIMARY KEY (area_id, geometry_revision, world, chunk_x, chunk_z, container_id),
                FOREIGN KEY (area_id, geometry_revision, world, chunk_x, chunk_z)
                    REFERENCES area_chunks(area_id, geometry_revision, world, chunk_x, chunk_z)
                    ON DELETE CASCADE,
                FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS area_chunk_containers_container
                ON area_chunk_containers(container_id)
            """
    );

    private StoreSchema() {
    }

    static int readUserVersionReadOnly(Path path) throws IOException, SQLException {
        if (!Files.exists(path) || Files.size(path) == 0L) {
            return 0;
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection readOnly = config.createConnection("jdbc:sqlite:" + path)) {
            return readUserVersion(readOnly);
        }
    }

    static int readUserVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA synchronous=NORMAL");
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!resultSet.next() || resultSet.getInt(1) != 1) {
                throw new SQLException("failed to enable SQLite foreign keys");
            }
        }
    }

    /**
     * Creates a consistent pre-migration snapshot next to the database.
     *
     * <p>Uses a {@link SQLiteConfig} read-only connection rather than the JDBC
     * {@code mode=ro} flag. With WAL-only schema/data, {@code mode=ro} was observed
     * to report {@code user_version=0} and omit tables entirely, producing an empty
     * backup; a read-only config connection sees the WAL. {@code VACUUM INTO} writes
     * only the destination and leaves the source files unchanged.</p>
     */
    static Path backupBeforeMigration(Path dbPath, int fromVersion) throws IOException, SQLException {
        Path parent = dbPath.getParent();
        Path backupRoot = parent == null
                ? Path.of("migration-backups")
                : parent.resolve("migration-backups");
        Files.createDirectories(backupRoot);
        Path directory = Files.createTempDirectory(
                backupRoot, BACKUP_TIMESTAMP.format(Instant.now()) + "-v" + fromVersion + "-");
        Path destination = directory.resolve(dbPath.getFileName());

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, destination.toString());
            statement.execute();
        } catch (SQLException failure) {
            try {
                Files.deleteIfExists(destination);
                Files.deleteIfExists(directory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return destination;
    }

    static void migrateSchema(Connection connection) throws SQLException {
        migrateSchema(connection, () -> {
        });
    }

    /** Test seam: the hook runs after schema changes but before user_version/commit. */
    static void migrateSchema(Connection connection, Runnable afterSchemaChanges) throws SQLException {
        int startingVersion = readUserVersion(connection);
        if (startingVersion > SCHEMA_VERSION) {
            // Never "migrate down" an unknown future schema, even when this helper is
            // called directly instead of through SqliteProjectStore's precheck.
            throw new SQLException("refusing to migrate schema " + startingVersion
                    + " down to " + SCHEMA_VERSION);
        }
        boolean restoreAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : DDL) {
                    statement.execute(ddl);
                }
                if (!hasColumn(connection, "projects", "interpretation_id")) {
                    statement.execute(
                            "ALTER TABLE projects ADD COLUMN interpretation_id TEXT NOT NULL "
                                    + "DEFAULT 'unversioned'");
                }
                if (!hasColumn(connection, "areas", "revision")) {
                    statement.execute(
                            "ALTER TABLE areas ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 "
                                    + "CHECK (revision >= 0)");
                }
                if (!hasColumn(connection, "areas", "geometry_revision")) {
                    statement.execute(
                            "ALTER TABLE areas ADD COLUMN geometry_revision INTEGER NOT NULL DEFAULT 0 "
                                    + "CHECK (geometry_revision >= 0)");
                }
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }

            afterSchemaChanges.run();

            int versionAfterMigration = readUserVersion(connection);
            if (versionAfterMigration != SCHEMA_VERSION) {
                throw new SQLException(
                        "schema version check failed after migration: " + versionAfterMigration
                                + " (from " + startingVersion + ")");
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (restoreAutoCommit) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Initialization will fail on the next statement if the connection is broken.
                }
            }
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

    static void createTemporarySchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMP TABLE IF NOT EXISTS temp_ingest_items (item_id TEXT PRIMARY KEY) WITHOUT ROWID");
        }
    }

    static StorageException unsupportedVersion(Path dbPath, int version) {
        return new StorageException(
                "Unsupported schema version " + version + " (supported max " + SCHEMA_VERSION + "): " + dbPath);
    }
}
