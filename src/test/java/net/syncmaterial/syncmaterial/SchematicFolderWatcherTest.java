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

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicFolderWatcher;

/**
 * 原理图目录监控流程测试。
 * 目录通过构造参数注入临时目录，解析器用 mock（解析本身另有覆盖），
 * 验证：新文件自动入库、同 hash 不重复解析、hash 变化重建记录、placement 移除后清理数据库。
 */
public class SchematicFolderWatcherTest {

    @TempDir
    Path tempDir;

    private Path syncamaticaFolder;
    private Path syncmaticsFolder;
    private SchematicDatabase db;
    private SchematicFolderWatcher watcher;

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void setUp() throws Exception {
        syncamaticaFolder = Files.createDirectories(tempDir.resolve("syncamatica"));
        syncmaticsFolder = Files.createDirectories(tempDir.resolve("syncmatics"));
        db = new SchematicDatabase();
        db.initialize(tempDir.resolve("watcher-test.db").toString());
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.stop();
        watcher = null;
        if (db != null) db.close();
        SchematicFolderWatcher.placementNames.remove("s1");
    }

    // ========== 造数据辅助 ==========

    private void writeLitematic(String hash) throws Exception {
        // 内容无所谓：解析器是 mock，只按文件名（hash 前缀 + .litematic）匹配
        Files.writeString(syncmaticsFolder.resolve(hash + ".litematic"), "dummy");
    }

    private void writePlacements(String id, String hash, String displayName) throws Exception {
        String json = """
            {"placements": [{"id": "%s", "hash": "%s", "display_name": "%s",
              "file_name": "%s.litematic", "owner": {"name": "Player1"}}]}
            """.formatted(id, hash, displayName, hash);
        Files.writeString(syncamaticaFolder.resolve("placements.json"), json);
    }

    private SchematicFolderWatcher startWatcher(LitematicaParser parser) {
        SchematicFolderWatcher w = new SchematicFolderWatcher(
            syncamaticaFolder, syncmaticsFolder, db, new DatabaseQueryService(db), parser);
        w.start();
        return w;
    }

    private LitematicaParser parserReturning(List<MaterialEntry> materials) {
        LitematicaParser parser = mock(LitematicaParser.class);
        when(parser.parseAsync(anyString()))
            .thenReturn(CompletableFuture.completedFuture(materials));
        return parser;
    }

    /** 异步解析在后台线程执行，轮询等待数据库出现结果 */
    private boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private int materialCount(String schematicId, String itemId) throws Exception {
        try (var rs = db.executeQuery(
                "SELECT COALESCE(SUM(count), 0) FROM material_entries WHERE schematic_id = ? AND item_id = ?",
                schematicId, itemId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ========== 新文件自动入库 ==========

    @Test
    void newPlacement_parsedAggregatedAndStored() throws Exception {
        // 同一种物品的两条记录应聚合成一行
        watcher = startWatcher(parserReturning(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 10),
            new MaterialEntry(0, new ItemStack(Items.STONE), 20))));
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");

        assertTrue(await(() -> {
            try {
                return new DatabaseQueryService(db).schematicExists("s1")
                    && materialCount("s1", "minecraft:stone") == 30;
            } catch (Exception e) { return false; }
        }), "原理图应在监控启动后自动入库");

        assertEquals(30, materialCount("s1", "minecraft:stone"), "同物品两笔应聚合为 30");
        try (var rs = db.executeQuery("SELECT COUNT(*) FROM material_entries WHERE item_id = 'minecraft:stone'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "同物品应只有一行记录");
        }
        try (var rs = db.executeQuery("SELECT uploaded_by, file_hash FROM schematics WHERE id = 's1'")) {
            rs.next();
            assertEquals("Player1", rs.getString("uploaded_by"), "应记录上传者");
            assertEquals("hash1", rs.getString("file_hash"), "应记录文件 hash");
        }
        assertEquals("主城堡", SchematicFolderWatcher.placementNames.get("s1"));
    }

    // ========== 重启后同 hash 不重复解析 ==========

    @Test
    void unchangedHashOnSecondRun_skipsReparse() throws Exception {
        LitematicaParser parser = parserReturning(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 10)));
        watcher = startWatcher(parser);
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        assertTrue(await(() -> {
            try { return materialCount("s1", "minecraft:stone") == 10; }
            catch (Exception e) { return false; }
        }), "第一次应完成解析入库");
        watcher.stop();
        watcher = null;

        // 第二次启动（如服务器重启）：文件没变，不应再次解析
        watcher = startWatcher(parser);
        Thread.sleep(300);
        verify(parser, times(1)).parseAsync(anyString());
        assertEquals(10, materialCount("s1", "minecraft:stone"), "记录不应被重复写入");
    }

    // ========== 文件更新（hash 变化）重建记录 ==========

    @Test
    void changedHash_replacesRecords() throws Exception {
        watcher = startWatcher(parserReturning(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        assertTrue(await(() -> {
            try { return materialCount("s1", "minecraft:stone") == 10; }
            catch (Exception e) { return false; }
        }));
        watcher.stop();
        watcher = null;

        // 文件更新：hash 变化 → 旧记录删除后重新解析
        writeLitematic("hash2");
        writePlacements("s1", "hash2", "主城堡");
        watcher = startWatcher(parserReturning(List.of(
            new MaterialEntry(0, new ItemStack(Items.DIAMOND), 5))));
        assertTrue(await(() -> {
            try { return materialCount("s1", "minecraft:diamond") == 5; }
            catch (Exception e) { return false; }
        }), "新 hash 应触发重新入库");

        assertEquals(0, materialCount("s1", "minecraft:stone"), "旧材料的记录应被清除");
        try (var rs = db.executeQuery("SELECT file_hash FROM schematics WHERE id = 's1'")) {
            rs.next();
            assertEquals("hash2", rs.getString("file_hash"), "应更新为新 hash");
        }
    }

    // ========== placement 移除后清理数据库 ==========
    // 注意：移除检测依赖同一 watcher 实例的记忆（processedHashes 是实例级），
    // 服务器重启后新实例不会清理残留记录——这是现有行为限制，见提交说明。

    @Test
    void removedPlacement_cleansDatabase() throws Exception {
        watcher = startWatcher(parserReturning(List.of(
            new MaterialEntry(0, new ItemStack(Items.STONE), 10))));
        writeLitematic("hash1");
        writePlacements("s1", "hash1", "主城堡");
        assertTrue(await(() -> {
            try { return materialCount("s1", "minecraft:stone") == 10; }
            catch (Exception e) { return false; }
        }));

        // placements.json 里该原理图被移除（如 syncmatica 删除共享），
        // 同一实例重新扫描（生产中由文件监听触发，start() 会走相同的处理入口）
        Files.writeString(syncamaticaFolder.resolve("placements.json"), "{\"placements\": []}");
        watcher.start();

        assertTrue(await(() -> {
            try { return !new DatabaseQueryService(db).schematicExists("s1"); }
            catch (Exception e) { return false; }
        }), "被移除的原理图应从数据库清理");

        try (var rs = db.executeQuery("SELECT COUNT(*) FROM material_entries")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "材料记录应全部清除");
        }
        assertNull(SchematicFolderWatcher.placementNames.get("s1"), "名称缓存应清理");
    }
}
