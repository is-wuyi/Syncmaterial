package net.syncmaterial.syncmaterial.server.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/**
 * Server-owned registry of project or global interests held by online player
 * connections.
 *
 * <p>The registry is deliberately Minecraft-free. One player connection holds at most
 * one interest per {@link Scope} and {@link InterestKind}; repeated
 * {@link #acquire} calls are idempotent, because several client consumers (GUI, HUD,
 * pickup, editor, ...) merge into the same server interest. Merging those client
 * leases is the client's responsibility.</p>
 *
 * <p>A key is the player UUID, the positive connection generation, the scope and the
 * kind. The registry never accepts an arbitrary connection object and never derives
 * identity from {@code hashCode}; the platform adapter must validate the actual
 * connection and hand over its generation. A newer generation supersedes every
 * interest still recorded for an older generation of the same player. An older
 * generation can therefore never release or disconnect interests owned by the
 * current generation.</p>
 *
 * <p>All mutations are synchronized and bounded by {@link Limits}. Reaching a limit
 * throws {@link LimitExceededException} instead of growing without bound. Closing the
 * registry (by its explicit owner) clears all state and rejects further mutations;
 * queries after close simply return empty results.</p>
 */
public final class ProjectInterestRegistry implements AutoCloseable {
    public static final int DEFAULT_MAX_PLAYERS = 4096;
    public static final int DEFAULT_MAX_PROJECT_SCOPES = 4096;
    public static final int DEFAULT_MAX_GLOBAL_SCOPES = 64;
    public static final int DEFAULT_MAX_INTERESTS = 65536;

    private final Limits limits;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<Scope, Map<InterestKind, Set<UUID>>> subscribers = new HashMap<>();
    private final Map<String, Integer> projectRefs = new HashMap<>();
    private final Map<String, Integer> globalRefs = new HashMap<>();
    private int interestCount;
    private boolean closed;

    public ProjectInterestRegistry() {
        this(Limits.defaults());
    }

    public ProjectInterestRegistry(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Adds one interest. This is idempotent for the same player, generation, scope and
     * kind. An acquire from a newer connection generation first discards every stale
     * interest of that player, then adds the requested one. An acquire from an older
     * generation is ignored.
     *
     * @return {@code true} only when this call added a new interest
     * @throws LimitExceededException when a player, scope or total-interest bound is
     *                                reached
     */
    public synchronized boolean acquire(UUID playerId, long connectionGeneration, Scope scope,
            InterestKind kind) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        requireGeneration(connectionGeneration);
        requireOpen();

        PlayerState state = players.get(playerId);
        if (state != null && connectionGeneration < state.generation) {
            return false;
        }
        if (state != null && connectionGeneration > state.generation) {
            // A newer connection supersedes every interest of the old generation before
            // limits are checked. If this acquire then fails a limit, the stale
            // generation must stay cleared; never fall back to attributing its
            // interests to the old connection.
            removePlayerState(playerId, state);
            state = null;
        }

        EnumSet<InterestKind> kinds = state == null ? null : state.interests.get(scope);
        if (kinds != null && kinds.contains(kind)) {
            return false;
        }

        boolean newPlayer = state == null;
        if (newPlayer) {
            ensurePlayerCapacity();
        }
        if (kinds == null) {
            ensureScopeCapacity(scope);
        }
        if (interestCount >= limits.maxInterests()) {
            throw new LimitExceededException("interest limit reached: " + limits.maxInterests());
        }

        if (newPlayer) {
            state = new PlayerState(connectionGeneration);
            players.put(playerId, state);
        }
        if (kinds == null) {
            kinds = EnumSet.noneOf(InterestKind.class);
            state.interests.put(scope, kinds);
            incrementScope(scope);
        }
        kinds.add(kind);
        interestCount++;
        subscribers.computeIfAbsent(scope, ignored -> new EnumMap<>(InterestKind.class))
                .computeIfAbsent(kind, ignored -> new LinkedHashSet<>())
                .add(playerId);
        return true;
    }

