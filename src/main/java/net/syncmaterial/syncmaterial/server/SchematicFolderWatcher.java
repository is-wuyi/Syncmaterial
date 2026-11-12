package net.syncmaterial.syncmaterial.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.legacy.LegacySchematicIngest;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * 旧后端原理图目录监控。
 *
 * <p>本实例只服务一个 {@link LegacyServerContext}：目录、数据库、解析器、名称缓存和
 * 取消状态全部来自该 owner，不再使用静态 placement/processed 缓存。{@link #start()}
 * 不在服务器线程做文件 IO：注册 WatchService、初始扫描和事件循环都在后台执行器上
 * 完成，扫描就绪通过 {@link #initialScanCompleted()} 与导入完成回调观察。解析完成由
 * {@link LegacySchematicIngest} 在所属上下文仍有效时原子提交，并按代次谓词回调
 * {@code owner.schematicChanged(id, ...)}；删除/变更会先使旧解析结果失效，晚到的解析
 * 不允许把已删除项目写回来。</p>
 */
public final class SchematicFolderWatcher implements AutoCloseable {

    private static final String PLACEMENTS_FILE = "placements.json";
    private static final String PLACEMENTS_TEMP_FILE = "placements.json.new";

    private final LegacyServerContext owner;
    private final Path syncmaticaFolder;
    private final Path placementsFile;
    private final Path schematicsRootFolder;
    private final ExecutorService watchExecutor;
    private final LegacySchematicIngest ingest;
    private final Gson gson = new Gson();
    private final Set<LegacySchematicIngest.Job> jobs = ConcurrentHashMap.newKeySet();
    private final Map<String, String> knownHashes = new ConcurrentHashMap<>();
    private final Set<String> removalRetries = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> initialScan = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile WatchService watchService;

    public SchematicFolderWatcher(LegacyServerContext owner) {
        this(owner, newWatchExecutor(), owner.ingest());
    }

    SchematicFolderWatcher(LegacyServerContext owner, ExecutorService watchExecutor,
                           LegacySchematicIngest ingest) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.syncmaticaFolder = owner.worldRoot().resolve("syncmatica");
        this.placementsFile = syncmaticaFolder.resolve(PLACEMENTS_FILE);
        this.schematicsRootFolder = owner.schematicsRoot();
        this.watchExecutor = Objects.requireNonNull(watchExecutor, "watchExecutor");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    private static ExecutorService newWatchExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncmaterial-legacy-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动目录监控。方法本身不在调用线程做文件 IO：注册 WatchService、初始扫描和
     * 事件循环全部在后台执行器上完成。重复调用幂等，关闭后不再启动。
     */
    public void start() {
        if (closed.get() || !owner.isCurrent()) {
            initialScan.complete(null);
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        try {
            watchExecutor.execute(this::runInBackground);
        } catch (RuntimeException error) {
            SyncMaterial.LOGGER.error("启动原理图监控失败: {}", syncmaticaFolder, error);
            initialScan.complete(null);
        }
    }

    private void runInBackground() {
        if (closed.get()) {
            initialScan.complete(null);
            return;
        }
        createDirectory(syncmaticaFolder);
        createDirectory(schematicsRootFolder);
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            syncmaticaFolder.register(service,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            watchService = service;
            if (closed.get()) {
                closeWatchService();
                initialScan.complete(null);
                return;
            }
        } catch (IOException | RuntimeException error) {
            SyncMaterial.LOGGER.error("注册原理图目录监控失败: {}", syncmaticaFolder, error);
            closeWatchService();
        }
        boolean scanFailed = false;
        try {
            if (!closed.get()) scanOnce();
        } catch (RuntimeException error) {
            scanFailed = true;
            SyncMaterial.LOGGER.error("初始原理图扫描失败: {}", syncmaticaFolder, error);
        } finally {
            initialScan.complete(null);
        }
        if (!scanFailed && !closed.get() && watchService != null) {
            SyncMaterial.LOGGER.info("旧后端原理图监控已启动: {}", syncmaticaFolder);
            watchLoop();
        }
    }

    /**
     * 初始扫描（仅目录清单读取与排队，不等待解析/入库）完成信号。
     */
    public CompletableFuture<Void> initialScanCompleted() {
        return initialScan;
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            SyncMaterial.LOGGER.warn("创建原理图目录失败: {}", directory, error);
        }
    }

    /**
     * 消费并 reset 一个 key。
     *
     * @return {@code valid=false} 表示 key 已失效，调用方应退出；否则报告是否需要扫描。
     */
    static EventResult consumeEvents(WatchKey key) {
        boolean needsScan = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                needsScan = true;
                continue;
            }
            if (!(event.context() instanceof Path changed)) continue;
            Path fileName = changed.getFileName();
            if (fileName == null) continue;
            String filename = fileName.toString();
            if (PLACEMENTS_FILE.equals(filename) || PLACEMENTS_TEMP_FILE.equals(filename)) {
                needsScan = true;
            }
        }
        return new EventResult(key.reset(), needsScan);
    }

    private void watchLoop() {
        while (!closed.get()) {
            WatchService service = watchService;
            if (service == null) return;
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException closedService) {
                return;
            }

            boolean needsScan;
            try {
                EventResult initial = consumeEvents(key);
                if (!initial.valid()) return;
                needsScan = initial.needsScan();
            } catch (ClosedWatchServiceException closedService) {
                return;
            } catch (RuntimeException error) {
                SyncMaterial.LOGGER.warn("读取原理图目录事件失败", error);
                needsScan = false;
            }

            if (needsScan && !closed.get()) {
                // 合并 Syncmatica 写入 .new 与重命名的连续事件；有界等待可被关闭唤醒，
                // 并完整 drain 额外 key，避免未消费事件导致持续自旋。
                try {
                    WatchKey extra = service.poll(200, TimeUnit.MILLISECONDS);
                    while (extra != null) {
                        EventResult extraResult = consumeEvents(extra);
                        if (!extraResult.valid()) return;
                        needsScan |= extraResult.needsScan();
                        extra = service.poll();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ClosedWatchServiceException closedService) {
                    return;
                }
                if (!closed.get()) scanOnce();
            }
        }
    }

    /**
     * 确定性扫描入口：读取 placements.json，排队新增/变更导入，并对已消失的项目
     * 发出删除意图。返回 {@code true} 表示配置文件已成功读取并处理；已有扫描在
     * 进行时返回 {@code false}，调用方（后台 watch/启动扫描）可依赖事件或下次轮询。
     * 方法不持有 watcher monitor，关闭/等待不会被文件 IO 阻塞。
     */
    boolean scanOnce() {
        if (!scanning.compareAndSet(false, true)) return false;
        try {
            return scanOnceLocked();
        } finally {
            scanning.set(false);
            // 关闭与扫描并发时避免扫描末尾把旧状态重新写回已结束的 owner。
            if (closed.get()) knownHashes.clear();
        }
    }

    private boolean scanOnceLocked() {
        if (closed.get() || !owner.isCurrent()) return false;

        Path actualFile = Files.exists(placementsFile)
                ? placementsFile
                : syncmaticaFolder.resolve(PLACEMENTS_TEMP_FILE);
        if (!Files.exists(actualFile)) {
            SyncMaterial.LOGGER.info("placements.json 文件不存在: {}", actualFile);
            return false;
        }

        JsonArray placements;
        try {
            String content = Files.readString(actualFile);
            JsonObject root = gson.fromJson(content, JsonObject.class);
            if (root == null || !root.has("placements") || !root.get("placements").isJsonArray()) {
                SyncMaterial.LOGGER.warn("placements.json 缺少 placements 数组: {}", actualFile);
                return false;
            }
            placements = root.getAsJsonArray("placements");
        } catch (IOException | RuntimeException error) {
            SyncMaterial.LOGGER.error("解析 placements.json 失败: {}", actualFile, error);
            return false;
        }

        Map<String, Placement> current = new LinkedHashMap<>();
        boolean allValid = true;
        for (var element : placements) {
            try {
                Placement placement = readPlacement(element);
                if (placement == null) {
                    allValid = false;
                } else {
                    current.put(placement.id(), placement);
                }
            } catch (RuntimeException error) {
                allValid = false;
                SyncMaterial.LOGGER.warn("忽略无效 placement 条目", error);
            }
        }

        List<String> retryRemovals = new ArrayList<>(removalRetries);
        for (Placement placement : current.values()) {
            if (closed.get() || !owner.isCurrent()) return false;
            try {
                retryRemovals.remove(placement.id());
                removalRetries.remove(placement.id());
                boolean firstSeen = !knownHashes.containsKey(placement.id());
                String previousHash = knownHashes.get(placement.id());
                owner.setPlacementName(placement.id(), placement.displayName());
                if (closed.get() || !owner.isCurrent()) return false;

                // hash 变化立即作废旧解析，即使新文件尚未出现也不能让旧结果落库。
                if (previousHash != null && !previousHash.equals(placement.hash())) {
                    ingest.invalidateStale(placement.id(), placement.hash());
                }

                Path litematicFile = findLitematicFile(placement.hash());
                if (litematicFile == null) {
                    SyncMaterial.LOGGER.warn("未找到 hash={} 对应的 litematic 文件", placement.hash());
                    continue;
                }
                LegacySchematicIngest.Metadata metadata = new LegacySchematicIngest.Metadata(
                        placement.id(),
                        placement.displayName(),
                        litematicFile,
                        placement.hash(),
                        placement.ownerName(),
                        LegacySchematicIngest.Source.WATCHER,
                        firstSeen);
                LegacySchematicIngest.Job job = ingest.submit(metadata, this);
                if (job != null) {
                    if (closed.get()) {
                        job.release(this);
                        return false;
                    }
                    track(job);
                }
            } catch (RuntimeException error) {
                SyncMaterial.LOGGER.error("处理 placement 失败: {}", placement.id(), error);
            }
        }

        if (closed.get() || !owner.isCurrent()) return false;

        // 只要有一条 placement 暂时无法解析，就保留已知集合与删除重试，避免把格式抖动
        // 误判为删除/重导入。
        if (allValid) {
            // 上次删除失败/未确认的意图继续重试，直到数据库删除确实提交。
            for (String schematicId : retryRemovals) {
                if (closed.get() || !owner.isCurrent()) return false;
                requestRemoval(schematicId);
            }

            List<String> removed = new ArrayList<>(knownHashes.keySet());
            removed.removeAll(current.keySet());
            for (String schematicId : removed) {
                if (closed.get() || !owner.isCurrent()) return false;
                try {
                    removalRetries.add(schematicId);
                    requestRemoval(schematicId);
                    owner.forgetPlacementName(schematicId);
                } catch (RuntimeException error) {
                    SyncMaterial.LOGGER.error("清理已删除 placement 失败: {}", schematicId, error);
                }
            }
            knownHashes.clear();
        }
        if (!closed.get()) {
            for (Placement placement : current.values()) {
                knownHashes.put(placement.id(), placement.hash());
            }
        }
        return true;
    }

    private Placement readPlacement(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject placement = element.getAsJsonObject();
        if (!placement.has("id") || !placement.has("hash")) {
            SyncMaterial.LOGGER.warn("placement 缺少 id/hash，跳过");
            return null;
        }
        String id = placement.get("id").getAsString();
        String hash = placement.get("hash").getAsString();
        if (id.isEmpty() || hash.isEmpty()) {
            SyncMaterial.LOGGER.warn("placement 的 id/hash 为空，跳过");
            return null;
        }
        String displayName = placement.has("display_name")
                ? placement.get("display_name").getAsString()
                : "unknown";
        String ownerName = "unknown";
        if (placement.has("owner") && placement.get("owner").isJsonObject()) {
            JsonObject ownerObject = placement.getAsJsonObject("owner");
            if (ownerObject.has("name")) ownerName = ownerObject.get("name").getAsString();
        }
        return new Placement(id, hash, displayName, ownerName);
    }

    private Path findLitematicFile(String hash) {
        if (hash.isEmpty() || !Files.isDirectory(schematicsRootFolder)) return null;
        List<NamedPath> candidates = new ArrayList<>();
        try (var files = Files.list(schematicsRootFolder)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                Path fileName = file.getFileName();
                if (fileName != null) candidates.add(new NamedPath(fileName.toString(), file));
            });
        } catch (IOException | RuntimeException error) {
            SyncMaterial.LOGGER.error("查找 litematic 文件失败: {}", hash, error);
            return null;
        }
        candidates.removeIf(candidate -> !candidate.name().startsWith(hash)
                || !candidate.name().endsWith(".litematic"));
        candidates.sort(Comparator.comparing(NamedPath::name));
        return candidates.isEmpty() ? null : candidates.get(0).path();
    }

    private void requestRemoval(String schematicId) {
        CompletableFuture<Boolean> removal;
        try {
            removal = ingest.remove(schematicId);
        } catch (RuntimeException error) {
            SyncMaterial.LOGGER.error("请求删除原理图失败: {}", schematicId, error);
            return;
        }
        removal.whenComplete((committed, error) -> {
            // 只有事务确实提交才退出待删集合；false/异常都保留重试意图，
            // 直到下一次扫描重新请求，或 placement 重新出现时由扫描循环剔除。
            if (error == null && Boolean.TRUE.equals(committed)) {
                removalRetries.remove(schematicId);
            }
        });
    }

    private void track(LegacySchematicIngest.Job job) {
        if (closed.get()) {
            job.release(this);
            return;
        }
        jobs.add(job);
        if (closed.get()) {
            job.release(this);
            jobs.remove(job);
            return;
        }
        job.completion().whenComplete((ignored, error) -> jobs.remove(job));
    }

    private void closeWatchService() {
        WatchService service = watchService;
        watchService = null;
        if (service == null) return;
        try {
            service.close();
        } catch (IOException error) {
            SyncMaterial.LOGGER.warn("关闭原理图目录监控失败", error);
        }
    }

    /** 停止扫描并取消本实例提交的导入；不等待线程或解析结束。 */
    public void stop() {
        if (!closed.compareAndSet(false, true)) return;
        closeWatchService();
        for (LegacySchematicIngest.Job job : jobs) job.release(this);
        jobs.clear();
        knownHashes.clear();
        removalRetries.clear();
        initialScan.complete(null);
        watchExecutor.shutdownNow();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 在统一关闭预算内等待 watch 线程退出；解析器和协调器的回收由 owner 负责。
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return watchExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        stop();
    }

    record EventResult(boolean valid, boolean needsScan) {
    }

    private record Placement(String id, String hash, String displayName, String ownerName) {
    }

    private record NamedPath(String name, Path path) {
    }
}
