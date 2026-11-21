package net.syncmaterial.syncmaterial.server.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.server.storage.CommittedStorageException;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

/**
 * Foundation collaboration application service. Admission and membership epochs are checked
 * by the writer; observations and change markers are applied after the real commit and before
 * the next writer job. No Minecraft object, world read or packet send crosses this boundary.
 */
public final class CollaborationService {
    private static final int MAX_MEMBERSHIP_EPOCHS = 65_536;

    private final SqliteProjectStore store;
    private final OnlineInventoryTracker inventories;
    private final Predicate<ProjectSnapshot> currentMaterials;
    private final BooleanSupplier open;
    private final Consumer<String> projectChanged;
    private final Object membershipLock = new Object();
    private final Map<Participation, Membership> memberships = new HashMap<>();

    public CollaborationService(SqliteProjectStore store, OnlineInventoryTracker inventories,
                                Predicate<ProjectSnapshot> currentMaterials, BooleanSupplier open,
                                Consumer<String> projectChanged) {
        this.store = Objects.requireNonNull(store, "store");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.currentMaterials = Objects.requireNonNull(currentMaterials, "currentMaterials");
        this.open = Objects.requireNonNull(open, "open");
        this.projectChanged = Objects.requireNonNull(projectChanged, "projectChanged");
    }

    public CompletableFuture<CollaborationOutcome> join(String projectId, int materialId,
            PlayerIdentity player, Map<Integer, Long> reportedInventory) {
        Objects.requireNonNull(player, "player");
        UUID playerId = requireUuid(player);
        long connection = inventories.connection(playerId);
        if (!open.getAsBoolean() || connection == 0) {
            return CompletableFuture.completedFuture(outcome(CollaborationOutcome.Action.JOIN,
                    CollaborationOutcome.Status.DENIED, projectId, materialId));
        }
        Map<Integer, Long> counts = new HashMap<>(Objects.requireNonNull(reportedInventory, "reportedInventory"));
        counts.putIfAbsent(materialId, 0L);
        Map<Integer, Long> initial = Map.copyOf(counts);
        Reservation reservation = reserve(playerId, connection, projectId, materialId);
        BooleanSupplier admission = admission(playerId, connection);
        BooleanSupplier current = () -> isCurrent(reservation.current());
        CompletableFuture<CollaborationOutcome> operation = store.joinCollaboration(
                projectId, materialId, player, initial, currentMaterials, admission, current,
                outcome -> {
                    if (!outcome.success()) {
                        restore(reservation);
                        return;
                    }
                    inventories.update(playerId, connection, projectId, materialId,
                            initial.get(materialId));
                    projectChanged.accept(projectId);
                });
        return restoreAfterUncommittedFailure(operation, reservation);
    }

    public CompletableFuture<CollaborationOutcome> leave(String projectId, int materialId,
                                                         PlayerIdentity player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = requireUuid(player);
        long connection = inventories.connection(playerId);
        if (!open.getAsBoolean() || connection == 0) {
            return CompletableFuture.completedFuture(outcome(CollaborationOutcome.Action.LEAVE,
                    CollaborationOutcome.Status.DENIED, projectId, materialId));
        }
        Reservation reservation = reserve(playerId, connection, projectId, materialId);
        CompletableFuture<CollaborationOutcome> operation = store.leaveCollaboration(
                projectId, materialId, player, currentMaterials, admission(playerId, connection),
                () -> isCurrent(reservation.current()), outcome -> {
                    if (!outcome.success()) {
                        restore(reservation);
                        return;
                    }
                    inventories.forget(playerId, projectId, materialId);
                    projectChanged.accept(projectId);
                });
        return restoreAfterUncommittedFailure(operation, reservation);
    }

    public CompletableFuture<CollaborationOutcome> report(String projectId, int materialId,
                                                          PlayerIdentity player, long count) {
        Objects.requireNonNull(player, "player");
        UUID playerId = requireUuid(player);
        long connection = inventories.connection(playerId);
        if (!open.getAsBoolean() || connection == 0) {
            return CompletableFuture.completedFuture(outcome(CollaborationOutcome.Action.REPORT,
                    CollaborationOutcome.Status.DENIED, projectId, materialId));
        }
        Membership captured = capture(playerId, connection, projectId, materialId);
        return store.reportCollaboration(projectId, materialId, player, count, currentMaterials,
                admission(playerId, connection), () -> isCurrent(captured), outcome -> {
                    if (outcome.success()) {
                        inventories.update(playerId, connection, projectId, materialId, count);
                        projectChanged.accept(projectId);
                    }
                });
    }

