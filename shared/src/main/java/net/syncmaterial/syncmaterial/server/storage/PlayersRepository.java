package net.syncmaterial.syncmaterial.server.storage;

import net.syncmaterial.syncmaterial.core.PlayerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Player identity resolution. A UUID is adopted from a real online identity; a
 * name-only row is upgraded in place when the same name later arrives with a UUID.
 *
 * <p>Team writes use the stricter helpers at the bottom: a resolved identity only
 * adopts an unresolved same-name row when that name maps unambiguously to it, and
 * two resolved identities with the same name stay distinct.</p>
 */
final class PlayersRepository {
    private PlayersRepository() {
    }

    /** One physical {@code players} row. */
    record PlayerRow(long id, UUID uuid, String name) {
        PlayerIdentity identity() {
            return new PlayerIdentity(uuid, name);
        }

        boolean isResolved() {
            return uuid != null;
        }
    }

    /**
     * A stored actor candidate. {@code needsUpgrade} means the row is still
     * name-only and may be upgraded to the trusted actor UUID after authorization.
     */
    record ActorCandidate(PlayerRow row, boolean needsUpgrade) {
    }

    /** Several resolved rows share the requested name; no arbitrary row may be picked. */
    static final class AmbiguousPlayerNameException extends SQLException {
        private static final long serialVersionUID = 1L;

        AmbiguousPlayerNameException(String name) {
            super("Player name is ambiguous: " + name);
        }
    }

    /** A UUID-based target must already have a stored trusted identity row. */
    static final class UnknownPlayerUuidException extends SQLException {
        private static final long serialVersionUID = 1L;