    /**
     * Atomically replaces one scope's mask. Capacity is checked before same-generation
     * state is changed; a newer connection still clears its stale interests before a
     * possible limit failure, matching {@link #acquire}.
     *
     * @return false only for a stale generation
     */
    public synchronized boolean setMask(UUID playerId, long connectionGeneration, Scope scope, int mask) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(scope, "scope");
        requireGeneration(connectionGeneration);
        requireOpen();
        if (mask < 0 || (mask & ~InterestKind.ALL_MASK) != 0) {
            throw new IllegalArgumentException("unknown interest mask: " + mask);
        }
        PlayerState state = players.get(playerId);
        if (state != null && connectionGeneration < state.generation) return false;
        if (state != null && connectionGeneration > state.generation) {
            removePlayerState(playerId, state);
            state = null;
        }
        EnumSet<InterestKind> previous = state == null ? null : state.interests.get(scope);
        Set<InterestKind> desired = InterestKind.fromMask(mask);
        if (previous == null && desired.isEmpty()) return true;
        if (previous != null && previous.equals(desired)) return true;

        boolean newPlayer = state == null && !desired.isEmpty();
        if (newPlayer) ensurePlayerCapacity();
        if (previous == null && !desired.isEmpty()) ensureScopeCapacity(scope);
        int previousCount = previous == null ? 0 : previous.size();
        long total = (long) interestCount - previousCount + desired.size();
        if (total > limits.maxInterests()) {
            throw new LimitExceededException("interest limit reached: " + limits.maxInterests());
        }

