package net.syncmaterial.syncmaterial.core;

/**
 * One material row projected for a concrete viewer.
 *
 * <p>All counts are non-negative {@code long}s. {@code teamMissing} is
 * {@code max(0, required - staging - warehouse - allEffectiveHeld)} for the whole
 * team. {@code myObserved}/{@code otherObserved} split that effective held into the
 * viewer's server-side observation and everybody else's; neither is a client-local
 * inventory number. {@code pickupMissing} is
 * {@code max(0, min(required - staging - myObserved, warehouse))}, so it only tells
 * the viewer what can actually be fetched from the warehouse.</p>
 *
 * <p>{@code stagingReady} means the staging count alone is sufficient and trusted;
 * {@code teamCollected} means the trusted team total has no shortfall. Both require
 * {@code preparationKnown}: last-known counts behind an unknown/stale
 * area view never become a confirmed completion. Deduplicated overlap does not
 * invalidate current known counts. A zero demand is reported as
 * collected but not as "staging ready".</p>
 *
 * <p>{@code observedSaturated} records whether any non-negative accumulation used to
 * build {@code myObserved}, {@code otherObserved} or the full team total exceeded
 * {@link Long#MAX_VALUE}. It is true for a bound such as two others contributing
 * {@code Long.MAX_VALUE + 1}; it is false when a single participant contributes
 * exactly {@code Long.MAX_VALUE}. The 13-argument constructor keeps fixture
 * compatibility and derives only the legacy two-term {@code my + other} overflow.</p>
 */
public record ProjectProgressMaterialView(
        int materialId,
        String itemId,
        long required,
        long staging,
        long warehouse,
        boolean claimed,
        long myObserved,
        long otherObserved,
        long teamMissing,
        long pickupMissing,
        boolean stagingReady,
        boolean teamCollected,
        boolean preparationKnown,
        boolean observedSaturated
) {
    public ProjectProgressMaterialView {
        if (materialId <= 0) {
            throw new IllegalArgumentException("materialId must be > 0");
        }
        CoreChecks.requireRegistryId(itemId, "itemId");
        if (required < 0 || staging < 0 || warehouse < 0
                || myObserved < 0 || otherObserved < 0
                || teamMissing < 0 || pickupMissing < 0) {
            throw new IllegalArgumentException("progress counts must be >= 0");
        }
        if ((stagingReady || teamCollected) && !preparationKnown) {
            throw new IllegalArgumentException("completion cannot be confirmed while preparation is unknown");
        }
    }

    /**
     * Compatibility constructor for fixtures that only know the two aggregate sides.
     * It cannot see internal partial-sum saturation; production projections use the
     * canonical constructor with the cumulative flag computed while summing.
     */
    public ProjectProgressMaterialView(
            int materialId,
            String itemId,
            long required,
            long staging,
            long warehouse,
            boolean claimed,
            long myObserved,
            long otherObserved,
            long teamMissing,
            long pickupMissing,
            boolean stagingReady,
            boolean teamCollected,
            boolean preparationKnown
    ) {
        this(materialId, itemId, required, staging, warehouse, claimed, myObserved,
                otherObserved, teamMissing, pickupMissing, stagingReady, teamCollected,
                preparationKnown,
                otherObserved != 0 && myObserved > Long.MAX_VALUE - otherObserved);
    }

    /** Sum of this viewer's and everybody else's effective held, saturated at {@link Long#MAX_VALUE}. */
    public long teamObserved() {
        return net.syncmaterial.syncmaterial.api.ProgressFormulas.saturatingAdd(myObserved, otherObserved);
    }

    /** True when teamObserved is a bound, not an exact mathematical total. */
    public boolean teamObservedSaturated() {
        return observedSaturated;
    }
}
