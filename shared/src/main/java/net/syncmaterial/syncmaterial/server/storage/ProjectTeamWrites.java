package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Team command writes. Every method here runs inside one caller-owned writer
 * transaction; role authorization and current-material validation happen in that
 * same transaction, never from client-provided flags.
 */
final class ProjectTeamWrites {
    private ProjectTeamWrites() {
    }

    static TeamOutcome apply(Connection connection, TeamCommand command, PlayerIdentity actor,
                             Predicate<ProjectSnapshot> currentMaterial, BooleanSupplier admission)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(currentMaterial, "currentMaterial");
        Objects.requireNonNull(admission, "admission");

        Optional<ProjectSnapshot> existing = ProjectReads.findProject(connection, command.projectId());
        if (existing.isEmpty()) {
            return TeamOutcome.rejected(TeamOutcome.Status.PROJECT_MISSING, null);
        }
        ProjectSnapshot project = existing.get();

        if (!admission.getAsBoolean()) {
            return rejected(TeamOutcome.Status.DENIED, connection, command.projectId());
        }

        TeamAction action = command.action();
        if (action == TeamAction.TEAM_STATE || action == TeamAction.PLAYERS) {
            return new TeamOutcome(TeamOutcome.Status.SUCCESS,
                    currentState(connection, command.projectId()), 0, 0, Set.of(), List.of());
        }

        PlayersRepository.ActorCandidate actorCandidate;
        try {
            actorCandidate = PlayersRepository.resolveActorForWrite(connection, actor).orElse(null);
        } catch (PlayersRepository.AmbiguousPlayerNameException ambiguous) {
            return rejected(TeamOutcome.Status.DENIED, connection, command.projectId());
        }
        if (actorCandidate == null) {
            return rejected(TeamOutcome.Status.DENIED, connection, command.projectId());
        }

        // Actor reconciliation may have redirected ownership/deputies; read roles only
        // after it and never authorize from the pre-merge row ids.
        Optional<ProjectTeamReads.TeamRoles> storedRoles =
                ProjectTeamReads.roles(connection, command.projectId());
        if (storedRoles.isEmpty()) {
            return TeamOutcome.rejected(TeamOutcome.Status.PROJECT_MISSING, null);
        }
        ProjectTeamReads.TeamRoles roles = storedRoles.get();

        long actorId = actorCandidate.row().id();
        boolean authorized = action.requiresMainOwner()
                ? roles.isMainOwner(actorId)
                : roles.isOwner(actorId);
        if (!authorized) {
            return rejected(TeamOutcome.Status.DENIED, connection, command.projectId());
        }

        if (action.usesMaterials() && !currentMaterial.test(project)) {
            return rejected(TeamOutcome.Status.STALE_MATERIALS, connection, command.projectId());
        }

        Set<Integer> projectMaterialIds = new HashSet<>();
        for (MaterialSnapshot material : project.materials()) {
            projectMaterialIds.add(material.id());
        }

        TargetPlan targetPlan;
        try {
            targetPlan = planTargets(connection, command.targets());
        } catch (PlayersRepository.AmbiguousPlayerNameException
                 | PlayersRepository.UnknownPlayerUuidException invalidTarget) {
            return rejected(TeamOutcome.Status.INVALID_TARGET, connection, command.projectId());
        }

        // Authorization and all request validation passed; identity reconciliation of the
        // trusted actor is part of the same committed operation.
        if (actorCandidate.needsUpgrade()) {
            actorCandidate = new PlayersRepository.ActorCandidate(
                    PlayersRepository.upgradeActor(connection, actorCandidate.row(), actor), false);
        }
        List<PlayersRepository.PlayerRow> targets =
                materializeTargets(connection, targetPlan, actorCandidate.row().id(),
                        actorCandidate.row());

        int processed;
        int changed;
        Set<Integer> changedMaterials = new LinkedHashSet<>();
        List<ClaimSnapshot> removedObservations = new ArrayList<>();

