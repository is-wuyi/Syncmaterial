package net.syncmaterial.syncmaterial.server.runtime;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class OnlineInventoryTracker {
    private final LongSupplier clock;
    private final long freshnessNanos;
    private final Map<UUID, Map<String, Map<Integer, Observation>>> observations = new HashMap<>();
    private final Map<UUID, Long> connections = new HashMap<>();
    private long nextConnection;

    public OnlineInventoryTracker() {
        this(System::nanoTime, Duration.ofSeconds(10));
    }

    public OnlineInventoryTracker(LongSupplier clock, Duration freshness) {
        if (clock == null || freshness == null || freshness.isNegative() || freshness.isZero()) {
            throw new IllegalArgumentException("A clock and positive freshness duration are required");
        }
        this.clock = clock;
        this.freshnessNanos = freshness.toNanos();
    }

    public synchronized void connected(UUID playerId) {
        java.util.Objects.requireNonNull(playerId);
        if (!connections.containsKey(playerId)) {
            connections.put(playerId, ++nextConnection);
            observations.put(playerId, new HashMap<>());
        }
    }

    public synchronized long connection(UUID playerId) {
        return connections.getOrDefault(playerId, 0L);
    }

    public synchronized void disconnected(UUID playerId) {
        connections.remove(playerId);
        observations.remove(playerId);
    }

    public synchronized void update(UUID playerId, String projectId, int materialId, long count) {
        update(playerId, connection(playerId), projectId, materialId, count);
    }

    public synchronized void update(UUID playerId, long connection, String projectId, int materialId, long count) {
        if (playerId == null || projectId == null || projectId.isBlank() || materialId <= 0 || count < 0) {
            throw new IllegalArgumentException("Invalid inventory observation");
        }
        if (connection == 0 || connection != connection(playerId)) return;
        Map<String, Map<Integer, Observation>> projects = observations.get(playerId);
        if (projects == null) {
            return;
        }
        projects.computeIfAbsent(projectId, ignored -> new HashMap<>())
                .put(materialId, new Observation(count, clock.getAsLong()));
    }

    public synchronized long count(UUID playerId, String projectId, int materialId) {
        Map<String, Map<Integer, Observation>> projects = observations.get(playerId);
        if (projects == null) {
            return 0;
        }
        Map<Integer, Observation> materials = projects.get(projectId);
        Observation value = materials == null ? null : materials.get(materialId);
        if (value == null || clock.getAsLong() - value.observedAt() >= freshnessNanos) {
            return 0;
        }
        return value.count();
    }

    public synchronized void forget(UUID playerId, String projectId, int materialId) {
        Map<String, Map<Integer, Observation>> projects = observations.get(playerId);
        if (projects != null) {
            Map<Integer, Observation> materials = projects.get(projectId);
            if (materials != null) {
                materials.remove(materialId);
                if (materials.isEmpty()) projects.remove(projectId);
            }
        }
    }

    public synchronized Set<String> expire() {
        Set<String> changed = new java.util.HashSet<>();
        long now = clock.getAsLong();
        for (var projects : observations.values()) {
            for (var project : projects.entrySet()) {
                boolean removed = project.getValue().entrySet().removeIf(entry ->
                        now - entry.getValue().observedAt() >= freshnessNanos);
                if (removed) changed.add(project.getKey());
            }
        }
        return Set.copyOf(changed);
    }

    public synchronized void clear() {
        observations.clear();
        connections.clear();
    }

    private record Observation(long count, long observedAt) {}
}
