package net.syncmaterial.syncmaterial.server.runtime;

/**
 * Small platform seam for runtime-wide lifecycle work that must stay outside the
 * shared module (for example Syncmatica discovery listeners).
 *
 * <p>The runtime binds itself once in {@link #attach(ServerRuntime)} before
 * {@code open} returns. {@link #tick()} runs from the server tick path and
 * {@link #close()} runs exactly once during runtime shutdown.</p>
 */
@FunctionalInterface
public interface LifecycleHook extends AutoCloseable {
    void attach(ServerRuntime runtime);

    default void tick() {
    }

    @Override
    default void close() {
    }

    /** No-op binding for runtimes without a platform hook. */
    LifecycleHook NONE = runtime -> {
    };
}
