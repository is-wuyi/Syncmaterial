package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.ChunkKey;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and monotonic bumps of the singleton state row. Configuration revision is separate
 * from inventory/observation revision so scanner freshness commits never change the project
 * catalog delivery fingerprint.
 */
final class StoreState {
    private StoreState() {
    }

    static long configRevision(Connection connection) throws SQLException {
        return readLong(connection, "revision");
    }

    static long inventoryRevision(Connection connection) throws SQLException {
        return readLong(connection, "inventory_revision");
    }

    static long scanEpoch(Connection connection) throws SQLException {
        return readLong(connection, "scan_epoch");
    }

    static long nextObservationSequence(Connection connection) throws SQLException {
        return bump(connection, "observation_sequence");
    }

    static long bumpScanEpoch(Connection connection) throws SQLException {
        return bump(connection, "scan_epoch");
    }

    static void bumpConfigRevision(Connection connection) throws SQLException {
        bump(connection, "revision");
    }

    static void bumpInventoryRevision(Connection connection) throws SQLException {
        bump(connection, "inventory_revision");
    }

    static void bumpConfigAndInventory(Connection connection) throws SQLException {
        bumpConfigRevision(connection);
        bumpInventoryRevision(connection);
    }

    static long chunkFence(Connection connection, ChunkKey chunk) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT generation FROM chunk_generations WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            select.setString(1, chunk.world());
            select.setInt(2, chunk.x());
            select.setInt(3, chunk.z());
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    static long bumpChunkFence(Connection connection, ChunkKey chunk) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO chunk_generations (world, chunk_x, chunk_z, generation) VALUES (?, ?, ?, 1) "
                        + "ON CONFLICT(world, chunk_x, chunk_z) "
                        + "DO UPDATE SET generation = generation + 1 "
                        + "RETURNING generation")) {
            upsert.setString(1, chunk.world());
            upsert.setInt(2, chunk.x());
            upsert.setInt(3, chunk.z());
            try (ResultSet resultSet = upsert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("chunk fence bump returned no generation");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static long readLong(Connection connection, String column) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + column + " FROM store_state WHERE id = 1");
             ResultSet resultSet = select.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("store_state row is missing");
            }
            return resultSet.getLong(1);
        }
    }

    private static long bump(Connection connection, String column) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE store_state SET " + column + " = " + column + " + 1 WHERE id = 1 "
                        + "RETURNING " + column)) {
            try (ResultSet resultSet = update.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("store_state row is missing");
                }
                return resultSet.getLong(1);
            }
        }
    }
}
