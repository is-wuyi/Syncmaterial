package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 旧后端导入协调器测试：真实 JDBC + 单线程直执行器，不 sleep。
 * 覆盖文件监听/上传共用入口、hash 变更原子替换、删除与晚到解析拒绝、关闭取消。
 */
public class LegacySchematicIngestTest {

    @TempDir
    Path tempDir;

    private SchematicDatabase db;
    private DatabaseQueryService queryService;
    private LitematicaParser parser;
    private LegacyServerContext owner;
    private LegacySchematicIngest ingest;
    private AtomicBoolean current;
    private final UUID epoch = UUID.randomUUID();
    private final List<String> changed = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new SchematicDatabase();
        db.initialize(tempDir.resolve("ingest-test.db").toString());
        queryService = new DatabaseQueryService(db);
        parser = mock(LitematicaParser.class);
        current = new AtomicBoolean(true);
        owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenAnswer(invocation -> current.get());
        when(owner.epoch()).thenReturn(epoch);
        when(owner.database()).thenReturn(db);
        when(owner.queryService()).thenReturn(queryService);
        when(owner.parser()).thenReturn(parser);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            BooleanSupplier currentIntent = invocation.getArgument(1);
            if (currentIntent.getAsBoolean()) changed.add(id);
            return null;
        }).when(owner).schematicChanged(anyString(), any(BooleanSupplier.class));
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            BooleanSupplier currentIntent = invocation.getArgument(1);
            if (currentIntent.getAsBoolean()) removed.add(id);
            return null;
        }).when(owner).schematicRemoved(anyString(), any(BooleanSupplier.class));
        ingest = new LegacySchematicIngest(owner, new DirectExecutor());
    }

    @AfterEach
    void tearDown() {
        if (ingest != null) ingest.close();
        if (db != null) db.close();
    }

    // ========== 新项目 ==========

    @Test
    void watcherImport_aggregatesMaterialsAndWritesAtomically() throws Exception {
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10),
                new MaterialEntry(0, new ItemStack(Items.STONE), 20),
                new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5))));

        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));

        assertNotNull(job);
        assertTrue(job.completion().isDone(), "直执行器应同步完成");
        assertSchematic("s1", "hash1", "Player1");
        assertEquals(30, materialCount("s1", "minecraft:stone"), "同物品两笔应聚合为 30");
        assertEquals(5, materialCount("s1", "minecraft:diamond"));
        assertEquals(1, rowCount("SELECT COUNT(*) FROM material_entries WHERE item_id = 'minecraft:stone'"));
        assertEquals(List.of("s1"), changed, "提交完成后应通知所属上下文");
        assertTrue(removed.isEmpty());
    }

    @Test
    void sameProjectSameHash_reusesInflightJobAndParsesOnce() throws Exception {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job first = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));
        LegacySchematicIngest.Job duplicate = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.UPLOAD, false));

        assertSame(first, duplicate, "同项目同 hash 应复用在途任务");
        verify(parser, times(1)).parseAsync(anyString());

        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        assertTrue(first.completion().isDone());
        assertSchematic("s1", "hash1", "Player1");
        assertEquals(10, materialCount("s1", "minecraft:stone"));
        assertEquals(List.of("s1"), changed, "复用任务只应发布一次");
    }

    @Test
    void sharedJob_survivesOneParticipantRelease() {
        Object uploadParticipant = new Object();
        Object watcherParticipant = new Object();
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job upload = ingest.submit(
                metadata("s1", "hash1", file, LegacySchematicIngest.Source.UPLOAD, false),
                uploadParticipant);
        LegacySchematicIngest.Job watcher = ingest.submit(
                metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true),
                watcherParticipant);
        assertSame(upload, watcher);

        // 旧监听器实例关闭只释放自己的兴趣，文件监听仍在等待该任务。
        watcher.release(uploadParticipant);
        assertFalse(watcher.completion().isDone());

        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        assertTrue(watcher.completion().isDone());
        assertSchematic("s1", "hash1", "Player1");
    }

    @Test
    void sharedJob_lastParticipantReleaseCancelsWithoutWrite() {
        Object first = new Object();
        Object second = new Object();
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = nonCancellableFuture();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job job = ingest.submit(
                metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true), first);
        ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.UPLOAD, false), second);

        job.release(first);
        assertFalse(job.completion().isDone(), "仍有其他参与者时不得取消");
        job.release(second);
        assertTrue(job.completion().isDone(), "最后一个参与者释放后应取消");

        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        assertFalse(queryService.schematicExists("s1"));
        assertTrue(changed.isEmpty());
    }

    @Test
    void watcherJoiningUploadInFlight_upgradesAnnounceAndParsesOnce() {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job upload = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.UPLOAD, false));
        LegacySchematicIngest.Job watcher = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));

        assertSame(upload, watcher);
        verify(parser, times(1)).parseAsync(anyString());

        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        assertTrue(watcher.completion().isDone());
        assertEquals(List.of("s1"), changed, "上传先到时文件监听的首次补扫信号不能丢");
    }

    @Test
    void watcherFirstSeenAfterUploadIntent_upgradesAnnounceForExistingHash() throws Exception {
        db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, file_hash) " +
                        "VALUES ('s1', 'old', '/old.litematic', 'Old', 'hash1')");
        QueueExecutor queued = new QueueExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, queued);
        Path file = writeLitematic("hash1");

        LegacySchematicIngest.Job upload = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.UPLOAD, false));
        LegacySchematicIngest.Job watcher = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));
        assertSame(upload, watcher, "文件监听应复用上传在途任务");

        queued.runAll();

        assertTrue(watcher.completion().isDone());
        verify(parser, never()).parseAsync(anyString());
        assertEquals(List.of("s1"), changed,
                "上传先排队、文件监听后到时仍应补发既有项目的启动扫描通知");
    }

    @Test
    void invalidateStale_keepsMatchingNewIntent() {
        Path file = writeLitematic("hash2");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);
        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash2", file,
                LegacySchematicIngest.Source.UPLOAD, false));

        ingest.invalidateStale("s1", "hash2");

        assertFalse(job.completion().isDone(), "已匹配新 hash 的上传任务不得被文件扫描取消");
        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        assertSchematic("s1", "hash2", "Player1");
    }

    @Test
    void differentProjectsSameHash_areIndependent() throws Exception {
        Path file = writeLitematic("shared-hash");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));

        LegacySchematicIngest.Job first = ingest.submit(metadata("s1", "shared-hash", file,
                LegacySchematicIngest.Source.WATCHER, true));
        LegacySchematicIngest.Job second = ingest.submit(metadata("s2", "shared-hash", file,
                LegacySchematicIngest.Source.WATCHER, true));

        assertNotSame(first, second);
        verify(parser, times(2)).parseAsync(anyString());
        assertSchematic("s1", "shared-hash", "Player1");
        assertSchematic("s2", "shared-hash", "Player1");
        assertEquals(10, materialCount("s2", "minecraft:stone"));
    }

    // ========== hash 变化与既有记录 ==========

    @Test
    void changedHash_replacesRecordsOnlyAtSuccessfulCommit() throws Exception {
        Path firstFile = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        ingest.submit(metadata("s1", "hash1", firstFile,
                LegacySchematicIngest.Source.WATCHER, true));
        insertStagingArea("s1");

        // 变更进行中：旧记录仍在，不提前删除
        Path secondFile = writeLitematic("hash2");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);
        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash2", secondFile,
                LegacySchematicIngest.Source.WATCHER, false));

        assertSchematic("s1", "hash1", "Player1");
        assertEquals(10, materialCount("s1", "minecraft:stone"), "解析未完成前旧记录保留");
        assertEquals(1, rowCount("SELECT COUNT(*) FROM staging_areas WHERE schematic_id = 's1'"));

        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5)));
        assertTrue(job.completion().isDone());
        assertSchematic("s1", "hash2", "Player1");
        assertEquals(0, materialCount("s1", "minecraft:stone"), "旧材料应被整体替换");
        assertEquals(5, materialCount("s1", "minecraft:diamond"));
        assertEquals(0, rowCount("SELECT COUNT(*) FROM staging_areas WHERE schematic_id = 's1'"),
                "hash 变化沿用旧的整项目重置语义");
    }

    @Test
    void existingSameHash_watcherAnnouncesWithoutReparse() throws Exception {
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        changed.clear();

        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));

        assertNotNull(job);
        assertTrue(job.completion().isDone());
        verify(parser, times(1)).parseAsync(anyString());
        assertEquals(List.of("s1"), changed, "首次发现既有项目也要补一次通知");

        // 后续轮询同一 hash 不应反复广播启动补扫
        LegacySchematicIngest.Job polledAgain = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, false));
        assertNotNull(polledAgain);
        assertTrue(polledAgain.completion().isDone());
        assertEquals(List.of("s1"), changed, "非首次扫描不得重复发送补扫通知");
        verify(parser, times(1)).parseAsync(anyString());
    }

    @Test
    void existingSameHash_uploadSkipsWithoutNotify() throws Exception {
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        changed.clear();

        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.UPLOAD, false));

        assertNotNull(job);
        verify(parser, times(1)).parseAsync(anyString());
        assertTrue(changed.isEmpty(), "上传路径保持旧语义：已有项目不覆盖、不广播");
    }

    @Test
    void uploadImport_preservesPerEntryUpsertConvention() throws Exception {
        Path file = writeLitematic("hash-upload");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10),
                new MaterialEntry(0, new ItemStack(Items.STONE), 20),
                new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5))));

        ingest.submit(metadata("up-1", "hash-upload", file,
                LegacySchematicIngest.Source.UPLOAD, false));

        assertEquals(20, materialCount("up-1", "minecraft:stone"),
                "上传路径逐条 upsert，重复物品以最后一条为准（不是求和 30）");
        assertEquals(5, materialCount("up-1", "minecraft:diamond"));
        assertEquals(1, rowCount("SELECT COUNT(*) FROM material_entries WHERE item_id = 'minecraft:stone'"));
        assertSchematic("up-1", "hash-upload", "Player1");
        assertEquals(List.of("up-1"), changed);
    }

    // ========== 失败与删除 ==========

    @Test
    void parseFailure_noWriteAndRetrySucceeds() throws Exception {
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("文件损坏")));

        LegacySchematicIngest.Job failed = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));
        assertNotNull(failed);
        assertTrue(failed.completion().isCompletedExceptionally());
        assertFalse(queryService.schematicExists("s1"), "解析失败不得写入数据库");

        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));

        assertSchematic("s1", "hash1", "Player1");
        assertEquals(10, materialCount("s1", "minecraft:stone"));
    }

    @Test
    void remove_deletesRecordsAndPublishes() throws Exception {
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        insertStagingArea("s1");
        changed.clear();

        CompletableFuture<Boolean> removal = ingest.remove("s1");
        assertTrue(removal.getNow(false), "只有删除事务提交后才报告成功");

        assertFalse(queryService.schematicExists("s1"));
        assertEquals(0, rowCount("SELECT COUNT(*) FROM material_entries"));
        assertEquals(0, rowCount("SELECT COUNT(*) FROM staging_areas"));
        assertEquals(List.of("s1"), removed);
    }

    @Test
    void removeFailure_doesNotPublishAsRemoved() throws Exception {
        db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, file_hash) " +
                        "VALUES ('s1', 'old', '/old.litematic', 'Old', 'hash1')");
        db.seal();

        CompletableFuture<Boolean> removal = ingest.remove("s1");

        assertTrue(removal.isCompletedExceptionally(), "数据库未提交时删除不得当作成功");
        assertTrue(removed.isEmpty(), "失败时不得发布 SCHEMATIC_DELETED");
    }

    @Test
    void lateParseAfterRemove_cannotReviveDeletedProject() throws Exception {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = nonCancellableFuture();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));
        ingest.remove("s1");
        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));

        assertTrue(job.completion().isDone());
        assertFalse(queryService.schematicExists("s1"), "已删除项目不得被晚到的解析写回");
        assertEquals(0, rowCount("SELECT COUNT(*) FROM material_entries"));
        assertEquals(List.of("s1"), removed);
    }

    @Test
    void cancelBeforeCommitLease_preventsWritesAndDeleteWins() throws Exception {
        QueueExecutor queued = new QueueExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, queued);
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> parse = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(parse);

        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        queued.runAll(); // begin -> parse pending

        parse.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        // complete 任务已排队但尚未执行；删除先到，提交租约 CAS 必须失败。
        CompletableFuture<Boolean> removal = ingest.remove("s1");
        queued.runAll();

        assertTrue(removal.getNow(false));
        assertFalse(queryService.schematicExists("s1"), "取消先于提交租约时不得写入");
        assertEquals(List.of("s1"), removed);
        assertTrue(changed.isEmpty());
    }

    @Test
    void admittedCommitLinearizesBeforeCancellationThenDeleteWins() throws Exception {
        ExecutorService serializer = Executors.newSingleThreadExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, serializer);
        Path file = writeLitematic("hash1");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MaterialEntry(0, new ItemStack(Items.STONE), 10))));

        CountDownLatch inTransactionWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        SchematicDatabase spyDb = spy(db);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!paused.getAndSet(true) && sql.startsWith("INSERT INTO schematics")) {
                inTransactionWrite.countDown();
                try {
                    releaseWrite.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return invocation.callRealMethod();
        }).when(spyDb).executeUpdate(anyString(), any(Object[].class));
        when(owner.database()).thenReturn(spyDb);

        LegacySchematicIngest.Job job = ingest.submit(
                metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        assertTrue(inTransactionWrite.await(5, TimeUnit.SECONDS), "提交事务应在写 schematics 前暂停");

        // 租约已由提交线程持有：取消不能回滚本次提交，但随后的删除任务必须获胜。
        CompletableFuture<Boolean> removal = ingest.remove("s1");
        assertFalse(job.completion().isDone(), "已准入提交不得在事务结束前报告完成");
        releaseWrite.countDown();

        assertTrue(removal.get(5, TimeUnit.SECONDS));
        assertFalse(queryService.schematicExists("s1"), "已准入提交完成后删除仍应生效");
        assertTrue(job.completion().isDone(), "事务结束后提交句柄才完成");
        assertEquals(List.of("s1"), removed);
        assertTrue(changed.isEmpty(), "取消后不得再发布 changed");
    }

    @Test
    void supersededHash_lateOldParseCannotOverwriteNewImport() throws Exception {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> oldParse = nonCancellableFuture();
        when(parser.parseAsync(anyString())).thenReturn(oldParse);
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));

        CompletableFuture<List<MaterialEntry>> newParse = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(newParse);
        LegacySchematicIngest.Job newJob = ingest.submit(metadata("s1", "hash2", file,
                LegacySchematicIngest.Source.WATCHER, false));

        oldParse.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 99)));
        assertFalse(queryService.schematicExists("s1"), "被取代的旧解析不得写库");

        newParse.complete(List.of(new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5)));
        assertTrue(newJob.completion().isDone());
        assertSchematic("s1", "hash2", "Player1");
        assertEquals(5, materialCount("s1", "minecraft:diamond"));
        assertEquals(0, materialCount("s1", "minecraft:stone"));
    }

    // ========== 关闭与上下文代次 ==========

    @Test
    void close_terminatesQueuedRemovalFutures() {
        QueueExecutor queued = new QueueExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, queued);

        CompletableFuture<Boolean> removal = ingest.remove("s1");
        assertFalse(removal.isDone(), "删除任务尚未执行");

        ingest.close();

        assertTrue(removal.isDone(), "关闭必须终结排队中的删除 future");
        assertFalse(removal.getNow(true), "未提交的删除不得报告成功");
    }

    @Test
    void close_cancelsPendingParseWithoutWrite() throws Exception {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = nonCancellableFuture();
        when(parser.parseAsync(anyString())).thenReturn(pending);

        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));
        ingest.close();
        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));

        assertTrue(job.completion().isDone(), "关闭应完成句柄 future");
        assertFalse(queryService.schematicExists("s1"));
        assertTrue(ingest.isClosed());
        assertTrue(ingest.awaitTermination(1, TimeUnit.SECONDS), "有界等待应在关闭后独立完成");
        assertNull(ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true)), "关闭后拒绝新任务");
    }

    @Test
    void delayedChangedSignal_afterNewerIntentDoesNotMutate() throws Exception {
        db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, file_hash) " +
                        "VALUES ('s1', 'old', '/old.litematic', 'Old', 'hash1')");
        QueueExecutor queued = new QueueExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, queued);
        List<BooleanSupplier> delayed = new ArrayList<>();
        doAnswer(invocation -> {
            delayed.add(invocation.getArgument(1));
            return null;
        }).when(owner).schematicChanged(anyString(), any(BooleanSupplier.class));
        Path file = writeLitematic("hash1");

        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        queued.runAll();
        assertEquals(1, delayed.size(), "首次发现既有项目应排队一次补扫");

        // 回调尚未执行时出现新 hash 意图；上下文执行谓词必须拒绝旧回调。
        ingest.submit(metadata("s1", "hash2", file, LegacySchematicIngest.Source.WATCHER, false));
        assertFalse(delayed.get(0).getAsBoolean(),
                "延迟的旧 changed 回调不得在新意图之后产生变更");
        assertTrue(changed.isEmpty());
    }

    @Test
    void delayedRemovedSignal_afterReimportDoesNotMutate() throws Exception {
        db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, file_hash) " +
                        "VALUES ('s1', 'old', '/old.litematic', 'Old', 'hash1')");
        QueueExecutor queued = new QueueExecutor();
        ingest.close();
        ingest = new LegacySchematicIngest(owner, queued);
        List<BooleanSupplier> delayed = new ArrayList<>();
        doAnswer(invocation -> {
            delayed.add(invocation.getArgument(1));
            return null;
        }).when(owner).schematicRemoved(anyString(), any(BooleanSupplier.class));
        Path file = writeLitematic("hash1");

        CompletableFuture<Boolean> removal = ingest.remove("s1");
        queued.runAll();
        assertTrue(removal.isDone());
        assertTrue(removal.getNow(false), "删除事务提交后才算成功");
        assertEquals(1, delayed.size());

        // 删除回调尚未执行时重新导入同一项目；旧删除回调必须被谓词拒绝。
        ingest.submit(metadata("s1", "hash1", file, LegacySchematicIngest.Source.WATCHER, true));
        assertFalse(delayed.get(0).getAsBoolean(),
                "延迟的旧 removed 回调不得清掉重新导入的新状态");
        assertTrue(removed.isEmpty());
    }

    @Test
    void oldContext_rejectsNewSubmitBeforeQueueing() {
        current.set(false);
        Path file = writeLitematic("hash1");

        assertNull(ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true)));
        verify(parser, never()).parseAsync(anyString());
    }

    @Test
    void oldContextGeneration_rejectedBeforeWrite() throws Exception {
        Path file = writeLitematic("hash1");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        when(parser.parseAsync(anyString())).thenReturn(pending);
        LegacySchematicIngest.Job job = ingest.submit(metadata("s1", "hash1", file,
                LegacySchematicIngest.Source.WATCHER, true));

        current.set(false); // 模拟原上下文已停止/被新上下文取代
        pending.complete(List.of(new MaterialEntry(0, new ItemStack(Items.STONE), 10)));

        assertTrue(job.completion().isDone());
        assertFalse(queryService.schematicExists("s1"), "旧上下文不得写入数据库");
        assertTrue(changed.isEmpty());
    }

    // ========== 辅助 ==========

    private Path writeLitematic(String hash) {
        try {
            Path folder = Files.createDirectories(tempDir.resolve("syncmatics"));
            Path file = folder.resolve(hash + ".litematic");
            Files.writeString(file, "dummy");
            return file;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private LegacySchematicIngest.Metadata metadata(String id, String hash, Path file,
                                                    LegacySchematicIngest.Source source,
                                                    boolean announce) {
        return new LegacySchematicIngest.Metadata(id, "name-" + id, file, hash, "Player1", source, announce);
    }

    private void insertStagingArea(String schematicId) throws Exception {
        db.executeUpdate(
                "INSERT INTO staging_areas (schematic_id, world, x1, y1, z1, x2, y2, z2, name) " +
                        "VALUES (?, 'minecraft:overworld', 0, 0, 0, 1, 1, 1, 'test')",
                schematicId);
    }

    private void assertSchematic(String id, String hash, String uploader) {
        try (var rows = db.executeQuery(
                "SELECT name, uploaded_by, file_hash FROM schematics WHERE id = ?", id)) {
            assertTrue(rows.next(), "应存在原理图记录: " + id);
            assertEquals("name-" + id, rows.getString("name"), "应记录展示名");
            assertEquals(uploader, rows.getString("uploaded_by"));
            assertEquals(hash, rows.getString("file_hash"));
        } catch (Exception error) {
            fail(error);
        }
    }

    private int materialCount(String schematicId, String itemId) throws Exception {
        try (var rows = db.executeQuery(
                "SELECT COALESCE(SUM(count), 0) FROM material_entries WHERE schematic_id = ? AND item_id = ?",
                schematicId, itemId)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int rowCount(String sql) throws Exception {
        try (var rows = db.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** 模拟解析任务不可取消：即使 cancel 也保持未完成，用于验证晚到结果仍被拒绝。 */
    private static CompletableFuture<List<MaterialEntry>> nonCancellableFuture() {
        return new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
    }

    /** 测试用直执行器：所有提交在当前线程内联执行，不引入真实等待。 */
    private static final class DirectExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    /** 手工执行器：submit 只排队，由测试显式 runAll，便于构造竞态顺序。 */
    private static final class QueueExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) task.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }
}
