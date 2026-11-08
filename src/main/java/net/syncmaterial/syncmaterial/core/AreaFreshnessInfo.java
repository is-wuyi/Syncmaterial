package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/**
 * Freshness of an area's inventory contribution. A non-blank {@code warning}
 * means the count is not trustworthy and must not be presented as ready.
 */
public record AreaFreshnessInfo(AreaKind kind, long count, String label, String warning) {
    public AreaFreshnessInfo {
        Objects.requireNonNull(kind, "kind");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        CoreChecks.requireNonBlank(label, "label");
        Objects.requireNonNull(warning, "warning");
    }

    public boolean hasWarning() {
        return !warning.isBlank();
    }
}
