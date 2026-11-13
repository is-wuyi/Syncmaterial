package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Shared fixtures for real-JDBC team storage tests. */
final class TeamTestFixtures {
    static final String STONE = "minecraft:stone";
    static final String DIRT = "minecraft:dirt";
    static final String IRON = "minecraft:iron_ingot";
    static final Predicate<ProjectSnapshot> CURRENT_MATERIAL = snapshot -> true;
    static final BooleanSupplier ADMITTED = () -> true;

    private TeamTestFixtures() {
    }

    static SchematicMetadata metadata(String id, String hash, PlayerIdentity owner) {
        return new SchematicMetadata(id, "Project " + id, Path.of("/schematics", id + ".litematic"), hash, owner);
    }

    static MaterialCount count(String itemId, long count) {
        return new MaterialCount(itemId, count);
    }

    static PlayerIdentity named(String name) {
        return new PlayerIdentity(null, name);
    }

    static PlayerIdentity online(String name) {
        return onlineUuid(name, name);
    }

    static PlayerIdentity onlineUuid(String uuidSeed, String name) {
        return new PlayerIdentity(UUID.nameUUIDFromBytes(uuidSeed.getBytes(StandardCharsets.UTF_8)), name);
    }

    static TeamCommand command(String projectId, long requestId, TeamAction action,
                               List<Integer> materialIds, List<PlayerIdentity> targets) {
        return new TeamCommand(projectId, requestId, action, -1L, materialIds, targets);
    }

    static MaterialSnapshot material(ProjectSnapshot project, String itemId) {
        return project.materials().stream()
                .filter(material -> material.itemId().equals(itemId))
                .findFirst()
                .orElseThrow();
    }

    static int materialId(ProjectSnapshot project, String itemId) {
        return material(project, itemId).id();
    }

    static CompletableFuture<TeamOutcome> apply(SqliteProjectStore store, TeamCommand command,
                                               PlayerIdentity actor) {
        return store.applyTeamCommand(command, actor, CURRENT_MATERIAL, ADMITTED);
    }

    static CompletableFuture<TeamOutcome> apply(SqliteProjectStore store, TeamCommand command,
                                               PlayerIdentity actor,
                                               Predicate<ProjectSnapshot> currentMaterial,
                                               BooleanSupplier admission) {
        return store.applyTeamCommand(command, actor, currentMaterial, admission);
    }

    // ========== Fixture SQL ==========

