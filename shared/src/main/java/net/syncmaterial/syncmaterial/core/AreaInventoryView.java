package net.syncmaterial.syncmaterial.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Area inventory projection for one project view. Maps are immutable and sorted by item id.
 *
 * <p>{@code known} is true only when every contributing area is completely observed and no
 * source is opaque: an empty configuration with no areas is known and empty. Stale last-known
 * counts may still appear in the maps but then {@code known} is false and {@code freshness}
 * explains why. {@code overlap} reports intersecting contributing areas and is deliberately
 * independent of unknown/stale/opaque freshness.</p>
 */
public record AreaInventoryView(
        Map<String, Long> staging,
        Map<String, Long> warehouse,
        List<AreaInventoryWarning> freshness,
        boolean known,
        boolean overlap,
        long revision
) {
    public AreaInventoryView {
        staging = countsCopy(staging, "staging");
        warehouse = countsCopy(warehouse, "warehouse");
        freshness = List.copyOf(Objects.requireNonNull(freshness, "freshness"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (known && !freshness.isEmpty()) {
            throw new IllegalArgumentException("known inventory cannot carry freshness warnings");
        }
    }

    /** Initial/default fixture state: nothing has been observed yet. */
    public static AreaInventoryView unknown() {
        return new AreaInventoryView(Map.of(), Map.of(), List.of(), false, false, 0);
    }

    public boolean hasWarnings() {
        return !freshness.isEmpty();
    }

    private static Map<String, Long> countsCopy(Map<String, Long> counts, String field) {
        Objects.requireNonNull(counts, field);
        Map<String, Long> copy = new TreeMap<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String itemId = CoreChecks.requireRegistryId(entry.getKey(), field + " itemId");
            Long count = Objects.requireNonNull(entry.getValue(), field + " count for " + itemId);
            if (count < 0) {
                throw new IllegalArgumentException(field + " count must be >= 0 for " + itemId);
            }
            copy.put(itemId, count);
        }
        return Collections.unmodifiableMap(copy);
    }
}
