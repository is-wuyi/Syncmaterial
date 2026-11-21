package net.syncmaterial.syncmaterial.client.gui;

/** Latest-intent autosave correlation; older replies never mark newer input as saved. */
public final class EditorSaveTracker {
    public enum State { IDLE, PENDING, SAVING, SAVED, UNCONFIRMED, FAILED }

    private long version;
    private long requestId = -1;
    private State state = State.IDLE;

    public synchronized long edit() {
        version = Math.incrementExact(version);
        requestId = -1;
        state = State.PENDING;
        return version;
    }

    public synchronized boolean sent(long expectedVersion, long sentRequestId) {
        if (expectedVersion != version || sentRequestId <= 0 || state != State.PENDING) return false;
        requestId = sentRequestId;
        state = State.SAVING;
        return true;
    }

    public synchronized boolean unconfirmed(long expectedVersion) {
        if (expectedVersion != version) return false;
        requestId = -1;
        state = State.UNCONFIRMED;
        return true;
    }

    /** A request that never received a reply may not stay SAVING; it is not replayed. */
    public synchronized boolean timeout(long expectedRequestId) {
        if (state != State.SAVING || expectedRequestId <= 0 || expectedRequestId != requestId) return false;
        requestId = -1;
        state = State.UNCONFIRMED;
        return true;
    }

    public synchronized boolean complete(long completedRequestId, boolean success) {
        if (state != State.SAVING || completedRequestId != requestId) return false;
        state = success ? State.SAVED : State.FAILED;
        return true;
    }

    public synchronized void invalidate() {
        requestId = -1;
        if (state == State.SAVING || state == State.PENDING) state = State.UNCONFIRMED;
    }

    public synchronized State state() { return state; }
    public synchronized long version() { return version; }
    public synchronized long requestId() { return requestId; }
}
