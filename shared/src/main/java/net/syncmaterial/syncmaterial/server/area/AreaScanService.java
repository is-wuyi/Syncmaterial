package net.syncmaterial.syncmaterial.server.area;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime-owned area scanner scheduler. All mutable state is confined to the server thread;
 * storage futures only enqueue completions, which {@link #tick()} drains. A heavy chunk read
 * is a resumable cursor whose state contains no Minecraft objects, and every chunk has at
 * most one full-scan/dirty operation at a time. Overlapping areas share one chunk read.
 */
public final class AreaScanService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AreaScanService.class);

    static final int MAX_CONTAINERS_PER_TICK = 64;
    static final int MAX_SLOTS_PER_TICK = 1024;
    static final int MAX_CONTAINERS_PER_SLICE = 16;
    static final int MAX_SLOTS_PER_SLICE = 256;
    static final int MAX_DIRTY_CHUNKS_PER_TICK = 8;
    static final int MAX_SCAN_STARTS_PER_TICK = 1;
    static final long SCAN_NANOS_PER_TICK = 1_500_000L;
    static final int CATALOG_POLL_TICKS = 100;
    static final int RECONCILE_INTERVAL_TICKS = 200;
    static final int MAX_RESCAN_CHUNKS = 4096;
    static final int MAX_CURSOR_CONTAINERS = 2048;

    private static final AreaCatalog EMPTY_CATALOG = new AreaCatalog(List.of(), Map.of(), Map.of(), 0);

    private final UUID runtimeEpoch;
    private final Consumer<String> onProjectChanged;
    private final AreaScanBackend backend;
    private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    // ---- server-thread state ----
    private AreaSnapshotSource source = AreaSnapshotSource.NONE;
    private AreaCatalog catalog = EMPTY_CATALOG;
    private Map<Integer, AreaDefinition> areasById = Map.of();
    private Map<Integer, Set<String>> warehouseProjectRefs = Map.of();
    private boolean catalogApplied;
    private long configApplyRevision = -1;
    private long epoch = -1;
    private boolean epochReady;
    private long epochRetryTick = Long.MAX_VALUE;
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong lastRefreshTick = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicInteger refreshInFlight = new AtomicInteger();
    private final Set<CompletableFuture<Void>> pendingRefreshFutures = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<RescanResult>> pendingRescanFutures = ConcurrentHashMap.newKeySet();

    private final Set<ChunkKey> loadedChunks = new LinkedHashSet<>();
    private final Map<ChunkKey, ChunkState> chunks = new HashMap<>();
    private final Queue<ChunkKey> scanQueue = new java.util.ArrayDeque<>();
    private final Set<ChunkKey> scanQueued = new HashSet<>();
    private final Queue<ChunkKey> dirtyQueue = new java.util.ArrayDeque<>();
    private final Set<ChunkKey> dirtyQueued = new HashSet<>();
    private final List<DirtyOp> activeDirtyOps = new ArrayList<>();
    private ScanOp activeScan;
    private final List<RescanRequest> rescans = new ArrayList<>();

    private final Map<Long, Set<String>> reasons = new HashMap<>();
    private final Map<String, Set<Long>> projectReasons = new HashMap<>();
    private final Map<String, Long> requiredRevision = new HashMap<>();
    private long nextReasonId;

    private final Queue<ChunkKey> reconcileQueue = new java.util.ArrayDeque<>();
    private final AtomicLong lastReconcileTick = new AtomicLong(Long.MIN_VALUE / 2);

    /** Production constructor: binds directly to the SQLite scan store. */
    public AreaScanService(UUID runtimeEpoch, Consumer<String> onProjectChanged, SqliteProjectStore store) {
        this(runtimeEpoch, onProjectChanged, new StoreAreaScanBackend(
                Objects.requireNonNull(store, "store")));
    }

    /** Test constructor: fake backends live in the scanner package. */
    AreaScanService(UUID runtimeEpoch, Consumer<String> onProjectChanged, AreaScanBackend backend) {
        this.runtimeEpoch = Objects.requireNonNull(runtimeEpoch, "runtimeEpoch");
        this.onProjectChanged = onProjectChanged == null ? ignored -> {
        } : onProjectChanged;
        this.backend = Objects.requireNonNull(backend, "backend");
        requestEpoch();
        refreshConfiguration().exceptionally(error -> null);
    }

    public UUID runtimeEpoch() {
        return runtimeEpoch;
    }

    public Optional<AreaCatalog> configuration() {
        return catalogApplied ? Optional.of(catalog) : Optional.empty();
    }

    public void attachSource(AreaSnapshotSource source) {
        if (!open.get()) {
            return;
        }
        this.source = Objects.requireNonNull(source, "source");
        Set<ChunkKey> reported = source.loadedChunks();
        if (reported != null) {
            for (ChunkKey chunk : reported) {
                if (loadedChunks.add(chunk)) {
                    ChunkState state = state(chunk);
                    state.sequence++;
                    if (!catalog.areasCovering(chunk).isEmpty()) {
                        markScan(state, false);
                    }
                }
            }
        }
        reconcileQueue.clear();
        lastReconcileTick.set(Long.MIN_VALUE / 2);
        for (ChunkKey chunk : new ArrayList<>(loadedChunks)) {
            if (!catalog.areasCovering(chunk).isEmpty()) {
                markScan(state(chunk), false);
            }
        }
    }

    // ==================================================================
    // Configuration / epoch
    // ==================================================================

    public CompletableFuture<Void> refreshConfiguration() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!open.get()) {
            future.completeExceptionally(new IllegalStateException("area scanner is closed"));
            return future;
        }
        pendingRefreshFutures.add(future);
        refreshInFlight.incrementAndGet();
        backend.areaCatalog().whenComplete((nextCatalog, error) -> enqueue(() -> {
            refreshInFlight.decrementAndGet();
            pendingRefreshFutures.remove(future);
            if (!open.get()) {
                future.completeExceptionally(new IllegalStateException("area scanner is closed"));
                return;
            }
            if (error != null) {
                LOGGER.warn("区域配置刷新失败", error);
                future.completeExceptionally(error);
                return;
            }
            applyCatalog(nextCatalog);
            future.complete(null);
        }));
        return future;
    }

    private void requestEpoch() {
        if (!open.get()) {
            return;
        }
        backend.beginEpoch().whenComplete((value, error) -> enqueue(() -> {
            if (!open.get()) {
                return;
            }
            if (error != null) {
                LOGGER.warn("区域扫描会话 epoch 初始化失败，稍后重试", error);
                epochRetryTick = tickCount.get() + 100;
                return;
            }
            epoch = value;
            epochReady = true;
            epochRetryTick = Long.MAX_VALUE;
            for (ChunkKey chunk : new ArrayList<>(loadedChunks)) {
                if (!catalog.areasCovering(chunk).isEmpty()) {
                    markScan(state(chunk), false);
                }
            }
        }));
    }

    private void applyCatalog(AreaCatalog nextCatalog) {
        Objects.requireNonNull(nextCatalog, "catalog");
        if (nextCatalog.revision() < configApplyRevision) {
            // A stale asynchronous refresh must never roll the index backwards.
            return;
        }
        AreaCatalog previous = catalog;
        Map<Integer, Set<String>> previousRefs = warehouseProjectRefs;
        Map<Integer, AreaDefinition> previousAreas = areasById;

        Map<Integer, Set<String>> nextRefs = buildWarehouseRefs(nextCatalog);
        Set<String> affected = new LinkedHashSet<>();
        Set<Integer> invalidating = new LinkedHashSet<>();

        Set<Integer> ids = new LinkedHashSet<>(previousAreas.keySet());
        ids.addAll(nextCatalog.areas().stream().map(AreaDefinition::id).toList());
        for (int areaId : ids) {
            AreaDefinition oldArea = previousAreas.get(areaId);
            AreaDefinition newArea = nextCatalog.findArea(areaId).orElse(null);
            if (oldArea == null || newArea == null || invalidatesCoverage(oldArea, newArea)) {
                if (oldArea != null) {
                    invalidating.add(areaId);
                    affected.addAll(projectsForArea(oldArea, previousRefs));
                }
                if (newArea != null) {
                    invalidating.add(areaId);
                    affected.addAll(projectsForArea(newArea, nextRefs));
                }
            } else if (!oldArea.equals(newArea)) {
                affected.addAll(projectsForArea(newArea, nextRefs));
            }
        }

        Set<String> referenceProjects = new LinkedHashSet<>(previous.warehouseReferences().keySet());
        referenceProjects.addAll(nextCatalog.warehouseReferences().keySet());
        for (String projectId : referenceProjects) {
            if (!Objects.equals(previous.warehouseReferences().get(projectId),
                    nextCatalog.warehouseReferences().get(projectId))) {
                affected.add(projectId);
                invalidating.addAll(nextCatalog.referencedWarehouseIds(projectId));
                invalidating.addAll(previous.warehouseReferences().getOrDefault(projectId, Set.of()));
            }
        }
        Set<String> nameProjects = new LinkedHashSet<>(previous.projectNames().keySet());
        nameProjects.addAll(nextCatalog.projectNames().keySet());
        for (String projectId : nameProjects) {
            if (!Objects.equals(previous.projectNames().get(projectId), nextCatalog.projectNames().get(projectId))) {
                affected.add(projectId);
            }
        }

        catalog = nextCatalog;
        areasById = new HashMap<>();
        for (AreaDefinition area : nextCatalog.areas()) {
            areasById.put(area.id(), area);
        }
        warehouseProjectRefs = nextRefs;
        catalogApplied = true;
        configApplyRevision = nextCatalog.revision();

        List<AreaDefinition> invalidatingAreas = new ArrayList<>();
        for (int areaId : invalidating) {
            AreaDefinition area = nextCatalog.findArea(areaId).orElse(null);
            if (area != null) {
                invalidatingAreas.add(area);
            }
        }
        if (!invalidatingAreas.isEmpty()) {
            for (ChunkKey chunk : new ArrayList<>(loadedChunks)) {
                if (coversAny(chunk, invalidatingAreas)) {
                    markScan(state(chunk), false);
                }
            }
        }

        for (ChunkState state : new ArrayList<>(chunks.values())) {
            syncState(state);
        }
        broadcast(affected);
        captureProjectWatermark(affected);
    }

    private static boolean invalidatesCoverage(AreaDefinition oldArea, AreaDefinition newArea) {
        return oldArea.kind() != newArea.kind()
                || !Objects.equals(oldArea.projectId(), newArea.projectId())
                || !oldArea.world().equals(newArea.world())
                || !oldArea.bounds().equals(newArea.bounds())
                || oldArea.geometryRevision() != newArea.geometryRevision();
    }

    private static boolean coversAny(ChunkKey chunk, List<AreaDefinition> areas) {
        for (AreaDefinition area : areas) {
            if (area.world().equals(chunk.world())
                    && area.bounds().containsChunk(chunk.x(), chunk.z())) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, Set<String>> buildWarehouseRefs(AreaCatalog catalog) {
        Map<Integer, Set<String>> refs = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : catalog.warehouseReferences().entrySet()) {
            for (Integer areaId : entry.getValue()) {
                refs.computeIfAbsent(areaId, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        Map<Integer, Set<String>> immutable = new HashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : refs.entrySet()) {
            immutable.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private void addProjectsForArea(Set<String> out, AreaDefinition area, Map<Integer, Set<String>> refs) {
        if (area.kind() == AreaKind.STAGING_AREA) {
            if (area.projectId() != null) {
                out.add(area.projectId());
            }
        } else {
            out.addAll(refs.getOrDefault(area.id(), Set.of()));
        }
    }

    private Set<String> projectsForArea(AreaDefinition area, Map<Integer, Set<String>> refs) {
        Set<String> out = new LinkedHashSet<>();
        addProjectsForArea(out, area, refs);
        return out;
    }

    private Set<String> projectsForChunk(ChunkKey chunk) {
        Set<String> out = new LinkedHashSet<>();
        for (AreaDefinition area : catalog.areasCovering(chunk)) {
            addProjectsForArea(out, area, warehouseProjectRefs);
        }
        return out;
    }

    // ==================================================================
    // Chunk / container events
    // ==================================================================

    public void chunkLoaded(ChunkKey chunk) {
        if (!open.get() || !loadedChunks.add(chunk)) {
            return;
        }
        ChunkState state = state(chunk);
        state.sequence++;
        if (!catalog.areasCovering(chunk).isEmpty()) {
            markScan(state, false);
        } else {
            syncState(state);
        }
    }

    public void chunkUnloaded(ChunkKey chunk) {
        if (!open.get() || !loadedChunks.remove(chunk)) {
            return;
        }
        ChunkState state = chunks.get(chunk);
        if (state != null) {
            state.sequence++;
            state.scanWanted = false;
            scanQueued.remove(chunk);
            state.changed.clear();
            state.removed.clear();
            if (state.scan != null) {
                cancelScan(state.scan, false);
            }
            if (state.dirty != null) {
                cancelDirtyOp(state.dirty);
            }
        }

        // Stale is physical coverage state, so it cannot depend on a project reference
        // (an unreferenced warehouse would otherwise look known after a later reference).
        if (catalog.areasCovering(chunk).isEmpty()) {
            if (state != null) {
                syncState(state);
            }
            return;
        }
        queueStale(state != null ? state : state(chunk), projectsForChunk(chunk));
    }

    public void containerChanged(ContainerKey key) {
        onContainerEvent(key, true);
    }

    public void containerRemoved(ContainerKey key) {
        onContainerEvent(key, false);
    }

    private void onContainerEvent(ContainerKey key, boolean changed) {
        if (!open.get() || key == null || !loadedChunks.contains(key.chunk())) {
            return;
        }
        Set<String> projects = projectsForChunk(key.chunk());
        if (projects.isEmpty()) {
            return;
        }
        ChunkState state = state(key.chunk());
        state.sequence++;
        if (changed) {
            state.removed.remove(key);
            state.changed.add(key);
        } else {
            state.changed.remove(key);
            state.removed.add(key);
        }
        ensureReason(state, projects);
        ensureInvalidation(state);
        enqueueDirty(state);
        syncState(state);
    }

    // ==================================================================
    // Tick / scheduling
    // ==================================================================

    public void tick() {
        if (!open.get()) {
            return;
        }
        long nowTick = tickCount.incrementAndGet();
        TickBudget budget = new TickBudget(System.nanoTime() + SCAN_NANOS_PER_TICK);
        drainCompletions();
        if (!open.get()) {
            return;
        }
        if (!epochReady && nowTick >= epochRetryTick) {
            epochRetryTick = Long.MAX_VALUE;
            requestEpoch();
        }
        maybeRefreshCatalog();
        reconcileLoadedChunks(budget);
        processDirty(budget);
        processScan(budget);
        updateRescans();
        dropIdleStates();
    }

    private void drainCompletions() {
        int budget = 256;
        Runnable task;
        while (budget-- > 0 && (task = completions.poll()) != null) {
            if (!open.get()) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException | Error error) {
                LOGGER.warn("区域扫描完成回调失败", error);
            }
        }
    }

    private void maybeRefreshCatalog() {
        if (refreshInFlight.get() > 0) {
            return;
        }
        long nowTick = tickCount.get();
        if (nowTick - lastRefreshTick.get() < (catalogApplied ? CATALOG_POLL_TICKS : 1)) {
            return;
        }
        lastRefreshTick.set(nowTick);
        refreshConfiguration().exceptionally(error -> null);
    }

    private void reconcileLoadedChunks(TickBudget budget) {
        if (!source.isAvailable() || !budget.hasTime() || activeScan != null) {
            return;
        }
        if (tickCount.get() - lastReconcileTick.get() < RECONCILE_INTERVAL_TICKS) {
            return;
        }
        lastReconcileTick.set(tickCount.get());
        if (reconcileQueue.isEmpty()) {
            reconcileQueue.addAll(loadedChunks);
        }
        ChunkKey chunk = reconcileQueue.poll();
        if (chunk == null || !loadedChunks.contains(chunk) || !source.isChunkLoaded(chunk)) {
            return;
        }
        if (catalog.areasCovering(chunk).isEmpty()) {
            return;
        }
        ChunkState state = state(chunk);
        if (state.scan != null || state.dirty != null || state.scanWanted
                || !state.changed.isEmpty() || !state.removed.isEmpty()) {
            return;
        }
        markScan(state, false);
    }

    // ==================================================================
    // Dirty updates
    // ==================================================================

    private void processDirty(TickBudget budget) {
        if (!budget.hasTime()) {
            return;
        }
        int progressed = 0;
        for (DirtyOp op : new ArrayList<>(activeDirtyOps)) {
            if (!budget.hasTime() || progressed >= MAX_DIRTY_CHUNKS_PER_TICK) {
                return;
            }
            if (op.phase == DirtyPhase.BEGINS && op.waits == 0) {
                advanceDirty(op, budget);
                progressed++;
            }
        }
        int attempts = dirtyQueue.size();
        while (attempts-- > 0 && progressed < MAX_DIRTY_CHUNKS_PER_TICK && budget.hasTime()) {
            ChunkKey chunk = dirtyQueue.poll();
            if (chunk == null) {
                return;
            }
            dirtyQueued.remove(chunk);
            ChunkState state = chunks.get(chunk);
            if (state == null || state.dirty != null
                    || (state.changed.isEmpty() && state.removed.isEmpty())) {
                continue;
            }
            if (!loadedChunks.contains(state.chunk)) {
                state.changed.clear();
                state.removed.clear();
                syncState(state);
                continue;
            }
            if (state.scanWanted) {
                // The full scan captures these changes when it starts.
                continue;
            }
            if (!source.isAvailable()) {
                dirtyQueue.add(chunk);
                dirtyQueued.add(chunk);
                return;
            }
            if (!source.isChunkLoaded(state.chunk)) {
                handleMissingChunk(state);
                continue;
            }
            if (!ensureInvalidation(state)) {
                // Wait for the metadata invalidation transaction before beginning per-area
                // dirty tokens. Re-queue only after it settles.
                continue;
            }
            startDirty(state);
            progressed++;
        }
    }

    /**
     * Coalesced source-event invalidation: one writer transaction per event burst, and no
     * dirty begin can run until the canonical fence bump is committed.
     */
    private boolean ensureInvalidation(ChunkState state) {
        if (state.invalidationInFlight) {
            return false;
        }
        if (state.invalidatedSequence >= state.sequence) {
            return true;
        }
        state.invalidationInFlight = true;
        state.invalidatedSequence = state.sequence;
        Set<String> projects = projectsForChunk(state.chunk);
        backend.invalidateChunk(state.chunk).whenComplete((rows, error) -> enqueue(() -> {
            if (!open.get()) {
                return;
            }
            state.invalidationInFlight = false;
            if (error != null) {
                state.invalidatedSequence = -1L;
                LOGGER.warn("区域区块 fence 失效标记失败: {}", state.chunk, error);
            } else {
                broadcast(projects);
                captureWatermark(state, projects);
            }
            syncState(state);
            enqueueDirty(state);
        }));
        return false;
    }

    private void enqueueDirty(ChunkState state) {
        if (state.dirty != null || (state.changed.isEmpty() && state.removed.isEmpty())) {
            return;
        }
        if (dirtyQueued.add(state.chunk)) {
            dirtyQueue.add(state.chunk);
        }
    }

    private void startDirty(ChunkState state) {
        List<AreaDefinition> areas = catalog.areasCovering(state.chunk);
        if (areas.isEmpty()) {
            state.changed.clear();
            state.removed.clear();
            syncState(state);
            return;
        }
        DirtyOp op = new DirtyOp(state, new LinkedHashSet<>(state.changed), new LinkedHashSet<>(state.removed));
        state.changed.clear();
        state.removed.clear();
        state.dirty = op;
        activeDirtyOps.add(op);
        op.areas.addAll(areas);
        op.waits = areas.size();
        for (AreaDefinition area : areas) {
            backend.beginContainerUpdates(area.id(), area.geometryRevision(), state.chunk)
                    .whenComplete((token, error) -> {
                        boolean accepted = enqueue(() -> {
                            if (error == null && token != null && token.isPresent()
                                    && (!open.get() || state.dirty != op || op.finished)) {
                                abandonToken(token.get());
                                return;
                            }
                            if (!open.get() || state.dirty != op || op.finished) {
                                return;
                            }
                            op.waits--;
                            if (error != null) {
                                op.failures++;
                            } else if (token.isEmpty()) {
                                op.needsFullScan = true;
                            } else {
                                op.tokens.add(token.get());
                            }
                        });
                        if (!accepted && error == null && token != null && token.isPresent()) {
                            abandonToken(token.get());
                        }
                    });
        }
    }

    private void advanceDirty(DirtyOp op, TickBudget budget) {
        ChunkState state = op.state;
        if (!open.get() || state.dirty != op || op.finished) {
            return;
        }
        if (!loadedChunks.contains(state.chunk)) {
            endDirtyOp(op);
            syncState(state);
            return;
        }
        if (!source.isAvailable()) {
            return;
        }
        if (!source.isChunkLoaded(state.chunk)) {
            endDirtyOp(op);
            handleMissingChunk(state);
            return;
        }
        if (op.needsFullScan || op.failures > 0 || op.tokens.isEmpty()) {
            endDirtyOp(op);
            state.scanWanted = true;
            syncState(state);
            return;
        }

        Set<ContainerKey> keys = new LinkedHashSet<>(op.changed);
        keys.addAll(op.removed);
        if (keys.isEmpty()) {
            endDirtyOp(op);
            broadcast(projectsForChunk(state.chunk));
            syncState(state);
            return;
        }

        ChunkScanFilter filter = filterFor(state.chunk, op.areas);
        ScanBudget slice = budget.slice(MAX_CONTAINERS_PER_SLICE, MAX_SLOTS_PER_SLICE);
        AreaSnapshotSource.ContainerReadResult read;
        try {
            read = source.readContainers(state.chunk, filter, keys, slice);
        } catch (RuntimeException error) {
            LOGGER.warn("读取脏容器失败，改为整区块重扫: {}", state.chunk, error);
            endDirtyOp(op);
            state.scanWanted = true;
            syncState(state);
            return;
        }
        budget.consume(read.containersUsed(), read.slotsUsed());
        if (!read.loaded()) {
            endDirtyOp(op);
            handleMissingChunk(state);
            return;
        }
        if (read.truncated()) {
            // The selected read cannot prove it saw every change; a full chunk read can.
            endDirtyOp(op);
            state.scanWanted = true;
            syncState(state);
            return;
        }

        Map<ContainerKey, ContainerObservation> observed = new LinkedHashMap<>();
        for (ContainerObservation observation : read.observations()) {
            observed.put(observation.key(), observation);
        }
        List<ContainerObservation> changedObservations = new ArrayList<>();
        boolean missing = false;
        for (ContainerKey key : op.changed) {
            ContainerObservation observation = observed.get(key);
            if (observation == null) {
                missing = true;
                break;
            }
            changedObservations.add(observation);
        }
        if (missing) {
            // A just-created/replaced source was not visible to the selected read: only a
            // full chunk read can distinguish "new" from "already gone".
            endDirtyOp(op);
            state.scanWanted = true;
            syncState(state);
            return;
        }

        op.phase = DirtyPhase.APPLY;
        op.waits = op.tokens.size();
        if (op.waits == 0) {
            endDirtyOp(op);
            state.scanWanted = true;
            syncState(state);
            return;
        }
        for (AreaScanBackend.ScanToken token : op.tokens) {
            AreaDefinition area = areasById.get(token.areaId());
            List<ContainerObservation> upserts;
            List<ContainerKey> removals;
            if (area == null) {
                upserts = List.of();
                removals = List.of();
            } else {
                List<ContainerObservation> filtered = new ArrayList<>();
                for (ContainerObservation observation : changedObservations) {
                    if (area.bounds().contains(observation.key())) {
                        filtered.add(observation);
                    }
                }
                upserts = filtered;
                List<ContainerKey> inside = new ArrayList<>();
                for (ContainerKey removal : op.removed) {
                    if (area.bounds().contains(removal)) {
                        inside.add(removal);
                    }
                }
                removals = List.copyOf(inside);
            }
            backend.applyContainerUpdates(token, upserts, removals)
                    .whenComplete((success, error) -> enqueue(() -> {
                        if (!open.get() || state.dirty != op || op.finished) {
                            return;
                        }
                        op.waits--;
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            op.failures++;
                        } else {
                            op.applied = true;
                        }
                        if (op.waits == 0) {
                            finishDirtyApply(op);
                        }
                    }));
        }
    }

    private void endDirtyOp(DirtyOp op) {
        op.finished = true;
        op.state.dirty = null;
        activeDirtyOps.remove(op);
    }

    private void cancelDirtyOp(DirtyOp op) {
        endDirtyOp(op);
    }

    private void finishDirtyApply(DirtyOp op) {
        ChunkState state = op.state;
        if (!open.get()) {
            return;
        }
        if (state.dirty != op) {
            return;
        }
        endDirtyOp(op);
        if (op.failures > 0) {
            state.scanWanted = true;
        } else if (op.applied) {
            broadcast(projectsForChunk(state.chunk));
        }
        if (!state.changed.isEmpty() || !state.removed.isEmpty()) {
            enqueueDirty(state);
        }
        captureWatermark(state, projectsForChunk(state.chunk));
        syncState(state);
    }

    private void handleMissingChunk(ChunkState state) {
        if (!loadedChunks.remove(state.chunk)) {
            return;
        }
        state.sequence++;
        state.changed.clear();
        state.removed.clear();
        queueStale(state, projectsForChunk(state.chunk));
    }

    private ChunkScanFilter filterFor(ChunkKey chunk, List<AreaDefinition> areas) {
        List<AreaBounds> bounds = new ArrayList<>(areas.size());
        for (AreaDefinition area : areas) {
            bounds.add(area.bounds());
        }
        return new ChunkScanFilter(chunk.world(), bounds);
    }

    // ==================================================================
    // Full chunk scans
    // ==================================================================

    private void processScan(TickBudget budget) {
        if (activeScan != null) {
            continueScan(activeScan, budget);
            return;
        }
        if (!source.isAvailable() || !budget.hasTime()) {
            return;
        }
        ChunkKey chunk = scanQueue.poll();
        if (chunk == null) {
            return;
        }
        scanQueued.remove(chunk);
        ChunkState state = chunks.get(chunk);
        if (state == null || !state.scanWanted || state.scan != null) {
            return;
        }
        if (!loadedChunks.contains(chunk)) {
            state.scanWanted = false;
            syncState(state);
            return;
        }
        if (!source.isChunkLoaded(chunk)) {
            state.scanWanted = false;
            state.sequence++;
            loadedChunks.remove(chunk);
            queueStale(state, projectsForChunk(chunk));
            return;
        }
        List<AreaDefinition> areas = catalog.areasCovering(chunk);
        if (areas.isEmpty()) {
            state.scanWanted = false;
            syncState(state);
            return;
        }
        ScanOp op = new ScanOp(state, areas);
        state.scan = op;
        activeScan = op;
        for (AreaDefinition area : areas) {
            backend.beginChunkScan(area.id(), area.geometryRevision(), chunk)
                    .whenComplete((token, error) -> {
                        boolean accepted = enqueue(() -> {
                            if (error == null && token != null && token.isPresent()
                                    && (!open.get() || activeScan != op || op.finished)) {
                                abandonToken(token.get());
                                return;
                            }
                            if (!open.get() || activeScan != op || op.finished) {
                                return;
                            }
                            op.waits--;
                            if (error != null) {
                                op.beginFailures++;
                            } else if (token.isEmpty()) {
                                op.missingAreas++;
                            } else {
                                op.tokens.add(token.get());
                            }
                            if (op.waits == 0) {
                                finishScanBegins(op);
                            }
                        });
                        if (!accepted && error == null && token != null && token.isPresent()) {
                            abandonToken(token.get());
                        }
                    });
        }
        op.waits = areas.size();
        if (op.waits == 0) {
            finishScanBegins(op);
        }
    }

    private void finishScanBegins(ScanOp op) {
        ChunkState state = op.state;
        if (!open.get() || activeScan != op || op.finished) {
            return;
        }
        if (op.tokens.isEmpty()) {
            op.restoreCapturedKeys();
            op.finished = true;
            activeScan = null;
            state.scan = null;
            state.scanWanted = op.beginFailures > 0 || state.scanRequestSequence != op.requestSequence;
            captureWatermark(state, projectsForChunk(state.chunk));
            syncState(state);
            return;
        }
        if (op.beginFailures > 0) {
            // Missing geometry/epoch race: refresh coverage on the next catalog/app-update.
            op.errors = true;
        }
        op.cursor = null;
        op.phase = ScanPhase.READ;
        // Read on the next tick so a whole-chunk read gets its own budget.
    }

    private void continueScan(ScanOp op, TickBudget budget) {
        ChunkState state = op.state;
        if (op.phase == ScanPhase.APPLY) {
            return;
        }
        if (!open.get() || activeScan != op || op.finished) {
            return;
        }
        if (op.phase == ScanPhase.BEGINS) {
            return;
        }
        if (!loadedChunks.contains(state.chunk) || !source.isChunkLoaded(state.chunk)) {
            cancelScan(op, false);
            return;
        }
        if (state.sequence != op.sequence) {
            cancelScan(op, true);
            return;
        }
        if (!budget.hasTime()) {
            return;
        }
        ChunkScanFilter filter = filterFor(state.chunk, op.areas);
        ScanBudget slice = budget.slice(MAX_CONTAINERS_PER_SLICE, MAX_SLOTS_PER_SLICE);
        ChunkScanSlice result;
        try {
            result = source.scanChunk(state.chunk, filter, op.cursor, slice);
        } catch (RuntimeException error) {
            LOGGER.warn("区块扫描失败，保留旧观测并稍后重试: {}", state.chunk, error);
            cancelScan(op, true);
            return;
        }
        budget.consume(result.containersUsed(), result.slotsUsed());
        if (result.sourceFailure()) {
            abandonScan(op);
            return;
        }
        if (!result.loaded()) {
            cancelScan(op, false);
            return;
        }
        if (!result.observations().isEmpty()) {
            op.observations.addAll(result.observations());
        }
        op.cursor = result.next();
        if (!result.complete()) {
            return;
        }
        if (state.sequence != op.sequence) {
            cancelScan(op, true);
            return;
        }
        commitScan(op);
    }

    private void commitScan(ScanOp op) {
        ChunkState state = op.state;
        Map<ContainerKey, ContainerObservation> unique = new LinkedHashMap<>();
        for (ContainerObservation observation : op.observations) {
            unique.put(observation.key(), observation);
        }
        List<ContainerObservation> snapshot = List.copyOf(unique.values());
        op.phase = ScanPhase.APPLY;
        op.waits = op.tokens.size();
        if (op.waits == 0) {
            finishScanApply(op);
            return;
        }
        for (AreaScanBackend.ScanToken token : op.tokens) {
            AreaDefinition area = areasById.get(token.areaId());
            List<ContainerObservation> filtered;
            if (area == null) {
                filtered = List.of();
            } else {
                List<ContainerObservation> inside = new ArrayList<>();
                for (ContainerObservation observation : snapshot) {
                    if (area.bounds().contains(observation.key())) {
                        inside.add(observation);
                    }
                }
                filtered = inside;
            }
            backend.applyChunkScan(token, filtered)
                    .whenComplete((success, error) -> enqueue(() -> {
                        if (!open.get() || activeScan != op || op.finished) {
                            return;
                        }
                        op.waits--;
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            op.failures++;
                        } else {
                            op.applied = true;
                        }
                        if (op.waits == 0) {
                            finishScanApply(op);
                        }
                    }));
        }
    }

    private void finishScanApply(ScanOp op) {
        ChunkState state = op.state;
        if (!open.get()) {
            return;
        }
        if (activeScan != op) {
            return;
        }
        op.finished = true;
        activeScan = null;
        state.scan = null;
        boolean requestedAgain = state.scanRequestSequence != op.requestSequence;
        boolean requiresRescan = requestedAgain || op.failures > 0 || op.errors
                || state.sequence != op.sequence || !state.changed.isEmpty() || !state.removed.isEmpty();
        if (requiresRescan) {
            op.restoreCapturedKeys();
            state.scanWanted = loadedChunks.contains(state.chunk);
            enqueueDirty(state);
        } else {
            state.scanWanted = false;
        }
        if (op.applied) {
            broadcast(projectsForChunk(state.chunk));
        }
        captureWatermark(state, projectsForChunk(state.chunk));
        syncState(state);
    }

    private void cancelScan(ScanOp op, boolean rescan) {
        if (op.finished) {
            return;
        }
        op.finished = true;
        for (AreaScanBackend.ScanToken token : op.tokens) {
            abandonToken(token);
        }
        if (activeScan == op) {
            activeScan = null;
        }
        ChunkState state = op.state;
        state.scan = null;
        if (!open.get()) {
            return;
        }
        boolean loaded = loadedChunks.contains(state.chunk) && source.isChunkLoaded(state.chunk);
        if (!loaded && loadedChunks.contains(state.chunk)) {
            loadedChunks.remove(state.chunk);
            state.sequence++;
            queueStale(state, projectsForChunk(state.chunk));
        }
        state.scanWanted = rescan && loaded;
        if (!loaded) {
            // Unloaded chunks keep last-known storage rows; the next load reschedules.
            state.changed.clear();
            state.removed.clear();
        } else {
            op.restoreCapturedKeys();
            enqueueDirty(state);
        }
        captureWatermark(state, projectsForChunk(state.chunk));
        syncState(state);
    }

    /**
     * A source reported that it cannot observe the whole loaded chunk. Abandon every token
     * so storage keeps the last-known rows stale instead of committing a partial/empty
     * snapshot. Captured dirty keys are dropped with the scan: retrying an unobservable
     * chunk from the dirty queue every tick would spin, while reconciliation and explicit
     * rescans still retry the full read later.
     */
    private void abandonScan(ScanOp op) {
        if (op.finished) {
            return;
        }
        op.finished = true;
        for (AreaScanBackend.ScanToken token : op.tokens) {
            abandonToken(token);
        }
        if (activeScan == op) {
            activeScan = null;
        }
        ChunkState state = op.state;
        state.scan = null;
        state.scanWanted = false;
        state.changed.clear();
        state.removed.clear();
        for (RescanRequest request : rescans) {
            if (request.remaining.contains(state.chunk)) {
                request.sourceFailures++;
            }
        }
        captureWatermark(state, projectsForChunk(state.chunk));
        syncState(state);
    }

    private void queueStale(ChunkState state, Set<String> projects) {
        if (projects.isEmpty() && catalog.areasCovering(state.chunk).isEmpty()) {
            return;
        }
        state.stalePending++;
        ensureReason(state, projects);
        backend.markChunkStale(state.chunk).whenComplete((rows, error) -> enqueue(() -> {
            if (!open.get()) {
                return;
            }
            state.stalePending--;
            if (error != null) {
                LOGGER.warn("区块扫描发现未加载后标记过期失败: {}", state.chunk, error);
            } else {
                broadcast(projectsForChunk(state.chunk));
            }
            captureWatermark(state, projectsForChunk(state.chunk));
            syncState(state);
        }));
        syncState(state);
    }

    // ==================================================================
    // Rescan requests
    // ==================================================================

    public CompletableFuture<RescanResult> rescan(String projectId) {
        CompletableFuture<RescanResult> future = new CompletableFuture<>();
        if (!open.get()) {
            future.complete(new RescanResult(false, "区域扫描器已关闭"));
            return future;
        }
        if (projectId == null || projectId.isBlank()) {
            future.complete(new RescanResult(false, "项目不存在"));
            return future;
        }
        pendingRescanFutures.add(future);
        refreshConfiguration().whenComplete((ignored, refreshError) -> {
            if (refreshError != null) {
                completeRescan(future, new RescanResult(false,
                        "区域配置刷新失败: " + refreshError.getMessage()));
                return;
            }
            if (!open.get()) {
                completeRescan(future, new RescanResult(false, "区域扫描器已关闭"));
                return;
            }
            backend.findProject(projectId).whenComplete((project, error) -> enqueue(() -> {
                if (!open.get()) {
                    completeRescan(future, new RescanResult(false, "区域扫描器已关闭"));
                    return;
                }
                if (error != null) {
                    completeRescan(future, new RescanResult(false,
                            "项目校验失败: " + error.getMessage()));
                    return;
                }
                if (project.isEmpty()) {
                    completeRescan(future, new RescanResult(false, "项目不存在"));
                    return;
                }
                scheduleRescan(projectId, future);
            }));
        });
        return future;
    }

    private void completeRescan(CompletableFuture<RescanResult> future, RescanResult result) {
        pendingRescanFutures.remove(future);
        future.complete(result);
    }

    private void scheduleRescan(String projectId, CompletableFuture<RescanResult> future) {
        List<AreaDefinition> areas = new ArrayList<>(catalog.stagingAreas(projectId));
        areas.addAll(catalog.referencedWarehouses(projectId));
        if (areas.isEmpty()) {
            completeRescan(future, new RescanResult(true, "项目没有配置区域"));
            return;
        }
        if (!source.isAvailable()) {
            completeRescan(future, new RescanResult(false, "服务器世界源不可用，无法确认区域扫描"));
            return;
        }

        RescanRequest request = new RescanRequest(projectId, future, areas.size());
        Set<ChunkKey> seen = new LinkedHashSet<>();
        long candidates = 0;
        outer:
        for (AreaDefinition area : areas) {
            AreaBounds bounds = area.bounds();
            for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
                for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
                    if (candidates >= MAX_RESCAN_CHUNKS) {
                        request.truncated = true;
                        break outer;
                    }
                    ChunkKey chunk = new ChunkKey(area.world(), chunkX, chunkZ);
                    if (!seen.add(chunk)) {
                        continue;
                    }
                    candidates++;
                    if (loadedChunks.contains(chunk) && source.isChunkLoaded(chunk)) {
                        ChunkState state = state(chunk);
                        state.sequence++;
                        markScan(state, true);
                        request.remaining.add(chunk);
                    } else {
                        request.unloaded++;
                    }
                }
            }
        }
        if (request.remaining.isEmpty()) {
            completeRescan(future, request.result(true));
            return;
        }
        rescans.add(request);
    }

    private void updateRescans() {
        if (rescans.isEmpty()) {
            return;
        }
        for (RescanRequest request : new ArrayList<>(rescans)) {
            if (!open.get()) {
                futureFalse(request);
                continue;
            }
            for (ChunkKey chunk : new ArrayList<>(request.remaining)) {
                if (!loadedChunks.contains(chunk) || !source.isChunkLoaded(chunk)) {
                    request.remaining.remove(chunk);
                    request.unloaded++;
                    continue;
                }
                ChunkState state = chunks.get(chunk);
                if (state == null) {
                    request.remaining.remove(chunk);
                    continue;
                }
                boolean busy = state.scanWanted || state.scan != null || state.dirty != null
                        || !state.changed.isEmpty() || !state.removed.isEmpty();
                if (!busy) {
                    request.remaining.remove(chunk);
                }
            }
            if (request.remaining.isEmpty()) {
                rescans.remove(request);
                completeRescan(request.future, request.result(request.sourceFailures == 0));
            }
        }
    }

    private void futureFalse(RescanRequest request) {
        rescans.remove(request);
        completeRescan(request.future, new RescanResult(false, "区域扫描器已关闭"));
    }

    /** Truthful rescan outcome for one project. */
    public record RescanResult(boolean success, String message) {
    }

    private static final class RescanRequest {
        private final String projectId;
        private final CompletableFuture<RescanResult> future;
        private final int areas;
        private final Set<ChunkKey> remaining = new LinkedHashSet<>();
        private int unloaded;
        private int sourceFailures;
        private boolean truncated;

        private RescanRequest(String projectId, CompletableFuture<RescanResult> future, int areas) {
            this.projectId = projectId;
            this.future = future;
            this.areas = areas;
        }

        private RescanResult result(boolean success) {
            StringBuilder message = new StringBuilder();
            if (success) {
                message.append("区域扫描完成");
            } else {
                message.append("区域扫描失败");
            }
            message.append("：").append(areas).append(" 个区域，");
            if (sourceFailures > 0) {
                message.append(sourceFailures).append(" 个区块源读取失败，保持未知");
                if (unloaded > 0) {
                    message.append("；").append(unloaded).append(" 个区块未加载，保持未知");
                }
            } else if (unloaded > 0) {
                message.append(unloaded).append(" 个区块未加载，保持未知");
            } else {
                message.append("所有已加载区块已扫描");
            }
            if (truncated) {
                message.append("（区块数量超过单次扫描上限，剩余部分稍后自动扫描）");
            }
            return new RescanResult(success, message.toString());
        }
    }

    // ==================================================================
    // Fence / reasons
    // ==================================================================

    public boolean isInventoryCurrent(String projectId, AreaInventoryView view) {
        if (!open.get() || !catalogApplied || projectId == null || view == null || !view.known()) {
            return false;
        }
        Set<Long> pending = projectReasons.get(projectId);
        if (pending != null && !pending.isEmpty()) {
            return false;
        }
        Long required = requiredRevision.get(projectId);
        return required == null || view.revision() >= required;
    }

    public boolean hasPendingWork() {
        return !open.get() || !catalogApplied || !reasons.isEmpty()
                || refreshInFlight.get() > 0 || !rescans.isEmpty();
    }

    /**
     * Reads the storage inventory watermark after a committed observation/config change.
     * The read is issued on the same single-writer queue AFTER the write that bumped it, so
     * a view can only pass the fence once it reflects that revision or a newer one.
     */
    private void captureProjectWatermark(Set<String> projects) {
        if (projects.isEmpty()) {
            return;
        }
        Set<String> copy = Set.copyOf(projects);
        backend.inventoryRevision().whenComplete((revision, error) -> enqueue(() -> {
            if (!open.get()) {
                return;
            }
            if (error != null) {
                LOGGER.warn("读取区域库存版本失败", error);
                return;
            }
            for (String projectId : copy) {
                requiredRevision.merge(projectId, revision, Math::max);
            }
        }));
    }

    private void captureWatermark(ChunkState state, Set<String> projects) {
        if (projects.isEmpty()) {
            return;
        }
        state.watermarkPending++;
        Set<String> copy = Set.copyOf(projects);
        backend.inventoryRevision().whenComplete((revision, error) -> enqueue(() -> {
            if (!open.get()) {
                return;
            }
            long reasonBefore = state.reasonId;
            Set<String> released = reasonBefore == 0 ? Set.of() : Set.copyOf(state.projects);
            state.watermarkPending--;
            if (error != null) {
                LOGGER.warn("读取区块库存版本失败", error);
            } else {
                for (String projectId : copy) {
                    requiredRevision.merge(projectId, revision, Math::max);
                }
            }
            syncState(state);
            if (error == null && reasonBefore != 0 && state.reasonId == 0) {
                // The last broadcast happened while this chunk still counted as pending,
                // so subscribers received known=false. Once the watermark is confirmed and
                // the project has no pending reasons, publish the settled snapshot again.
                broadcastReady(released);
            }
        }));
    }

    private void broadcastReady(Set<String> projects) {
        for (String projectId : projects) {
            Set<Long> pending = projectReasons.get(projectId);
            if (pending == null || pending.isEmpty()) {
                onProjectChanged.accept(projectId);
            }
        }
    }

    private void ensureReason(ChunkState state, Set<String> projects) {
        if (projects.isEmpty()) {
            return;
        }
        if (state.reasonId != 0 && projects.equals(state.projects)) {
            return;
        }
        if (state.reasonId != 0) {
            endReason(state.reasonId);
        }
        state.projects = new LinkedHashSet<>(projects);
        state.reasonId = beginReason(projects);
    }

    private long beginReason(Set<String> projects) {
        long id = ++nextReasonId;
        Set<String> copy = Set.copyOf(projects);
        reasons.put(id, copy);
        for (String projectId : copy) {
            projectReasons.computeIfAbsent(projectId, ignored -> new LinkedHashSet<>()).add(id);
        }
        return id;
    }

    private void endReason(long id) {
        if (id == 0) {
            return;
        }
        Set<String> projects = reasons.remove(id);
        if (projects == null) {
            return;
        }
        for (String projectId : projects) {
            Set<Long> ids = projectReasons.get(projectId);
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    projectReasons.remove(projectId);
                }
            }
        }
    }

    private boolean isBusy(ChunkState state) {
        return state.scanWanted || state.scan != null || state.dirty != null
                || state.stalePending > 0 || state.watermarkPending > 0
                || state.invalidationInFlight
                || !state.changed.isEmpty() || !state.removed.isEmpty();
    }

    private void syncState(ChunkState state) {
        boolean busy = isBusy(state);
        if (!busy) {
            if (state.reasonId != 0) {
                endReason(state.reasonId);
                state.reasonId = 0;
            }
            state.projects.clear();
            chunks.remove(state.chunk);
            scanQueued.remove(state.chunk);
            return;
        }
        Set<String> projects = projectsForChunk(state.chunk);
        if (projects.isEmpty()) {
            // No current area owns this work anymore (area removed/project vanished).
            if (state.scan != null) {
                cancelScan(state.scan, false);
                return;
            }
            if (state.dirty != null) {
                cancelDirtyOp(state.dirty);
            }
            state.scanWanted = false;
            state.changed.clear();
            state.removed.clear();
            if (state.stalePending == 0 && state.watermarkPending == 0) {
                if (state.reasonId != 0) {
                    endReason(state.reasonId);
                    state.reasonId = 0;
                }
                chunks.remove(state.chunk);
                scanQueued.remove(state.chunk);
            }
            return;
        }
        ensureReason(state, projects);
        if (state.scanWanted && state.scan == null && !scanQueued.contains(state.chunk)) {
            scanQueue.add(state.chunk);
            scanQueued.add(state.chunk);
        }
    }

    private void markScan(ChunkState state, boolean force) {
        if (!open.get()) {
            return;
        }
        if (!catalog.areasCovering(state.chunk).isEmpty()) {
            state.scanWanted = true;
            state.scanRequestSequence++;
            if (state.scan == null && !scanQueued.contains(state.chunk)) {
                scanQueue.add(state.chunk);
                scanQueued.add(state.chunk);
            }
        } else if (force) {
            state.scanWanted = false;
        }
        syncState(state);
    }

    private ChunkState state(ChunkKey chunk) {
        return chunks.computeIfAbsent(chunk, ChunkState::new);
    }

    private void dropIdleStates() {
        for (ChunkState state : new ArrayList<>(chunks.values())) {
            if (!isBusy(state)) {
                syncState(state);
            }
        }
    }

    private void broadcast(Set<String> projects) {
        for (String projectId : projects) {
            onProjectChanged.accept(projectId);
        }
    }

    private boolean enqueue(Runnable task) {
        if (!open.get()) {
            return false;
        }
        completions.add(task);
        if (!open.get() && completions.remove(task)) {
            // Closed after the queue check but before a drain: the caller still owns
            // cleanup (for example a scan token that must be abandoned).
            return false;
        }
        return true;
    }

    private void abandonToken(AreaScanBackend.ScanToken token) {
        backend.abandonChunkScan(token).exceptionally(ignored -> false);
    }

    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        for (ChunkState state : new ArrayList<>(chunks.values())) {
            if (state.scan != null && !state.scan.finished) {
                state.scan.finished = true;
                for (AreaScanBackend.ScanToken token : state.scan.tokens) {
                    abandonToken(token);
                }
            }
            if (state.dirty != null && !state.dirty.finished) {
                state.dirty.finished = true;
                for (AreaScanBackend.ScanToken token : state.dirty.tokens) {
                    abandonToken(token);
                }
            }
        }
        for (DirtyOp op : new ArrayList<>(activeDirtyOps)) {
            if (!op.finished) {
                op.finished = true;
                for (AreaScanBackend.ScanToken token : op.tokens) {
                    abandonToken(token);
                }
            }
            op.state.dirty = null;
        }
        activeDirtyOps.clear();
        activeScan = null;
        chunks.clear();
        scanQueue.clear();
        scanQueued.clear();
        dirtyQueue.clear();
        dirtyQueued.clear();
        loadedChunks.clear();
        reconcileQueue.clear();
        for (RescanRequest request : rescans) {
            request.future.complete(new RescanResult(false, "区域扫描器已关闭"));
        }
        rescans.clear();
        reasons.clear();
        projectReasons.clear();
        requiredRevision.clear();
        pendingRefreshFutures.forEach(future ->
                future.completeExceptionally(new IllegalStateException("area scanner is closed")));
        pendingRefreshFutures.clear();
        pendingRescanFutures.forEach(future ->
                future.complete(new RescanResult(false, "区域扫描器已关闭")));
        pendingRescanFutures.clear();
        // Run already queued callbacks so tokens that arrived just before close are
        // abandoned by the same closed-guard path instead of being silently dropped.
        Runnable task;
        while ((task = completions.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException | Error error) {
                LOGGER.warn("区域扫描关闭回调失败", error);
            }
        }
        source = AreaSnapshotSource.NONE;
    }

    // ==================================================================
    // Internal state
    // ==================================================================

    private enum ScanPhase {
        BEGINS, READ, APPLY
    }

    private enum DirtyPhase {
        BEGINS, APPLY
    }

    private static final class ChunkState {
        private final ChunkKey chunk;
        private long sequence;
        private long reasonId;
        private Set<String> projects = new LinkedHashSet<>();
        private final Set<ContainerKey> changed = new LinkedHashSet<>();
        private final Set<ContainerKey> removed = new LinkedHashSet<>();
        private boolean scanWanted;
        /** Bumped per accepted scan request; an op only clears requests it captured. */
        private long scanRequestSequence;
        private int stalePending;
        private int watermarkPending;
        private boolean invalidationInFlight;
        private long invalidatedSequence = -1L;
        private ScanOp scan;
        private DirtyOp dirty;

        private ChunkState(ChunkKey chunk) {
            this.chunk = chunk;
        }
    }

    private final class ScanOp {
        private final ChunkState state;
        private final List<AreaDefinition> areas;
        private final List<AreaScanBackend.ScanToken> tokens = new ArrayList<>();
        private final Set<ContainerKey> capturedChanged = new LinkedHashSet<>();
        private final Set<ContainerKey> capturedRemoved = new LinkedHashSet<>();
        private int waits;
        private int beginFailures;
        private int missingAreas;
        private int failures;
        private boolean errors;
        private boolean finished;
        private boolean applied;
        private ScanPhase phase = ScanPhase.BEGINS;
        private long sequence;
        private final long requestSequence;
        private ChunkScanCursor cursor;
        private final List<ContainerObservation> observations = new ArrayList<>();

        private ScanOp(ChunkState state, List<AreaDefinition> areas) {
            this.state = state;
            this.areas = List.copyOf(areas);
            this.sequence = state.sequence;
            this.requestSequence = state.scanRequestSequence;
            this.capturedChanged.addAll(state.changed);
            this.capturedRemoved.addAll(state.removed);
            state.changed.clear();
            state.removed.clear();
        }

        private void restoreCapturedKeys() {
            state.changed.addAll(capturedChanged);
            state.removed.addAll(capturedRemoved);
        }
    }

    private final class DirtyOp {
        private final ChunkState state;
        private final Set<ContainerKey> changed;
        private final Set<ContainerKey> removed;
        private final List<AreaDefinition> areas = new ArrayList<>();
        private final List<AreaScanBackend.ScanToken> tokens = new ArrayList<>();
        private DirtyPhase phase = DirtyPhase.BEGINS;
        private int waits;
        private int failures;
        private boolean applied;
        private boolean needsFullScan;
        private boolean finished;

        private DirtyOp(ChunkState state, Set<ContainerKey> changed, Set<ContainerKey> removed) {
            this.state = state;
            this.changed = Set.copyOf(changed);
            this.removed = Set.copyOf(removed);
        }
    }

    private final class TickBudget {
        private final long deadlineNanos;
        private int containers = MAX_CONTAINERS_PER_TICK;
        private int slots = MAX_SLOTS_PER_TICK;

        private TickBudget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        private boolean hasTime() {
            return containers > 0 && slots > 0 && System.nanoTime() < deadlineNanos;
        }

        private ScanBudget slice(int maxContainers, int maxSlots) {
            return new ScanBudget(Math.min(containers, maxContainers), Math.min(slots, maxSlots));
        }

        private void consume(int usedContainers, int usedSlots) {
            containers = Math.max(0, containers - Math.max(0, usedContainers));
            slots = Math.max(0, slots - Math.max(0, usedSlots));
        }
    }
}
