package net.syncmaterial.syncmaterial.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;

/** Connection-scoped delivery fences for asynchronous Foundation snapshots. */
final class FoundationDeliveryTracker {
    private final Map<UUID, PlayerDelivery> players = new HashMap<>();
    private long sequence;

    synchronized ProjectToken beginProject(UUID playerId, long connection, String projectId, boolean opening) {
        PlayerDelivery player = player(playerId, connection);
        if (player == null) throw new IllegalArgumentException("Player connection is no longer active");
        long next = Math.incrementExact(sequence);
        sequence = next;
        if (opening) player.latestOpening = next;
        return new ProjectToken(playerId, connection, projectId, next, opening);
    }

    synchronized boolean acceptProject(ProjectToken token) {
        PlayerDelivery player = players.get(token.playerId());
        if (player == null || player.connection != token.connection()
                || token.opening() && player.latestOpening != token.sequence()
                || !token.opening() && player.latestOpening > player.acceptedOpening) return false;
        long previous = player.projects.getOrDefault(token.projectId(), 0L);
        if (token.sequence() <= previous) return false;
        player.projects.put(token.projectId(), token.sequence());
        if (token.opening()) player.acceptedOpening = token.sequence();
        return true;
    }

    synchronized boolean acceptConfiguration(UUID playerId, long connection, String scope, long revision) {
        if (connection <= 0 || revision < 0) return false;
        PlayerDelivery player = player(playerId, connection);
        if (player == null) return false;
        long previous = player.configurations.getOrDefault(scope, -1L);
        if (revision < previous) return false;
        player.configurations.put(scope, revision);
        return true;
    }

    synchronized void subscribeWarehouse(UUID playerId, long connection, String projectId, boolean subscribe) {
        PlayerDelivery player = player(playerId, connection);
        if (player == null) return;
        if (subscribe) player.warehouses.add(projectId);
        else player.warehouses.remove(projectId);
        player.warehouseGeneration = Math.incrementExact(player.warehouseGeneration);
    }

    synchronized WarehouseToken beginWarehouse(UUID playerId, long connection, String world) {
        PlayerDelivery player = player(playerId, connection);
        if (player == null) throw new IllegalArgumentException("Player connection is no longer active");
        player.warehouseGeneration = Math.incrementExact(player.warehouseGeneration);
        return new WarehouseToken(playerId, connection, player.warehouseGeneration,
                Set.copyOf(player.warehouses), world);
    }

    synchronized boolean acceptWarehouse(WarehouseToken token) {
        PlayerDelivery player = players.get(token.playerId());
        return player != null && player.connection == token.connection()
                && player.warehouseGeneration == token.generation()
                && player.warehouses.equals(token.projects());
    }

    /**
     * Starts a project-view warehouse revision delivery. A null result means the revision is
     * already delivered/pending, older than the pending revision, or the view was superseded.
     */
    synchronized WarehouseRevisionToken beginWarehouseRevision(ProjectViewTracker.View view,
                                                               long revision, boolean initial) {
        Objects.requireNonNull(view, "view");
        if (revision < 0) throw new IllegalArgumentException("Warehouse revision must be >= 0");
        if (!view.scope().isProject()) {
            throw new IllegalArgumentException("Warehouse revisions require a project view");
        }
        PlayerDelivery player = player(view.playerId(), view.connection());
        if (player == null) return null;
        String projectId = view.scope().projectId();
        WarehouseRevision state = player.warehouseRevisions.get(projectId);
        if (state == null) {
            state = new WarehouseRevision(view.viewId(), view.generation());
            player.warehouseRevisions.put(projectId, state);
        } else {
            int comparison = state.compareView(view);
            if (comparison > 0) return null;
            if (comparison < 0) {
                state = new WarehouseRevision(view.viewId(), view.generation());
                player.warehouseRevisions.put(projectId, state);
            }
        }
        if (state.pendingRevision >= 0 && revision <= state.pendingRevision) return null;
        if (revision < state.deliveredRevision
                || revision == state.deliveredRevision && !initial) return null;
        state.pendingRevision = revision;
        return new WarehouseRevisionToken(view.playerId(), view.connection(), projectId,
                view.viewId(), view.generation(), revision);
    }

    /** True only while the token still matches the live connection, view and pending revision. */
    synchronized boolean acceptWarehouseRevision(WarehouseRevisionToken token) {
        return pendingRevision(token) != null;
    }

    /** Records success only for a currently accepted token; does not mutate rejected tokens. */
    synchronized void completeWarehouseRevision(WarehouseRevisionToken token) {
        WarehouseRevision state = pendingRevision(token);
        if (state == null) return;
        state.deliveredRevision = token.revision();
        state.pendingRevision = -1;
    }

