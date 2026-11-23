package net.syncmaterial.syncmaterial.server.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/** Server-owned, connection-scoped project view identities and their interest masks. */
public final class ProjectViewTracker implements AutoCloseable {
    private final ProjectInterestRegistry interests;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean closed;

    public ProjectViewTracker(ProjectInterestRegistry interests) {
        this.interests = Objects.requireNonNull(interests, "interests");
    }

    public synchronized View open(UUID playerId, long connection, ProjectViewScope scope,
                                  long viewId, long generation, int mask) {
        requireOpen();
        Session session = session(playerId, connection);
        if (session == null) return null;
        View previous = session.views.get(scope);
        if (previous != null) {
            if (generation < previous.generation()
                    || generation == previous.generation() && viewId != previous.viewId()) {
                return null;
            }
        }
        View view = new View(playerId, connection, scope, viewId, generation, mask);
        if (!interests.setMask(playerId, connection, interestScope(scope), mask)) {
            return null;
        }
        session.views.put(scope, view);
        return view;
    }

    public synchronized View setInterests(UUID playerId, long connection, ProjectViewScope scope,
                                          long viewId, long generation, int mask) {
        requireOpen();
        Session session = sessions.get(playerId);
        View previous = session == null ? null : session.views.get(scope);
        if (!matches(previous, connection, viewId, generation)) return null;
        View view = new View(playerId, connection, scope, viewId, generation, mask);
        if (!interests.setMask(playerId, connection, interestScope(scope), mask)) {
            return null;
        }
        session.views.put(scope, view);
        return view;
    }

    public synchronized boolean matches(UUID playerId, long connection, ProjectViewScope scope,
                                        long viewId, long generation) {
        Session session = sessions.get(playerId);
        View view = session == null ? null : session.views.get(scope);
        return matches(view, connection, viewId, generation);
    }

    /** Current mask, or -1 when the scoped view does not match. */
    public synchronized int mask(UUID playerId, long connection, ProjectViewScope scope,
                                 long viewId, long generation) {
        Session session = sessions.get(playerId);
        View view = session == null ? null : session.views.get(scope);
        return matches(view, connection, viewId, generation) ? view.mask() : -1;
    }

    /**
     * @return whether the current view for exactly this player, connection and scope
     *         holds any of {@code kinds}; never creates a session for an invalid connection
     */
    public synchronized boolean hasAnyInterest(UUID playerId, long connection, ProjectViewScope scope,
                                               InterestKind... kinds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(scope, "scope");
        int requested = InterestKind.mask(Objects.requireNonNull(kinds, "kinds"));
        if (closed || connection <= 0) return false;
        Session session = sessions.get(playerId);
        View view = session == null ? null : session.views.get(scope);
        return view != null && view.connection() == connection
                && (view.mask() & requested) != 0;
    }

    /**
     * @return immutable snapshots of every nonzero-mask view in {@code scope}, at most one
     *         per player
     */
    public synchronized List<View> interestedViews(ProjectViewScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (closed) return List.of();
        return sessions.values().stream()
                .map(session -> session.views.get(scope))
                .filter(view -> view != null && view.mask() != 0)
                .map(View::immutableCopy)
                .toList();
    }

    public synchronized boolean close(UUID playerId, long connection, ProjectViewScope scope,
                                      long viewId, long generation) {
        if (closed) return false;
        Session session = sessions.get(playerId);
        View view = session == null ? null : session.views.get(scope);
        if (!matches(view, connection, viewId, generation)) return false;
        session.views.remove(scope);
        if (session.views.isEmpty()) sessions.remove(playerId);
        interests.setMask(playerId, connection, interestScope(scope), 0);
        return true;
    }

    public synchronized List<View> views(String projectId, InterestKind kind) {
        return views(ProjectViewScope.project(projectId), kind);
    }

    public synchronized List<View> views(ProjectViewScope scope, InterestKind kind) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        if (closed) return List.of();
        return sessions.values().stream().flatMap(session -> session.views.values().stream())
                .filter(view -> view.scope().equals(scope) && (view.mask() & kind.bit()) != 0)
                .map(View::immutableCopy)
                .toList();
    }

    public synchronized void disconnected(UUID playerId, long connection) {
        if (closed) return;
        Session session = sessions.get(playerId);
        if (session == null || session.connection != connection) return;
        sessions.remove(playerId);
        interests.disconnect(playerId, connection);
    }

    public synchronized int viewCount() {
        return closed ? 0 : sessions.values().stream().mapToInt(session -> session.views.size()).sum();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        sessions.clear();
    }

    private Session session(UUID playerId, long connection) {
        Objects.requireNonNull(playerId, "playerId");
        requirePositive(connection, "connection");
        Session session = sessions.get(playerId);
        if (session != null && connection < session.connection) return null;
        if (session == null || connection > session.connection) {
            if (session != null) interests.disconnect(playerId, session.connection);
            session = new Session(connection);
            sessions.put(playerId, session);
        }
        return session;
    }

    private static ProjectInterestRegistry.Scope interestScope(ProjectViewScope scope) {
        return new ProjectInterestRegistry.Scope(scope.id(), scope.global());
    }

    private static boolean matches(View view, long connection, long viewId, long generation) {
        return view != null && view.connection() == connection
                && view.viewId() == viewId && view.generation() == generation;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be > 0");
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("ProjectViewTracker is closed");
    }

    private static final class Session {
        private final long connection;
        private final Map<ProjectViewScope, View> views = new HashMap<>();

        private Session(long connection) {
            this.connection = connection;
        }
    }

    public record View(UUID playerId, long connection, ProjectViewScope scope,
                       long viewId, long generation, int mask) {
        public View {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(scope, "scope");
            requirePositive(connection, "connection");
            requirePositive(viewId, "viewId");
            requirePositive(generation, "generation");
            InterestKind.fromMask(mask);
        }

        private View immutableCopy() {
            return new View(playerId, connection, scope, viewId, generation, mask);
        }
    }
}
