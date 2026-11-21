package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

/** Localized presentation of typed area freshness; legacy free-text statuses stay unchanged. */
public final class AreaWarningLabels {
    private AreaWarningLabels() {}

    public static String status(String status) {
        if (status == null || status.isBlank()) return "";
        return switch (status) {
            case "stale", "unknown", "opaque" -> StringUtils.translate("syncmaterial.gui.area_status." + status);
            default -> status;
        };
    }

    public static String summary(List<CollaborationStatusS2CPacket.AreaFreshnessInfo> warnings) {
        String label = StringUtils.translate("syncmaterial.gui.label.freshness_warning");
        if (warnings == null || warnings.isEmpty()) return label;
        return label + " " + warnings.stream()
                .map(warning -> warning.areaName() + " — " + status(warning.status()))
                .distinct().collect(java.util.stream.Collectors.joining(", "));
    }
}
