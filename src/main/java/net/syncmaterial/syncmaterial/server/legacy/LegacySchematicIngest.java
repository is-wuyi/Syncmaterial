package net.syncmaterial.syncmaterial.server.legacy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;

/**
 * 旧后端原理图导入协调器。
 *
 * <p>文件监听和 Syncmatica 上传事件共享同一个协调器实例，所有数据库写入和删除
 * 都在单线程上串行执行。这样同一项目从两条路径同时到达时，只有第一个意图会解析，
 * 不会出现重复插入或半套材料；变更/删除会递增项目代次，旧的解析完成结果在写入前
 * 即被拒绝。</p>
 *
 * <p>本类不阻塞加入异步解析：{@link LitematicaParser#parseAsync(String)} 的完成回调
 * 只负责把提交任务重新排到协调器线程。数据库提交通过
 * {@code database.inTransaction} 原子完成；关闭时先封闭入口并取消 future，
 * 实际等待由 {@link #awaitTermination(long, TimeUnit)} 在统一关闭预算内完成。</p>
 */
public final class LegacySchematicIngest implements AutoCloseable {

    private static final Object DEFAULT_INTEREST = new Object();

    private static final int OPEN = 0;
    private static final int COMMITTING = 1;
    private static final int CANCELLED = 2;
    private static final int FINISHED = 3;

    private static final int DETACH_CANCEL_NOW = 0;
    private static final int DETACH_DEFER_COMMIT = 1;
    private static final int DETACH_ALREADY_TERMINAL = 2;

    public enum Source {
        WATCHER,
        UPLOAD
    }

    /**
     * 一次导入的不可变元数据。监听器/上传监听器在事件线程上同步捕获，之后不再触碰
     * 原来的 Syncmatica placement 对象。
     *
     * @param announceUnchanged 仅文件监听首次看到该项目时使用：数据库已有同 hash
     *                          记录时也触发一次 {@code schematicChanged}，作为启动扫描
     *                          的补扫信号。后续重复扫描不会反复广播。
     */
    public record Metadata(String id, String name, Path file, String hash, String uploader,
                           Source source, boolean announceUnchanged) {
        public Metadata {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(uploader, "uploader");
            Objects.requireNonNull(source, "source");
        }
    }

    /** 提交句柄。关闭时由拥有者取消；取消后不会再写库或发布。 */
    public final class Job {
        private final String id;
        private final String name;
        private final Path file;
        private final String hash;
        private final String uploader;
        private final Source source;
        private final UUID epoch;
        private final long generation;
        private volatile boolean announceUnchanged;
        private volatile boolean watcherJoined;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final Set<Object> interests = new HashSet<>();
        private volatile CompletableFuture<List<MaterialEntry>> parseFuture;
        private final AtomicInteger state = new AtomicInteger(OPEN);

        private Job(Metadata metadata, UUID epoch, long generation) {
            this.id = metadata.id();
            this.name = metadata.name();
            this.file = metadata.file();
            this.hash = metadata.hash();
            this.uploader = metadata.uploader();
            this.source = metadata.source();
            this.epoch = epoch;
            this.generation = generation;
            this.announceUnchanged = metadata.announceUnchanged();
        }

        public CompletableFuture<Void> completion() {
            return completion;
        }

        /** 强制取消本次导入（协调器/测试用）；幂等，且不等待解析或数据库操作。 */
        public void cancel() {
            int detachMode;
            synchronized (lock) {
                detachMode = detachLocked(this);
            }
            completeCancellation(this, detachMode);
        }

        /**
         * 释放一个参与者（文件监听/上传监听实例）对该任务的兴趣。多个来源共享同一
         * 任务时，只有最后一个参与者释放后才会取消；旧监听器实例关闭不会取消仍在
         * 被新监听器或文件监听使用的任务。
         */
        public void release(Object participant) {
            if (participant == null) return;
            int detachMode;
            synchronized (lock) {
                if (!interests.remove(participant)) return;
                if (!interests.isEmpty()) return;
                detachMode = detachLocked(this);
            }
            completeCancellation(this, detachMode);
        }

        /** 事务内获取提交租约：一旦成功，后续取消线性化到完整提交之后。 */
        private boolean beginCommit() {
            return state.compareAndSet(OPEN, COMMITTING);
        }

        private boolean isOpen() {
            return state.get() == OPEN;
        }

        private boolean isInFlight() {
            int current = state.get();
            return current == OPEN || current == COMMITTING;
        }

        /** @return true 表示取消在提交租约之前生效 */
        private boolean cancelState() {
            return state.compareAndSet(OPEN, CANCELLED);
        }

