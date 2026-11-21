package net.syncmaterial.syncmaterial.core;

/**
 * Command/operation carried by the protocol-5 project view envelope.
 *
 * <p>{@link #NONE} exists only in server-to-client results (push/result correlation); a C2S
 * command must always carry one of the concrete actions.</p>
 */
public enum ProjectViewAction {
    NONE,
    OPEN,
    SET_INTERESTS,
    AREA_CONFIG,
    RESCAN,
    CLOSE,
    JOIN,
    LEAVE;

    public boolean isCommand() {
        return this != NONE;
    }

    public static ProjectViewAction fromWireName(String name) {
        for (ProjectViewAction action : values()) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown project view action: " + name);
    }
}
