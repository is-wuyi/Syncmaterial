package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/**
 * A project view and its claims read in one storage task/transaction, so the two
 * halves cannot come from different revisions. Counts from online players are
 * computed by the runtime and are not stored here.
 */
public record ProjectState(ProjectSnapshot project, List<ClaimSnapshot> claims) {
    public ProjectState {
        Objects.requireNonNull(project, "project");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }
}