        if (previous != null) {
            for (InterestKind kind : Set.copyOf(previous)) {
                removeSubscriber(scope, kind, playerId);
            }
            interestCount -= previous.size();
            state.interests.remove(scope);
            decrementScope(scope);
            if (state.interests.isEmpty()) players.remove(playerId);
        }
        for (InterestKind kind : desired) {
            acquire(playerId, connectionGeneration, scope, kind);
        }
        return true;
    }

    /**
     * Removes exactly one interest. Only the current generation of that player may
     * release it; an old generation or a missing interest returns {@code false}
     * without touching state.
     */
    public synchronized boolean release(UUID playerId, long connectionGeneration, Scope scope,
            InterestKind kind) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        requireGeneration(connectionGeneration);
        requireOpen();

        PlayerState state = players.get(playerId);
        if (state == null || state.generation != connectionGeneration) {
            return false;
        }
        EnumSet<InterestKind> kinds = state.interests.get(scope);
        if (kinds == null || !kinds.remove(kind)) {
            return false;
        }
        interestCount--;
        removeSubscriber(scope, kind, playerId);
        if (kinds.isEmpty()) {
            state.interests.remove(scope);
            decrementScope(scope);
        }
        if (state.interests.isEmpty()) {
            players.remove(playerId);
        }
        return true;
    }

    /**
     * Removes every interest belonging to the exact connection generation.
     *
     * @return the number of interests removed; zero for an unknown or stale generation
     */
    public synchronized int disconnect(UUID playerId, long connectionGeneration) {
        Objects.requireNonNull(playerId, "playerId");
        requireGeneration(connectionGeneration);
        requireOpen();

        PlayerState state = players.get(playerId);
        if (state == null || state.generation != connectionGeneration) {
            return 0;
        }
        return removePlayerState(playerId, state);
    }

    /**
     * Releases one kind for every player holding it in {@code scope}. Other kinds in
     * the same scope and other scopes are untouched.
     *
     * @return the players whose interest was actually removed
     */
    public synchronized Set<UUID> releaseScope(Scope scope, InterestKind kind) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        requireOpen();

        Map<InterestKind, Set<UUID>> byKind = subscribers.get(scope);
        if (byKind == null) {
            return Set.of();
        }
        Set<UUID> holders = byKind.get(kind);
        if (holders == null || holders.isEmpty()) {
            return Set.of();
        }
        Set<UUID> affected = new LinkedHashSet<>(holders);
        Set<UUID> removed = new LinkedHashSet<>();
        for (UUID playerId : affected) {
            PlayerState state = players.get(playerId);
            if (state == null) {
                continue;
            }
            EnumSet<InterestKind> kinds = state.interests.get(scope);
            if (kinds == null || !kinds.remove(kind)) {
                continue;
            }
            removed.add(playerId);
            interestCount--;
            if (kinds.isEmpty()) {
                state.interests.remove(scope);
                decrementScope(scope);
            }
            if (state.interests.isEmpty()) {
                players.remove(playerId);
            }
        }
        byKind.remove(kind);
        if (byKind.isEmpty()) {
            subscribers.remove(scope);
        }
        return Set.copyOf(removed);
    }

    /**
     * Removes every project-scoped interest of {@code projectId}, regardless of kind
     * or connection generation. Global scopes with the same string id are not project
     * scopes and stay intact.
     *
     * @return the players whose interests were actually removed
     */
    public synchronized Set<UUID> removeProject(String projectId) {
        requireProjectId(projectId);
        requireOpen();
        return removeScope(Scope.project(projectId));
    }

    /**
     * @return an immutable snapshot of players holding {@code kind} for the project
     */
    public synchronized Set<UUID> subscribers(String projectId, InterestKind kind) {
        requireProjectId(projectId);
        return subscribers(Scope.project(projectId), kind);
    }

    /**
     * @return an immutable snapshot of players holding {@code kind} for {@code scope},
     *         including a global scope with the same string id as a project scope
     */
    public synchronized Set<UUID> subscribers(Scope scope, InterestKind kind) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        if (closed) {
            return Set.of();
        }
        Map<InterestKind, Set<UUID>> byKind = subscribers.get(scope);
        if (byKind == null) {
            return Set.of();
        }
        Set<UUID> holders = byKind.get(kind);
        return holders == null || holders.isEmpty() ? Set.of() : Set.copyOf(holders);
    }

    /**
     * @return all interests recorded for the exact player connection generation
     */
    public synchronized Set<Interest> interests(UUID playerId, long connectionGeneration) {
        Objects.requireNonNull(playerId, "playerId");
        requireGeneration(connectionGeneration);
        if (closed) {
            return Set.of();
        }
        PlayerState state = players.get(playerId);
        if (state == null || state.generation != connectionGeneration) {
            return Set.of();
        }
        return snapshot(playerId, state);
    }

    /**
     * @return all interests of the player's current connection generation
     */
    public synchronized Set<Interest> interests(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closed) {
            return Set.of();
        }
        PlayerState state = players.get(playerId);
        return state == null ? Set.of() : snapshot(playerId, state);
    }

    /** Number of players that currently hold at least one interest. */
    public synchronized int playerCount() {
        return closed ? 0 : players.size();
    }

    /** Number of recorded player/scope/kind interests. */
    public synchronized int interestCount() {
        return closed ? 0 : interestCount;
    }

    /** Number of distinct project scopes that currently have at least one interest. */
    public synchronized int projectCount() {
        return closed ? 0 : projectRefs.size();
    }

    /** Number of distinct global scopes that currently have at least one interest. */
    public synchronized int globalScopeCount() {
        return closed ? 0 : globalRefs.size();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Closes the registry, clears every interest and rejects later mutations. Closing
     * an already closed registry is a no-op.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        players.clear();
        subscribers.clear();
        projectRefs.clear();
        globalRefs.clear();
        interestCount = 0;
    }

    private Set<UUID> removeScope(Scope scope) {
        Map<InterestKind, Set<UUID>> byKind = subscribers.get(scope);
        if (byKind == null) {
            return Set.of();
        }
        Set<UUID> affected = new LinkedHashSet<>();
        for (Set<UUID> holders : byKind.values()) {
            affected.addAll(holders);
        }
        Set<UUID> removed = new LinkedHashSet<>();
        for (UUID playerId : affected) {
            PlayerState state = players.get(playerId);
            if (state == null) {
                continue;
            }
            EnumSet<InterestKind> kinds = state.interests.remove(scope);
            if (kinds == null) {
                continue;
            }
            removed.add(playerId);
            interestCount -= kinds.size();
            decrementScope(scope);
            if (state.interests.isEmpty()) {
                players.remove(playerId);
            }
        }
        subscribers.remove(scope);
        return Set.copyOf(removed);
    }

    private int removePlayerState(UUID playerId, PlayerState state) {
        int removed = 0;
        for (Map.Entry<Scope, EnumSet<InterestKind>> entry : state.interests.entrySet()) {
            Scope scope = entry.getKey();
            for (InterestKind kind : entry.getValue()) {
                removeSubscriber(scope, kind, playerId);
                removed++;
            }
            decrementScope(scope);
        }
        interestCount -= removed;
        players.remove(playerId);
        return removed;
    }

    private void removeSubscriber(Scope scope, InterestKind kind, UUID playerId) {
        Map<InterestKind, Set<UUID>> byKind = subscribers.get(scope);
        if (byKind == null) {
            return;
        }
        Set<UUID> holders = byKind.get(kind);
        if (holders != null) {
            holders.remove(playerId);
            if (holders.isEmpty()) {
                byKind.remove(kind);
            }
        }
        if (byKind.isEmpty()) {
            subscribers.remove(scope);
        }
    }

    private Set<Interest> snapshot(UUID playerId, PlayerState state) {
        Set<Interest> snapshot = new LinkedHashSet<>();
        for (Map.Entry<Scope, EnumSet<InterestKind>> entry : state.interests.entrySet()) {
            for (InterestKind kind : entry.getValue()) {
                snapshot.add(new Interest(playerId, state.generation, entry.getKey(), kind));
            }
        }
        return Set.copyOf(snapshot);
    }

    private void ensurePlayerCapacity() {
        if (players.size() >= limits.maxPlayers()) {
            throw new LimitExceededException("player interest limit reached: " + limits.maxPlayers());
        }
    }

    private void ensureScopeCapacity(Scope scope) {
        if (scope.isProject()) {
            if (!projectRefs.containsKey(scope.id())
                    && projectRefs.size() >= limits.maxProjectScopes()) {
                throw new LimitExceededException(
                        "project scope limit reached: " + limits.maxProjectScopes());
            }
        } else if (!globalRefs.containsKey(scope.id())
                && globalRefs.size() >= limits.maxGlobalScopes()) {
            throw new LimitExceededException(
                    "global scope limit reached: " + limits.maxGlobalScopes());
        }
    }

    private void incrementScope(Scope scope) {
        scopeRefs(scope).merge(scope.id(), 1, Integer::sum);
    }

    private void decrementScope(Scope scope) {
        Map<String, Integer> refs = scopeRefs(scope);
        Integer count = refs.get(scope.id());
        if (count == null) {
            return;
        }
        if (count <= 1) {
            refs.remove(scope.id());
        } else {
            refs.put(scope.id(), count - 1);
        }
    }

    private Map<String, Integer> scopeRefs(Scope scope) {
        return scope.isProject() ? projectRefs : globalRefs;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("ProjectInterestRegistry is closed");
        }
    }

    private static void requireGeneration(long connectionGeneration) {
        if (connectionGeneration <= 0) {
            throw new IllegalArgumentException("connection generation must be positive");
        }
    }

    private static void requireProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (projectId.length() > TeamLimits.PROJECT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "projectId exceeds " + TeamLimits.PROJECT_ID_LENGTH + " characters");
        }
    }

    /**
     * A project scope is addressed by its project id. A global scope is a separate
     * namespace even when its id string equals a project id.
     */
    public record Scope(String id, boolean global) {
        public Scope {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("scope id must not be blank");
            }
            if (id.length() > TeamLimits.PROJECT_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "scope id exceeds " + TeamLimits.PROJECT_ID_LENGTH + " characters");
            }
        }

        public static Scope project(String projectId) {
            return new Scope(projectId, false);
        }

        public static Scope global(String scopeId) {
            return new Scope(scopeId, true);
        }

        public boolean isProject() {
            return !global;
        }

        public String projectId() {
            if (global) {
                throw new IllegalStateException("global scope has no project id");
            }
            return id;
        }

        @Override
        public String toString() {
            return (global ? "global:" : "project:") + id;
        }
    }

    /** One recorded interest, usable as a stable query result. */
    public record Interest(UUID playerId, long connectionGeneration, Scope scope, InterestKind kind) {
        public Interest {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(kind, "kind");
            requireGeneration(connectionGeneration);
        }
    }

    /** Upper bounds for registry growth. All values must be positive. */
    public record Limits(int maxPlayers, int maxProjectScopes, int maxGlobalScopes,
            int maxInterests) {
        public Limits {
            if (maxPlayers <= 0 || maxProjectScopes <= 0 || maxGlobalScopes <= 0
                    || maxInterests <= 0) {
                throw new IllegalArgumentException("interest limits must all be positive");
            }
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_PLAYERS, DEFAULT_MAX_PROJECT_SCOPES,
                    DEFAULT_MAX_GLOBAL_SCOPES, DEFAULT_MAX_INTERESTS);
        }
    }

    /** Raised when accepting one more player, scope or interest would exceed a limit. */
    public static final class LimitExceededException extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public LimitExceededException(String message) {
            super(message);
        }
    }

    private static final class PlayerState {
        private final long generation;
        private final Map<Scope, EnumSet<InterestKind>> interests = new LinkedHashMap<>();

        private PlayerState(long generation) {
            this.generation = generation;
        }
    }
}
