package net.syncmaterial.syncmaterial.server.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/** Connection-scoped request admission and project-scoped response ordering. No Minecraft state. */
public final class TeamRequestTracker {
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, ConnectionIdentity> identities = new HashMap<>();
    private final Map<String, Long> sequences = new java.util.LinkedHashMap<>();
    private long sequence;
    private long nextConnection;

    /** Legacy adapters use actual connection identity, never a hash as an ownership token. */
    public synchronized long connection(UUID playerId, Object connection) {
        if (playerId == null || connection == null) return 0;
        ConnectionIdentity current = identities.get(playerId);
        if (current != null && current.connection == connection) return current.generation;
        long generation = Math.addExact(nextConnection, 1L);
        nextConnection = generation;
        identities.put(playerId, new ConnectionIdentity(connection, generation));
        return generation;
    }

    public synchronized boolean begin(UUID playerId, long connection, long requestId) {
        if (playerId == null || connection <= 0 || requestId <= 0) return false;
        Session session = sessions.get(playerId);
        if (session != null && connection < session.connection) return false;
        if (session == null || session.connection != connection) {
            session = new Session(connection);
            sessions.put(playerId, session);
        }
        if (requestId <= session.lastRequest || session.inFlight >= TeamLimits.IN_FLIGHT) return false;
        session.lastRequest = requestId;
        session.inFlight++;
        return true;
    }

    public synchronized void finish(UUID playerId, long connection) {
        Session session = sessions.get(playerId);
        if (session != null && session.connection == connection && session.inFlight > 0) session.inFlight--;
    }

    public synchronized long nextSequence(String projectId) {
        long next = Math.addExact(sequence, 1L);
        sequence = next;
        if (!sequences.containsKey(projectId) && sequences.size() >= 4096) {
            sequences.remove(sequences.keySet().iterator().next());
        }
        sequences.put(projectId, next);
        return next;
    }

    public synchronized long currentSequence(String projectId) {
        return sequences.getOrDefault(projectId, 0L);
    }

    public synchronized void disconnected(UUID playerId) {
        sessions.remove(playerId);
        identities.remove(playerId);
    }

    public synchronized void clear() {
        sessions.clear();
        identities.clear();
        sequences.clear();
    }

    private record ConnectionIdentity(Object connection, long generation) {}

    private static final class Session {
        private final long connection;
        private long lastRequest;
        private int inFlight;

        private Session(long connection) {
            this.connection = connection;
        }
    }
}