        switch (action) {
            case TRANSFER -> {
                processed = 1;
                long targetId = singleTarget(targets).id();
                if (targetId == roles.ownerId()) {
                    changed = 0;
                } else {
                    updateProjectOwner(connection, command.projectId(), targetId);
                    changed = 1;
                }
            }
            case ADD_DEPUTY -> {
                processed = 1;
                changed = addDeputy(connection, command.projectId(), singleTarget(targets).id()) ? 1 : 0;
            }
            case REMOVE_DEPUTY -> {
                processed = 1;
                changed = removeDeputy(connection, command.projectId(), singleTarget(targets).id());
            }
            case TOGGLE_SELF_CLAIM -> {
                processed = 1;
                setAllowSelfClaim(connection, command.projectId(), !project.allowSelfClaim());
                changed = 1;
            }
            case ASSIGN -> {
                int assigned = 0;
                int inserted = 0;
                for (int materialId : command.materialIds()) {
                    if (!projectMaterialIds.contains(materialId)) {
                        continue;
                    }
                    for (PlayersRepository.PlayerRow target : targets) {
                        assigned++;
                        if (insertClaim(connection, command.projectId(), materialId, target.id())) {
                            inserted++;
                            changedMaterials.add(materialId);
                        }
                    }
                }
                processed = assigned;
                changed = inserted;
            }
            case KICK -> {
                PlayersRepository.PlayerRow target = singleTarget(targets);
                int attempts = 0;
                int removed = 0;
                Set<Integer> observedMaterials = new LinkedHashSet<>();
                for (int materialId : command.materialIds()) {
                    attempts++;
                    if (!projectMaterialIds.contains(materialId)) {
                        continue;
                    }
                    // A runtime observation may outlive the persistent row. Emit every
                    // valid requested project/material target once so the caller can
                    // clear both persistent and in-memory state.
                    if (observedMaterials.add(materialId)) {
                        removedObservations.add(new ClaimSnapshot(materialId, target.identity()));
                    }
                    boolean deleted = deleteClaim(connection, command.projectId(), materialId, target.id()) > 0;
                    deleted |= deleteInventory(connection, command.projectId(), materialId, target.id()) > 0;
                    if (deleted) {
                        removed++;
                        changedMaterials.add(materialId);
                    }
                }
                processed = attempts;
                changed = removed;
            }
            default -> throw new SQLException("Unsupported team action: " + action);
        }