    /** Clears only a currently matching pending revision; the same revision may be retried. */
    synchronized void failWarehouseRevision(WarehouseRevisionToken token) {
        WarehouseRevision state = pendingRevision(token);
        if (state != null) state.pendingRevision = -1;
    }

    synchronized boolean hasWarehouseSubscription(UUID playerId, long connection, String projectId) {
        PlayerDelivery player = players.get(playerId);
        return player != null && player.connection == connection && player.warehouses.contains(projectId);
    }

    /** Captures legacy pickup subscribers without exposing the mutable delivery maps. */
    synchronized Set<WarehouseSubscription> warehouseSubscriptions(String projectId) {
        Set<WarehouseSubscription> result = new HashSet<>();
        players.forEach((playerId, player) -> {
            if (player.warehouses.contains(projectId)) {
                result.add(new WarehouseSubscription(playerId, player.connection, player.warehouseGeneration));
            }
        });
        return Set.copyOf(result);
    }

    /** Removes a deleted pickup project only while its captured connection and generation remain current. */
    synchronized boolean forgetWarehouseProject(WarehouseSubscription subscription, String projectId) {
        PlayerDelivery player = players.get(subscription.playerId());
        if (player == null || player.connection != subscription.connection()
                || player.warehouseGeneration != subscription.generation()
                || !player.warehouses.contains(projectId)) return false;
        player.warehouses.remove(projectId);
        player.warehouseGeneration = Math.incrementExact(player.warehouseGeneration);
        return true;
    }

    synchronized void forgetProject(UUID playerId, long connection, String projectId) {
        PlayerDelivery player = players.get(playerId);
        if (player == null || player.connection != connection) return;
        long invalidated = Math.incrementExact(sequence);
        sequence = invalidated;
        player.projects.put(projectId, invalidated);
        player.configurations.remove("S:LIST:" + projectId);
        player.configurations.remove("S:LIST_WAREHOUSE_REFS:" + projectId);
        player.warehouseRevisions.remove(projectId);
        if (player.warehouses.remove(projectId)) {
            player.warehouseGeneration = Math.incrementExact(player.warehouseGeneration);
        }
    }

    synchronized void disconnected(UUID playerId) {
        players.remove(playerId);
    }

    private WarehouseRevision pendingRevision(WarehouseRevisionToken token) {
        if (token == null || token.connection() <= 0 || token.viewId() <= 0
                || token.viewGeneration() <= 0 || token.revision() < 0) return null;
        PlayerDelivery player = players.get(token.playerId());
        if (player == null || player.connection != token.connection()) return null;
        WarehouseRevision state = player.warehouseRevisions.get(token.projectId());
        if (state == null || state.viewId != token.viewId()
                || state.viewGeneration != token.viewGeneration()) return null;
        return state.pendingRevision == token.revision() ? state : null;
    }

    private PlayerDelivery player(UUID playerId, long connection) {
        if (connection <= 0) throw new IllegalArgumentException("Player connection is not active");
        PlayerDelivery player = players.get(playerId);
        if (player != null && connection < player.connection) return null;
        if (player == null || player.connection != connection) {
            player = new PlayerDelivery(connection);
            players.put(playerId, player);
        }
        return player;
    }

    record ProjectToken(UUID playerId, long connection, String projectId, long sequence, boolean opening) {}
    record WarehouseSubscription(UUID playerId, long connection, long generation) {}
    record WarehouseToken(UUID playerId, long connection, long generation, Set<String> projects, String world) {}
    record WarehouseRevisionToken(UUID playerId, long connection, String projectId, long viewId,
                                  long viewGeneration, long revision) {}

    private static final class WarehouseRevision {
        private final long viewId;
        private final long viewGeneration;
        private long deliveredRevision = -1;
        private long pendingRevision = -1;

        private WarehouseRevision(long viewId, long viewGeneration) {
            this.viewId = viewId;
            this.viewGeneration = viewGeneration;
        }

        /** Positive when this state belongs to a newer view than {@code view}. */
        private int compareView(ProjectViewTracker.View view) {
            int generation = Long.compare(viewGeneration, view.generation());
            return generation != 0 ? generation : Long.compare(viewId, view.viewId());
        }
    }

    private static final class PlayerDelivery {
        private final long connection;
        private final Map<String, Long> projects = new HashMap<>();
        private final Map<String, Long> configurations = new HashMap<>();
        private final Set<String> warehouses = new HashSet<>();
        private final Map<String, WarehouseRevision> warehouseRevisions = new HashMap<>();
        private long latestOpening;
        private long acceptedOpening;
        private long warehouseGeneration;

        private PlayerDelivery(long connection) {
            this.connection = connection;
        }
    }
}
