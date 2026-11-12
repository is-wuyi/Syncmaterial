package net.syncmaterial.syncmaterial.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import net.syncmaterial.syncmaterial.server.legacy.LegacySchematicIngest;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * 文件监听职责测试：只验证 owner 作用域、扫描意图与生命周期；数据库语义由
 * LegacySchematicIngestTest 用真实 JDBC 覆盖。不依赖静态缓存，不 sleep。
 */
public class SchematicFolderWatcherTest {

    @TempDir
    Path tempDir;

    private Path syncmaticaFolder;
    private Path syncmaticsFolder;
    private LegacyServerContext owner;
    private LegacySchematicIngest ingest;
    private AtomicBoolean current;
    private Map<String, String> names;
    private RecordingExecutor watchExecutor;
    private SchematicFolderWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        Path worldRoot = Files.createDirectories(tempDir.resolve("world"));
        syncmaticaFolder = Files.createDirectories(worldRoot.resolve("syncmatica"));
        syncmaticsFolder = Files.createDirectories(tempDir.resolve("game").resolve("syncmatics"));
        current = new AtomicBoolean(true);
        names = new ConcurrentHashMap<>();
        owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenAnswer(invocation -> current.get());
        when(owner.worldRoot()).thenReturn(worldRoot);
        when(owner.schematicsRoot()).thenReturn(syncmaticsFolder);
        when(owner.placementNames()).thenAnswer(invocation -> Map.copyOf(names));
        doAnswer(invocation -> {
            names.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(owner).setPlacementName(anyString(), anyString());
        doAnswer(invocation -> {
            names.remove(invocation.getArgument(0));
            return null;
        }).when(owner).forgetPlacementName(anyString());
        ingest = mock(LegacySchematicIngest.class);
        when(ingest.remove(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        watchExecutor = new RecordingExecutor();
        watcher = new SchematicFolderWatcher(owner, watchExecutor, ingest);
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.stop();
    }

    // ========== 扫描与元数据 ==========

    @Test
    void scanOnce_submitsOwnerScopedMetadataAndFirstSeenFlag() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        assertTrue(watcher.scanOnce());

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest, times(1)).submit(captor.capture(), any());
        LegacySchematicIngest.Metadata first = captor.getValue();
        assertEquals("s1", first.id());
        assertEquals("主城堡", first.name());
        assertEquals("hash1", first.hash());
        assertEquals("Player1", first.uploader());
        assertEquals(LegacySchematicIngest.Source.WATCHER, first.source());
        assertTrue(first.announceUnchanged(), "首次发现应带启动补扫信号");
        assertEquals(syncmaticsFolder.resolve("hash1.litematic").toAbsolutePath(), first.file());
        verify(owner, times(1)).setPlacementName("s1", "主城堡");
        assertEquals("主城堡", names.get("s1"));

        // 同一实例再次扫描：仍在途/已入库的相同意图复用，但不再要求启动补扫通知
        ArgumentCaptor<LegacySchematicIngest.Metadata> secondCaptor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        assertTrue(watcher.scanOnce());
        verify(ingest, times(2)).submit(secondCaptor.capture(), any());
        assertFalse(secondCaptor.getAllValues().get(1).announceUnchanged());
        verify(owner, times(2)).setPlacementName("s1", "主城堡");
    }

    @Test
    void changedHash_sameProjectSubmitsNewIntent() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "旧名字");
        watcher.scanOnce();

