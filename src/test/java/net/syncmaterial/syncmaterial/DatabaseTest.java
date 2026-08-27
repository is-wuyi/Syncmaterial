package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * SchematicDatabase 真实类测试。
 * 直接实例化 SchematicDatabase 并注入临时数据库文件路径，
 * 因此 schema/迁移/业务方法测的都是生产代码本身，schema 漂移会直接红灯。
 * 不依赖 Minecraft 运行时。
 */
public class DatabaseTest
{
    @TempDir
    Path tempDir;

    private SchematicDatabase db;

    @BeforeEach
    void setUp()
    {
        db = new SchematicDatabase();
        db.initialize(tempDir.resolve("test.db").toString());
    }

    @AfterEach
    void tearDown()
    {
        if (db != null) db.close();
    }

    // ========== 造数据辅助 ==========

    private void insertSchematic(String id, String uploadedBy) throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
            id, "test", "/test.litematic", uploadedBy);
    }

    private int insertMaterial(String schematicId, String itemId, int count) throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
            schematicId, itemId, count);
        return lastInsertId();
    }

    private int lastInsertId() throws SQLException
    {
        try (var rs = db.executeQuery("SELECT last_insert_rowid()"))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countRows(String table) throws SQLException
    {
        try (var rs = db.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ========== Schema 初始化（真实 createTables） ==========

    @Test
    void initialize_createsAllTables() throws SQLException
    {
        String[] expected = {"schematics", "material_entries", "claims", "deputy_owners",
            "staging_areas", "staging_area_inventory", "player_inventories", "warehouses",
            "schematic_warehouses", "warehouse_inventory", "container_inventory"};
        var actual = queryNames("SELECT name FROM sqlite_master WHERE type='table'");
        for (String name : expected)
        {
            assertTrue(actual.contains(name), "表应存在: " + name);
        }
    }

    @Test
    void initialize_createsIndexes() throws SQLException
    {
        String[] expected = {"idx_claim_unique", "idx_claims_schematic", "idx_claims_player",
            "idx_staging_inventory_area", "idx_staging_inventory_item",
            "idx_deputy_owners_schematic", "idx_deputy_owners_player",
            "idx_schematic_warehouses_schematic", "idx_warehouse_inventory_warehouse",
            "idx_container_inventory_area", "idx_container_inventory_pos"};
        var actual = queryNames("SELECT name FROM sqlite_master WHERE type='index'");
        for (String name : expected)
        {
            assertTrue(actual.contains(name), "索引应存在: " + name);
        }
    }

    private java.util.Set<String> queryNames(String sql) throws SQLException
    {
        java.util.Set<String> names = new java.util.HashSet<>();
        try (var rs = db.executeQuery(sql))
        {
            while (rs.next())
            {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Test
    void initialize_appliesColumnMigrations() throws SQLException
    {
        // 全新库走迁移分支：staging_areas.name 和 schematics.file_hash 应被补上
        assertTrue(columnExists("staging_areas", "name"), "staging_areas.name 迁移列应存在");
        assertTrue(columnExists("schematics", "file_hash"), "schematics.file_hash 迁移列应存在");
    }

    @Test
    void initialize_secondRun_idempotent()
    {
        // 重复初始化（CREATE IF NOT EXISTS + 迁移检查）不应抛异常
        db.initialize(tempDir.resolve("test.db").toString());
    }

    private boolean columnExists(String table, String column) throws SQLException
    {
        try (var rs = db.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while (rs.next())
            {
                if (column.equals(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    @Test
    void foreignKeys_enforcedByInitialize() throws SQLException
    {
        // initialize() 开启了 PRAGMA foreign_keys=ON：
        // 向不存在的原理图插入材料应被外键拒绝
        assertThrows(SQLException.class, () ->
            db.executeUpdate(
                "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                "nonexistent", "minecraft:stone", 64));
    }

    // ========== 主/副负责人（Phase 4 业务方法） ==========

    @Test
    void isMainOwner_onlyForUploader() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        assertTrue(db.isMainOwner("s1", "Owner1"));
        assertFalse(db.isMainOwner("s1", "OtherPlayer"));
        assertFalse(db.isMainOwner("nonexistent", "Owner1"));
    }

    @Test
    void isOwner_includesDeputies() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        db.addDeputyOwner("s1", "Deputy1");

        assertTrue(db.isOwner("s1", "Owner1"), "主负责人应是 owner");
        assertTrue(db.isOwner("s1", "Deputy1"), "副负责人应是 owner");
        assertFalse(db.isOwner("s1", "RandomPlayer"), "普通玩家不应是 owner");
    }

    @Test
    void addDeputyOwner_duplicateIgnored() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        // INSERT OR IGNORE：重复添加不抛异常，也不产生重复行
        assertDoesNotThrow(() -> db.addDeputyOwner("s1", "Deputy1"));
        db.addDeputyOwner("s1", "Deputy1");
        assertEquals(1, db.getDeputyOwners("s1").size());
    }

    @Test
    void getDeputyOwners_returnsAll() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        db.addDeputyOwner("s1", "Deputy1");
        db.addDeputyOwner("s1", "Deputy2");

        List<String> deputies = db.getDeputyOwners("s1");
        assertEquals(2, deputies.size());
        assertTrue(deputies.contains("Deputy1"));
        assertTrue(deputies.contains("Deputy2"));
    }

    @Test
    void removeDeputyOwner_revokesOwner() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        db.addDeputyOwner("s1", "Deputy1");

        db.removeDeputyOwner("s1", "Deputy1");

        assertFalse(db.isDeputyOwner("s1", "Deputy1"));
        assertFalse(db.isOwner("s1", "Deputy1"));
        assertTrue(db.getDeputyOwners("s1").isEmpty());
    }

    @Test
    void transferOwnership_demotesOldOwner() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        db.transferOwnership("s1", "Owner2");

        assertTrue(db.isMainOwner("s1", "Owner2"), "新负责人应是主负责人");
        assertFalse(db.isOwner("s1", "Owner1"), "原负责人应被降级");
        assertEquals("Owner2", db.getUploadedBy("s1"));
    }

    @Test
    void getUploadedBy_nonexistent_returnsEmpty() throws SQLException
    {
        assertEquals("", db.getUploadedBy("nonexistent"));
    }

    // ========== 自行认领开关 ==========

    @Test
    void allowSelfClaim_defaultTrue() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        assertTrue(db.getAllowSelfClaim("s1"), "默认应允许自行认领");
    }

    @Test
    void allowSelfClaim_toggleRoundtrip() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        db.setAllowSelfClaim("s1", false);
        assertFalse(db.getAllowSelfClaim("s1"));

        db.setAllowSelfClaim("s1", true);
        assertTrue(db.getAllowSelfClaim("s1"));
    }

    // ========== 玩家背包（upsert + 聚合加载） ==========

    @Test
    void upsertInventory_updatesExistingRow() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        int matId = insertMaterial("s1", "minecraft:stone", 64);

        db.upsertPlayerInventory("s1", "Player1", matId, 10);
        db.upsertPlayerInventory("s1", "Player1", matId, 25);

        Map<String, Map<Integer, Integer>> all = db.loadPlayerInventories("s1");
        assertEquals(1, all.size());
        assertEquals(25, all.get("Player1").get(matId), "重复 upsert 应更新数量而非新增行");
    }

    @Test
    void loadPlayerInventories_groupsByPlayer() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        int stone = insertMaterial("s1", "minecraft:stone", 64);
        int diamond = insertMaterial("s1", "minecraft:diamond", 10);

        db.upsertPlayerInventory("s1", "Player1", stone, 10);
        db.upsertPlayerInventory("s1", "Player1", diamond, 2);
        db.upsertPlayerInventory("s1", "Player2", stone, 5);

        Map<String, Map<Integer, Integer>> all = db.loadPlayerInventories("s1");
        assertEquals(2, all.size());
        assertEquals(10, all.get("Player1").get(stone));
        assertEquals(2, all.get("Player1").get(diamond));
        assertEquals(5, all.get("Player2").get(stone));
    }

    // ========== 级联删除（deleteSchematicRecords） ==========

    @Test
    void deleteSchematicRecords_cascadesAllTables() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        int matId = insertMaterial("s1", "minecraft:stone", 64);
        db.executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES (?, ?, ?, 'active')",
            "s1", matId, "p1");
        db.addDeputyOwner("s1", "p2");
        db.upsertPlayerInventory("s1", "p1", matId, 10);
        db.executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, 0, 0, 0, 10, 10, 10)",
            "s1", "area1", "overworld");
        int areaId = lastInsertId();
        db.executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) VALUES (?, ?, 64)",
            areaId, "minecraft:stone");
        db.executeUpdate("INSERT INTO warehouses (name, world) VALUES ('w1', 'overworld')");
        int warehouseId = lastInsertId();
        db.executeUpdate(
            "INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES (?, ?)", "s1", warehouseId);

        db.deleteSchematicRecords("s1");

        assertEquals(0, countRows("schematics"));
        assertEquals(0, countRows("material_entries"));
        assertEquals(0, countRows("claims"));
        assertEquals(0, countRows("deputy_owners"));
        assertEquals(0, countRows("player_inventories"));
        assertEquals(0, countRows("staging_areas"));
        assertEquals(0, countRows("staging_area_inventory"));
        assertEquals(0, countRows("schematic_warehouses"), "原理图-仓库引用应随外键级联删除");
        assertEquals(1, countRows("warehouses"), "仓库是全局的，不应被连带删除");
    }

    // ========== 事务 ==========

    @Test
    void transaction_rollbackDiscardsChanges() throws SQLException
    {
        db.beginTransaction();
        insertSchematic("s1", "Owner1");
        db.rollbackTransaction();

        assertEquals(0, countRows("schematics"));
    }

    @Test
    void transaction_commitPersistsChanges() throws SQLException
    {
        db.beginTransaction();
        insertSchematic("s1", "Owner1");
        db.commitTransaction();

        assertEquals(1, countRows("schematics"));
    }

    // ========== 约束语义（裸 SQL，但跑在真实 schema 上） ==========

    @Test
    void claim_uniqueIndex_preventsDuplicateClaims() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        int matId = insertMaterial("s1", "minecraft:stone", 64);
        db.executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name) VALUES (?, ?, ?)", "s1", matId, "p1");

        // 同一玩家对同一材料不能重复认领
        assertThrows(SQLException.class, () ->
            db.executeUpdate(
                "INSERT INTO claims (schematic_id, material_id, player_name) VALUES (?, ?, ?)",
                "s1", matId, "p1"));
    }

    @Test
    void schematics_insertOrIgnore_doesNotOverwrite() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        db.executeUpdate(
            "INSERT OR IGNORE INTO schematics (id, name, file_path, file_hash) VALUES (?, ?, ?, ?)",
            "s1", "duplicate", "/other.litematic", "hash2");

        try (var rs = db.executeQuery("SELECT name, file_path FROM schematics WHERE id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("test", rs.getString("name"), "重复 ID 插入应被忽略");
            assertEquals("/test.litematic", rs.getString("file_path"));
        }
        assertEquals(1, countRows("schematics"));
    }

    // ========== 事务的可重入与跨线程隔离 ==========

    @Test
    void transaction_nestedSameThread_onlyOutermostCommits() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        // 内层 commit 不应提前结束外层事务（旧实现会在此销毁事务上下文）
        db.beginTransaction();
        insertMaterial("s1", "minecraft:stone", 10);
        db.beginTransaction();
        insertMaterial("s1", "minecraft:oak_log", 20);
        db.commitTransaction();          // 内层
        insertMaterial("s1", "minecraft:diamond", 30);
        db.rollbackTransaction();        // 外层回滚，三条都应消失

        assertEquals(0, countRows("material_entries"),
            "外层回滚应撤销全部写入，说明内层 commit 没有提前提交");
    }

    @Test
    void transaction_nestedRollback_preventsOuterCommit() throws SQLException
    {
        insertSchematic("s1", "Owner1");

        db.beginTransaction();
        insertMaterial("s1", "minecraft:stone", 10);
        db.beginTransaction();
        insertMaterial("s1", "minecraft:oak_log", 20);
        db.rollbackTransaction();        // 内层标记回滚

        // 外层 commit 必须被拒绝，否则会提交半成品数据
        assertThrows(SQLException.class, () -> db.commitTransaction(),
            "内层已回滚时外层提交应被拒绝");
        assertEquals(0, countRows("material_entries"), "数据应被整体回滚");
    }

    @Test
    void transaction_concurrentThreads_doNotSwallowEachOther() throws Exception
    {
        insertSchematic("s1", "Owner1");

        // 两个线程各自跑完整事务：单 Connection 无法并行事务，
        // 必须串行化，否则先开始者 commit 时会报 "database in auto-commit mode"
        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Exception>());
        var latch = new java.util.concurrent.CountDownLatch(2);
        Runnable task = () -> {
            try
            {
                String item = "minecraft:item_" + Thread.currentThread().getId();
                db.beginTransaction();
                for (int i = 0; i < 5; i++)
                {
                    db.executeUpdate(
                        "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?) " +
                        "ON CONFLICT(schematic_id, item_id) DO UPDATE SET count = count + 1",
                        "s1", item, 1);
                    Thread.sleep(10);
                }
                db.commitTransaction();
            }
            catch (Exception e) { errors.add(e); }
            finally { latch.countDown(); }
        };
        new Thread(task).start();
        new Thread(task).start();
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "两个事务应在超时前完成");

        assertTrue(errors.isEmpty(), "并发事务不应抛异常，实际: " + errors);
        assertEquals(2, countRows("material_entries"), "两个线程各写入一种物品");
    }

    @Test
    void transaction_commitWithoutBegin_isNoop() throws SQLException
    {
        // 兼容旧调用点：未开启事务时调用不应抛错
        assertDoesNotThrow(() -> db.commitTransaction());
        assertDoesNotThrow(() -> db.rollbackTransaction());
    }

    @Test
    void materialEntries_uniqueIndex_preventsDuplicates() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        insertMaterial("s1", "minecraft:stone", 64);

        // 同一原理图的同种物品不能有第二行
        assertThrows(SQLException.class, () ->
            insertMaterial("s1", "minecraft:stone", 32),
            "同 (schematic_id, item_id) 应被唯一索引拒绝");

        // 不同原理图、不同物品仍可插入
        insertSchematic("s2", "Owner1");
        assertDoesNotThrow(() -> insertMaterial("s2", "minecraft:stone", 10));
        assertDoesNotThrow(() -> insertMaterial("s1", "minecraft:oak_log", 10));
    }

    @Test
    void materialEntries_upsert_overwritesCountNotAccumulates() throws SQLException
    {
        insertSchematic("s1", "Owner1");
        insertMaterial("s1", "minecraft:stone", 100);

        // 快照语义：重复写入同种物品应覆盖而非累加
        db.executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?) " +
            "ON CONFLICT(schematic_id, item_id) DO UPDATE SET count = excluded.count",
            "s1", "minecraft:stone", 500);

        try (var rs = db.executeQuery(
            "SELECT count FROM material_entries WHERE schematic_id = 's1' AND item_id = 'minecraft:stone'"))
        {
            assertTrue(rs.next());
            assertEquals(500, rs.getInt("count"), "upsert 应覆盖为最新解析值，不能累加成 600");
        }
        assertEquals(1, countRows("material_entries"), "不应产生第二行");
    }

    // ========== 存量重复数据的迁移（走真实 initialize） ==========

    /**
     * 构造"旧库"：手写不含唯一索引的 schema 并塞入重复行，
     * 之后交给真实 initialize() 触发迁移，因此迁移入口也是生产代码本身。
     */
    private void buildLegacyDatabaseWithDuplicates(Path dbPath) throws Exception
    {
        Class.forName("org.sqlite.JDBC");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement())
        {
            st.executeUpdate("PRAGMA foreign_keys=ON");
            st.executeUpdate("""
                CREATE TABLE schematics (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, file_path TEXT NOT NULL,
                    uploaded_by TEXT, allow_self_claim INTEGER DEFAULT 1,
                    created_at INTEGER DEFAULT (strftime('%s','now')*1000))
                """);
            // 关键：没有 UNIQUE(schematic_id, item_id)，模拟修复前的 schema
            st.executeUpdate("""
                CREATE TABLE material_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, schematic_id TEXT NOT NULL,
                    item_id TEXT NOT NULL, count INTEGER NOT NULL,
                    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE)
                """);
            st.executeUpdate("""
                CREATE TABLE claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, schematic_id TEXT NOT NULL,
                    material_id INTEGER NOT NULL, player_name TEXT NOT NULL,
                    status TEXT DEFAULT 'active',
                    created_at INTEGER DEFAULT (strftime('%s','now')*1000),
                    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                    FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE)
                """);
            st.executeUpdate("""
                CREATE TABLE player_inventories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, schematic_id TEXT NOT NULL,
                    player_name TEXT NOT NULL, material_id INTEGER NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER DEFAULT (strftime('%s','now')*1000),
                    FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                    UNIQUE(schematic_id, player_name, material_id))
                """);

            st.executeUpdate("INSERT INTO schematics (id,name,file_path,uploaded_by) VALUES ('s1','城堡','/p','Alice')");
            // stone 因重复解析产生 3 行（各 100，实际只需 100）；oak_log 正常 1 行
            st.executeUpdate("INSERT INTO material_entries (id,schematic_id,item_id,count) VALUES (1,'s1','minecraft:stone',100)");
            st.executeUpdate("INSERT INTO material_entries (id,schematic_id,item_id,count) VALUES (2,'s1','minecraft:stone',100)");
            st.executeUpdate("INSERT INTO material_entries (id,schematic_id,item_id,count) VALUES (5,'s1','minecraft:stone',100)");
            st.executeUpdate("INSERT INTO material_entries (id,schematic_id,item_id,count) VALUES (3,'s1','minecraft:oak_log',50)");
            // Bob 在三行上都有背包记录（10+20+30=60）；Carol/Dave 只持有非保留行
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Bob',1,10)");
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Bob',2,20)");
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Bob',5,30)");
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Carol',2,15)");
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Dave',5,25)");
            st.executeUpdate("INSERT INTO player_inventories (schematic_id,player_name,material_id,count) VALUES ('s1','Eve',3,5)");
            st.executeUpdate("INSERT INTO claims (schematic_id,material_id,player_name) VALUES ('s1',1,'Bob')");
            st.executeUpdate("INSERT INTO claims (schematic_id,material_id,player_name) VALUES ('s1',2,'Carol')");
            st.executeUpdate("INSERT INTO claims (schematic_id,material_id,player_name) VALUES ('s1',5,'Dave')");
            st.executeUpdate("INSERT INTO claims (schematic_id,material_id,player_name) VALUES ('s1',3,'Eve')");
        }
    }

    private int scalar(SchematicDatabase database, String sql, Object... params) throws SQLException
    {
        try (var rs = database.executeQuery(sql, params))
        {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @Test
    void migration_deduplicatesMaterialEntries_preservingClaimsAndInventoryTotals() throws Exception
    {
        Path legacyDb = tempDir.resolve("legacy.db");
        buildLegacyDatabaseWithDuplicates(legacyDb);

        SchematicDatabase migrated = new SchematicDatabase();
        try
        {
            // 真实迁移入口：存量重复行不应让 initialize 失败
            assertDoesNotThrow(() -> migrated.initialize(legacyDb.toString()),
                "存量重复行下 initialize 应完成迁移而非抛异常");

            // 断言 1：材料数量等于单次解析值，不能被 SUM 成 300
            assertEquals(100, scalar(migrated,
                "SELECT count FROM material_entries WHERE schematic_id='s1' AND item_id='minecraft:stone'"),
                "重复行合并必须保留单行原值，求和会把翻倍错误固化");
            assertEquals(1, scalar(migrated,
                "SELECT COUNT(*) FROM material_entries WHERE schematic_id='s1' AND item_id='minecraft:stone'"),
                "stone 应只剩一行");
            assertEquals(50, scalar(migrated,
                "SELECT count FROM material_entries WHERE item_id='minecraft:oak_log'"),
                "未重复的材料不应被改动");

            // 断言 2：玩家持有量守恒
            assertEquals(60, scalar(migrated,
                "SELECT count FROM player_inventories WHERE player_name='Bob'"),
                "Bob 分散在三行的 10+20+30 必须合并为 60");
            assertEquals(15, scalar(migrated,
                "SELECT count FROM player_inventories WHERE player_name='Carol'"),
                "Carol 只持有非保留行，应被重定向而非删除");
            assertEquals(25, scalar(migrated,
                "SELECT count FROM player_inventories WHERE player_name='Dave'"),
                "Dave 只持有非保留行，应被重定向而非删除");
            assertEquals(5, scalar(migrated,
                "SELECT count FROM player_inventories WHERE player_name='Eve'"),
                "Eve 持有未重复的材料，不应受影响");

            // 断言 3：认领记录不因 CASCADE 丢失
            assertEquals(4, scalar(migrated, "SELECT COUNT(*) FROM claims"),
                "四位玩家的认领都应保留（删除重复行会 CASCADE 删掉它们）");

            // 断言 4：无孤儿引用
            try (var rs = migrated.executeQuery("PRAGMA foreign_key_check"))
            {
                assertFalse(rs.next(), "迁移后不应存在外键违规");
            }
            assertEquals(0, scalar(migrated,
                "SELECT COUNT(*) FROM player_inventories WHERE material_id NOT IN (SELECT id FROM material_entries)"),
                "player_inventories 无外键，必须手工确认没有孤儿记录");

            // 迁移后唯一索引应已生效
            assertThrows(SQLException.class, () -> migrated.executeUpdate(
                "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1','minecraft:stone',1)"),
                "迁移完成后唯一索引应阻止新的重复行");
        }
        finally
        {
            migrated.close();
        }
    }

    @Test
    void migration_isIdempotent_onCleanDatabase() throws Exception
    {
        // 无重复行的库反复 initialize 不应报错也不应改动数据
        insertSchematic("s1", "Owner1");
        insertMaterial("s1", "minecraft:stone", 64);
        db.close();

        SchematicDatabase again = new SchematicDatabase();
        try
        {
            assertDoesNotThrow(() -> again.initialize(tempDir.resolve("test.db").toString()));
            assertEquals(64, scalar(again,
                "SELECT count FROM material_entries WHERE item_id='minecraft:stone'"));
            assertEquals(1, scalar(again, "SELECT COUNT(*) FROM material_entries"));
        }
        finally
        {
            again.close();
            db = null;
        }
    }
}