    /** Team kicks and assignments invalidate queued reports in the same writer commit order. */
    void afterTeamCommit(TeamCommand command, TeamOutcome outcome) {
        if (!outcome.success()) return;
        if (command.action() == TeamAction.KICK) {
            for (var observation : outcome.removedObservations()) {
                PlayerIdentity target = observation.player();
                if (!target.hasUuid()) continue;
                inventories.forget(target.uuid(), command.projectId(), observation.materialId());
                advance(target.uuid(), command.projectId(), observation.materialId());
            }
        } else if (command.action() == TeamAction.ASSIGN && !outcome.changedMaterials().isEmpty()) {
            for (int materialId : outcome.changedMaterials()) {
                for (PlayerIdentity target : command.targets()) {
                    if (target.hasUuid()) advance(target.uuid(), command.projectId(), materialId);
                }
            }
        }
    }

    void disconnected(UUID playerId) {
        synchronized (membershipLock) {
            memberships.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    void clear() {
        synchronized (membershipLock) {
            memberships.clear();
        }
    }

    private Reservation reserve(UUID playerId, long connection, String projectId, int materialId) {
        synchronized (membershipLock) {
            Participation key = new Participation(playerId, projectId, materialId);
            Membership previous = memberships.get(key);
            long previousEpoch = previous == null ? 0L : previous.epoch();
            ensureCapacity(key);
            Membership current = new Membership(playerId, connection, projectId, materialId,
                    Math.incrementExact(previousEpoch));
            memberships.put(key, current);
            return new Reservation(key, previous, current);
        }
    }

    private Membership capture(UUID playerId, long connection, String projectId, int materialId) {
        synchronized (membershipLock) {
            Membership current = memberships.get(new Participation(playerId, projectId, materialId));
            long epoch = current == null ? 0L : current.epoch();
            return new Membership(playerId, connection, projectId, materialId, epoch);
        }
    }

    private void advance(UUID playerId, String projectId, int materialId) {
        synchronized (membershipLock) {
            Participation key = new Participation(playerId, projectId, materialId);
            Membership previous = memberships.get(key);
            ensureCapacity(key);
            long connection = previous == null ? inventories.connection(playerId) : previous.connection();
            memberships.put(key, new Membership(playerId, connection, projectId, materialId,
                    Math.incrementExact(previous == null ? 0L : previous.epoch())));
        }
    }

    private boolean isCurrent(Membership expected) {
        synchronized (membershipLock) {
            Membership current = memberships.get(expected.participation());
            return expected.epoch() == (current == null ? 0L : current.epoch());
        }
    }

    private void restore(Reservation reservation) {
        synchronized (membershipLock) {
            if (!reservation.current().equals(memberships.get(reservation.key()))) return;
            if (reservation.previous() == null) memberships.remove(reservation.key());
            else memberships.put(reservation.key(), reservation.previous());
        }
    }

    private CompletableFuture<CollaborationOutcome> restoreAfterUncommittedFailure(
            CompletableFuture<CollaborationOutcome> operation, Reservation reservation) {
        return operation.whenComplete((outcome, error) -> {
            if (error != null && !committed(error)) restore(reservation);
        });
    }

    private static boolean committed(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CommittedStorageException) return true;
            current = current.getCause();
        }
        return false;
    }

    private BooleanSupplier admission(UUID playerId, long connection) {
        return () -> open.getAsBoolean() && inventories.connection(playerId) == connection;
    }

    private void ensureCapacity(Participation key) {
        if (memberships.containsKey(key) || memberships.size() < MAX_MEMBERSHIP_EPOCHS) return;
        memberships.entrySet().removeIf(entry -> inventories.connection(entry.getKey().playerId()) == 0);
        if (!memberships.containsKey(key) && memberships.size() >= MAX_MEMBERSHIP_EPOCHS) {
            throw new IllegalStateException("membership epoch limit reached");
        }
    }

    private static UUID requireUuid(PlayerIdentity player) {
        if (!player.hasUuid()) {
            throw new IllegalArgumentException("online collaboration requires a trusted UUID");
        }
        return player.uuid();
    }

    private static CollaborationOutcome outcome(CollaborationOutcome.Action action,
            CollaborationOutcome.Status status, String projectId, int materialId) {
        return CollaborationOutcome.of(action, status, projectId, materialId);
    }

    private record Participation(UUID playerId, String projectId, int materialId) {
        private Participation {
            Objects.requireNonNull(playerId, "playerId");
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId must not be blank");
            }
            if (materialId <= 0) throw new IllegalArgumentException("materialId must be > 0");
        }
    }

    private record Membership(UUID playerId, long connection, String projectId, int materialId, long epoch) {
        private Membership {
            if (connection < 0 || epoch < 0) throw new IllegalArgumentException("negative membership state");
        }

        private Participation participation() {
            return new Participation(playerId, projectId, materialId);
        }
    }

    private record Reservation(Participation key, Membership previous, Membership current) {}

}