        writeLitematic("hash2");
        writePlacements("s1", "hash2", "新名字");
        watcher.scanOnce();

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest, times(2)).submit(captor.capture(), any());
        assertEquals("hash1", captor.getAllValues().get(0).hash());
        assertEquals("hash2", captor.getAllValues().get(1).hash());
        verify(owner).setPlacementName("s1", "新名字");
    }

    @Test
    void changedHashWithoutFile_invalidatesOldIntent() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "旧名字");
        watcher.scanOnce();

        // 新 hash 已出现在 placements，但 litematic 文件尚未落盘：旧解析必须立即失效。
        writePlacements("s1", "hash2", "新名字");
        assertTrue(watcher.scanOnce());

        verify(ingest).invalidateStale("s1", "hash2");
        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest, times(1)).submit(captor.capture(), any());
        assertEquals("hash1", captor.getValue().hash(), "文件缺失时不得提交新 hash 任务");
    }

    @Test
    void sameHashMissingFile_keepsOldIntentValid() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();
        Files.delete(syncmaticsFolder.resolve("hash1.litematic"));

        assertTrue(watcher.scanOnce());
        verify(ingest, never()).invalidateStale(anyString(), anyString());
        verify(ingest, times(1)).submit(any(), any());
    }

    @Test
    void missingLitematic_doesNotSubmit() throws Exception {
        writePlacements("s1", "missing", "无文件");

        assertTrue(watcher.scanOnce());
        verify(ingest, never()).submit(any(), any());
    }

    @Test
    void invalidJson_doesNotChangeKnownOrSubmit() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();

        Files.writeString(syncmaticaFolder.resolve("placements.json"), "not valid json");
        assertFalse(watcher.scanOnce());
        verify(ingest, never()).remove(anyString());
    }

    // ========== 删除 ==========

    @Test
    void removedPlacement_requestsRemovalAndForgetsName() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();

        Files.writeString(syncmaticaFolder.resolve("placements.json"), "{\"placements\": []}");
        assertTrue(watcher.scanOnce());

        verify(ingest).remove("s1");
        verify(owner).forgetPlacementName("s1");
        assertNull(names.get("s1"));
    }

    @Test
    void failedRemoval_isRetriedOnNextScan() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();

        when(ingest.remove("s1"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("db busy")))
                .thenReturn(CompletableFuture.completedFuture(true));
        Files.writeString(syncmaticaFolder.resolve("placements.json"), "{\"placements\": []}");

        // 第一次删除失败：保留重试意图，不认为已删除。
        assertTrue(watcher.scanOnce());
        verify(ingest, times(1)).remove("s1");

        // 下一次扫描重试成功后才从待删集合移除。
        assertTrue(watcher.scanOnce());
        verify(ingest, times(2)).remove("s1");
    }

    @Test
    void nonCommittedRemoval_keepsRetryIntent() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();

        when(ingest.remove("s1"))
                .thenReturn(CompletableFuture.completedFuture(false))
                .thenReturn(CompletableFuture.completedFuture(true));
        Files.writeString(syncmaticaFolder.resolve("placements.json"), "{\"placements\": []}");

        assertTrue(watcher.scanOnce());
        verify(ingest, times(1)).remove("s1");

        // false 表示删除未获确认，必须保留下次扫描重试。
        assertTrue(watcher.scanOnce());
        verify(ingest, times(2)).remove("s1");
    }

    @Test
    void missingPlacementsFile_doesNotRemoveKnownProjects() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();
        Files.delete(syncmaticaFolder.resolve("placements.json"));

        assertFalse(watcher.scanOnce());
        verify(ingest, never()).remove(anyString());
    }

    // ========== WatchKey 消费 ==========

    @Test
    @SuppressWarnings("unchecked")
    void consumeEvents_drainsEventsResetsAndRetainsOverflow() {
        WatchKey key = mock(WatchKey.class);
        WatchEvent<?> overflow = mock(WatchEvent.class);
        WatchEvent<?> modify = mock(WatchEvent.class);
        doReturn(StandardWatchEventKinds.OVERFLOW).when(overflow).kind();
        doReturn(StandardWatchEventKinds.ENTRY_MODIFY).when(modify).kind();
        doReturn(Path.of("placements.json")).when(modify).context();
        when(key.pollEvents()).thenReturn(List.of(overflow, modify));
        when(key.reset()).thenReturn(true);

        SchematicFolderWatcher.EventResult result = SchematicFolderWatcher.consumeEvents(key);
        assertTrue(result.valid());
        assertTrue(result.needsScan());
        verify(key).reset();
    }

    @Test
    void consumeEvents_invalidKeyReturnsNull() {
        WatchKey key = mock(WatchKey.class);
        when(key.pollEvents()).thenReturn(List.of());
        when(key.reset()).thenReturn(false);

        assertFalse(SchematicFolderWatcher.consumeEvents(key).valid());
    }

    // ========== 生命周期 ==========

    @Test
    void stop_releasesTrackedJobsAndIsIdempotent() throws Exception {
        LegacySchematicIngest.Job job = mock(LegacySchematicIngest.Job.class);
        when(job.completion()).thenReturn(new CompletableFuture<>());
        when(ingest.submit(any(), any())).thenReturn(job);
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        watcher.scanOnce();

        watcher.stop();
        watcher.stop();

        verify(job, times(1)).release(watcher);
        verify(job, never()).cancel();
        assertTrue(watcher.isClosed());
        assertTrue(watchExecutor.shutdown);
    }

    @Test
    void start_isIdempotentAndStopDoesNotWait() throws Exception {
        writePlacements("s1", "missing", "无文件");

        watcher.start();
        watcher.start();

        assertEquals(1, watchExecutor.tasks.size(), "重复 start 不应重复提交 watch 循环");

        watcher.stop();
        assertTrue(watcher.initialScanCompleted().isDone(), "关闭必须释放初始扫描等待者");
        assertTrue(watcher.awaitTermination(1, TimeUnit.SECONDS));
        watcher.start();
        assertTrue(watcher.isClosed());
        assertEquals(1, watchExecutor.tasks.size(), "stop 后不得再次启动");
    }

    @Test
    void start_defersInitialScanToBackgroundExecutor() throws Exception {
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        watcher.start();

        verify(ingest, never()).submit(any(), any());
        verify(owner, never()).setPlacementName(anyString(), anyString());
        assertFalse(watcher.initialScanCompleted().isDone(), "初始扫描尚未在后台执行");
    }

    @Test
    void backgroundStartPerformsActualInitialScanAndStopTerminates() throws Exception {
        watcher.stop();
        ExecutorService realExecutor = Executors.newSingleThreadExecutor();
        watcher = new SchematicFolderWatcher(owner, realExecutor, ingest);
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        watcher.start();
        watcher.initialScanCompleted().get(5, TimeUnit.SECONDS);

        verify(ingest).submit(any(LegacySchematicIngest.Metadata.class), eq(watcher));
        watcher.stop();
        assertTrue(watcher.awaitTermination(5, TimeUnit.SECONDS), "关闭应在有界时间内终止后台 watch 线程");
    }

    @Test
    void closedWatcher_doesNotScanOrSubmit() throws Exception {
        watcher.stop();
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        assertFalse(watcher.scanOnce());
        verify(ingest, never()).submit(any(), any());
    }

    @Test
    void oldContext_scanRejected() throws Exception {
        current.set(false);
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        assertFalse(watcher.scanOnce());
        verify(ingest, never()).submit(any(), any());
        verify(owner, never()).setPlacementName(anyString(), anyString());
    }

    // ========== 辅助 ==========

    private void writeLitematic(String hash) throws Exception {
        Files.writeString(syncmaticsFolder.resolve(hash + ".litematic"), "dummy");
    }

    private void writePlacements(String id, String hash, String displayName) throws Exception {
        String json = """
            {"placements": [{"id": "%s", "hash": "%s", "display_name": "%s",
              "file_name": "%s.litematic", "owner": {"name": "Player1"}}]}
            """.formatted(id, hash, displayName, hash);
        Files.writeString(syncmaticaFolder.resolve("placements.json"), json);
    }

    /** 不实际运行 watch 循环的测试执行器，避免测试依赖真实文件事件。 */
    private static final class RecordingExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return new ArrayList<>(tasks);
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
