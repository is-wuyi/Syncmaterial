package net.syncmaterial.syncmaterial.network;

/**
 * Machine marker carried in the existing {@code message} field of successful LIST-class
 * area responses. It is a translation key, not human text: the client localizes it, so the
 * server never depends on the client's language and old clients simply ignore it.
 *
 * <p>Only this exact key is an overlap warning. Legacy mutation confirmations ("备货区已添加"
 * and similar) and the legacy "ok" list acknowledgement must never be mistaken for it.</p>
 */
public final class AreaOverlapStatus {
    public static final String MESSAGE_KEY = "syncmaterial.gui.label.area_overlap_warning";

    private AreaOverlapStatus() {
    }

    public static boolean isWarning(String message) {
        return message != null && MESSAGE_KEY.equals(message.trim());
    }
}
