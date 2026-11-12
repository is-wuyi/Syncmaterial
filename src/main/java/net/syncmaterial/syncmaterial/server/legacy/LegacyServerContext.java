package net.syncmaterial.syncmaterial.server.legacy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.engine.DefaultMaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.network.Phase4Handler;
import net.syncmaterial.syncmaterial.network.ProtocolHandshake;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicFolderWatcher;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;

public final class LegacyServerContext implements AutoCloseable {
    public enum State { STARTING, RUNNING, STOPPING, CLOSED }

    private static final long CLOSE_TIMEOUT = Duration.ofSeconds(5).toNanos();
    private final Object lifecycleLock = new Object();
    private final UUID epoch = UUID.randomUUID();
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private final java.util.concurrent.atomic.AtomicBoolean closeStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> closed = new java.util.concurrent.CompletableFuture<>();
    private final MinecraftServer server;
    private final Path worldRoot;
    private final Path gameRoot;
    private final SchematicDatabase database;
    private final DatabaseQueryService queryService;
    private final LitematicaParser parser;
    private final DefaultMaterialStatisticsEngine engine;
    private final LegacySchematicIngest ingest;
    private final CollaborationManager collaboration;
    private final StagingAreaManager staging;
    private final LegacySubscriptions subscriptions = new LegacySubscriptions();
    private final Map<String, String> placementNames = new ConcurrentHashMap<>();
    private final LegacyTickTasks ticks;
    private SchematicFolderWatcher watcher;
    private LegacySyncmaticaDiscovery discovery;

    LegacyServerContext(MinecraftServer server, Path worldRoot, Path gameRoot,
                        SchematicDatabase database, DatabaseQueryService queryService,
                        LitematicaParser parser, CollaborationManager collaboration,
                        StagingAreaManager staging, LongSupplier clock) {
        this.server = Objects.requireNonNull(server);
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        this.gameRoot = gameRoot.toAbsolutePath().normalize();
        this.database = Objects.requireNonNull(database);
        this.queryService = Objects.requireNonNull(queryService);
        this.parser = Objects.requireNonNull(parser);
        this.collaboration = Objects.requireNonNull(collaboration);
        this.staging = Objects.requireNonNull(staging);
        this.ticks = new LegacyTickTasks(Objects.requireNonNull(clock), this::scanStartupAreas, staging::processDirtyContainers);
        this.engine = new DefaultMaterialStatisticsEngine(parser);
        try {
            staging.bind(this);
            collaboration.setStagingAreaManager(staging);
            this.ingest = new LegacySchematicIngest(this);
        } catch (RuntimeException | Error failure) {
            cleanup("区域管理器", staging::close);
            cleanup("协作管理器", collaboration::close);
            cleanup("统计引擎", engine::shutdown);
            throw failure;
        }
    }

    public static LegacyServerContext open(MinecraftServer server, Path worldRoot, Path gameRoot) {
        return open(server, worldRoot, gameRoot, new SchematicDatabase(),
                () -> new DefaultLitematicaParser(new ParsingThreadPool()));
    }