        return new TeamOutcome(TeamOutcome.Status.SUCCESS,
                currentState(connection, command.projectId()), processed, changed,
                changedMaterials, removedObservations);
    }

    /**
     * Admission-checked self-service join. Every reported inventory material id
     * must belong to the same project snapshot before the claim is written.
     *
     * @return true when the claim exists after the operation, false when the join
     *         was rejected by admission, permissions, material staleness or scope.
     */
    static boolean joinAuthorized(Connection connection, String projectId, int materialId,
                                  PlayerIdentity actor, Set<Integer> reportedMaterialIds,
                                  Predicate<ProjectSnapshot> currentMaterial, BooleanSupplier admission)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reportedMaterialIds, "reportedMaterialIds");
        Objects.requireNonNull(currentMaterial, "currentMaterial");
        Objects.requireNonNull(admission, "admission");

        Optional<ProjectSnapshot> existing = ProjectReads.findProject(connection, projectId);
        if (existing.isEmpty() || !admission.getAsBoolean()) {
            return false;
        }
        ProjectSnapshot project = existing.get();
        if (!currentMaterial.test(project)) {
            return false;
        }

        Set<Integer> projectMaterialIds = new HashSet<>();
        for (MaterialSnapshot material : project.materials()) {
            projectMaterialIds.add(material.id());
        }
        if (!projectMaterialIds.contains(materialId)) {
            return false;
        }
        for (int reportedMaterialId : reportedMaterialIds) {
            if (reportedMaterialId <= 0 || !projectMaterialIds.contains(reportedMaterialId)) {
                return false;
            }
        }

        if (project.allowSelfClaim()) {
            PlayersRepository.PlayerRow actorRow;
            try {
                actorRow = PlayersRepository.ensureTrustedActor(connection, actor);
            } catch (PlayersRepository.AmbiguousPlayerNameException ambiguous) {
                return false;
            }
            insertClaim(connection, projectId, materialId, actorRow.id());
            return true;
        }

        PlayersRepository.ActorCandidate candidate;
        try {
            candidate = PlayersRepository.resolveActorForWrite(connection, actor).orElse(null);
        } catch (PlayersRepository.AmbiguousPlayerNameException ambiguous) {
            return false;
        }
        if (candidate == null) {
            return false;
        }
        Optional<ProjectTeamReads.TeamRoles> storedRoles = ProjectTeamReads.roles(connection, projectId);
        if (storedRoles.isEmpty() || !storedRoles.get().isOwner(candidate.row().id())) {
            return false;
        }

        PlayersRepository.PlayerRow actorRow = candidate.row();
        if (candidate.needsUpgrade()) {
            actorRow = PlayersRepository.upgradeActor(connection, actorRow, actor);
        }
        insertClaim(connection, projectId, materialId, actorRow.id());
        return true;
    }

    // ========== Targets ==========

    private record TargetPlan(List<PlayersRepository.PlayerRow> rows,
                              Map<String, String> pendingNames,
                              List<String> pendingKeys) {
    }

    private static TargetPlan planTargets(Connection connection, List<PlayerIdentity> targets)
            throws SQLException {
        List<PlayersRepository.PlayerRow> rows = new ArrayList<>(targets.size());
        List<String> pendingKeys = new ArrayList<>(targets.size());
        Map<String, String> pendingNames = new LinkedHashMap<>();

        for (PlayerIdentity target : targets) {
            Optional<PlayersRepository.PlayerRow> row = PlayersRepository.findTeamTarget(connection, target);
            if (row.isPresent()) {
                rows.add(row.get());
                pendingKeys.add(null);
            } else {
                String key = normalizeName(target.name());
                pendingNames.putIfAbsent(key, target.name());
                rows.add(null);
                pendingKeys.add(key);
            }
        }
        return new TargetPlan(rows, pendingNames, pendingKeys);
    }

    private static List<PlayersRepository.PlayerRow> materializeTargets(
            Connection connection, TargetPlan plan, long upgradedActorId,
            PlayersRepository.PlayerRow upgradedActor) throws SQLException {
        Map<String, PlayersRepository.PlayerRow> created = new LinkedHashMap<>();
        for (Map.Entry<String, String> pending : plan.pendingNames().entrySet()) {
            created.put(pending.getKey(),
                    PlayersRepository.createUnresolvedPlayer(connection, pending.getValue()));
        }

        List<PlayersRepository.PlayerRow> targets = new ArrayList<>(plan.rows().size());
        for (int i = 0; i < plan.rows().size(); i++) {
            PlayersRepository.PlayerRow row = plan.rows().get(i);
            if (row == null) {
                row = created.get(plan.pendingKeys().get(i));
            } else if (row.id() == upgradedActorId) {
                row = upgradedActor;
            }
            if (row == null) {
                throw new SQLException("team target was not resolved");
            }
            targets.add(row);
        }
        return targets;
    }

    private static PlayersRepository.PlayerRow singleTarget(List<PlayersRepository.PlayerRow> targets)
            throws SQLException {
        if (targets.size() != 1) {
            throw new SQLException("team action expected exactly one resolved target");
        }
        return targets.get(0);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ========== SQL writes ==========

    private static boolean insertClaim(Connection connection, String projectId, int materialId, long playerId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO material_claims (project_id, material_id, player_id, claimed_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT(project_id, material_id, player_id) DO NOTHING")) {
            insert.setString(1, projectId);
            insert.setInt(2, materialId);
            insert.setLong(3, playerId);
            insert.setLong(4, System.currentTimeMillis());
            return insert.executeUpdate() > 0;
        }
    }

    private static int deleteClaim(Connection connection, String projectId, int materialId, long playerId)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM material_claims WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            delete.setString(1, projectId);
            delete.setInt(2, materialId);
            delete.setLong(3, playerId);
            return delete.executeUpdate();
        }
    }

    private static int deleteInventory(Connection connection, String projectId, int materialId, long playerId)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM player_inventories WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            delete.setString(1, projectId);
            delete.setInt(2, materialId);
            delete.setLong(3, playerId);
            return delete.executeUpdate();
        }
    }

    private static void updateProjectOwner(Connection connection, String projectId, long playerId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE projects SET owner_player_id = ?, updated_at = ? WHERE id = ?")) {
            update.setLong(1, playerId);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, projectId);
            if (update.executeUpdate() != 1) {
                throw new SQLException("team transfer did not update exactly one project");
            }
        }
    }

    private static boolean addDeputy(Connection connection, String projectId, long playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO project_deputies (project_id, player_id, added_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(project_id, player_id) DO NOTHING")) {
            insert.setString(1, projectId);
            insert.setLong(2, playerId);
            insert.setLong(3, System.currentTimeMillis());
            return insert.executeUpdate() > 0;
        }
    }

    private static int removeDeputy(Connection connection, String projectId, long playerId)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM project_deputies WHERE project_id = ? AND player_id = ?")) {
            delete.setString(1, projectId);
            delete.setLong(2, playerId);
            return delete.executeUpdate();
        }
    }

    private static void setAllowSelfClaim(Connection connection, String projectId, boolean allow)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE projects SET allow_self_claim = ?, updated_at = ? WHERE id = ?")) {
            update.setInt(1, allow ? 1 : 0);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, projectId);
            if (update.executeUpdate() != 1) {
                throw new SQLException("team toggle did not update exactly one project");
            }
        }
    }

    // ========== State helpers ==========

    private static TeamState currentState(Connection connection, String projectId) throws SQLException {
        return ProjectTeamReads.teamState(connection, projectId).orElse(null);
    }

    private static TeamOutcome rejected(TeamOutcome.Status status, Connection connection, String projectId)
            throws SQLException {
        return TeamOutcome.rejected(status, currentState(connection, projectId));
    }
}
