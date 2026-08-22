package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SchematicDatabase SQL 逻辑测试。
 * 使用内存 SQLite，不依赖 Minecraft 运行时。
 */
public class DatabaseTest
{
    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException
    {
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.createStatement().execute("PRAGMA foreign_keys = ON");
        createTables();
    }

    @AfterEach
    void tearDown() throws SQLException
    {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    private void createTables() throws SQLException
    {
        conn.createStatement().execute("""
            CREATE TABLE schematics (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                file_path TEXT,
                uploaded_by TEXT,
                file_hash TEXT,
                material_count INTEGER DEFAULT 0
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE material_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_name TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE deputy_owners (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name)
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE player_inventories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name, material_id)
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE staging_areas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER, y1 INTEGER, z1 INTEGER,
                x2 INTEGER, y2 INTEGER, z2 INTEGER,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE staging_area_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staging_area_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id) ON DELETE CASCADE
            )
        """);
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_area ON staging_area_inventory(staging_area_id)");
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_item ON staging_area_inventory(item_id)");
    }

    // ========== 基础 CRUD ==========

    @Test
    void insertAndQuerySchematic() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, material_count) VALUES ('s1', 'test', 10)");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT name, material_count FROM schematics WHERE id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("test", rs.getString("name"));
            assertEquals(10, rs.getInt("material_count"));
        }
    }

    @Test
    void insertOrIgnore_duplicateId_doesNotOverwrite() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_hash) VALUES ('s1', 'original', 'hash1')");

        // INSERT OR IGNORE 应该静默跳过重复主键
        conn.createStatement().executeUpdate(
            "INSERT OR IGNORE INTO schematics (id, name, file_hash) VALUES ('s1', 'duplicate', 'hash2')");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT name, file_hash FROM schematics WHERE id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("original", rs.getString("name"));
            assertEquals("hash1", rs.getString("file_hash"));
        }
        assertEquals(1, countRows("schematics"));
    }

    @Test
    void fileHash_updateByDeleteAndReinsert() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_hash) VALUES ('s1', 'test', 'old_hash')");

        // 模拟原理图更新流程：删除旧记录，重新插入
        conn.createStatement().executeUpdate("DELETE FROM schematics WHERE id = 's1'");
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_hash) VALUES ('s1', 'test', 'new_hash')");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT file_hash FROM schematics WHERE id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("new_hash", rs.getString("file_hash"));
        }
    }

    // ========== Claims（材料认领） ==========

    @Test
    void claim_insertAndQuery() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, count) " +
            "VALUES ('s1', " + matId + ", 'player1', 32)");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT player_name, count FROM claims WHERE schematic_id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("player1", rs.getString("player_name"));
            assertEquals(32, rs.getInt("count"));
        }
    }

    @Test
    void claim_multiplePlayersSameMaterial() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 128)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, count) VALUES ('s1', " + matId + ", 'player1', 64)");
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, count) VALUES ('s1', " + matId + ", 'player2', 32)");

        try (var ps = conn.prepareStatement(
                "SELECT SUM(count) as total FROM claims WHERE material_id = ?"))
        {
            ps.setInt(1, matId);
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals(96, rs.getInt("total"));
            }
        }
    }

    @Test
    void claim_cascadeDeleteOnSchematic() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, count) VALUES ('s1', " + matId + ", 'player1', 32)");

        conn.createStatement().executeUpdate("DELETE FROM schematics WHERE id = 's1'");

        assertEquals(0, countRows("claims"));
        assertEquals(0, countRows("material_entries"));
    }

    // ========== Deputy Owners（副负责人） ==========

    @Test
    void deputyOwner_insertAndQuery() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'deputy1')");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT player_name FROM deputy_owners WHERE schematic_id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("deputy1", rs.getString("player_name"));
        }
    }

    @Test
    void deputyOwner_uniqueConstraint_preventsDuplicates() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'deputy1')");

        assertThrows(SQLException.class, () ->
            conn.createStatement().executeUpdate(
                "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'deputy1')"));
    }

    @Test
    void deputyOwner_multipleDeputiesPerSchematic() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'deputy1')");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'deputy2')");

        assertEquals(2, countRows("deputy_owners"));
    }

    // ========== Player Inventories（玩家背包） ==========

    @Test
    void playerInventory_upsertPattern() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        // 首次插入
        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) " +
            "VALUES ('s1', 'player1', " + matId + ", 10)");

        // 更新（SQLite INSERT OR REPLACE 模式）
        conn.createStatement().executeUpdate(
            "INSERT OR REPLACE INTO player_inventories (schematic_id, player_name, material_id, count) " +
            "VALUES ('s1', 'player1', " + matId + ", 25)");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT count FROM player_inventories WHERE schematic_id = 's1' AND player_name = 'player1'"))
        {
            assertTrue(rs.next());
            assertEquals(25, rs.getInt("count"));
        }
        assertEquals(1, countRows("player_inventories"));
    }

    @Test
    void playerInventory_uniqueConstraint() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) " +
            "VALUES ('s1', 'player1', " + matId + ", 10)");

        // 相同 (schematic, player, material) 不能重复插入
        assertThrows(SQLException.class, () ->
            conn.createStatement().executeUpdate(
                "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) " +
                "VALUES ('s1', 'player1', " + matId + ", 20)"));
    }

    // ========== 备货区 ==========

    @Test
    void stagingInventoryQuery_correctCount() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) " +
            "VALUES ('s1', 'area1', 'overworld', 0, 0, 0, 10, 10, 10)");
        int areaId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");

        try (var ps = conn.prepareStatement(
                "SELECT SUM(count) as total FROM staging_area_inventory " +
                "WHERE staging_area_id = ? AND item_id = ?"))
        {
            ps.setInt(1, areaId);
            ps.setString(2, "minecraft:stone");
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals(128, rs.getInt("total"));
            }
        }
    }

    @Test
    void cascadeDelete_removesAllRelatedData() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");

        // 材料 + 认领 + 副负责人 + 玩家背包
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, count) VALUES ('s1', " + matId + ", 'p1', 32)");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'p2')");
        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) VALUES ('s1', 'p1', " + matId + ", 10)");

        // 备货区 + 库存
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) " +
            "VALUES ('s1', 'area1', 'overworld', 0, 0, 0, 10, 10, 10)");
        int areaId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");

        // 全部删光
        conn.createStatement().executeUpdate("DELETE FROM schematics WHERE id = 's1'");

        assertEquals(0, countRows("schematics"));
        assertEquals(0, countRows("material_entries"));
        assertEquals(0, countRows("claims"));
        assertEquals(0, countRows("deputy_owners"));
        assertEquals(0, countRows("player_inventories"));
        assertEquals(0, countRows("staging_areas"));
        assertEquals(0, countRows("staging_area_inventory"));
    }

    // ========== 索引 ==========

    @Test
    void indexExists() throws SQLException
    {
        assertIndexExists("idx_staging_inventory_area");
        assertIndexExists("idx_staging_inventory_item");
    }

    // ========== 事务安全 ==========

    @Test
    void transactionRollback_doesNotPersist() throws SQLException
    {
        conn.setAutoCommit(false);
        conn.createStatement().executeUpdate("INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.rollback();
        assertEquals(0, countRows("schematics"));
    }

    @Test
    void transactionCommit_persists() throws SQLException
    {
        conn.setAutoCommit(false);
        conn.createStatement().executeUpdate("INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.commit();
        assertEquals(1, countRows("schematics"));
    }

    // ========== helpers ==========

    private int lastInsertId() throws SQLException
    {
        try (var rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()"))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countRows(String table) throws SQLException
    {
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + table))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void assertIndexExists(String indexName) throws SQLException
    {
        try (var rs = conn.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='" + indexName + "'"))
        {
            assertTrue(rs.next(), indexName + " should exist");
        }
    }
}