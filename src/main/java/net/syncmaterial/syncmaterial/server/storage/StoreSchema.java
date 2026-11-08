package net.syncmaterial.syncmaterial.server.storage;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Fresh schema, version gate and migration for the Foundation store. */
final class StoreSchema {
    static final int SCHEMA_VERSION = 1;
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

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

    static void migrateSchema(Connection connection) throws SQLException {
        boolean restoreAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : DDL) {
                    statement.execute(ddl);
                }
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }

            int versionAfterMigration = readUserVersion(connection);
            if (versionAfterMigration != SCHEMA_VERSION) {
                throw new SQLException(
                        "schema version check failed after migration: " + versionAfterMigration);
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
