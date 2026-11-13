package net.syncmaterial.syncmaterial.server.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

/** Runtime-owned team application. Storage completions never access Minecraft objects. */
public final class TeamManagementService {
    private final SqliteProjectStore store;
    private final OnlineInventoryTracker inventories;
    private final Executor directoryExecutor;
    private final Predicate<ProjectSnapshot> currentMaterials;
    private final BooleanSupplier open;
    private final Consumer<String> materialsChanged;
    private final Consumer<String> teamChanged;
    private final java.util.concurrent.ConcurrentHashMap<String, TeamState> committedStates = new java.util.concurrent.ConcurrentHashMap<>();

    TeamManagementService(SqliteProjectStore store, OnlineInventoryTracker inventories, Executor directoryExecutor,
                          Predicate<ProjectSnapshot> currentMaterials, BooleanSupplier open,
                          Consumer<String> materialsChanged, Consumer<String> teamChanged) {
        this.store = Objects.requireNonNull(store);
        this.inventories = Objects.requireNonNull(inventories);
        this.directoryExecutor = Objects.requireNonNull(directoryExecutor);
        this.currentMaterials = Objects.requireNonNull(currentMaterials);
        this.open = Objects.requireNonNull(open);
        this.materialsChanged = Objects.requireNonNull(materialsChanged);
        this.teamChanged = Objects.requireNonNull(teamChanged);
    }

    public CompletableFuture<TeamOutcome> execute(TeamCommand command, PlayerIdentity actor,
                                                  BooleanSupplier connected) {
        Objects.requireNonNull(connected);
        return store.applyTeamCommand(command, actor, currentMaterials,
                () -> open.getAsBoolean() && connected.getAsBoolean()).handle((outcome, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof net.syncmaterial.syncmaterial.server.storage.CommittedStorageException committed) {
                    TeamOutcome durable = committed.committedResult(TeamOutcome.class);
                    if (durable != null) afterCommit(command, durable);
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            afterCommit(command, outcome);
            return outcome;
        });
    }

    private void afterCommit(TeamCommand command, TeamOutcome outcome) {
        if (!outcome.success() || !open.getAsBoolean()) return;
        if (outcome.state() != null && command.action().changesTeam() && outcome.changedCount() > 0) {
            committedStates.put(command.projectId(), outcome.state());
        }
        for (var observation : outcome.removedObservations()) {
            if (observation.player().hasUuid()) {
                inventories.forget(observation.player().uuid(), command.projectId(), observation.materialId());
            }
        }
        // A commit belongs to the project even when the initiating connection left.
        if (!outcome.changedMaterials().isEmpty() || !outcome.removedObservations().isEmpty()) {
            materialsChanged.accept(command.projectId());
        }
        if (command.action().changesTeam() && outcome.changedCount() > 0) teamChanged.accept(command.projectId());
    }

    public TeamState takeCommittedState(String projectId) {
        return committedStates.remove(projectId);
    }

    void clear() {
        committedStates.clear();
    }

    public CompletableFuture<Optional<TeamState>> state(String projectId) {
        return store.teamState(projectId);
    }

    public CompletableFuture<net.syncmaterial.syncmaterial.core.PlayerDirectoryView> directory(String projectId,
            List<PlayerIdentity> online, Supplier<List<PlayerIdentity>> knownSource) {
        List<PlayerIdentity> identities = List.copyOf(online);
        return PlayerDirectoryInput.load(identities, knownSource, open, directoryExecutor)
                .thenCompose(input -> reconcileKnown(input.trusted())
                        .thenCompose(ignored -> store.knownPlayerDirectory(projectId))
                        .thenApply(stored -> {
                            List<PlayerDirectoryEntry> merged = PlayerDirectory.merge(stored.players(), input.cached(), identities);
                            boolean limited = stored.limited() || input.limited()
                                    || merged.size() == net.syncmaterial.syncmaterial.core.TeamLimits.DIRECTORY;
                            return new net.syncmaterial.syncmaterial.core.PlayerDirectoryView(merged, limited);
                        }));
    }

    public CompletableFuture<TeamCommand> resolveTargets(TeamCommand command, List<PlayerIdentity> online,
                                                          Supplier<List<PlayerIdentity>> knownSource) {
        if (command.targets().isEmpty()) return CompletableFuture.completedFuture(command);
        List<PlayerIdentity> identities = List.copyOf(online);
        return PlayerDirectoryInput.load(identities, knownSource, open, directoryExecutor)
                .thenCompose(input -> reconcileKnown(input.trusted()).thenCompose(ignored ->
                        store.resolveTeamTargets(command.targets(), identities, input.cached())))
                .thenApply(targets -> new TeamCommand(command.projectId(), command.requestId(), command.action(),
                        command.catalogRevision(), command.materialIds(), targets));
    }

    public CompletableFuture<Boolean> join(String projectId, int materialId, PlayerIdentity actor,
                                            Set<Integer> reportedMaterials, BooleanSupplier connected) {
        return store.joinAuthorized(projectId, materialId, actor, reportedMaterials, currentMaterials,
                () -> open.getAsBoolean() && connected.getAsBoolean());
    }

    public CompletableFuture<Void> reconcile(PlayerIdentity player) {
        if (!open.getAsBoolean()) return CompletableFuture.failedFuture(new IllegalStateException("Team runtime is closed"));
        return reconcileKnown(List.of(player));
    }

    private CompletableFuture<Void> reconcileKnown(List<PlayerIdentity> identities) {
        return store.reconcileTeamPlayers(identities).thenAccept(projects -> {
            if (open.getAsBoolean()) projects.forEach(teamChanged);
        });
    }
}
