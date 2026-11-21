package net.syncmaterial.syncmaterial.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.area.AreaScanService;
import net.syncmaterial.syncmaterial.server.ingest.IngestService;
import net.syncmaterial.syncmaterial.server.ingest.PlacementsDiscovery;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRuntime.class);

    private final UUID epoch = UUID.randomUUID();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final SqliteProjectStore store;
    private final ExecutorService parserExecutor;
    private final ScheduledExecutorService discoveryExecutor;
    private final IngestService ingest;
    private final LifecycleHook lifecycleHook;
    private final String interpretationId;
    private final AreaScanService areas;
    private final OnlineInventoryTracker inventories = new OnlineInventoryTracker();
    private final Object connectionLock = new Object();
    private final CollaborationService collaboration;
    private final ProjectProgressService progress;
    private final AtomicLong publicationSequences = new AtomicLong();
    private final TeamManagementService teams;
    private final TeamRequestTracker teamRequests = new TeamRequestTracker();
    private final Set<String> pendingTeamChanges = ConcurrentHashMap.newKeySet();
    private final MaterialCatalogTracker catalogs = new MaterialCatalogTracker();
    private final ProjectInterestRegistry interests = new ProjectInterestRegistry();
    private final ProjectViewTracker views = new ProjectViewTracker(interests);
    private final Map<UUID, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final Consumer<String> onProjectChanged;
    private final Set<String> pendingChanges = ConcurrentHashMap.newKeySet();
    private int discoveryTicks;

    private ServerRuntime(SqliteProjectStore store, MaterialDecoder decoder, Consumer<String> onProjectChanged,
                          LifecycleHook lifecycleHook) {
        this.store = store;
        this.onProjectChanged = onProjectChanged;
        this.parserExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "syncmaterial-decode-" + epoch);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.discoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncmaterial-discovery-" + epoch);
            thread.setDaemon(true);
            return thread;
        });
        this.ingest = new IngestService(store, decoder, parserExecutor);
        // Freeze once: the runtime-wide stale guard must not observe a decoder that
        // changes interpretation between calls.
        this.interpretationId = ingest.interpretationId();
        // Instantiate the scanner before attaching the lifecycle hook: the hook may
        // synchronously observe or drive the runtime during attach.
        this.areas = new AreaScanService(epoch, this::projectChanged, store);
        this.collaboration = new CollaborationService(store, inventories,
                this::isMaterialSnapshotCurrent, this::isOpen, this::projectChanged);
        this.progress = new ProjectProgressService(inventories);
        this.teams = new TeamManagementService(store, collaboration, discoveryExecutor,
                this::isMaterialSnapshotCurrent, this::isOpen, this::projectChanged,
                projectId -> { if (isOpen()) pendingTeamChanges.add(projectId); });
        this.lifecycleHook = lifecycleHook == null ? LifecycleHook.NONE : lifecycleHook;
        try {
            this.lifecycleHook.attach(this);
        } catch (RuntimeException | Error failure) {
            // attach runs after executors/ingest exist: close the whole runtime, not just
            // the store, so a failed open cannot leak parser/discovery threads.
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static ServerRuntime open(Path worldRoot, Path gameRoot, MaterialDecoder decoder,
                                     Consumer<String> onProjectChanged) throws IOException {
        return open(worldRoot, gameRoot, decoder, onProjectChanged, LifecycleHook.NONE);
    }

    public static ServerRuntime open(Path worldRoot, Path gameRoot, MaterialDecoder decoder,
                                     Consumer<String> onProjectChanged, LifecycleHook lifecycleHook)
            throws IOException {
        Path dataRoot = worldRoot.resolve("syncmaterial");
        Path database = dataRoot.resolve("runtime-v2.sqlite");
        if (!java.nio.file.Files.exists(database)) {
            Path legacy = Path.of("syncmaterial.db").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(legacy)) legacy = gameRoot.resolve("syncmaterial.db");
            Path backup = LegacyDatabaseBackup.preserve(legacy, dataRoot.resolve("legacy-backups"));
            if (backup != null) LOGGER.info("旧数据库已保留到 {}，不会导入新后端", backup);
        }
        SqliteProjectStore store = SqliteProjectStore.open(database);
        try {
            return new ServerRuntime(store, decoder,
                    java.util.Objects.requireNonNull(onProjectChanged), lifecycleHook);
        } catch (RuntimeException | Error failure) {
            // The constructor closes the runtime when attach fails; this also covers
            // failures before that point in case the store was the only live resource.
            try {
                store.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void startDiscovery(Path worldRoot, Path schematicsRoot) {
        PlacementsDiscovery discovery = new PlacementsDiscovery(
                worldRoot.resolve("syncmatica").resolve("placements.json"), schematicsRoot, ingest,
                this::projectChanged, error -> LOGGER.warn("新后端原理图导入失败，下次扫描将重试", error));
        discoveryExecutor.scheduleWithFixedDelay(() -> {
            if (!open.get()) return;
            try {
                discovery.scan();
            } catch (Exception error) {
                LOGGER.warn("新后端 placements 发现失败，不修改已提交项目", error);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public SqliteProjectStore store() {
        return store;
    }

    public CollaborationService collaboration() {
        return collaboration;
    }

    public TeamManagementService teams() {
        return teams;
    }

    public TeamRequestTracker teamRequests() {
        return teamRequests;
    }

    /** Platform ticks consume role changes separately from material-catalog changes. */
    public Set<String> takeTeamChanges() {
        if (!isOpen()) return Set.of();
        Set<String> changed = new java.util.HashSet<>();
        for (String projectId : Set.copyOf(pendingTeamChanges)) {
            if (pendingTeamChanges.remove(projectId)) changed.add(projectId);
        }
        return Set.copyOf(changed);
    }

    /** Runtime-owned area scanner; platform sources attach after the runtime opens. */
    public AreaScanService areas() {
        return areas;
    }

    public UUID epoch() {
        return epoch;
    }

    public MaterialCatalogTracker catalogs() {
        return catalogs;
    }

    public ProjectInterestRegistry interests() {
        return interests;
    }

    public ProjectViewTracker views() {
        return views;
    }

    public boolean isOpen() {
        return open.get();
    }

    /**
     * Material interpretation frozen for this runtime. Network stale guards compare
     * this value instead of asking the decoder again, so one runtime cannot mix
     * interpretation ids across requests.
     */
    public String interpretationId() {
        return interpretationId;
    }

    /**
     * Latest announced intent for a committed snapshot. Cold start (no announcement)
     * accepts a matching stored interpretation; a differing announced hash rejects it.
     */
    public boolean isMaterialSnapshotCurrent(ProjectSnapshot project) {
        return ingest.isMaterialSnapshotCurrent(project);
    }

    public CompletableFuture<Void> ingest(SchematicMetadata metadata) {
        // Notify the tick path on both outcomes: a failed re-parse must also let the
        // platform reject the preserved old rows instead of presenting them as current.
        return ingest.ingest(metadata).whenComplete((ignored, error) -> projectChanged(metadata.id()));
    }

    public CompletableFuture<Void> removeProject(String projectId) {
        return ingest.remove(projectId).whenComplete((ignored, error) -> {
            if (error == null) projectChanged(projectId);
            else LOGGER.warn("删除新后端项目失败: {}", projectId, error);
        });
    }

    private void projectChanged(String projectId) {
        if (open.get()) pendingChanges.add(projectId);
    }

    public void requestBroadcast(String projectId) {
        projectChanged(projectId);
    }

    public void connected(UUID playerId) {
        synchronized (connectionLock) {
            if (open.get()) {
                long previous = inventories.connection(playerId);
                if (previous > 0) {
                    views.disconnected(playerId, previous);
                    interests.disconnect(playerId, previous);
                }
                inventories.connected(playerId);
            }
        }
    }

    public void connected(UUID playerId, Object identity) {
        synchronized (connectionLock) {
            if (!open.get() || inventories.matchesConnection(playerId, identity)) return;
            disconnected(playerId, inventories.connection(playerId));
            inventories.connected(playerId, identity).forEach(this::projectChanged);
        }
    }

    public boolean matchesConnection(UUID playerId, Object identity) {
        return inventories.matchesConnection(playerId, identity);
    }

    public long connection(UUID playerId) {
        return inventories.connection(playerId);
    }

    public void disconnected(UUID playerId) {
        synchronized (connectionLock) {
            disconnected(playerId, inventories.connection(playerId));
        }
    }

    public void disconnected(UUID playerId, Object identity) {
        synchronized (connectionLock) {
            if (!inventories.matchesConnection(playerId, identity)) return;
            disconnected(playerId, inventories.connection(playerId));
        }
    }

    public void disconnected(UUID playerId, long expectedConnection) {
        synchronized (connectionLock) {
            if (expectedConnection <= 0 || inventories.connection(playerId) != expectedConnection) return;
            Set<String> affected = inventories.disconnected(playerId, expectedConnection);
            Set<String> subscribed = subscriptions.remove(playerId);
            catalogs.disconnected(playerId);
            teamRequests.disconnected(playerId);
            collaboration.disconnected(playerId);
            views.disconnected(playerId, expectedConnection);
            interests.disconnect(playerId, expectedConnection);
            // A claimant need not be a subscriber. Their removed observations still
            // change the project even when they never opened the material screen.
            affected.forEach(this::projectChanged);
            if (subscribed != null) subscribed.forEach(this::projectChanged);
        }
    }

    public void subscribe(UUID playerId, String projectId) {
        subscriptions.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(projectId);
    }

    public void unsubscribe(UUID playerId, String projectId) {
        Set<String> projects = subscriptions.get(playerId);
        if (projects != null) {
            projects.remove(projectId);
            if (projects.isEmpty()) subscriptions.remove(playerId, projects);
        }
        catalogs.forget(playerId, projectId);
    }

    public Set<UUID> subscribers(String projectId) {
        Set<UUID> players = new java.util.HashSet<>();
        for (var entry : subscriptions.entrySet()) {
            if (entry.getValue().contains(projectId)) players.add(entry.getKey());
        }
        return Set.copyOf(players);
    }

    public void tick() {
        if (!open.get()) return;
        try {
            areas.tick();
        } catch (RuntimeException | Error error) {
            LOGGER.warn("区域扫描 tick 失败，runtime 继续运行", error);
        }
        if (++discoveryTicks % 20 == 0) {
            try {
                lifecycleHook.tick();
            } catch (RuntimeException | Error error) {
                LOGGER.warn("平台生命周期钩子 tick 失败，runtime 继续运行", error);
            }
        }
        for (String projectId : inventories.expire()) projectChanged(projectId);
        for (String projectId : Set.copyOf(pendingChanges)) {
            if (pendingChanges.remove(projectId)) onProjectChanged.accept(projectId);
        }
    }

    public CompletableFuture<net.syncmaterial.syncmaterial.core.CollaborationOutcome> reportInventory(
            String projectId, int materialId, PlayerIdentity player, long count) {
        return collaboration.report(projectId, materialId, player, count);
    }

    public long inventoryCount(UUID playerId, String projectId, int materialId) {
        return inventories.count(playerId, projectId, materialId);
    }

    /** Reads all progress facts and one online sample inside the writer transaction. */
    public CompletableFuture<Optional<ProjectProgressRead>> readProgress(String projectId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Runtime is closed"));
        }
        return store.snapshot(projectId, state -> new ProjectProgressRead(state,
                inventories.snapshot(projectId, state.claims()),
                nextPublicationSequence()));
    }

    /** Runtime-wide monotonic id for ordered project-view content, independent of catalog revisions. */
    public long nextPublicationSequence() {
        return publicationSequences.updateAndGet(Math::incrementExact);
    }

    /**
     * Finalizes a writer read on the owning server thread. Area scans and material-intent
     * state are deliberately checked here, never from the storage thread. Empty means the
     * delayed online sample is no longer deliverable, not that the project was deleted.
     */
    public Optional<net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot> progressOnServer(
            ProjectProgressRead read) {
        Objects.requireNonNull(read, "read");
        if (!open.get() || !inventories.isCurrent(read.observations())) return Optional.empty();
        if (!isMaterialSnapshotCurrent(read.state().project())) {
            throw new IllegalStateException("stale material snapshot");
        }
        boolean areaCurrent = areas.isInventoryCurrent(read.state().project().id(), read.state().inventory());
        return Optional.of(progress.build(read.state(), read.observations(),
                read.publicationSequence(), areaCurrent));
    }

    public CompletableFuture<net.syncmaterial.syncmaterial.core.CollaborationOutcome> leave(
            String projectId, int materialId, PlayerIdentity player) {
        return collaboration.leave(projectId, materialId, player);
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        try {
            areas.close();
        } catch (RuntimeException | Error error) {
            LOGGER.warn("关闭区域扫描失败，继续 runtime 清理", error);
        }
        try {
            ingest.close();
        } catch (RuntimeException | Error error) {
            LOGGER.warn("关闭导入服务失败，继续 runtime 清理", error);
        }
        try {
            lifecycleHook.close();
        } catch (RuntimeException | Error error) {
            // A broken platform hook must never skip executor/store cleanup.
            LOGGER.warn("关闭平台生命周期钩子失败，继续 runtime 清理", error);
        }
        discoveryExecutor.shutdownNow();
        parserExecutor.shutdown();
        try {
            if (!discoveryExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("原理图发现任务仍在结束，旧 runtime 已封闭");
            }
            if (!parserExecutor.awaitTermination(5, TimeUnit.SECONDS)) parserExecutor.shutdownNow();
        } catch (InterruptedException error) {
            parserExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            inventories.clear();
            subscriptions.clear();
            pendingChanges.clear();
            pendingTeamChanges.clear();
            teamRequests.clear();
            collaboration.clear();
            teams.clear();
            catalogs.clear();
            views.close();
            interests.close();
            store.close();
        }
    }
}
