package net.syncmaterial.syncmaterial.client;

/**
 * Tracks the client dimension id across ticks and reports real dimension changes.
 *
 * <p>Warehouse container replies are dimension-scoped but the packet carries no dimension
 * field. When the player changes dimension the cached containers must be dropped and the
 * active pickup subscription re-requested, otherwise same-coordinate chests from the previous
 * dimension would remain in the highlight cache. The first observed world is not a change
 * (there is nothing cached yet); leaving the world resets the tracker.</p>
 */
public final class DimensionChangeTracker {
    private String lastWorldId;

    /**
     * @return true only when a previously observed world is replaced by a different one
     */
    public boolean observe(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            this.lastWorldId = null;
            return false;
        }
        if (worldId.equals(this.lastWorldId)) {
            return false;
        }
        boolean changed = this.lastWorldId != null;
        this.lastWorldId = worldId;
        return changed;
    }

    public void reset() {
        this.lastWorldId = null;
    }
}
