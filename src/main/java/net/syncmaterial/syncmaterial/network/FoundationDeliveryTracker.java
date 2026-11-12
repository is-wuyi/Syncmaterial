package net.syncmaterial.syncmaterial.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    synchronized boolean hasWarehouseSubscription(UUID playerId, long connection, String projectId) {
        PlayerDelivery player = players.get(playerId);
        return player != null && player.connection == connection && player.warehouses.contains(projectId);
    }

    synchronized void forgetProject(UUID playerId, long connection, String projectId) {
        PlayerDelivery player = players.get(playerId);
        if (player == null || player.connection != connection) return;
        long invalidated = Math.incrementExact(sequence);
        sequence = invalidated;
        player.projects.put(projectId, invalidated);
        player.configurations.remove("S:LIST:" + projectId);
        player.configurations.remove("S:LIST_WAREHOUSE_REFS:" + projectId);
        if (player.warehouses.remove(projectId)) {
            player.warehouseGeneration = Math.incrementExact(player.warehouseGeneration);
        }
    }

    synchronized void disconnected(UUID playerId) {
        players.remove(playerId);
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
    record WarehouseToken(UUID playerId, long connection, long generation, Set<String> projects, String world) {}

    private static final class PlayerDelivery {
        private final long connection;
        private final Map<String, Long> projects = new HashMap<>();
        private final Map<String, Long> configurations = new HashMap<>();
        private final Set<String> warehouses = new HashSet<>();
        private long latestOpening;
        private long acceptedOpening;
        private long warehouseGeneration;

        private PlayerDelivery(long connection) {
            this.connection = connection;
        }
    }
}
