package net.syncmaterial.syncmaterial.server.storage;

/** Raised when the SQLite project store cannot be opened or an operation fails. */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