        UnknownPlayerUuidException(UUID uuid) {
            super("Player UUID is not known to the store: " + uuid);
        }
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
            if (unresolved != null && !hasResolvedRowsByName(connection, identity.name())) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE players SET uuid = ?, name = ? WHERE id = ?")) {
                    update.setString(1, uuid.toString());
                    update.setString(2, identity.name());
                    update.setLong(3, unresolved);
                    update.executeUpdate();
                }
                return unresolved;
            }

            // A different resolved UUID already owns this display name. Never upgrade the
            // unresolved row across identities; insert the trusted identity separately.
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
            if (byUuid != null) {
                return byUuid;
            }
            return hasResolvedRowsByName(connection, identity.name())
                    ? null
                    : findUnresolvedPlayerIdByName(connection, identity.name());
        }
        return findPlayerIdByName(connection, identity.name());
    }

    static PlayerIdentity readPlayerIdentity(ResultSet resultSet, int uuidColumn) throws SQLException {
        String uuidText = resultSet.getString(uuidColumn);
        UUID uuid = uuidText == null ? null : UUID.fromString(uuidText);
        return new PlayerIdentity(uuid, resultSet.getString(uuidColumn + 1));
    }

    // ========== Team identity resolution ==========

    /**
     * Resolves the trusted actor for an authority check. A UUID actor is matched by
     * UUID first; only when no resolved row shares the actor's display name may an
     * unresolved same-name row stand in for it. This variant may merge one
     * unambiguous unresolved same-name row into the stored UUID row, because a
     * command must not depend on a prior directory refresh to recover ownership.
     */
    static Optional<ActorCandidate> resolveActorForWrite(Connection connection, PlayerIdentity actor)
            throws SQLException {
        Optional<ActorCandidate> candidate = resolveActorCandidate(connection, actor);
        if (candidate.isEmpty() || actor.uuid() == null || !candidate.get().row().isResolved()) {
            return candidate;
        }
        PlayerRow merged = mergeUnresolvedIfUnambiguous(connection, candidate.get().row(), actor.name());
        return Optional.of(new ActorCandidate(merged, false));
    }

    /**
     * Resolves the trusted actor without writing anything. A UUID actor is matched
     * by UUID first. Only when no resolved row shares the actor's display name may
     * an unresolved same-name row stand in for it (and then be upgraded after
     * authorization). A different resolved UUID holding the same name means the
     * name-based row cannot be claimed, so no role is returned.
     */
    static Optional<ActorCandidate> resolveActorCandidate(Connection connection, PlayerIdentity actor)
            throws SQLException {
        Objects.requireNonNull(actor, "actor");
        if (actor.uuid() != null) {
            Optional<PlayerRow> byUuid = findRowByUuid(connection, actor.uuid());
            if (byUuid.isPresent()) {
                return Optional.of(new ActorCandidate(byUuid.get(), false));
            }
            if (!findResolvedRowsByName(connection, actor.name()).isEmpty()) {
                return Optional.empty();
            }
            return findUnresolvedRowByName(connection, actor.name())
                    .map(row -> new ActorCandidate(row, true));
        }

        List<PlayerRow> resolved = findResolvedRowsByName(connection, actor.name());
        if (resolved.size() > 1) {
            throw new AmbiguousPlayerNameException(actor.name());
        }
        if (resolved.size() == 1) {
            return Optional.of(new ActorCandidate(resolved.get(0), false));
        }
        return findUnresolvedRowByName(connection, actor.name())
                .map(row -> new ActorCandidate(row, false));
    }

    /** Upgrades one authorized name-only actor row to its trusted UUID in place. */
    static PlayerRow upgradeActor(Connection connection, PlayerRow row, PlayerIdentity actor)
            throws SQLException {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(actor, "actor");
        if (actor.uuid() == null) {
            throw new IllegalArgumentException("actor upgrade requires a UUID");
        }
        if (row.uuid() != null) {
            return row;
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET uuid = ?, name = ? WHERE id = ? AND uuid IS NULL")) {
            update.setString(1, actor.uuid().toString());
            update.setString(2, actor.name());
            update.setLong(3, row.id());
            if (update.executeUpdate() != 1) {
                throw new SQLException("actor identity upgrade did not update exactly one row");
            }
        }
        return new PlayerRow(row.id(), actor.uuid(), actor.name());
    }

    /**
     * Ensures a trusted join actor has a real row. UUID actors are inserted as
     * resolved identities when no unambiguous same-name row exists; a name-only
     * actor remains unresolved (no synthetic UUID).
     */
    static PlayerRow ensureTrustedActor(Connection connection, PlayerIdentity actor) throws SQLException {
        Objects.requireNonNull(actor, "actor");
        UUID uuid = actor.uuid();
        if (uuid == null) {
            List<PlayerRow> resolved = findResolvedRowsByName(connection, actor.name());
            if (resolved.size() > 1) {
                throw new AmbiguousPlayerNameException(actor.name());
            }
            if (resolved.size() == 1) {
                PlayerRow row = resolved.get(0);
                updatePlayerName(connection, row.id(), actor.name());
                return new PlayerRow(row.id(), row.uuid(), actor.name());
            }
            Optional<PlayerRow> unresolved = findUnresolvedRowByName(connection, actor.name());
            if (unresolved.isPresent()) {
                return unresolved.get();
            }
            long id = insertPlayer(connection, null, actor.name());
            return new PlayerRow(id, null, actor.name());
        }

        Optional<PlayerRow> byUuid = findRowByUuid(connection, uuid);
        if (byUuid.isPresent()) {
            return mergeUnresolvedIfUnambiguous(connection, byUuid.get(), actor.name());
        }

        if (!hasResolvedRowsByName(connection, actor.name())) {
            Optional<PlayerRow> unresolved = findUnresolvedRowByName(connection, actor.name());
            if (unresolved.isPresent()) {
                return upgradeActor(connection, unresolved.get(), actor);
            }
        }

        long id = insertPlayer(connection, uuid.toString(), actor.name());
        return new PlayerRow(id, uuid, actor.name());
    }

    /**
     * Merges one unresolved same-name row into a known resolved row when the name
     * maps to that UUID alone. A second resolved UUID with the same name keeps the
     * rows distinct and leaves the unresolved row untouched.
     */
    private static PlayerRow mergeUnresolvedIfUnambiguous(Connection connection, PlayerRow resolvedRow,
                                                          String currentName) throws SQLException {
        List<PlayerRow> sameNameResolved = findResolvedRowsByName(connection, currentName);
        boolean otherResolved = sameNameResolved.stream()
                .anyMatch(row -> row.id() != resolvedRow.id());
        Optional<PlayerRow> unresolved = findUnresolvedRowByName(connection, currentName);
        if (!otherResolved && unresolved.isPresent() && unresolved.get().id() != resolvedRow.id()) {
            mergeUnresolvedIntoResolved(connection, unresolved.get().id(), resolvedRow.id());
        }
        updatePlayerName(connection, resolvedRow.id(), currentName);
        return new PlayerRow(resolvedRow.id(), resolvedRow.uuid(), currentName);
    }

    /**
     * Looks up one command target. A UUID target must already be persisted; a
     * name-only target may fall back to one resolved row, one unresolved row, or
     * be created unresolved by the caller. Multiple resolved same-name rows are
     * never collapsed into an arbitrary identity.
     */
    static Optional<PlayerRow> findTeamTarget(Connection connection, PlayerIdentity target)
            throws SQLException {
        Objects.requireNonNull(target, "target");
        if (target.uuid() != null) {
            return Optional.of(findRowByUuid(connection, target.uuid())
                    .orElseThrow(() -> new UnknownPlayerUuidException(target.uuid())));
        }

        List<PlayerRow> resolved = findResolvedRowsByName(connection, target.name());
        if (resolved.size() > 1) {
            throw new AmbiguousPlayerNameException(target.name());
        }
        if (resolved.size() == 1) {
            return Optional.of(resolved.get(0));
        }
        return findUnresolvedRowByName(connection, target.name());
    }

    /** Creates a name-only offline target. No UUID is invented. */
    static PlayerRow createUnresolvedPlayer(Connection connection, String name) throws SQLException {
        long id = insertPlayer(connection, null, name);
        return new PlayerRow(id, null, name);
    }

    /**
     * Reconciles trusted directory/cache identities in one transaction.
     *
     * <p>The batch is grouped by case-insensitive name before any adoption. A name
     * with multiple real UUIDs keeps all of them distinct and leaves any unresolved
     * row untouched. A name with one real UUID may upgrade or merge one unresolved
     * same-name row, but only while no other resolved UUID holds that name.</p>
     */
    static void reconcilePlayers(Connection connection, List<PlayerIdentity> players) throws SQLException {
        Objects.requireNonNull(players, "players");

        Map<UUID, PlayerIdentity> resolved = new LinkedHashMap<>();
        Set<String> nameOnly = new LinkedHashSet<>();
        for (PlayerIdentity player : players) {
            Objects.requireNonNull(player, "player");
            if (player.uuid() != null) {
                resolved.put(player.uuid(), player);
            } else {
                nameOnly.add(player.name());
            }
        }

        Map<String, List<PlayerIdentity>> resolvedByName = new LinkedHashMap<>();
        for (PlayerIdentity player : resolved.values()) {
            resolvedByName.computeIfAbsent(normalizeName(player.name()), key -> new ArrayList<>())
                    .add(player);
        }

        for (List<PlayerIdentity> group : resolvedByName.values()) {
            if (group.size() > 1) {
                for (PlayerIdentity player : group) {
                    ensureResolvedRow(connection, player);
                }
            } else {
                reconcileSingleResolvedName(connection, group.get(0));
            }
        }
        for (String name : nameOnly) {
            reconcileNameOnly(connection, name);
        }
    }

    private static void ensureResolvedRow(Connection connection, PlayerIdentity player) throws SQLException {
        Optional<PlayerRow> existing = findRowByUuid(connection, player.uuid());
        if (existing.isPresent()) {
            updatePlayerName(connection, existing.get().id(), player.name());
            return;
        }
        insertPlayer(connection, player.uuid().toString(), player.name());
    }

    private static void reconcileSingleResolvedName(Connection connection, PlayerIdentity player)
            throws SQLException {
        Optional<PlayerRow> target = findRowByUuid(connection, player.uuid());
        List<PlayerRow> sameNameResolved = findResolvedRowsByName(connection, player.name());
        boolean otherResolved = sameNameResolved.stream()
                .anyMatch(row -> !row.uuid().equals(player.uuid()));
        Optional<PlayerRow> unresolved = findUnresolvedRowByName(connection, player.name());

        if (target.isEmpty()) {
            if (!otherResolved && unresolved.isPresent()) {
                upgradeUnresolved(connection, unresolved.get(), player);
            } else {
                insertPlayer(connection, player.uuid().toString(), player.name());
            }
            return;
        }

        updatePlayerName(connection, target.get().id(), player.name());
        if (!otherResolved && unresolved.isPresent()) {
            mergeUnresolvedIntoResolved(connection, unresolved.get().id(), target.get().id());
        }
    }

    private static void reconcileNameOnly(Connection connection, String name) throws SQLException {
        if (!findResolvedRowsByName(connection, name).isEmpty()) {
            return;
        }
        if (findUnresolvedRowByName(connection, name).isEmpty()) {
            insertPlayer(connection, null, name);
        }
    }

    private static void upgradeUnresolved(Connection connection, PlayerRow source, PlayerIdentity player)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET uuid = ?, name = ? WHERE id = ? AND uuid IS NULL")) {
            update.setString(1, player.uuid().toString());
            update.setString(2, player.name());
            update.setLong(3, source.id());
            if (update.executeUpdate() != 1) {
                throw new SQLException("unresolved player row disappeared during reconciliation");
            }
        }
    }

    /**
     * Merges one unresolved source row into its resolved target. Project ownership,
     * deputies and claims are redirected/unioned; for duplicate inventories the
     * newest {@code updated_at} wins and a tie keeps the resolved target row.
     */
    private static void mergeUnresolvedIntoResolved(Connection connection, long sourceId, long targetId)
            throws SQLException {
        if (sourceId == targetId) {
            return;
        }

        try (PreparedStatement redirect = connection.prepareStatement(
                "UPDATE projects SET owner_player_id = ? WHERE owner_player_id = ?")) {
            redirect.setLong(1, targetId);
            redirect.setLong(2, sourceId);
            redirect.executeUpdate();
        }

        try (PreparedStatement union = connection.prepareStatement(
                "INSERT OR IGNORE INTO project_deputies (project_id, player_id, added_at) "
                        + "SELECT project_id, ?, added_at FROM project_deputies WHERE player_id = ?")) {
            union.setLong(1, targetId);
            union.setLong(2, sourceId);
            union.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM project_deputies WHERE player_id = ?")) {
            delete.setLong(1, sourceId);
            delete.executeUpdate();
        }

        try (PreparedStatement union = connection.prepareStatement(
                "INSERT OR IGNORE INTO material_claims (project_id, material_id, player_id, claimed_at) "
                        + "SELECT project_id, material_id, ?, claimed_at FROM material_claims WHERE player_id = ?")) {
            union.setLong(1, targetId);
            union.setLong(2, sourceId);
            union.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM material_claims WHERE player_id = ?")) {
            delete.setLong(1, sourceId);
            delete.executeUpdate();
        }

        mergeInventories(connection, sourceId, targetId);

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM players WHERE id = ?")) {
            delete.setLong(1, sourceId);
            if (delete.executeUpdate() != 1) {
                throw new SQLException("merged unresolved player row disappeared: " + sourceId);
            }
        }
    }

    private static void mergeInventories(Connection connection, long sourceId, long targetId)
            throws SQLException {
        List<InventoryRow> sourceRows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT project_id, material_id, count, updated_at FROM player_inventories "
                        + "WHERE player_id = ?")) {
            select.setLong(1, sourceId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    int materialId = StoreIds.checkedIntId(resultSet.getLong(2), "material");
                    sourceRows.add(new InventoryRow(resultSet.getString(1), materialId,
                            resultSet.getLong(3), resultSet.getLong(4)));
                }
            }
        }

        try (PreparedStatement findTarget = connection.prepareStatement(
                "SELECT count, updated_at FROM player_inventories "
                        + "WHERE project_id = ? AND material_id = ? AND player_id = ?");
             PreparedStatement moveSource = connection.prepareStatement(
                     "UPDATE player_inventories SET player_id = ? "
                             + "WHERE project_id = ? AND material_id = ? AND player_id = ?");
             PreparedStatement updateTarget = connection.prepareStatement(
                     "UPDATE player_inventories SET count = ?, updated_at = ? "
                             + "WHERE project_id = ? AND material_id = ? AND player_id = ?");
             PreparedStatement deleteSource = connection.prepareStatement(
                     "DELETE FROM player_inventories "
                             + "WHERE project_id = ? AND material_id = ? AND player_id = ?")) {
            for (InventoryRow row : sourceRows) {
                findTarget.setString(1, row.projectId());
                findTarget.setInt(2, row.materialId());
                findTarget.setLong(3, targetId);
                boolean targetExists;
                long targetUpdatedAt = 0L;
                try (ResultSet resultSet = findTarget.executeQuery()) {
                    targetExists = resultSet.next();
                    if (targetExists) {
                        targetUpdatedAt = resultSet.getLong(2);
                    }
                }

                if (!targetExists) {
                    moveSource.setLong(1, targetId);
                    moveSource.setString(2, row.projectId());
                    moveSource.setInt(3, row.materialId());
                    moveSource.setLong(4, sourceId);
                    if (moveSource.executeUpdate() != 1) {
                        throw new SQLException("failed to move merged inventory row");
                    }
                    continue;
                }

                if (row.updatedAt() > targetUpdatedAt) {
                    updateTarget.setLong(1, row.count());
                    updateTarget.setLong(2, row.updatedAt());
                    updateTarget.setString(3, row.projectId());
                    updateTarget.setInt(4, row.materialId());
                    updateTarget.setLong(5, targetId);
                    if (updateTarget.executeUpdate() != 1) {
                        throw new SQLException("failed to update merged inventory row");
                    }
                }
                deleteSource.setString(1, row.projectId());
                deleteSource.setInt(2, row.materialId());
                deleteSource.setLong(3, sourceId);
                deleteSource.executeUpdate();
            }
        }
    }

    private record InventoryRow(String projectId, int materialId, long count, long updatedAt) {
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ========== Basic row helpers ==========

    static Optional<PlayerRow> findRowByUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, uuid, name FROM players WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? Optional.of(readRow(resultSet)) : Optional.empty();
            }
        }
    }

    static List<PlayerRow> findResolvedRowsByName(Connection connection, String name) throws SQLException {
        List<PlayerRow> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, uuid, name FROM players "
                        + "WHERE uuid IS NOT NULL AND name = ? COLLATE NOCASE ORDER BY id")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(readRow(resultSet));
                }
            }
        }
        return List.copyOf(rows);
    }

    static Optional<PlayerRow> findUnresolvedRowByName(Connection connection, String name)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, uuid, name FROM players WHERE uuid IS NULL AND name = ? COLLATE NOCASE "
                        + "ORDER BY id LIMIT 1")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next() ? Optional.of(readRow(resultSet)) : Optional.empty();
            }
        }
    }

    private static PlayerRow readRow(ResultSet resultSet) throws SQLException {
        String uuidText = resultSet.getString(2);
        UUID uuid = uuidText == null ? null : UUID.fromString(uuidText);
        return new PlayerRow(resultSet.getLong(1), uuid, resultSet.getString(3));
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
        List<PlayerRow> resolved = findResolvedRowsByName(connection, name);
        if (resolved.size() > 1) {
            throw new AmbiguousPlayerNameException(name);
        }
        if (resolved.size() == 1) {
            return resolved.get(0).id();
        }
        return findUnresolvedPlayerIdByName(connection, name);
    }

    private static boolean hasResolvedRowsByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM players WHERE uuid IS NOT NULL AND name = ? COLLATE NOCASE LIMIT 1")) {
            select.setString(1, name);
            try (ResultSet resultSet = select.executeQuery()) {
                return resultSet.next();
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
