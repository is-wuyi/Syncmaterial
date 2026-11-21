package net.syncmaterial.syncmaterial.server.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable pure-value sample of the online inventory tracker for one project.
 *
 * <p>It is produced under the tracker lock in one pass. {@code counts} contains only
 * observations that were fresh and belonged to the player's current connection at
 * sampling time, restricted to the supplied claim identities and materials.
 * {@code connectionGenerations} records the connection generation that the sample
 * was based on, so a caller that delays delivery can verify the connection is still
 * the same instead of resurrecting a disconnected observation. A legacy identity
 * without a UUID never appears here: no UUID is invented for it.</p>
 */
public record OnlineInventorySnapshot(
        String projectId,
        long sampledAt,
        Map<UUID, Long> connectionGenerations,
        Map<Integer, Map<UUID, Long>> counts
) {
    public OnlineInventorySnapshot {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        Objects.requireNonNull(connectionGenerations, "connectionGenerations");
        Map<UUID, Long> generations = new TreeMap<>();
        for (Map.Entry<UUID, Long> entry : connectionGenerations.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "playerId");
            Long generation = Objects.requireNonNull(entry.getValue(), "connection generation");
            if (generation <= 0) {
                throw new IllegalArgumentException("connection generation must be > 0");
            }
            generations.put(playerId, generation);
        }
        connectionGenerations = Collections.unmodifiableMap(generations);

        Objects.requireNonNull(counts, "counts");
        Map<Integer, Map<UUID, Long>> materialCounts = new TreeMap<>();
        for (Map.Entry<Integer, Map<UUID, Long>> material : counts.entrySet()) {
            int materialId = Objects.requireNonNull(material.getKey(), "materialId");
            if (materialId <= 0) {
                throw new IllegalArgumentException("materialId must be > 0");
            }
            Map<UUID, Long> players = new TreeMap<>();
            for (Map.Entry<UUID, Long> entry : Objects.requireNonNull(material.getValue(), "counts").entrySet()) {
                UUID playerId = Objects.requireNonNull(entry.getKey(), "playerId");
                Long count = Objects.requireNonNull(entry.getValue(), "count");
                if (count < 0) {
                    throw new IllegalArgumentException("count must be >= 0");
                }
                players.put(playerId, count);
            }
            if (!players.isEmpty()) {
                materialCounts.put(materialId, Collections.unmodifiableMap(players));
            }
        }
        counts = Collections.unmodifiableMap(materialCounts);
    }

    /** Effective observed count, or zero when this player/material was not sampled. */
    public long count(int materialId, UUID playerId) {
        if (playerId == null) return 0L;
        Map<UUID, Long> players = counts.get(materialId);
        if (players == null) {
            return 0L;
        }
        return players.getOrDefault(playerId, 0L);
    }

    /** Connection generation the sample was based on, or zero when not sampled/connected. */
    public long connectionGeneration(UUID playerId) {
        return playerId == null ? 0L : connectionGenerations.getOrDefault(playerId, 0L);
    }
}
