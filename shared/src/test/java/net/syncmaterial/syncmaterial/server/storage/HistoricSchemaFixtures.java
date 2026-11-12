package net.syncmaterial.syncmaterial.server.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Test-only historic fixture helper. Fixtures start from a database created by the real
 * {@link StoreSchema}, then this helper removes exactly the v3 delta while preserving the
 * v2 tables/rows. No production code contains destructive schema seams.
 */
final class HistoricSchemaFixtures {
    /** V3-only tables, child-first so dropping them is safe with foreign keys enabled. */
    static final List<String> V3_TABLES = List.of(
            "area_chunk_containers",
            "area_chunks",
            "chunk_generations",
            "container_items",
            "containers",
            "container_tombstones",
            "store_state");

    /** V3-only columns added to the pre-existing v2 areas table. */
    static final List<String> V3_AREA_COLUMNS = List.of("revision", "geometry_revision");

    private HistoricSchemaFixtures() {
    }

    static void revertToV2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : V3_TABLES) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            for (String column : V3_AREA_COLUMNS) {
                if (hasColumn(connection, "areas", column)) {
                    statement.execute("ALTER TABLE areas DROP COLUMN " + column);
                }
            }
            statement.execute("PRAGMA user_version=2");
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
}
