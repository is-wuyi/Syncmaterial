package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/** The durable result of one join, leave or inventory report. */
public record CollaborationOutcome(Action action, Status status, String projectId, int materialId) {
    public enum Action { JOIN, LEAVE, REPORT }

    public enum Status {
        APPLIED,
        UNCHANGED,
        DENIED,
        PROJECT_MISSING,
        STALE_MATERIALS,
        NOT_CLAIMED,
        SUPERSEDED,
        INVALID_REQUEST
    }

    public CollaborationOutcome {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        CoreChecks.requireNonBlank(projectId, "projectId");
        if (materialId <= 0) {
            throw new IllegalArgumentException("materialId must be > 0");
        }
    }

    /** Rejection and staleness are not successful no-ops. */
    public boolean success() {
        return status == Status.APPLIED || status == Status.UNCHANGED;
    }

    /** Membership change for JOIN/LEAVE, or an accepted observation write for REPORT. */
    public boolean changed() {
        return status == Status.APPLIED;
    }

    public static CollaborationOutcome of(Action action, Status status, String projectId, int materialId) {
        return new CollaborationOutcome(action, status, projectId, materialId);
    }
}