    static long insertResolvedPlayer(SqliteProjectStore store, PlayerIdentity identity) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (?, ?) RETURNING id")) {
                insert.setString(1, identity.uuid().toString());
                insert.setString(2, identity.name());
                try (ResultSet resultSet = insert.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("player insert returned no id");
                    }
                    return resultSet.getLong(1);
                }
            }
        }));
    }

    static long insertUnresolvedPlayer(SqliteProjectStore store, String name) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (NULL, ?) RETURNING id")) {
                insert.setString(1, name);
                try (ResultSet resultSet = insert.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("player insert returned no id");
                    }
                    return resultSet.getLong(1);
                }
            }
        }));
    }

    static void addDeputyRow(SqliteProjectStore store, String projectId, long playerId) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO project_deputies (project_id, player_id, added_at) VALUES (?, ?, ?)")) {
                insert.setString(1, projectId);
                insert.setLong(2, playerId);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            return null;
        }));
    }

    static void insertClaim(SqliteProjectStore store, String projectId, int materialId, long playerId,
                            long claimedAt) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO material_claims (project_id, material_id, player_id, claimed_at) "
                            + "VALUES (?, ?, ?, ?)")) {
                insert.setString(1, projectId);
                insert.setInt(2, materialId);
                insert.setLong(3, playerId);
                insert.setLong(4, claimedAt);
                insert.executeUpdate();
            }
            return null;
        }));
    }

    static void insertInventory(SqliteProjectStore store, String projectId, int materialId, long playerId,
                                long count, long updatedAt) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_inventories "
                            + "(project_id, material_id, player_id, count, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, projectId);
                insert.setInt(2, materialId);
                insert.setLong(3, playerId);
                insert.setLong(4, count);
                insert.setLong(5, updatedAt);
                insert.executeUpdate();
            }
            return null;
        }));
    }

    static void setAllowSelfClaim(SqliteProjectStore store, String projectId, boolean allow) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE projects SET allow_self_claim = ? WHERE id = ?")) {
                update.setInt(1, allow ? 1 : 0);
                update.setString(2, projectId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("unknown project: " + projectId);
                }
            }
            return null;
        }));
    }

    static long ownerPlayerId(SqliteProjectStore store, String projectId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_player_id FROM projects WHERE id = ?")) {
                select.setString(1, projectId);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("unknown project: " + projectId);
                    }
                    return resultSet.getLong(1);
                }
            }
        }));
    }

    static Long playerIdByUuid(SqliteProjectStore store, UUID uuid) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM players WHERE uuid = ?")) {
                select.setString(1, uuid.toString());
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong(1) : null;
                }
            }
        }));
    }

    static UUID playerUuid(SqliteProjectStore store, long playerId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT uuid FROM players WHERE id = ?")) {
                select.setLong(1, playerId);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("unknown player: " + playerId);
                    }
                    String uuid = resultSet.getString(1);
                    return uuid == null ? null : UUID.fromString(uuid);
                }
            }
        }));
    }

    static void insertUnresolvedPlayersBatch(SqliteProjectStore store, List<String> names) throws Exception {
        await(store.runTestSupport(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (NULL, ?)")) {
                for (String name : names) {
                    insert.setString(1, name);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        }));
    }

    static Long unresolvedPlayerId(SqliteProjectStore store, String name) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM players WHERE uuid IS NULL AND name = ? COLLATE NOCASE")) {
                select.setString(1, name);
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong(1) : null;
                }
            }
        }));
    }

    static List<Long> playerIdsByName(SqliteProjectStore store, String name) throws Exception {
        return await(store.runTestSupport(connection -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM players WHERE name = ? COLLATE NOCASE ORDER BY id")) {
                select.setString(1, name);
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getLong(1));
                    }
                }
            }
            return List.copyOf(ids);
        }));
    }

    static long playerRowCount(SqliteProjectStore store) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM players");
                 ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }));
    }

    static long claimRowCount(SqliteProjectStore store, String projectId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM material_claims WHERE project_id = ?")) {
                select.setString(1, projectId);
                try (ResultSet resultSet = select.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }));
    }

    static long claimRowCount(SqliteProjectStore store, String projectId, int materialId, long playerId)
            throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM material_claims "
                            + "WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
                select.setString(1, projectId);
                select.setInt(2, materialId);
                select.setLong(3, playerId);
                try (ResultSet resultSet = select.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }));
    }

    static long inventoryRowCount(SqliteProjectStore store) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM player_inventories");
                 ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }));
    }

    static Optional<InventoryRow> inventoryRow(SqliteProjectStore store, String projectId, int materialId,
                                               long playerId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT count, updated_at FROM player_inventories "
                            + "WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
                select.setString(1, projectId);
                select.setInt(2, materialId);
                select.setLong(3, playerId);
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new InventoryRow(resultSet.getLong(1), resultSet.getLong(2)))
                            : Optional.empty();
                }
            }
        }));
    }

    static boolean deputyExists(SqliteProjectStore store, String projectId, long playerId) throws Exception {
        return await(store.runTestSupport(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM project_deputies WHERE project_id = ? AND player_id = ?")) {
                select.setString(1, projectId);
                select.setLong(2, playerId);
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next();
                }
            }
        }));
    }

    static List<Long> deputyPlayerIds(SqliteProjectStore store, String projectId) throws Exception {
        return await(store.runTestSupport(connection -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player_id FROM project_deputies WHERE project_id = ? ORDER BY player_id")) {
                select.setString(1, projectId);
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getLong(1));
                    }
                }
            }
            return List.copyOf(ids);
        }));
    }

    record InventoryRow(long count, long updatedAt) {
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }

    static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(15, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
