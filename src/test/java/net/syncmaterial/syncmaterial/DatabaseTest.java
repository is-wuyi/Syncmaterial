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
    void upsertPlayerInventory_updatesExistingRow() throws SQLException
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
}
