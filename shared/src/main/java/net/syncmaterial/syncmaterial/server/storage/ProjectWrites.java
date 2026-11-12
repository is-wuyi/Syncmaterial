package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Write-path SQL. Callers own the connection and transaction boundary. */
final class ProjectWrites {
    private ProjectWrites() {
    }

    static Map<String, Long> aggregateCounts(List<MaterialCount> counts) {
        Objects.requireNonNull(counts, "counts");
        Map<String, Long> aggregated = new LinkedHashMap<>();
        for (MaterialCount material : counts) {
            Objects.requireNonNull(material, "material count");
            aggregated.merge(material.itemId(), material.count(), (left, right) -> Math.addExact(left, right));
        }
        return aggregated;
    }

    static void ingest(Connection connection, SchematicMetadata metadata, Map<String, Long> materialCounts,
                       String interpretationId) throws SQLException {
        long now = System.currentTimeMillis();
        String displayName = effectiveName(metadata);
        String filePath = metadata.file().toAbsolutePath().normalize().toString();
        ExistingProject existing = findExistingProject(connection, metadata.id());

        if (existing == null) {
            long ownerId = PlayersRepository.resolvePlayer(connection, metadata.owner());
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO projects "
                            + "(id, name, file_path, hash, interpretation_id, owner_player_id, "
                            + "allow_self_claim, revision, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?, ?)")) {
                insert.setString(1, metadata.id());
                insert.setString(2, displayName);
                insert.setString(3, filePath);
                insert.setString(4, metadata.hash());
                insert.setString(5, interpretationId);
                insert.setLong(6, ownerId);
                insert.setLong(7, now);
                insert.setLong(8, now);
                insert.executeUpdate();
            }
            upsertMaterials(connection, metadata.id(), materialCounts);
            StoreState.bumpConfigRevision(connection);
        } else if (existing.hash().equals(metadata.hash())
                && existing.interpretationId().equals(interpretationId)) {
            // Same bytes and same material rules: path/name may refresh, counts/revision stay.
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE projects SET name = ?, file_path = ?, updated_at = ? WHERE id = ?")) {
                update.setString(1, displayName);
                update.setString(2, filePath);
                update.setLong(3, now);
                update.setString(4, metadata.id());
                update.executeUpdate();
            }
            if (!existing.name().equals(displayName)) {
                StoreState.bumpConfigRevision(connection);
            }
        } else {
            // New bytes or new material semantics: both invalidate the stored counts.
            // Material rows keep their ids where the item still exists, so claims and
            // inventories of surviving materials remain attached.
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE projects SET name = ?, file_path = ?, hash = ?, interpretation_id = ?, "
                            + "revision = revision + 1, updated_at = ? WHERE id = ?")) {
                update.setString(1, displayName);
                update.setString(2, filePath);
                update.setString(3, metadata.hash());
                update.setString(4, interpretationId);
                update.setLong(5, now);
                update.setString(6, metadata.id());
                update.executeUpdate();
            }
            upsertMaterials(connection, metadata.id(), materialCounts);
            if (!existing.name().equals(displayName)) {
                StoreState.bumpConfigRevision(connection);
            }
        }
    }

    static boolean join(Connection connection, String projectId, int materialId, PlayerIdentity player)
            throws SQLException {
        long playerId = PlayersRepository.resolvePlayer(connection, player);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO material_claims (project_id, material_id, player_id, claimed_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(project_id, material_id, player_id) DO NOTHING")) {
            insert.setString(1, projectId);
            insert.setInt(2, materialId);
            insert.setLong(3, playerId);
            insert.setLong(4, System.currentTimeMillis());
            return insert.executeUpdate() > 0;
        }
    }

    static boolean leave(Connection connection, String projectId, int materialId, PlayerIdentity player)
            throws SQLException {
        Long playerId = PlayersRepository.findPlayerId(connection, player);
        if (playerId == null) {
            return false;
        }

        int removedClaims;
        try (PreparedStatement deleteClaim = connection.prepareStatement(
                "DELETE FROM material_claims WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            deleteClaim.setString(1, projectId);
            deleteClaim.setInt(2, materialId);
            deleteClaim.setLong(3, playerId);
            removedClaims = deleteClaim.executeUpdate();
        }

        try (PreparedStatement deleteInventory = connection.prepareStatement(
                "DELETE FROM player_inventories WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            deleteInventory.setString(1, projectId);
            deleteInventory.setInt(2, materialId);
            deleteInventory.setLong(3, playerId);
            deleteInventory.executeUpdate();
        }

        return removedClaims > 0;
    }

    static void recordInventory(Connection connection, String projectId, int materialId, PlayerIdentity player,
                                long count) throws SQLException {
        long playerId = PlayersRepository.resolvePlayer(connection, player);
        try (PreparedStatement checkClaim = connection.prepareStatement(
                "SELECT 1 FROM material_claims WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            checkClaim.setString(1, projectId);
            checkClaim.setInt(2, materialId);
            checkClaim.setLong(3, playerId);
            try (ResultSet resultSet = checkClaim.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "player is not a claimant for project " + projectId + " material " + materialId);
                }
            }
        }

        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO player_inventories (project_id, material_id, player_id, count, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(project_id, material_id, player_id) "
                        + "DO UPDATE SET count = excluded.count, updated_at = excluded.updated_at")) {
            upsert.setString(1, projectId);
            upsert.setInt(2, materialId);
            upsert.setLong(3, playerId);
            upsert.setLong(4, count);
            upsert.setLong(5, System.currentTimeMillis());
            upsert.executeUpdate();
        }
    }

    static void removeProject(Connection connection, String projectId) throws SQLException {
        int removed;
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM projects WHERE id = ?")) {
            delete.setString(1, projectId);
            removed = delete.executeUpdate();
        }
        if (removed > 0) {
            // Project names and any cascaded project-owned areas/references changed.
            StoreState.bumpConfigAndInventory(connection);
        }
    }

    private static void upsertMaterials(Connection connection, String projectId, Map<String, Long> counts)
            throws SQLException {
        try (PreparedStatement clear = connection.prepareStatement("DELETE FROM temp_ingest_items")) {
            clear.executeUpdate();
        }

        if (!counts.isEmpty()) {
            try (PreparedStatement insertItem = connection.prepareStatement(
                    "INSERT INTO temp_ingest_items (item_id) VALUES (?)")) {
                for (String itemId : counts.keySet()) {
                    insertItem.setString(1, itemId);
                    insertItem.addBatch();
                }
                insertItem.executeBatch();
            }
        }

        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO materials (project_id, item_id, required_count) VALUES (?, ?, ?) "
                        + "ON CONFLICT(project_id, item_id) DO UPDATE SET required_count = excluded.required_count "
                        + "RETURNING id")) {
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                upsert.setString(1, projectId);
                upsert.setString(2, entry.getKey());
                upsert.setLong(3, entry.getValue());
                try (ResultSet resultSet = upsert.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("material upsert returned no id for " + entry.getKey());
                    }
                    StoreIds.checkedIntId(resultSet.getLong(1), "material");
                }
            }
        }

        try (PreparedStatement deleteMissing = connection.prepareStatement(
                "DELETE FROM materials WHERE project_id = ? "
                        + "AND item_id NOT IN (SELECT item_id FROM temp_ingest_items)")) {
            deleteMissing.setString(1, projectId);
            deleteMissing.executeUpdate();
        }
    }

    private static ExistingProject findExistingProject(Connection connection, String projectId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT hash, interpretation_id, name FROM projects WHERE id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next()
                        ? new ExistingProject(resultSet.getString(1), resultSet.getString(2),
                                resultSet.getString(3))
                        : null;
            }
        }
    }

    private static String effectiveName(SchematicMetadata metadata) {
        if (!metadata.name().isBlank()) {
            return metadata.name();
        }
        Path fileName = metadata.file().getFileName();
        return fileName != null ? fileName.toString() : metadata.id();
    }

    private record ExistingProject(String hash, String interpretationId, String name) {
    }
}
