package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.KnownPlayers;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Team role and directory reads. Callers own the writer thread and transaction. */
final class ProjectTeamReads {
    /** Trusted cache envelope is larger than the delivered directory cap. */
    static final int TARGET_CACHE_LIMIT = 65_536;

    private ProjectTeamReads() {
    }

    /** Stored role row ids; resolved at the writer transaction, never from client flags. */
    record TeamRoles(long ownerId, Set<Long> deputyIds) {
        boolean isMainOwner(long playerId) {
            return playerId == ownerId;
        }

        boolean isOwner(long playerId) {
            return isMainOwner(playerId) || deputyIds.contains(playerId);
        }
    }

    static Optional<TeamRoles> roles(Connection connection, String projectId) throws SQLException {
        Long ownerId;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT owner_player_id FROM projects WHERE id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                ownerId = resultSet.getLong(1);
            }
        }

        Set<Long> deputies = new HashSet<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT player_id FROM project_deputies WHERE project_id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    deputies.add(resultSet.getLong(1));
                }
            }
        }
        return Optional.of(new TeamRoles(ownerId, Set.copyOf(deputies)));
    }

    /** Authoritative team state, or empty when the project does not exist. */
    static Optional<TeamState> teamState(Connection connection, String projectId) throws SQLException {
        PlayerIdentity owner;
        boolean allowSelfClaim;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT p.allow_self_claim, pl.uuid, pl.name "
                        + "FROM projects p JOIN players pl ON pl.id = p.owner_player_id WHERE p.id = ?")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                allowSelfClaim = resultSet.getInt(1) != 0;
                owner = PlayersRepository.readPlayerIdentity(resultSet, 2);
            }
        }

        List<PlayerIdentity> deputies = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT pl.uuid, pl.name FROM project_deputies d "
                        + "JOIN players pl ON pl.id = d.player_id WHERE d.project_id = ? "
                        + "ORDER BY pl.name COLLATE NOCASE, pl.uuid, pl.id")) {
            select.setString(1, projectId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    deputies.add(PlayersRepository.readPlayerIdentity(resultSet, 1));
                }
            }
        }
        // Foundation authority is UUID-first; a name-only role must not grant client
        // manager rights merely because an unresolved row happens to share the name.
        return Optional.of(new TeamState(projectId, owner, deputies, allowSelfClaim, false));
    }

    /**
     * Bounded directory read: at most {@link TeamLimits#DIRECTORY} identities in
     * deterministic order. Players holding a role or a claim are returned before
     * orphan cache-only names, so a full page never silently drops relevant offline
     * claimants. The caller owns any UI warning when the cap is reached.
     */
    static List<PlayerIdentity> knownPlayers(Connection connection) throws SQLException {
        List<PlayerIdentity> players = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "WITH prioritized (id, uuid, name, priority) AS ("
                        + "SELECT pl.id, pl.uuid, pl.name, 0 FROM players pl "
                        + "WHERE EXISTS (SELECT 1 FROM projects p WHERE p.owner_player_id = pl.id) "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 1 FROM players pl "
                        + "WHERE EXISTS (SELECT 1 FROM project_deputies d WHERE d.player_id = pl.id) "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 2 FROM players pl "
                        + "WHERE EXISTS (SELECT 1 FROM material_claims c WHERE c.player_id = pl.id) "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 3 FROM players pl) "
                        + "SELECT uuid, name FROM (SELECT id, uuid, name, MIN(priority) AS priority "
                        + "FROM prioritized GROUP BY id) "
                        + "ORDER BY priority, name COLLATE NOCASE, uuid, id LIMIT ?")) {
            select.setInt(1, TeamLimits.DIRECTORY);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    players.add(PlayersRepository.readPlayerIdentity(resultSet, 1));
                }
            }
        }
        return List.copyOf(players);
    }

    /**
     * Project-scoped bounded directory. The requested project's owner, deputies and
     * claimants come before every other player, so unrelated-project owners and
     * orphan cache names cannot push relevant offline claimants out of the page.
     * Names that cannot fit the shared identity bound are skipped explicitly.
     */
    static KnownPlayers knownPlayerDirectory(Connection connection, String projectId) throws SQLException {
        List<PlayerIdentity> players = new ArrayList<>();
        boolean limited = false;
        int fetched = 0;

        try (PreparedStatement select = connection.prepareStatement(
                "WITH prioritized (id, uuid, name, priority) AS ("
                        + "SELECT pl.id, pl.uuid, pl.name, 0 FROM players pl "
                        + "JOIN projects p ON p.owner_player_id = pl.id WHERE p.id = ? "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 1 FROM players pl "
                        + "JOIN project_deputies d ON d.player_id = pl.id WHERE d.project_id = ? "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 2 FROM players pl "
                        + "JOIN material_claims c ON c.player_id = pl.id WHERE c.project_id = ? "
                        + "UNION ALL "
                        + "SELECT pl.id, pl.uuid, pl.name, 3 FROM players pl) "
                        + "SELECT uuid, name FROM (SELECT id, uuid, name, MIN(priority) AS priority "
                        + "FROM prioritized GROUP BY id) "
                        + "ORDER BY priority, name COLLATE NOCASE, uuid, id LIMIT ?")) {
            select.setString(1, projectId);
            select.setString(2, projectId);
            select.setString(3, projectId);
            select.setInt(4, TeamLimits.DIRECTORY + 1);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    fetched++;
                    if (fetched > TeamLimits.DIRECTORY) {
                        limited = true;
                        break;
                    }
                    PlayerIdentity identity = PlayersRepository.readPlayerIdentity(resultSet, 1);
                    if (identity.name().length() > TeamLimits.PLAYER_NAME_LENGTH) {
                        limited = true;
                        continue;
                    }
                    players.add(identity);
                }
            }
        }
        return new KnownPlayers(players, limited);
    }

    /**
     * Resolves requested command targets against actual trusted sources, never a
     * truncated UI directory. A UUID must exist in the store or in the trusted
     * online/cache lists; a name resolves by unique online identity, then by the
     * union of cache and complete store matches, then by an existing unresolved
     * row, then as a new name-only offline target. Matched cache/online identities
     * are reconciled in this writer transaction so a verified UUID has a row before
     * the command is applied.
     */
    static List<PlayerIdentity> resolveTargets(Connection connection, List<PlayerIdentity> requested,
                                               List<PlayerIdentity> online, List<PlayerIdentity> cached)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(online, "online");
        Objects.requireNonNull(cached, "cached");

        List<PlayerIdentity> resolved = new ArrayList<>(requested.size());
        for (PlayerIdentity target : requested) {
            resolved.add(resolveTarget(connection, target, online, cached));
        }
        return List.copyOf(resolved);
    }

    private static PlayerIdentity resolveTarget(Connection connection, PlayerIdentity target,
                                                List<PlayerIdentity> online, List<PlayerIdentity> cached)
            throws SQLException {
        if (target.uuid() != null) {
            // The current online name is the freshest trusted fact; a cache profile is
            // next, and the stored row is the fallback. Reconciling the trusted name
            // keeps an old stored display name from winning a rename.
            PlayerIdentity trusted = findByIdentity(online, target.uuid());
            if (trusted == null) {
                trusted = findByIdentity(cached, target.uuid());
            }
            if (trusted != null) {
                return reconcileTrusted(connection, trusted);
            }
            Optional<PlayersRepository.PlayerRow> stored =
                    PlayersRepository.findRowByUuid(connection, target.uuid());
            if (stored.isPresent()) {
                return stored.get().identity();
            }
            throw new PlayersRepository.UnknownPlayerUuidException(target.uuid());
        }

        List<PlayerIdentity> onlineMatches = matchesByName(online, target.name());
        UUID onlineUuid = singleUuid(onlineMatches, target.name());
        if (onlineUuid != null) {
            return reconcileTrusted(connection, findByIdentity(onlineMatches, onlineUuid));
        }

        Map<UUID, PlayerIdentity> candidates = new LinkedHashMap<>();
        for (PlayerIdentity known : matchesByName(cached, target.name())) {
            if (known.uuid() != null) {
                candidates.putIfAbsent(known.uuid(), known);
            }
        }
        for (PlayersRepository.PlayerRow row : PlayersRepository.findResolvedRowsByName(connection, target.name())) {
            candidates.putIfAbsent(row.uuid(), row.identity());
        }
        if (candidates.size() > 1) {
            throw new PlayersRepository.AmbiguousPlayerNameException(target.name());
        }
        if (candidates.size() == 1) {
            return reconcileTrusted(connection, candidates.values().iterator().next());
        }

        return PlayersRepository.findUnresolvedRowByName(connection, target.name())
                .map(PlayersRepository.PlayerRow::identity)
                .orElseGet(() -> new PlayerIdentity(null, target.name()));
    }

    private static PlayerIdentity reconcileTrusted(Connection connection, PlayerIdentity identity)
            throws SQLException {
        PlayersRepository.reconcilePlayers(connection, List.of(identity));
        return PlayersRepository.findRowByUuid(connection, identity.uuid())
                .map(PlayersRepository.PlayerRow::identity)
                .orElseThrow(() -> new SQLException(
                        "trusted identity was not persisted: " + identity.uuid()));
    }

    private static List<PlayerIdentity> matchesByName(List<PlayerIdentity> identities, String name) {
        List<PlayerIdentity> matches = new ArrayList<>();
        for (PlayerIdentity identity : identities) {
            if (identity.name().equalsIgnoreCase(name)) {
                matches.add(identity);
            }
        }
        return matches;
    }

    private static PlayerIdentity findByIdentity(List<PlayerIdentity> identities, UUID uuid) {
        for (PlayerIdentity identity : identities) {
            if (uuid.equals(identity.uuid())) {
                return identity;
            }
        }
        return null;
    }

    private static UUID singleUuid(List<PlayerIdentity> identities, String name) throws SQLException {
        Set<UUID> uuids = new LinkedHashSet<>();
        for (PlayerIdentity identity : identities) {
            if (identity.uuid() != null) {
                uuids.add(identity.uuid());
            }
        }
        if (uuids.size() > 1) {
            throw new PlayersRepository.AmbiguousPlayerNameException(name);
        }
        return uuids.isEmpty() ? null : uuids.iterator().next();
    }
}
