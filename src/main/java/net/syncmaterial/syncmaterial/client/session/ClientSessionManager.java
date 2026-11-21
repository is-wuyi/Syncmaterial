package net.syncmaterial.syncmaterial.client.session;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Sole owner of the current client connection and its deferred cleanup. */
public final class ClientSessionManager {
    private static final Object LOCK = new Object();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong PENDING_DISCONNECT = new AtomicLong(-1);
    private static Object activeConnection;
    private static ClientConnectionSession current;
    private static boolean connected;
    private static final net.syncmaterial.syncmaterial.client.PickupModeState DISCONNECTED_PICKUP =
            new net.syncmaterial.syncmaterial.client.PickupModeState();

    private ClientSessionManager() {}

    /** Called on the client thread before the new handshake is sent. */
    public static ClientConnectionSession join(Object connection) {
        if (connection == null) throw new NullPointerException("connection");
        ClientConnectionSession previous;
        ClientConnectionSession created;
        synchronized (LOCK) {
            previous = current;
            long token = GENERATION.incrementAndGet();
            created = new ClientConnectionSession(connection, token);
            current = created;
            activeConnection = connection;
            connected = true;
            PENDING_DISCONNECT.set(-1);
        }
        if (previous != null) previous.close();
        return created;
    }

    public static Optional<ClientConnectionSession> current() {
        synchronized (LOCK) {
            return Optional.ofNullable(current);
        }
    }

    public static long connectionToken() {
        return GENERATION.get();
    }

    /** Current pickup state; the disconnected instance is always inactive and empty. */
    public static net.syncmaterial.syncmaterial.client.PickupModeState pickup() {
        return current().filter(ClientConnectionSession::isOpen)
                .map(ClientConnectionSession::pickup).orElse(DISCONNECTED_PICKUP);
    }

    public static Object connectionIdentity() {
        synchronized (LOCK) {
            return connected ? activeConnection : null;
        }
    }

    public static boolean isConnected() {
        synchronized (LOCK) {
            return connected;
        }
    }

    public static boolean isCurrent(Object connection, long token) {
        synchronized (LOCK) {
            return connected && activeConnection == connection && current != null
                    && current.token() == token && current.isOpen();
        }
    }

    /** Runs only when the source connection is still the current open session. */
    public static boolean ifCurrent(Object connection, long token, Runnable action) {
        synchronized (LOCK) {
            if (!isCurrent(connection, token)) return false;
        }
        action.run();
        return true;
    }

    public static long invalidateConnection() {
        synchronized (LOCK) {
            return invalidateLocked();
        }
    }

    public static long invalidateConnection(Object connection) {
        synchronized (LOCK) {
            if (connection == null || activeConnection != connection) return -1;
            return invalidateLocked();
        }
    }

    private static long invalidateLocked() {
        if (!connected) return PENDING_DISCONNECT.get();
        connected = false;
        long token = GENERATION.incrementAndGet();
        PENDING_DISCONNECT.set(token);
        return token;
    }

    public static boolean finishPendingDisconnect() {
        return finishDisconnect(PENDING_DISCONNECT.get());
    }

    public static boolean finishDisconnect(long token) {
        ClientConnectionSession closing;
        synchronized (LOCK) {
            if (token < 0 || token != GENERATION.get() || connected
                    || !PENDING_DISCONNECT.compareAndSet(token, -1)) return false;
            closing = current;
            current = null;
            activeConnection = null;
        }
        if (closing != null) closing.close();
        return true;
    }

    /** Immediate compatibility close for the old single active client owner. */
    public static void discard() {
        ClientConnectionSession previous;
        synchronized (LOCK) {
            previous = current;
            current = null;
            activeConnection = null;
            connected = false;
            PENDING_DISCONNECT.set(-1);
        }
        if (previous != null) previous.close();
    }

    /** Test-only process reset; production must use the real connection callbacks. */
    public static void resetForTesting() {
        ClientConnectionSession previous;
        synchronized (LOCK) {
            previous = current;
            current = null;
            activeConnection = null;
            connected = false;
            GENERATION.set(0);
            PENDING_DISCONNECT.set(-1);
        }
        if (previous != null) previous.close();
    }
}
