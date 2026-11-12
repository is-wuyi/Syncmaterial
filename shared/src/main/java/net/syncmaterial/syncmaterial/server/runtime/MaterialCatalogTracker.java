package net.syncmaterial.syncmaterial.server.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;

/**
 * Per-player, per-connection baseline of the material catalog already delivered to a
 * client. The database revision is intentionally ignored: a rebuild of the same project
 * id can restart it at 1, while the catalog itself (name, hash, material ids and
 * requirements) is what the client must be notified about.
 */
public final class MaterialCatalogTracker {
    private final Map<UUID, Map<String, Baseline>> baselines = new HashMap<>();
    private long deliveryRevision;

    /**
     * Records the catalog as sent and returns the runtime-wide delivery sequence.
     * Call it for the initial response even though that response carries no revision.
     */
    public synchronized long sent(UUID playerId, long connection, ProjectSnapshot project) {
        requireArguments(playerId, connection, project);
        long revision = Math.addExact(deliveryRevision, 1L);
        deliveryRevision = revision;
        baselines.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(project.id(), new Baseline(connection, fingerprint(project), revision));
        return revision;
    }

    /**
     * Returns the exact catalog delivery sequence this connection last received for the
     * project. Area inventory snapshots carry this value so the client can reject an
     * inventory that belongs to a catalog it no longer has on screen.
     *
     * <p>Empty when no catalog was delivered on this connection (wrong connection
     * generation, reconnected player, forgotten project, or reset baseline). A non-positive
     * connection can never own a delivery and also yields empty instead of throwing: callers
     * use this as a delivery gate, not as input validation.</p>
     */
    public synchronized OptionalLong currentRevision(UUID playerId, String projectId, long connection) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(projectId, "projectId");
        if (connection <= 0) {
            return OptionalLong.empty();
        }
        Map<String, Baseline> playerBaselines = baselines.get(playerId);
        Baseline baseline = playerBaselines == null ? null : playerBaselines.get(projectId);
        if (baseline == null || baseline.connection() != connection) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(baseline.revision());
    }

    /**
     * Returns the new delivery sequence when this connection has not been sent this
     * catalog, or the catalog changed. Returns empty when the same connection already
     * received the same catalog.
     */
    public synchronized OptionalLong changed(UUID playerId, long connection, ProjectSnapshot project) {
        requireArguments(playerId, connection, project);
        Map<String, Baseline> playerBaselines = baselines.get(playerId);
        Baseline previous = playerBaselines == null ? null : playerBaselines.get(project.id());
        Catalog current = fingerprint(project);
        if (previous != null && previous.connection() == connection && previous.catalog().equals(current)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(sent(playerId, connection, project));
    }

    public synchronized void forget(UUID playerId, String projectId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(projectId, "projectId");
        Map<String, Baseline> playerBaselines = baselines.get(playerId);
        if (playerBaselines == null) {
            return;
        }
        playerBaselines.remove(projectId);
        if (playerBaselines.isEmpty()) {
            baselines.remove(playerId, playerBaselines);
        }
    }

    public synchronized void disconnected(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        baselines.remove(playerId);
    }

    /** Drops all baselines; the runtime-wide delivery sequence deliberately keeps counting. */
    public synchronized void clear() {
        baselines.clear();
    }

    private static void requireArguments(UUID playerId, long connection, ProjectSnapshot project) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(project, "project");
        if (connection <= 0) {
            throw new IllegalArgumentException("connection must be > 0");
        }
    }

    private static Catalog fingerprint(ProjectSnapshot project) {
        return new Catalog(project.id(), project.name(), project.hash(), project.materials());
    }

    private record Catalog(String id, String name, String hash, List<MaterialSnapshot> materials) {
        private Catalog {
            materials = List.copyOf(materials);
        }
    }

    private record Baseline(long connection, Catalog catalog, long revision) {
    }
}
