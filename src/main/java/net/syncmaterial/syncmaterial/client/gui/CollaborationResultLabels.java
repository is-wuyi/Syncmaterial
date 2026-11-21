package net.syncmaterial.syncmaterial.client.gui;

import java.util.Locale;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;

/** Foundation statuses are typed; frozen legacy results may carry readable free text. */
public final class CollaborationResultLabels {
    private CollaborationResultLabels() {}

    public static String message(String message) {
        if (message == null || message.isBlank()) return "";
        for (var status : CollaborationOutcome.Status.values()) {
            if (status.name().equals(message)) {
                return StringUtils.translate("syncmaterial.gui.result."
                        + status.name().toLowerCase(Locale.ROOT));
            }
        }
        return message;
    }
}
