package net.syncmaterial.syncmaterial.core;

/** A persisted material row. {@code id} is stable across re-ingests of the same item. */
public record MaterialSnapshot(int id, String itemId, long required) {
    public MaterialSnapshot {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be > 0");
        }
        CoreChecks.requireRegistryId(itemId, "itemId");
        if (required < 0) {
            throw new IllegalArgumentException("required must be >= 0");
        }
    }
}