    static LegacyServerContext open(MinecraftServer server, Path worldRoot, Path gameRoot,
                                    SchematicDatabase database, java.util.function.Supplier<LitematicaParser> parsers) {
        LitematicaParser parser = null;
        LegacyServerContext context = null;
        try {
            database.initialize();
            parser = parsers.get();
            context = new LegacyServerContext(server, worldRoot, gameRoot,
                    database, new DatabaseQueryService(database), parser,
                    new CollaborationManager(database), new StagingAreaManager(database), System::nanoTime);
            context.collaboration.loadAllInventories();
            return context;
        } catch (RuntimeException | Error failure) {
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            } else {
                if (parser != null) {
                    try {
                        parser.close();
                    } catch (RuntimeException | Error cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                try {
                    database.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    void install() {
        synchronized (lifecycleLock) {
            if (!isOpen()) throw new IllegalStateException("Cannot install a closed legacy context");
            LegacyServerRegistry.installOpen(this);
        }
    }

    public MinecraftServer server() { return server; }
    public UUID epoch() { return epoch; }
    public Path worldRoot() { return worldRoot; }
    public Path gameRoot() { return gameRoot; }
    public Path schematicsRoot() { return gameRoot.resolve("syncmatics"); }
    public SchematicDatabase database() { return database; }
    public DatabaseQueryService queryService() { return queryService; }
    public LitematicaParser parser() { return parser; }
    public DefaultMaterialStatisticsEngine engine() { return engine; }
    public LegacySchematicIngest ingest() { return ingest; }
    public CollaborationManager collaboration() { return collaboration; }
    public StagingAreaManager staging() { return staging; }
    public LegacySubscriptions subscriptions() { return subscriptions; }
    public State state() { return state.get(); }

    public boolean isOpen() {
        State current = state.get();
        return current == State.STARTING || current == State.RUNNING;
    }

    public boolean isCurrent() {
        return isOpen() && LegacyServerRegistry.get(server) == this;
    }

    public boolean owns(ServerLevel world) {
        return isCurrent() && world != null && world.getServer() == server;
    }

    public boolean isConnected(ServerPlayer player) {
        return player != null && owns(player.level()) && player.connection != null
                && server.getPlayerList() != null && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    public Runnable guardedTask(Runnable action) {
        return () -> {
            synchronized (lifecycleLock) {
                if (isCurrent()) action.run();
            }
        };
    }

    public void execute(Runnable action) {
        if (isCurrent()) server.execute(guardedTask(action));
    }

    public void execute(ServerPlayer player, Runnable action) {
        if (!isConnected(player)) return;
        var connection = player.connection;
        execute(() -> {
            if (isConnected(player) && player.connection == connection) action.run();
        });
    }

    public Map<String, String> placementNames() {
        return Map.copyOf(placementNames);
    }

    public void setPlacementName(String schematicId, String name) {
        synchronized (lifecycleLock) {
            if (isCurrent()) placementNames.put(schematicId, name);
        }
    }

    public void forgetPlacementName(String schematicId) {
        synchronized (lifecycleLock) {
            placementNames.remove(schematicId);
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (!isCurrent() || !state.compareAndSet(State.STARTING, State.RUNNING)) return;
            try {
                watcher = new SchematicFolderWatcher(this);
                discovery = new LegacySyncmaticaDiscovery(this);
                ticks.start();
                watcher.start();
                discovery.tick();
            } catch (RuntimeException | Error failure) {
                stop();
                throw failure;
            }
        }
    }

    public void tick() {
        synchronized (lifecycleLock) {
            if (!isCurrent()) return;
            if (discovery != null) discovery.tick();
            ticks.tick();
        }
    }

    private void scanStartupAreas() {
        int areas = 0;
        java.util.Set<String> projects = projectIds();
        for (String schematicId : projects) {
            for (var area : staging.getStagingAreas(schematicId)) {
                staging.rescanStagingArea(area.id());
                areas++;
            }
        }
        staging.loadWarehousesFromDb();
        for (var warehouse : staging.getAllWarehouses()) {
            staging.rescanWarehouseAndMarkChunks(warehouse.id());
        }
        for (String schematicId : projects) {
            Phase4Handler.broadcastAllMaterialStatus(this, schematicId);
        }
        ModNetworkHandler.pushWarehouseContainerUpdate(this);
        ModNetworkHandler.broadcastWarehouseAreas(this);
        SyncMaterial.LOGGER.info("启动时区域扫描完成: {} 个备货区、{} 个仓库", areas, staging.getAllWarehouses().size());
    }

    private java.util.Set<String> projectIds() {
        java.util.Set<String> projects = new java.util.HashSet<>(placementNames.keySet());
        try (var rows = database.executeQuery("SELECT id FROM schematics")) {
            while (rows.next()) projects.add(rows.getString("id"));
        } catch (java.sql.SQLException error) {
            SyncMaterial.LOGGER.warn("读取启动扫描项目失败", error);
        }
        return projects;
    }

    public void schematicChanged(String schematicId) {
        schematicChanged(schematicId, () -> true);
    }

    public void schematicChanged(String schematicId, java.util.function.BooleanSupplier currentIntent) {
        execute(() -> {
            if (!currentIntent.getAsBoolean() || !queryService.schematicExists(schematicId)) return;
            staging.refreshProject(schematicId);
            for (var area : staging.getStagingAreas(schematicId)) staging.rescanStagingArea(area.id());
            if (!currentIntent.getAsBoolean()) return;
            for (var warehouse : staging.getWarehousesForSchematic(schematicId)) staging.rescanWarehouseAndMarkChunks(warehouse.id());
            ingest.publishIfCurrent(currentIntent, () -> {
                Phase4Handler.broadcastAllMaterialStatus(this, schematicId);
                staging.broadcastUpdate(schematicId);
                ModNetworkHandler.pushWarehouseContainerUpdate(this);
                ModNetworkHandler.broadcastWarehouseAreas(this);
            });
        });
    }

    public void schematicRemoved(String schematicId) {
        schematicRemoved(schematicId, () -> true);
    }

    public void schematicRemoved(String schematicId, java.util.function.BooleanSupplier currentIntent) {
        execute(() -> ingest.publishIfCurrent(currentIntent, () -> {
            forgetPlacementName(schematicId);
            subscriptions.forgetProject(schematicId);
            staging.forgetProject(schematicId);
            ModNetworkHandler.sendToPlayers(this, java.util.Set.copyOf(server.getPlayerList().getPlayers()),
                    new StagingAreaConfigResponseS2CPacket("SCHEMATIC_DELETED", schematicId, "", true, "", java.util.List.of()));
        }));
    }

    public void stop() {
        synchronized (lifecycleLock) {
            State previous = state.getAndUpdate(current -> current == State.CLOSED ? current : State.STOPPING);
            if (previous == State.STOPPING || previous == State.CLOSED) return;
            ticks.close();
            cleanup("数据库封闭", database::seal);
            if (discovery != null) cleanup("上传监听器", discovery::close);
            if (watcher != null) cleanup("文件监听器", watcher::stop);
            cleanup("原理图摄取", ingest::close);
            cleanup("区域管理器", staging::close);
            cleanup("协作管理器", collaboration::close);
            subscriptions.close();
            placementNames.clear();
            if (LegacyServerRegistry.get(server) == this) ProtocolHandshake.clear(server);
            cleanup("统计引擎", engine::shutdown);
            cleanup("解析器", parser::close);
        }
    }

    @Override
    public void close() {
        stop();
        if (!closeStarted.compareAndSet(false, true)) {
            awaitClosed();
            return;
        }
        long deadline = System.nanoTime() + CLOSE_TIMEOUT;
        try {
            if (watcher != null && !watcher.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                SyncMaterial.LOGGER.warn("文件监听器未在关闭预算内退出: {}", epoch);
            }
            if (!ingest.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                SyncMaterial.LOGGER.warn("原理图摄取未在关闭预算内退出: {}", epoch);
            }
            if (!parser.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                SyncMaterial.LOGGER.warn("解析器未在关闭预算内退出: {}", epoch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup("数据库", database::close);
            state.set(State.CLOSED);
            closed.complete(null);
        }
    }

    public java.util.concurrent.CompletableFuture<Void> whenClosed() {
        return closed.copy();
    }

    private void awaitClosed() {
        try {
            closed.get(CLOSE_TIMEOUT, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            SyncMaterial.LOGGER.warn("等待 legacy 资源关闭未完成: {}", epoch, failure);
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static void cleanup(String resource, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error error) {
            SyncMaterial.LOGGER.error("关闭 legacy {} 失败", resource, error);
        }
    }
}
