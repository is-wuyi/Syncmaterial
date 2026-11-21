package net.syncmaterial.syncmaterial.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.syncmaterial.syncmaterial.client.ProjectViewAssembler.AssembledView;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/** Immutable-input client cache for one scoped view, with explicit staleness and content boundaries. */
public final class ClientProjectViewStore {
    /**
     * Safety bound for one uncommitted warehouse snapshot. Exceeding it rejects the whole
     * sequence rather than committing a truncated prefix as an authoritative warehouse.
     */
    public static final int MAX_PENDING_WAREHOUSE_OBSERVATIONS = 4096;

    private ProjectViewScope scope;
    private long viewId;
    private long generation;
    private ProjectProgressSnapshot progress;
    private final Map<String, Configuration> configurations = new HashMap<>();
    private long warehouseSequence = -1;
    private long rejectedWarehouseSequence = -1;
    private String warehouseWorld;
    private List<ContainerObservation> warehouse = List.of();
    private PendingWarehouse pendingWarehouse;
    private boolean valid = true;
    private String lastError = "";

    public synchronized boolean open(ProjectViewScope scope, long viewId, long generation) {
        Objects.requireNonNull(scope, "scope");
        if (viewId <= 0 || generation <= 0) throw new IllegalArgumentException("invalid view identity");
        if (scope.equals(this.scope) && this.viewId == viewId && this.generation == generation) return true;
        if (this.scope != null && this.viewId == viewId && this.generation == generation) return false;
        clear();
        this.scope = scope;
        this.viewId = viewId;
        this.generation = generation;
        return true;
    }

    public synchronized boolean close(ProjectViewScope scope, long viewId, long generation) {
        if (!matches(scope, viewId, generation)) return false;
        clear();
        return true;
    }

    public synchronized boolean install(AssembledView assembled) {
        Objects.requireNonNull(assembled, "assembled");
        if (!matches(assembled.scope(), assembled.viewId(), assembled.generation())) return false;
        ProjectProgressSnapshot snapshot = assembled.snapshot();
        if (scope.global() || !scope.projectId().equals(snapshot.projectId())
                || assembled.sequence() != snapshot.publicationSequence()
                || assembled.catalogRevision() != snapshot.catalogRevision()
                || assembled.inventoryRevision() != snapshot.inventoryRevision()
                || progress != null && snapshot.publicationSequence() <= progress.publicationSequence()) {
            return false;
        }
        progress = snapshot;
        valid = true;
        lastError = "";
        return true;
    }

    public synchronized boolean configuration(ProjectViewScope scope, long viewId, long generation,
            long sequence, String action, List<AreaDefinition> areas) {
        if (!matches(scope, viewId, generation) || sequence < 0) return false;
        Objects.requireNonNull(action, "action");
        List<AreaDefinition> copy = List.copyOf(Objects.requireNonNull(areas, "areas"));
        Configuration previous = configurations.get(action);
        if (previous != null) {
            if (sequence < previous.sequence()) return false;
            if (sequence == previous.sequence() && !previous.areas().equals(copy)) return false;
        }
        configurations.put(action, new Configuration(sequence, copy));
        return true;
    }

