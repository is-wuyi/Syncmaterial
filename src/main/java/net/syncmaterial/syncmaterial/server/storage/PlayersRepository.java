package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.PlayerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Player identity resolution. A UUID is adopted from a real online identity; a
 * name-only row is upgraded in place when the same name later arrives with a UUID.
 */
final class PlayersRepository {
    private PlayersRepository() {
    }

    static long resolvePlayer(Connection connection, PlayerIdentity identity) throws SQLException {
        UUID uuid = identity.uuid();
        if (uuid != null) {
            Long byUuid = findPlayerIdByUuid(connection, uuid);
            if (byUuid != null) {
                updatePlayerName(connection, byUuid, identity.name());
                return byUuid;
            }

            Long unresolved = findUnresolvedPlayerIdByName(connection, identity.name());
            if (unresolved != null) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE players SET uuid = ?, name = ? WHERE id = ?")) {
                    update.setString(1, uuid.toString());
                    update.setString(2, identity.name());
                    update.setLong(3, unresolved);
                    update.executeUpdate();
                }
                return unresolved;
            }

            return insertPlayer(connection, uuid.toString(), identity.name());
        }

        Long byName = findPlayerIdByName(connection, identity.name());
        if (byName != null) {
            return byName;
        }
        return insertPlayer(connection, null, identity.name());
    }

    static Long findPlayerId(Connection connection, PlayerIdentity identity) throws SQLException {
        if (identity.uuid() != null) {
            Long byUuid = findPlayerIdByUuid(connection, identity.uuid());
            return byUuid != null ? byUuid : findUnresolvedPlayerIdByName(connection, identity.name());
        }
        return findPlayerIdByName(connection, identity.name());
    }

    static PlayerIdentity readPlayerIdentity(ResultSet resultSet, int uuidColumn) throws SQLException {
        String uuidText = resultSet.getString(uuidColumn);
        UUID uuid = uuidText == null ? null : UUID.fromString(uuidText);
        return new PlayerIdentity(uuid, resultSet.getString(uuidColumn + 1));
    }

    private static Long findPlayerIdByUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM players WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static Long findUnresolvedPlayerIdByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM players WHERE uuid IS NULL AND name = ? COLLATE NOCASE ORDER BY id LIMIT 1")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static Long findPlayerIdByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM players WHERE name = ? COLLATE NOCASE "
                        + "ORDER BY (uuid IS NULL) DESC, id LIMIT 1")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static void updatePlayerName(Connection connection, long playerId, String name) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET name = ? WHERE id = ? AND name <> ?")) {
            update.setString(1, name);
            update.setLong(2, playerId);
            update.setString(3, name);
            update.executeUpdate();
        }
    }

    private static long insertPlayer(Connection connection, String uuid, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO players (uuid, name) VALUES (?, ?) RETURNING id")) {
            if (uuid == null) {
                insert.setNull(1, Types.VARCHAR);
            } else {
                insert.setString(1, uuid);
            }
            insert.setString(2, name);
            try (ResultSet resultSet = insert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("player insert returned no id");
                }
                long id = resultSet.getLong(1);
                if (id <= 0) {
                    throw new SQLException("player insert returned invalid id " + id);
                }
                return id;
            }
        }
    }
}
