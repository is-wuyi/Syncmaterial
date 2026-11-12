package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/**
 * Why one area's contribution is not fully trustworthy. Freshness warnings are independent
 * from the cross-area overlap flag; both make a view unsuitable to report "ready".
 */
public record AreaInventoryWarning(AreaKind kind, int areaId, String areaName, String status) {
    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_STALE = "stale";
    public static final String STATUS_OPAQUE = "opaque";

    public AreaInventoryWarning {
        Objects.requireNonNull(kind, "kind");
        if (areaId <= 0) {
            throw new IllegalArgumentException("areaId must be > 0");
        }
        CoreChecks.requireNonBlank(areaName, "areaName");
        CoreChecks.requireNonBlank(status, "status");
    }
}
