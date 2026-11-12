package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/**
 * A project view and its claims read in one storage task/transaction, so the two
 * halves cannot come from different revisions. Counts from online players are
 * computed by the runtime and are not stored here.
 *
 * <p>{@code inventory} is the area projection read in the same transaction as the
 * project and claims. The two-argument constructor is retained for fixtures and
 * starts from {@link AreaInventoryView#unknown()}.</p>
 */
public record ProjectState(ProjectSnapshot project, List<ClaimSnapshot> claims, AreaInventoryView inventory) {
    public ProjectState {
        Objects.requireNonNull(project, "project");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        Objects.requireNonNull(inventory, "inventory");
    }

    public ProjectState(ProjectSnapshot project, List<ClaimSnapshot> claims) {
        this(project, claims, AreaInventoryView.unknown());
    }
}