        private void finishState() {
            state.compareAndSet(OPEN, FINISHED);
            state.compareAndSet(COMMITTING, FINISHED);
        }

        private void failState() {
            if (!state.compareAndSet(OPEN, CANCELLED)) {
                state.compareAndSet(COMMITTING, FINISHED);
            }
        }
    }

    private final Object lock = new Object();
    private final LegacyServerContext owner;
    private final ExecutorService serializer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Job> active = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Boolean>> pendingRemovals = ConcurrentHashMap.newKeySet();

    public LegacySchematicIngest(LegacyServerContext owner) {
        this(owner, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "syncmaterial-legacy-ingest");
            thread.setDaemon(true);
            return thread;
        }));
    }

    LegacySchematicIngest(LegacyServerContext owner, ExecutorService serializer) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /**
     * 提交一次导入意图。同一项目已有相同 hash 的任务在途时复用并返回该任务
     * （完成和发布由在先任务负责，重复调用可据此得到真实的完成信号）；
     * 不同 hash 会取消旧任务并生成新一代。关闭、旧上下文或无法排队时返回 {@code null}。
     *
     * <p>去重只按项目 id；不同项目即使内容 hash 相同也各自独立解析和入库。</p>
     */
    public Job submit(Metadata metadata) {
        return submit(metadata, null);
    }

    /**
     * @param participant 提交者实例（文件监听/上传监听）。共享任务会记录多个参与者，
     *                    关闭时只 {@link Job#release(Object)} 自己的兴趣。
     */
    public Job submit(Metadata metadata, Object participant) {
        Objects.requireNonNull(metadata, "metadata");
        if (closed.get() || !owner.isCurrent()) return null;
        Object interest = participant == null ? DEFAULT_INTEREST : participant;
        UUID epoch = owner.epoch();
        Job job;
        Job superseded = null;
        int cancelSupersededParse = DETACH_ALREADY_TERMINAL;
        synchronized (lock) {
            if (closed.get()) return null;
            Job existing = active.get(metadata.id());
            if (existing != null) {
                if (existing.isInFlight() && existing.hash.equals(metadata.hash())) {
                    // 上传先到、文件监听后到：保留启动补扫信号，并升级为监听策略
                    // （既有记录按 hash 语义处理/变更时重新导入）。
                    if (metadata.source() == Source.WATCHER) existing.watcherJoined = true;
                    existing.announceUnchanged |= metadata.announceUnchanged();
                    existing.interests.add(interest);
                    return existing;
                }
                superseded = existing;
                cancelSupersededParse = detachLocked(existing);
            }
            long generation = generations.merge(metadata.id(), 1L, Long::sum);
            job = new Job(metadata, epoch, generation);
            job.interests.add(interest);
            active.put(metadata.id(), job);
        }
        if (superseded != null) completeCancellation(superseded, cancelSupersededParse);
        try {
            serializer.execute(() -> begin(job));
        } catch (RejectedExecutionException rejected) {
            synchronized (lock) {
                if (active.get(job.id) == job) active.remove(job.id);
            }
            job.completion.complete(null);
            return null;
        }
        return job;
    }

    /** 使项目在途导入失效（例如 hash 变化），但不删除数据库记录。 */
    public void invalidate(String schematicId) {
        invalidateStale(schematicId, null);
    }

    /**
     * hash 变化时使旧意图失效，但保留已经匹配新 hash 的在途任务（例如上传事件
     * 先于文件扫描到达）。{@code expectedHash} 为 {@code null} 时无条件失效。
     */
    public void invalidateStale(String schematicId, String expectedHash) {
        if (schematicId == null) return;
        Job detached = null;
        int cancelParse = DETACH_ALREADY_TERMINAL;
        synchronized (lock) {
            if (closed.get()) return;
            Job existing = active.get(schematicId);
            if (existing != null && expectedHash != null
                    && existing.isInFlight() && existing.hash.equals(expectedHash)) {
                return;
            }
            generations.merge(schematicId, 1L, Long::sum);
            if (existing != null) {
                detached = existing;
                cancelParse = detachLocked(existing);
            }
        }
        if (detached != null) completeCancellation(detached, cancelParse);
    }

    /**
     * 删除项目并清理缓存/订阅。删除任务与导入任务在同一串行线程上执行；
     * 如果期间又出现了新的导入意图，旧删除任务会自动放弃，避免删掉刚重新导入的数据。
     *
     * @return 完成信号：只有数据库删除确实提交时才为 {@code true}。失败/被新意图
     *         取代时不会发布删除；调用方可以保留待删意图并重试。
     */
    public CompletableFuture<Boolean> remove(String schematicId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (schematicId == null) {
            result.complete(false);
            return result;
        }
        long generation = 0;
        Job detached = null;
        int cancelParse = DETACH_ALREADY_TERMINAL;
        boolean closedRejected = false;
        synchronized (lock) {
            if (closed.get()) {
                closedRejected = true;
            } else {
                generation = generations.merge(schematicId, 1L, Long::sum);
                Job existing = active.remove(schematicId);
                if (existing != null) {
                    detached = existing;
                    cancelParse = detachLocked(existing);
                }
                pendingRemovals.add(result);
            }
        }
        if (closedRejected) {
            result.complete(false);
            return result;
        }
        if (detached != null) completeCancellation(detached, cancelParse);
        final long targetGeneration = generation;
        try {
            serializer.execute(() -> removeTask(schematicId, targetGeneration, result));
        } catch (RejectedExecutionException rejected) {
            pendingRemovals.remove(result);
            result.complete(false);
        }
        return result;
    }

    private void begin(Job job) {
        if (!admit(job)) {
            finish(job);
            return;
        }
        try {
            StoredSchematic stored = readStored(job.id);
            if (stored.exists) {
                if (!usesWatcherPolicy(job)) {
                    // 纯上传路径的既定语义：数据库已有该项目就跳过，不覆盖。
                    finish(job);
                    return;
                }
                if (stored.hash != null && !stored.hash.isEmpty()) {
                    if (stored.hash.equals(job.hash)) {
                        // 与旧文件监听一致：hash 未变不重复解析；首次发现时补一次通知。
                        if (finish(job) && job.announceUnchanged) announce(job);
                        return;
                    }
                    // hash 已变化：继续解析，完成后在同一事务里整体替换旧记录。
                } else {
                    // 旧实现把空 hash 视为“已处理”，保持该行为，避免启动时无谓重解析。
                    if (finish(job) && job.announceUnchanged) announce(job);
                    return;
                }
            }

            if (!Files.isRegularFile(job.file)) {
                fail(job, "原理图文件不存在");
                return;
            }
            // 数据库检查/文件检查期间可能已被删除或替换；排队解析前再次确认。
            if (!admit(job)) {
                finish(job);
                return;
            }

            CompletableFuture<List<MaterialEntry>> future;
            try {
                future = owner.parser().parseAsync(job.file.toString());
            } catch (RuntimeException error) {
                fail(job, error);
                return;
            }
            if (future == null) {
                fail(job, "解析器返回了 null future");
                return;
            }
            job.parseFuture = future;
            if (!job.isOpen()) {
                future.cancel(true);
                return;
            }
            future.whenComplete((materials, error) -> {
                if (!job.isOpen()) return;
                try {
                    serializer.execute(() -> complete(job, materials, error));
                } catch (RejectedExecutionException ignored) {
                    // 协调器已关闭：不能提交，也不得发布。
                }
            });
        } catch (SQLException | RuntimeException | Error error) {
            fail(job, error);
        }
    }

    private void complete(Job job, List<MaterialEntry> materials, Throwable error) {
        if (!admit(job)) {
            finish(job);
            return;
        }
        if (error != null) {
            fail(job, error);
            return;
        }
        List<MaterialEntry> parsed = materials == null ? List.of() : materials;
        try {
            boolean written = owner.database().inTransaction(() -> commitRows(job, parsed));
            boolean stillCurrent = finish(job);
            if (stillCurrent && (written
                    || (usesWatcherPolicy(job) && job.announceUnchanged))) {
                announce(job);
            }
        } catch (SQLException | RuntimeException | Error failure) {
            fail(job, failure);
        }
    }

    /**
     * 事务回调。进入时再次验证代次与上下文，所有 SQL 在同一连接事务中完成。
     *
     * @return true 表示确实写入了新记录
     */
    private boolean commitRows(Job job, List<MaterialEntry> materials) throws SQLException {
        // 在取得数据库 monitor 后原子领取提交租约。取消发生在租约之前则 CAS 失败、
        // 一个字节都不写；发生在租约之后则线性化到完整提交之后，随后排队的删除/新
        // 意图在串行器上执行并覆盖结果。事务回调内不再获取 ingest 锁，避免
        // DB monitor 与 ingest 锁互相等待。
        if (closed.get() || !owner.isCurrent() || !Objects.equals(job.epoch, owner.epoch())) {
            return false;
        }
        if (!job.beginCommit()) return false;

        StoredSchematic stored = readStored(job.id);
        if (stored.exists) {
            if (!usesWatcherPolicy(job)) return false;
            if (stored.hash != null && !stored.hash.isEmpty()) {
                if (stored.hash.equals(job.hash)) return false;
            } else {
                // 与旧行为一致：空 hash 已视为处理过，不覆盖。
                return false;
            }
        }

        if (stored.exists) {
            deleteProjectRows(job.id);
        }
        owner.database().executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, file_hash) VALUES (?, ?, ?, ?, ?)",
                job.id, job.name, job.file.toString(), job.uploader, job.hash);

        if (job.source == Source.UPLOAD && !job.watcherJoined) {
            insertUploadedMaterials(job.id, materials);
        } else {
            insertWatchedMaterials(job.id, materials);
        }
        return true;
    }

    /**
     * 文件监听口径：同物品聚合求和后每个物品一行。空 ItemStack 跳过。
     */
    private void insertWatchedMaterials(String schematicId, List<MaterialEntry> materials)
            throws SQLException {
        Map<String, Long> aggregated = new LinkedHashMap<>();
        for (MaterialEntry entry : materials) {
            if (entry == null || entry.getStack().isEmpty()) continue;
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getStack().getItem()).toString();
            aggregated.merge(itemId, entry.getCountTotal(), Long::sum);
        }
        for (Map.Entry<String, Long> entry : aggregated.entrySet()) {
            long count = entry.getValue();
            int countInt = (int) Math.min(count, Integer.MAX_VALUE);
            owner.database().executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?) " +
                            "ON CONFLICT(schematic_id, item_id) DO UPDATE SET count = excluded.count",
                    schematicId, entry.getKey(), countInt);
        }
    }

    /**
     * 上传口径：保持旧上传路径的逐条 upsert（重复物品以最后一条为准），不额外聚合。
     */
    private void insertUploadedMaterials(String schematicId, List<MaterialEntry> materials)
            throws SQLException {
        for (MaterialEntry entry : materials) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getStack().getItem()).toString();
            long countLong = entry.getCountTotal();
            int count = (int) Math.min(countLong, Integer.MAX_VALUE);
            owner.database().executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?) " +
                            "ON CONFLICT(schematic_id, item_id) DO UPDATE SET count = excluded.count",
                    schematicId, itemId, count);
        }
    }

    private void removeTask(String schematicId, long generation,
                            CompletableFuture<Boolean> result) {
        try {
            if (!isIntentCurrent(schematicId, generation) || !owner.isCurrent()) {
                result.complete(false);
                return;
            }
            final Boolean committed;
            try {
                committed = owner.database().inTransaction(() -> {
                    // 事务内不获取 ingest 锁：避免与提交路径形成 DB monitor/ingest 锁反转。
                    if (closed.get()) return false;
                    if (!owner.isCurrent()) return false;
                    Long current = generations.get(schematicId);
                    if (current == null || current.longValue() != generation) return false;
                    deleteProjectRows(schematicId);
                    return true;
                });
            } catch (SQLException | RuntimeException | Error error) {
                SyncMaterial.LOGGER.error("删除原理图记录失败: {}", schematicId, error);
                result.completeExceptionally(error);
                return;
            }
            if (!Boolean.TRUE.equals(committed)) {
                result.complete(false);
                return;
            }
            // 只有数据库删除确实提交后才对外发布删除；执行时由上下文再次校验谓词，
            // 避免排队回调在新导入之后清掉新缓存/项目。
            try {
                if (isIntentCurrent(schematicId, generation) && owner.isCurrent()) {
                    owner.schematicRemoved(schematicId,
                            () -> isIntentCurrent(schematicId, generation));
                }
                result.complete(true);
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        } finally {
            pendingRemovals.remove(result);
        }
    }

    // Called under the owner's lifecycle lock; publication cannot wait for server or serializer work.
    void publishIfCurrent(java.util.function.BooleanSupplier currentIntent, Runnable publication) {
        synchronized (lock) {
            if (!closed.get() && owner.isCurrent() && currentIntent.getAsBoolean()) publication.run();
        }
    }

    private boolean isIntentCurrent(String schematicId, long generation) {
        synchronized (lock) {
            if (closed.get()) return false;
            Long current = generations.get(schematicId);
            return current != null && current.longValue() == generation;
        }
    }

    private boolean admit(Job job) {
        synchronized (lock) {
            if (closed.get() || !job.isOpen()) return false;
            if (active.get(job.id) != job) return false;
            Long current = generations.get(job.id);
            if (current == null || current.longValue() != job.generation) return false;
        }
        return owner.isCurrent() && Objects.equals(job.epoch, owner.epoch());
    }

    private StoredSchematic readStored(String schematicId) throws SQLException {
        try (var rows = owner.database().executeQuery(
                "SELECT file_hash FROM schematics WHERE id = ?", schematicId)) {
            if (!rows.next()) return StoredSchematic.MISSING;
            return new StoredSchematic(true, rows.getString(1));
        }
    }

    private void deleteProjectRows(String schematicId) throws SQLException {
        owner.database().executeUpdate(
                "DELETE FROM staging_area_inventory WHERE staging_area_id IN " +
                        "(SELECT id FROM staging_areas WHERE schematic_id = ?)", schematicId);
        owner.database().executeUpdate("DELETE FROM staging_areas WHERE schematic_id = ?", schematicId);
        owner.database().executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", schematicId);
        owner.database().executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
    }

    private static boolean usesWatcherPolicy(Job job) {
        return job.source == Source.WATCHER || job.watcherJoined;
    }

    private void announce(Job job) {
        if (!isIntentCurrent(job.id, job.generation) || !owner.isCurrent()) return;
        try {
            owner.schematicChanged(job.id,
                    () -> isIntentCurrent(job.id, job.generation));
        } catch (RuntimeException error) {
            SyncMaterial.LOGGER.warn("请求原理图更新发布失败: {}", job.id, error);
        }
    }

    /** @return true 表示完成任务时仍是当前意图（未被新意图/删除取代） */
    private boolean finish(Job job) {
        boolean stillCurrent;
        synchronized (lock) {
            stillCurrent = active.get(job.id) == job;
            if (stillCurrent) active.remove(job.id);
        }
        job.finishState();
        job.completion.complete(null);
        return stillCurrent;
    }

    private void fail(Job job, String message) {
        fail(job, new IllegalStateException(message));
    }

    private void fail(Job job, Throwable error) {
        synchronized (lock) {
            if (active.get(job.id) == job) active.remove(job.id);
            job.failState();
        }
        cancelParseFuture(job);
        SyncMaterial.LOGGER.error("导入原理图失败: {} ({})", job.id, job.file, error);
        job.completion.completeExceptionally(error);
    }

    /** 调用方必须持有 {@link #lock}；future 取消与完成回调在锁外执行。 */
    private int detachLocked(Job job) {
        boolean cancelledBeforeCommit = job.cancelState();
        if (active.get(job.id) == job) active.remove(job.id);
        if (cancelledBeforeCommit) return DETACH_CANCEL_NOW;
        return job.state.get() == COMMITTING ? DETACH_DEFER_COMMIT : DETACH_ALREADY_TERMINAL;
    }

    private void completeCancellation(Job job, int detachMode) {
        if (detachMode == DETACH_CANCEL_NOW) cancelParseFuture(job);
        // 已准入提交必须在事务完整结束后由 finish/fail 完成，不能提前报告完成。
        if (detachMode != DETACH_DEFER_COMMIT) job.completion.complete(null);
    }

    private void cancelParseFuture(Job job) {
        CompletableFuture<List<MaterialEntry>> future = job.parseFuture;
        if (future != null) future.cancel(true);
    }

    /** 关闭入口：拒绝新任务、取消在途 future、立即中断串行线程；不等待。 */
    @Override
    public void close() {
        List<Job> snapshot;
        List<Job> cancelParse = new ArrayList<>();
        List<Job> completeNow = new ArrayList<>();
        List<CompletableFuture<Boolean>> removals;
        synchronized (lock) {
            if (!closed.compareAndSet(false, true)) return;
            snapshot = new ArrayList<>(active.values());
            active.clear();
            for (Job job : snapshot) {
                int detachMode = detachLocked(job);
                if (detachMode == DETACH_CANCEL_NOW) cancelParse.add(job);
                if (detachMode != DETACH_DEFER_COMMIT) completeNow.add(job);
            }
            removals = new ArrayList<>(pendingRemovals);
            pendingRemovals.clear();
        }
        for (Job job : cancelParse) cancelParseFuture(job);
        for (Job job : completeNow) job.completion.complete(null);
        for (CompletableFuture<Boolean> removal : removals) removal.complete(false);
        serializer.shutdownNow();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 在统一关闭预算内等待协调器终止。调用方应先 {@link #close()}。
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return serializer.awaitTermination(timeout, unit);
    }

    private record StoredSchematic(boolean exists, String hash) {
        private static final StoredSchematic MISSING = new StoredSchematic(false, null);
    }
}
