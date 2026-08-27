package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicUploadListener;

/**
 * Syncmatica 上传事件入库测试：用带反射接口的假 placement 对象驱动全链路。
 */
public class SchematicUploadListenerTest {

    /** 模拟 Syncmatica 的 ServerPlacement（监听器通过反射调 getId/getFile/getName/getOwner）。
     *  必须 public：监听器在 server 子包，包私有类会因跨包反射访问检查失败。 */
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
    }

    public static class FakePlayer {
        private final String name;
        FakePlayer(String name) { this.name = name; }
        public String getName() { return name; }
    }

    @TempDir
    Path tempDir;

    private SchematicDatabase db;
    private LitematicaParser parser;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        db = new SchematicDatabase();
        db.initialize(tempDir.resolve("upload-test.db").toString());
        parser = mock(LitematicaParser.class);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    private SchematicUploadListener listener() {
        return new SchematicUploadListener(db, new DatabaseQueryService(db), parser);
    }

    private void awaitSchematic(boolean expectExists) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (new DatabaseQueryService(db).schematicExists("up-1") == expectExists) return;
            Thread.sleep(50);
        }
        String state;
        try (var rs = db.executeQuery("SELECT COUNT(*) FROM schematics")) {
            rs.next();
            state = "schematics rows=" + rs.getInt(1);
        } catch (Exception e) {
            state = "query failed: " + e;
        }
        fail("上传处理未在预期时间内完成（expectExists=" + expectExists + "，" + state + "）");
    }

    private Path writeLitematic(String content) throws Exception {
        Path file = tempDir.resolve("up-1.litematic");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void upload_storesSchematicAndMaterials() throws Exception {
        Path file = writeLitematic("dummy");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 64),
            new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5))));

        listener().onSchematicUploaded(new FakePlacement("up-1", file, "上传的建筑", new FakePlayer("Architect")));
        awaitSchematic(true);

        try (var rs = db.executeQuery("SELECT name, uploaded_by FROM schematics WHERE id = 'up-1'")) {
            assertTrue(rs.next());
            assertEquals("上传的建筑", rs.getString("name"));
            assertEquals("Architect", rs.getString("uploaded_by"), "应记录上传者");
        }
        try (var rs = db.executeQuery(
                "SELECT item_id, count FROM material_entries WHERE schematic_id = 'up-1' ORDER BY item_id")) {
            assertTrue(rs.next());
            assertEquals("minecraft:diamond", rs.getString("item_id"));
            assertEquals(5, rs.getInt("count"));
            assertTrue(rs.next());
            assertEquals("minecraft:stone", rs.getString("item_id"));
            assertEquals(64, rs.getInt("count"));
        }
    }

    @Test
    void duplicateUpload_skipped() throws Exception {
        Path file = writeLitematic("dummy");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 64))));

        SchematicUploadListener l = listener();
        l.onSchematicUploaded(new FakePlacement("up-1", file, "第一次", new FakePlayer("A")));
        awaitSchematic(true);

        // 第二次上传同一 ID：数据库已有，应跳过解析
        l.onSchematicUploaded(new FakePlacement("up-1", file, "第二次", new FakePlayer("B")));
        Thread.sleep(300);
        verify(parser, times(1)).parseAsync(anyString());
        try (var rs = db.executeQuery("SELECT name FROM schematics WHERE id = 'up-1'")) {
            rs.next();
            assertEquals("第一次", rs.getString("name"), "重复上传不应覆盖已有记录");
        }
    }

    @Test
    void missingFile_notParsed() throws Exception {
        Path missing = tempDir.resolve("不存在.litematic");
        listener().onSchematicUploaded(new FakePlacement("up-1", missing, "幽灵", null));

        Thread.sleep(200);
        verify(parser, never()).parseAsync(anyString());
        assertFalse(new DatabaseQueryService(db).schematicExists("up-1"));
    }

    @Test
    void nullOwner_recordedAsUnknown() throws Exception {
        Path file = writeLitematic("dummy");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of()));

        listener().onSchematicUploaded(new FakePlacement("up-1", file, "无主上传", null));
        awaitSchematic(true);

        try (var rs = db.executeQuery("SELECT uploaded_by FROM schematics WHERE id = 'up-1'")) {
            assertTrue(rs.next());
            assertEquals("unknown", rs.getString("uploaded_by"));
        }
    }

    @Test
    void accept_delegatesToUpload() throws Exception {
        Path file = writeLitematic("dummy");
        when(parser.parseAsync(anyString())).thenReturn(CompletableFuture.completedFuture(List.of()));

        listener().accept(new FakePlacement("up-1", file, "Consumer 接口", new FakePlayer("A")));
        awaitSchematic(true);
        verify(parser, times(1)).parseAsync(anyString());
    }
}
