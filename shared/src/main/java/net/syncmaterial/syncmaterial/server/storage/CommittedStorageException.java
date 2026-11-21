package net.syncmaterial.syncmaterial.server.storage;

import java.util.Objects;
import java.util.Optional;

/**
 * Failure for the narrow case where every transaction write was committed but a
 * later completion step failed (restoring autoCommit or applying the pure runtime projection).
 *
 * <p>The transaction is durable exactly once and must not be re-executed. This exception
 * is deliberately independent of {@link StorageException} so callers must opt in to the
 * committed semantics instead of treating it as an ordinary "nothing changed" failure.
 * The carried result is the domain outcome returned by the transaction work (for example
 * a team outcome); it is never a JDBC connection or statement.</p>
 */
public final class CommittedStorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String operation;
    private final transient Object result;

    public CommittedStorageException(String operation, Object result, Throwable cause) {
        this(operation, result, cause, "could not restore autoCommit");
    }

    private CommittedStorageException(String operation, Object result, Throwable cause, String detail) {
        super("Committed " + Objects.requireNonNull(operation, "operation")
                + " but " + detail + "; the transaction must not be re-executed", cause);
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        this.operation = operation;
        this.result = result;
    }

    /** The write is durable even though the writer-local runtime projection failed. */
    public static CommittedStorageException afterCommit(String operation, Object result, Throwable cause) {
        return new CommittedStorageException(operation, result, cause,
                "its post-commit runtime projection failed");
    }

    /** The store operation whose transaction is already durable. */
    public String operation() {
        return operation;
    }

    /** Always true; present so committed failures share one explicit detection point. */
    public boolean committed() {
        return true;
    }

    /**
     * Returns the committed transaction's work result, requiring the caller's expected
     * domain type. Returns null when the transaction returned no result (for example a
     * {@code Void} operation). Never exposes JDBC objects.
     */
    public <T> T committedResult(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (result == null) {
            return null;
        }
        if (!type.isInstance(result)) {
            throw new IllegalStateException(
                    "Committed result is " + result.getClass().getName()
                            + ", not " + type.getName());
        }
        return type.cast(result);
    }

    /** Committed work result without a declared type; empty for a {@code Void} transaction. */
    public Optional<Object> committedResult() {
        return Optional.ofNullable(result);
    }
}
