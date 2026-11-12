package net.syncmaterial.syncmaterial.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One observation of a physical inventory source read by the scanner. A non-blank
 * {@code warning} means the contents are opaque/unknown and must not be treated as a real
 * count; an empty count map with a blank warning is a fully observed empty source.
 */
public record ContainerObservation(ContainerKey key, Map<String, Long> counts, String warning) {
    public ContainerObservation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(warning, "warning");

        Map<String, Long> copy = new TreeMap<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String itemId = CoreChecks.requireRegistryId(entry.getKey(), "itemId");
            Long count = Objects.requireNonNull(entry.getValue(), "count for " + itemId);
            if (count < 0) {
                throw new IllegalArgumentException("count must be >= 0 for " + itemId);
            }
            copy.put(itemId, count);
        }
        counts = Collections.unmodifiableMap(copy);
    }

    public boolean isOpaque() {
        return !warning.isBlank();
    }

    /** Workstation-friendly constructor for an observed empty, fully readable source. */
    public static ContainerObservation empty(ContainerKey key) {
        return new ContainerObservation(key, Map.of(), "");
    }
}
