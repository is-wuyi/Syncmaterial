package net.syncmaterial.syncmaterial.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import java.util.function.Consumer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialDecoder;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.ingest.IngestService;
import net.syncmaterial.syncmaterial.server.ingest.PlacementsDiscovery;
import net.syncmaterial.syncmaterial.server.storage.SqliteProjectStore;

public final class ServerRuntime implements AutoCloseable {
    private final UUID epoch = UUID.randomUUID();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final SqliteProjectStore store;
    private final ExecutorService parserExecutor;
    private final ScheduledExecutorService discoveryExecutor;
    private final IngestService ingest;
    private final net.syncmaterial.syncmaterial.server.ingest.SyncmaticaDiscoveryAdapter syncmatica;
    private final OnlineInventoryTracker inventories = new OnlineInventoryTracker();
    private final MaterialCatalogTracker catalogs = new MaterialCatalogTracker();
    private final Map<UUID, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final Consumer<String> onProjectChanged;
    private final Set<String> pendingChanges = ConcurrentHashMap.newKeySet();
    private int discoveryTicks;

    private ServerRuntime(SqliteProjectStore store, MaterialDecoder decoder, Consumer<String> onProjectChanged) {
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
        this.syncmatica = new net.syncmaterial.syncmaterial.server.ingest.SyncmaticaDiscoveryAdapter(this);
    }

    public static ServerRuntime open(Path worldRoot, Path gameRoot, MaterialDecoder decoder,
                                     Consumer<String> onProjectChanged) throws IOException {
        Path dataRoot = worldRoot.resolve("syncmaterial");
        Path database = dataRoot.resolve("runtime-v2.sqlite");
        if (!java.nio.file.Files.exists(database)) {
            Path legacy = Path.of("syncmaterial.db").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(legacy)) legacy = gameRoot.resolve("syncmaterial.db");
            Path backup = LegacyDatabaseBackup.preserve(legacy, dataRoot.resolve("legacy-backups"));
            if (backup != null) SyncMaterial.LOGGER.info("旧数据库已保留到 {}，不会导入新后端", backup);
        }
        return new ServerRuntime(SqliteProjectStore.open(database), decoder,
                java.util.Objects.requireNonNull(onProjectChanged));
    }

    public void startDiscovery(Path worldRoot, Path schematicsRoot) {
        PlacementsDiscovery discovery = new PlacementsDiscovery(
                worldRoot.resolve("syncmatica").resolve("placements.json"), schematicsRoot, ingest,
                this::projectChanged, error -> SyncMaterial.LOGGER.warn("新后端原理图导入失败，下次扫描将重试", error));
        discoveryExecutor.scheduleWithFixedDelay(() -> {
            if (!open.get()) return;
            try {
                discovery.scan();
            } catch (Exception error) {
                SyncMaterial.LOGGER.warn("新后端 placements 发现失败，不修改已提交项目", error);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public SqliteProjectStore store() {
        return store;
    }

    public UUID epoch() {
        return epoch;
    }

    public MaterialCatalogTracker catalogs() {
        return catalogs;
    }

    public boolean isOpen() {
        return open.get();
    }

    public CompletableFuture<Void> ingest(SchematicMetadata metadata) {
        return ingest.ingest(metadata).thenRun(() -> projectChanged(metadata.id()));
    }

    public CompletableFuture<Void> removeProject(String projectId) {
        return ingest.remove(projectId).whenComplete((ignored, error) -> {
            if (error == null) projectChanged(projectId);
            else SyncMaterial.LOGGER.warn("删除新后端项目失败: {}", projectId, error);
        });
    }

    private void projectChanged(String projectId) {
        if (open.get()) pendingChanges.add(projectId);
    }

    public void requestBroadcast(String projectId) {
        projectChanged(projectId);
    }

    public void connected(UUID playerId) {
        if (open.get()) inventories.connected(playerId);
    }

    public long connection(UUID playerId) {
        return inventories.connection(playerId);
    }

    public void disconnected(UUID playerId) {
        Set<String> projects = subscriptions.remove(playerId);
        inventories.disconnected(playerId);
        catalogs.disconnected(playerId);
        if (projects != null) projects.forEach(this::projectChanged);
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
        if (++discoveryTicks % 20 == 0) syncmatica.register();
        for (String projectId : inventories.expire()) projectChanged(projectId);
        for (String projectId : Set.copyOf(pendingChanges)) {
            if (pendingChanges.remove(projectId)) onProjectChanged.accept(projectId);
        }
    }

    public CompletableFuture<Void> reportInventory(String projectId, int materialId, PlayerIdentity player, long count) {
        long connection = inventories.connection(player.uuid());
        if (!open.get() || connection == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player session is not active"));
        }
        return store.recordInventory(projectId, materialId, player, count).thenRun(() -> {
            if (open.get()) inventories.update(player.uuid(), connection, projectId, materialId, count);
        });
    }

    public long inventoryCount(UUID playerId, String projectId, int materialId) {
        return inventories.count(playerId, projectId, materialId);
    }

    public CompletableFuture<Boolean> leave(String projectId, int materialId, PlayerIdentity player) {
        return store.leave(projectId, materialId, player).thenApply(changed -> {
            inventories.forget(player.uuid(), projectId, materialId);
            return changed;
        });
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        ingest.close();
        syncmatica.close();
        discoveryExecutor.shutdownNow();
        parserExecutor.shutdown();
        try {
            if (!discoveryExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                SyncMaterial.LOGGER.warn("原理图发现任务仍在结束，旧 runtime 已封闭");
            }
            if (!parserExecutor.awaitTermination(5, TimeUnit.SECONDS)) parserExecutor.shutdownNow();
        } catch (InterruptedException error) {
            parserExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            inventories.clear();
            subscriptions.clear();
            pendingChanges.clear();
            catalogs.clear();
            store.close();
        }
    }
}