    public synchronized boolean warehouse(ProjectViewScope scope, long viewId, long generation,
            long sequence, Optional<ContainerObservation> observation, boolean complete) {
        Objects.requireNonNull(observation, "observation");
        if (!matches(scope, viewId, generation) || scope.global() || sequence < 0) return false;
        if (complete) {
            if (observation.isPresent() || sequence < warehouseSequence) return false;
            if (rejectedWarehouseSequence >= 0) {
                // A sequence that overflowed must never be committed, even by its late
                // COMPLETE. A strictly newer publication supersedes the rejection.
                if (sequence <= rejectedWarehouseSequence) return false;
                rejectedWarehouseSequence = -1;
            }
            // The same sequence is one publication. Re-committing it as an empty snapshot
            // would silently erase content that was already complete.
            if (sequence == warehouseSequence) return false;
            if (pendingWarehouse != null && pendingWarehouse.sequence() > sequence) return false;
            List<ContainerObservation> completed = pendingWarehouse == null
                    || pendingWarehouse.sequence() < sequence ? List.of() : pendingWarehouse.observations();
            warehouse = completed;
            warehouseWorld = pendingWarehouse == null || pendingWarehouse.sequence() < sequence
                    ? "" : pendingWarehouse.world();
            warehouseSequence = sequence;
            pendingWarehouse = null;
            return true;
        }
        if (observation.isEmpty() || sequence <= warehouseSequence) return false;
        if (rejectedWarehouseSequence >= 0) {
            if (sequence <= rejectedWarehouseSequence) return false;
            rejectedWarehouseSequence = -1;
        }
        ContainerObservation value = observation.get();
        if (pendingWarehouse == null || pendingWarehouse.sequence() < sequence) {
            pendingWarehouse = new PendingWarehouse(sequence, value.key().world(),
                    new java.util.ArrayList<>());
        }
        if (pendingWarehouse.sequence() != sequence
                || !pendingWarehouse.world().equals(value.key().world())) return false;
        if (pendingWarehouse.observations.size() >= MAX_PENDING_WAREHOUSE_OBSERVATIONS) {
            // Do not keep a truncated prefix: a later COMPLETE must not be accepted as a
            // complete authoritative snapshot for this publication.
            pendingWarehouse = null;
            rejectedWarehouseSequence = sequence;
            return false;
        }
        pendingWarehouse.observations.add(value);
        return true;
    }

    public synchronized boolean invalidated(ProjectViewScope scope, long viewId, long generation, String message) {
        if (!matches(scope, viewId, generation)) return false;
        valid = false;
        pendingWarehouse = null;
        lastError = message == null ? "" : message;
        return true;
    }

    public synchronized boolean error(ProjectViewScope scope, long viewId, long generation, String message) {
        if (!matches(scope, viewId, generation)) return false;
        lastError = message == null ? "" : message;
        return true;
    }

    public synchronized boolean deleted(ProjectViewScope scope, long viewId, long generation) {
        return close(scope, viewId, generation);
    }

    public synchronized boolean matches(ProjectViewScope scope, long viewId, long generation) {
        return this.scope != null && this.scope.equals(scope)
                && this.viewId == viewId && this.generation == generation;
    }

    public synchronized ProjectViewScope scope() {
        return scope;
    }

    public synchronized Optional<ProjectProgressSnapshot> progress() {
        return Optional.ofNullable(progress);
    }

    public synchronized long configurationSequence(String action) {
        Configuration value = configurations.get(action);
        return value == null ? -1 : value.sequence();
    }

    public synchronized Optional<List<AreaDefinition>> configuration(String action) {
        Configuration value = configurations.get(action);
        return value == null ? Optional.empty() : Optional.of(value.areas());
    }

    public synchronized List<ContainerObservation> warehouse() {
        return warehouse;
    }

    public synchronized String warehouseWorld() {
        return warehouseWorld;
    }

    public synchronized int pendingWarehouseParts() {
        return pendingWarehouse == null ? 0 : pendingWarehouse.observations().size();
    }

    /** Whether at least one complete warehouse snapshot was installed for this view. */
    public synchronized boolean hasWarehouseSnapshot() {
        return warehouseSequence >= 0;
    }

    public synchronized boolean valid() {
        return valid;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized void clear() {
        scope = null;
        viewId = 0;
        generation = 0;
        progress = null;
        configurations.clear();
        warehouseSequence = -1;
        rejectedWarehouseSequence = -1;
        warehouseWorld = "";
        warehouse = List.of();
        pendingWarehouse = null;
        valid = true;
        lastError = "";
    }

    private record Configuration(long sequence, List<AreaDefinition> areas) {
        private Configuration {
            areas = List.copyOf(areas);
        }
    }

    private static final class PendingWarehouse {
        private final long sequence;
        private final String world;
        private final List<ContainerObservation> observations;

        private PendingWarehouse(long sequence, String world, List<ContainerObservation> observations) {
            this.sequence = sequence;
            this.world = world;
            this.observations = observations;
        }

        private long sequence() { return sequence; }
        private String world() { return world; }
        private List<ContainerObservation> observations() { return List.copyOf(observations); }
    }
}
