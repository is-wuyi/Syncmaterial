package net.syncmaterial.syncmaterial.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import net.syncmaterial.syncmaterial.server.legacy.LegacySchematicIngest;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * 上传监听器职责测试：同步不可变元数据捕获、owner/关闭守卫、future 跟踪。
 * 入库语义（沿用上传路径的逐条 upsert / 已有项目跳过）由
 * LegacySchematicIngestTest 覆盖。
 */
public class SchematicUploadListenerTest {

    /** 模拟 Syncmatica 的 ServerPlacement 反射接口。 */
    public static class FakePlacement {
        private final String id;
        private final Path file;
        private final String name;
        private final Object owner;

        FakePlacement(String id, Path file, String name, Object owner) {
            this.id = id;
            this.file = file;
            this.name = name;
            this.owner = owner;
        }

        public String getId() { return id; }
        public Path getFile() { return file; }
        public String getName() { return name; }
        public Object getOwner() { return owner; }
        public UUID getHash() {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 没有 getHash 的旧实现。 */
    public static class HashlessPlacement {
        private final String id;
        private final Path file;
        private final String name;
        private final Object owner;

        HashlessPlacement(String id, Path file, String name, Object owner) {
            this.id = id;
            this.file = file;
            this.name = name;
            this.owner = owner;
        }

        public String getId() { return id; }
        public Path getFile() { return file; }
        public String getName() { return name; }
        public Object getOwner() { return owner; }
    }

    public static class FakePlayer {
        private final String name;
        FakePlayer(String name) { this.name = name; }
        public String getName() { return name; }
    }

    @TempDir
    Path tempDir;

    private LegacyServerContext owner;
    private LegacySchematicIngest ingest;
    private AtomicBoolean current;
    private SchematicUploadListener listener;

    @BeforeEach
    void setUp() {
        current = new AtomicBoolean(true);
        owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenAnswer(invocation -> current.get());
        ingest = mock(LegacySchematicIngest.class);
        listener = new SchematicUploadListener(owner, ingest);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) listener.close();
    }

    @Test
    void accept_capturesMetadataAndSubmitsUploadSource() throws Exception {
        Path file = writeLitematic();

        listener.accept(new FakePlacement("up-1", file, "上传的建筑", new FakePlayer("Architect")));

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest).submit(captor.capture(), any());
        LegacySchematicIngest.Metadata metadata = captor.getValue();
        assertEquals("up-1", metadata.id());
        assertEquals("上传的建筑", metadata.name());
        assertEquals(file.toAbsolutePath().normalize(), metadata.file());
        assertEquals(UUID.nameUUIDFromBytes("up-1".getBytes(StandardCharsets.UTF_8)).toString(),
                metadata.hash());
        assertEquals("Architect", metadata.uploader());
        assertEquals(LegacySchematicIngest.Source.UPLOAD, metadata.source());
        assertFalse(metadata.announceUnchanged());
        verify(owner).setPlacementName("up-1", "上传的建筑");
    }

    @Test
    void onSchematicUploaded_delegatesToSamePath() throws Exception {
        Path file = writeLitematic();

        listener.onSchematicUploaded(new FakePlacement("up-1", file, "名字", new FakePlayer("A")));

        verify(ingest, times(1)).submit(any(), any());
    }

    @Test
    void nullOwner_recordedAsUnknown() throws Exception {
        Path file = writeLitematic();

        listener.accept(new FakePlacement("up-1", file, "无主上传", null));

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest).submit(captor.capture(), any());
        assertEquals("unknown", captor.getValue().uploader());
    }

    @Test
    void missingFile_doesNotSubmit() {
        Path missing = tempDir.resolve("不存在.litematic");

        listener.accept(new FakePlacement("up-1", missing, "幽灵", null));

        verify(ingest, never()).submit(any(), any());
    }

    @Test
    void missingGetHash_usesEmptyHash() throws Exception {
        Path file = writeLitematic();

        listener.accept(new HashlessPlacement("up-1", file, "老实现", new FakePlayer("A")));

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest).submit(captor.capture(), any());
        assertEquals("", captor.getValue().hash());
    }

    @Test
    void missingGetName_fallsBackToFileName() throws Exception {
        Path file = writeLitematic();

        listener.accept(new FakePlacement("up-1", file, null, new FakePlayer("A")));

        ArgumentCaptor<LegacySchematicIngest.Metadata> captor =
                ArgumentCaptor.forClass(LegacySchematicIngest.Metadata.class);
        verify(ingest).submit(captor.capture(), any());
        assertEquals(file.getFileName().toString(), captor.getValue().name());
    }

    @Test
    void oldContext_doesNotSubmit() throws Exception {
        current.set(false);
        Path file = writeLitematic();

        listener.accept(new FakePlacement("up-1", file, "旧上下文", new FakePlayer("A")));

        verify(ingest, never()).submit(any(), any());
        verify(owner, never()).setPlacementName(anyString(), anyString());
    }

    @Test
    void close_releasesTrackedJobsAndRejectsFurtherEvents() throws Exception {
        LegacySchematicIngest.Job job = mock(LegacySchematicIngest.Job.class);
        when(job.completion()).thenReturn(new CompletableFuture<>());
        when(ingest.submit(any(), any())).thenReturn(job);
        Path file = writeLitematic();
        listener.accept(new FakePlacement("up-1", file, "上传", new FakePlayer("A")));

        listener.close();
        listener.close();
        listener.accept(new FakePlacement("up-2", file, "上传2", new FakePlayer("A")));

        verify(job, times(1)).release(listener);
        verify(job, never()).cancel();
        verify(ingest, times(1)).submit(any(), any());
    }

    private Path writeLitematic() throws Exception {
        Path file = tempDir.resolve("up-1.litematic");
        Files.writeString(file, "dummy");
        return file;
    }
}
