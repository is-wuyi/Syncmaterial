package net.syncmaterial.syncmaterial.server.runtime;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;

public final class OnlineInventoryTracker {
    private final LongSupplier clock;
    private final long freshnessNanos;
    private final Map<UUID, Map<String, Map<Integer, Observation>>> observations = new HashMap<>();
    private final Map<UUID, Long> connections = new HashMap<>();
    private final Map<UUID, Object> connectionIdentities = new HashMap<>();
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
        Objects.requireNonNull(playerId, "playerId");
        if (!connections.containsKey(playerId)) connected(playerId, new Object());
    }

    /** The adapter supplies an opaque handler identity, compared by identity rather than equals. */
    public synchronized Set<String> connected(UUID playerId, Object identity) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(identity, "identity");
        if (matchesConnection(playerId, identity)) return Set.of();
        long generation = Math.incrementExact(nextConnection);
        Set<String> affected = disconnected(playerId, connection(playerId));
        nextConnection = generation;
        connections.put(playerId, generation);
        connectionIdentities.put(playerId, identity);
        observations.put(playerId, new HashMap<>());
        return affected;
    }

    public synchronized boolean matchesConnection(UUID playerId, Object identity) {
        return identity != null && connectionIdentities.get(playerId) == identity;
    }

    public synchronized long connection(UUID playerId) {
        return connections.getOrDefault(playerId, 0L);
    }

    public synchronized Set<String> disconnected(UUID playerId) {
        return disconnected(playerId, connection(playerId));
    }

    /** Clears only the captured connection and returns the projects whose observations were removed. */
    public synchronized Set<String> disconnected(UUID playerId, long expectedConnection) {
        if (expectedConnection <= 0 || expectedConnection != connection(playerId)) return Set.of();
        connections.remove(playerId);
        connectionIdentities.remove(playerId);
        Map<String, Map<Integer, Observation>> projects = observations.remove(playerId);
        return projects == null ? Set.of() : Set.copyOf(projects.keySet());
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
                .put(materialId, new Observation(count, clock.getAsLong(), connection));
    }

    public synchronized long count(UUID playerId, String projectId, int materialId) {
        Map<String, Map<Integer, Observation>> projects = observations.get(playerId);
        if (projects == null) {
            return 0;
        }
        long connection = connections.getOrDefault(playerId, 0L);
        if (connection == 0) {
            return 0;
        }
        Map<Integer, Observation> materials = projects.get(projectId);
        Observation value = materials == null ? null : materials.get(materialId);
        if (value == null || value.connection() != connection
                || !fresh(clock.getAsLong(), value.observedAt())) {
            return 0;
        }
        return value.count();
    }

    /**
     * One synchronized pure-value sample for a project view. Only observations that
     * belong to the player's current connection, are fresh, and match a supplied
     * claim identity/material are returned. Claim identities without a UUID are
     * skipped: the tracker never invents a UUID for a legacy unresolved name.
     */
    public synchronized OnlineInventorySnapshot snapshot(String projectId, List<ClaimSnapshot> claims) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        Objects.requireNonNull(claims, "claims");
        long now = clock.getAsLong();
        Map<Integer, Map<UUID, Long>> counts = new TreeMap<>();
        Map<UUID, Long> generations = new TreeMap<>();
        for (ClaimSnapshot claim : claims) {
            Objects.requireNonNull(claim, "claim");
            PlayerIdentity identity = claim.player();
            if (!identity.hasUuid()) {
                continue;
            }
            UUID playerId = identity.uuid();
            Long connection = connections.get(playerId);
            if (connection == null) {
                continue;
            }
            generations.put(playerId, connection);
            Map<String, Map<Integer, Observation>> projects = observations.get(playerId);
            if (projects == null) {
                continue;
            }
            Map<Integer, Observation> materials = projects.get(projectId);
            if (materials == null) {
                continue;
            }
            Observation value = materials.get(claim.materialId());
            if (value == null || value.connection() != connection) {
                continue;
            }
            if (!fresh(now, value.observedAt())) {
                continue;
            }
            counts.computeIfAbsent(claim.materialId(), ignored -> new TreeMap<>())
                    .put(playerId, value.count());
        }
        return new OnlineInventorySnapshot(projectId, now, generations, counts);
    }

    /** Publication guard for a delayed immutable sample, checked on the owning server thread. */
    public synchronized boolean isCurrent(OnlineInventorySnapshot sample) {
        Objects.requireNonNull(sample, "sample");
        long now = clock.getAsLong();
        if (!fresh(now, sample.sampledAt())) return false;
        for (var entry : sample.connectionGenerations().entrySet()) {
            if (connection(entry.getKey()) != entry.getValue()) return false;
        }
        return true;
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
            var iterator = projects.entrySet().iterator();
            while (iterator.hasNext()) {
                var project = iterator.next();
                boolean removed = project.getValue().entrySet().removeIf(entry ->
                        !fresh(now, entry.getValue().observedAt()));
                if (removed) changed.add(project.getKey());
                if (project.getValue().isEmpty()) iterator.remove();
            }
        }
        return Set.copyOf(changed);
    }

    public synchronized void clear() {
        observations.clear();
        connections.clear();
        connectionIdentities.clear();
    }

    private boolean fresh(long now, long observedAt) {
        long age = now - observedAt;
        return age >= 0 && age < freshnessNanos;
    }

    private record Observation(long count, long observedAt, long connection) {}
}
