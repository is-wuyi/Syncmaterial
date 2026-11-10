package net.syncmaterial.syncmaterial.core;

/**
 * One material line parsed from a schematic. Duplicate item ids may appear;
 * the storage layer sums them with overflow checks before writing.
 */
public record MaterialCount(String itemId, long count) {
    public MaterialCount {
        CoreChecks.requireRegistryId(itemId, "itemId");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
    }
}
