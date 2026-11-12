package net.syncmaterial.syncmaterial.server.area;

import java.util.List;
import java.util.Objects;
import net.syncmaterial.syncmaterial.core.ContainerObservation;

/**
 * One bounded slice of a full-chunk read. {@code next == null} means the caller may discard
 * the cursor; otherwise it must be passed back on the next tick. {@code loaded == false}
 * means the chunk disappeared before/during the read and NOTHING in this slice may be
 * committed as an empty observation.
 *
 * <p>{@code sourceFailure} means the source cannot honestly observe the whole loaded chunk
 * (for example its cursor was truncated by a source-side cap). The scheduler abandons the
 * scan tokens so storage keeps the last-known rows stale, and no partial or empty snapshot
 * is applied. A source-failure slice never carries observations or a cursor.</p>
 */
public record ChunkScanSlice(
        List<ContainerObservation> observations,
        ChunkScanCursor next,
        boolean complete,
        boolean loaded,
        int containersUsed,
        int slotsUsed,
        boolean sourceFailure
) {
    public ChunkScanSlice {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (!loaded && !observations.isEmpty()) {
            throw new IllegalArgumentException("an unloaded chunk cannot produce observations");
        }
        if (containersUsed < 0 || slotsUsed < 0) {
            throw new IllegalArgumentException("usage must be >= 0");
        }
        if (complete && next != null) {
            throw new IllegalArgumentException("complete slices must not carry a cursor");
        }
        if (sourceFailure && complete) {
            throw new IllegalArgumentException("a source-failure slice cannot be complete");
        }
        if (sourceFailure && next != null) {
            throw new IllegalArgumentException("a source-failure slice cannot carry a cursor");
        }
        if (sourceFailure && !observations.isEmpty()) {
            throw new IllegalArgumentException("a source-failure slice cannot carry observations");
        }
        if (!complete && !sourceFailure && next == null && !observations.isEmpty()) {
            throw new IllegalArgumentException("a slice without a cursor cannot carry observations");
        }
    }

    /** Backwards-compatible normal slice constructor. */
    public ChunkScanSlice(List<ContainerObservation> observations, ChunkScanCursor next, boolean complete,
                          boolean loaded, int containersUsed, int slotsUsed) {
        this(observations, next, complete, loaded, containersUsed, slotsUsed, false);
    }

    public static ChunkScanSlice notLoaded() {
        return new ChunkScanSlice(List.of(), null, false, false, 0, 0, false);
    }

    /**
     * The source could not produce a trustworthy full observation of the loaded chunk.
     * The scheduler abandons this scan and preserves the last-known storage rows as stale.
     */
    public static ChunkScanSlice incomplete() {
        return incomplete(0, 0);
    }

    /** Incomplete read that still consumed source budget before failing. */
    public static ChunkScanSlice incomplete(int containersUsed, int slotsUsed) {
        return new ChunkScanSlice(List.of(), null, false, true, containersUsed, slotsUsed, true);
    }

    public static ChunkScanSlice complete(List<ContainerObservation> observations, int containersUsed, int slotsUsed) {
        return new ChunkScanSlice(observations, null, true, true, containersUsed, slotsUsed, false);
    }
}
