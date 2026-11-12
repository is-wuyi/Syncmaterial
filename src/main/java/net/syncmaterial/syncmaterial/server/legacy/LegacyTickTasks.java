package net.syncmaterial.syncmaterial.server.legacy;

import java.time.Duration;
import java.util.function.LongSupplier;

final class LegacyTickTasks implements AutoCloseable {
    private static final long STARTUP_DELAY = Duration.ofSeconds(5).toNanos();
    private final LongSupplier clock;
    private final Runnable startup;
    private final Runnable dirty;
    private long startedAt;
    private boolean started;
    private boolean pending;
    private boolean closed;
    private int dirtyTicks;

    LegacyTickTasks(LongSupplier clock, Runnable startup, Runnable dirty) {
        this.clock = clock;
        this.startup = startup;
        this.dirty = dirty;
    }

    void start() {
        if (started || closed) return;
        started = true;
        pending = true;
        startedAt = clock.getAsLong();
    }

    void tick() {
        if (closed) return;
        if (++dirtyTicks == 4) {
            dirtyTicks = 0;
            dirty.run();
        }
        if (pending && clock.getAsLong() - startedAt >= STARTUP_DELAY) {
            pending = false;
            startup.run();
        }
    }

    @Override
    public void close() {
        closed = true;
        pending = false;
    }
}
