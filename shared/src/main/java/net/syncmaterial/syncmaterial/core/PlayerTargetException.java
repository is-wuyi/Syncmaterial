package net.syncmaterial.syncmaterial.core;

/** A command target has no known UUID or a name maps to multiple distinct identities. */
public final class PlayerTargetException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlayerTargetException(String message, Throwable cause) {
        super(message, cause);
    }
}
