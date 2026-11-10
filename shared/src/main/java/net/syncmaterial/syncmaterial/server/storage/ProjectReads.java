package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-side queries. Callers run these on the writer thread, optionally inside a read transaction. */
final class ProjectReads {
    private ProjectReads() {
    }

    static Optional<ProjectSnapshot> findProject(Connection connection, String projectId) throws SQLException {
        String name;
        String hash;
        String interpretationId;
        boolean allowSelfClaim;
        long revision;
        PlayerIdentity owner;

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT p.name, p.hash, p.interpretation_id, p.allow_self_claim, p.revision, "
                        + "pl.uuid, pl.name AS owner_name "
                        + "FROM projects p JOIN players pl ON pl.id = p.owner_player_id WHERE p.id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                name = resultSet.getString(1);
                hash = resultSet.getString(2);
                interpretationId = resultSet.getString(3);
                allowSelfClaim = resultSet.getInt(4) != 0;
                revision = resultSet.getLong(5);
                owner = PlayersRepository.readPlayerIdentity(resultSet, 6);
            }
        }

        List<MaterialSnapshot> materials = loadMaterials(connection, projectId);
        List<String> deputies = loadDeputies(connection, projectId);
        return Optional.of(new ProjectSnapshot(
                projectId, name, hash, owner, allowSelfClaim, deputies, materials, revision,
                interpretationId));
    }

    static List<ProjectSnapshot> listProjects(Connection connection) throws SQLException {
        List<String> projectIds = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM projects ORDER BY id");
             ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                projectIds.add(resultSet.getString(1));
            }
        }

        List<ProjectSnapshot> projects = new ArrayList<>(projectIds.size());
        for (String projectId : projectIds) {
            Optional<ProjectSnapshot> project = findProject(connection, projectId);
            project.ifPresent(projects::add);
        }
        return List.copyOf(projects);
    }

    static List<ClaimSnapshot> claims(Connection connection, String projectId) throws SQLException {
        List<ClaimSnapshot> claims = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT c.material_id, pl.uuid, pl.name "
                        + "FROM material_claims c JOIN players pl ON pl.id = c.player_id "
                        + "WHERE c.project_id = ? ORDER BY c.material_id, pl.name COLLATE NOCASE, pl.uuid")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    int materialId = StoreIds.checkedIntId(resultSet.getLong(1), "material");
                    claims.add(new ClaimSnapshot(materialId, PlayersRepository.readPlayerIdentity(resultSet, 2)));
                }
            }
        }
        return List.copyOf(claims);
    }

    static Map<Integer, Long> inventory(Connection connection, String projectId, PlayerIdentity player)
            throws SQLException {
        Long playerId = PlayersRepository.findPlayerId(connection, player);
        if (playerId == null) {
            return Map.of();
        }

        Map<Integer, Long> inventory = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT material_id, count FROM player_inventories WHERE project_id = ? AND player_id = ? "
                        + "ORDER BY material_id")) {
            select.setString(1, projectId);
            select.setLong(2, playerId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    int materialId = StoreIds.checkedIntId(resultSet.getLong(1), "material");
                    inventory.put(materialId, resultSet.getLong(2));
                }
            }
        }
        return Collections.unmodifiableMap(inventory);
    }

    private static List<MaterialSnapshot> loadMaterials(Connection connection, String projectId)
            throws SQLException {
        List<MaterialSnapshot> materials = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, item_id, required_count FROM materials WHERE project_id = ? ORDER BY id")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    int materialId = StoreIds.checkedIntId(resultSet.getLong(1), "material");
                    materials.add(new MaterialSnapshot(materialId, resultSet.getString(2), resultSet.getLong(3)));
                }
            }
        }
        return List.copyOf(materials);
    }

    private static List<String> loadDeputies(Connection connection, String projectId) throws SQLException {
        List<String> deputies = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT pl.name FROM project_deputies d JOIN players pl ON pl.id = d.player_id "
                        + "WHERE d.project_id = ? ORDER BY pl.name COLLATE NOCASE, pl.name")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    deputies.add(resultSet.getString(1));
                }
            }
        }
        return List.copyOf(deputies);
    }
}
