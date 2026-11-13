package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A committed domain outcome. Processed attempts and actual changes are deliberately separate. */
public record TeamOutcome(Status status, TeamState state, int processedCount, int changedCount,
                          Set<Integer> changedMaterials, List<ClaimSnapshot> removedObservations) {
    public enum Status { SUCCESS, DENIED, PROJECT_MISSING, INVALID_TARGET, STALE_MATERIALS, INVALID_REQUEST }

    public TeamOutcome {
        Objects.requireNonNull(status, "status");
        if (processedCount < 0 || changedCount < 0) {
            throw new IllegalArgumentException("Negative team result count");
        }
        changedMaterials = Set.copyOf(Objects.requireNonNull(changedMaterials, "changedMaterials"));
        removedObservations = List.copyOf(Objects.requireNonNull(removedObservations, "removedObservations"));
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public static TeamOutcome rejected(Status status, TeamState state) {
        return new TeamOutcome(status, state, 0, 0, Set.of(), List.of());
    }
}
