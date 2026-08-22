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
 * DDL 与 SchematicDatabase.createTables() 保持一致。
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

    /**
     * 复刻 SchematicDatabase.createTables() 的完整 DDL（含迁移列）。
     * 若真实 schema 变了，此处必须同步更新。
     */
    private void createTables() throws SQLException
    {
        // 原理图基础信息表
        conn.createStatement().execute("""
            CREATE TABLE schematics (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                uploaded_by TEXT,
                allow_self_claim INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
            )
        """);
        // 迁移列：file_hash
        conn.createStatement().execute("ALTER TABLE schematics ADD COLUMN file_hash TEXT DEFAULT ''");

        // 材料条目表
        conn.createStatement().execute("""
            CREATE TABLE material_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            )
        """);

        // 协作认领记录表
        conn.createStatement().execute("""
            CREATE TABLE claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                player_name TEXT NOT NULL,
                status TEXT DEFAULT 'active',
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (material_id) REFERENCES material_entries(id) ON DELETE CASCADE
            )
        """);

        // 副负责人表
        conn.createStatement().execute("""
            CREATE TABLE deputy_owners (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name)
            )
        """);

        // 备货区区域定义表
        conn.createStatement().execute("""
            CREATE TABLE staging_areas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER, y1 INTEGER, z1 INTEGER,
                x2 INTEGER, y2 INTEGER, z2 INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE
            )
        """);
        // 迁移列：name
        conn.createStatement().execute("ALTER TABLE staging_areas ADD COLUMN name TEXT NOT NULL DEFAULT '未命名'");

        // 备货区内容物统计表
        conn.createStatement().execute("""
            CREATE TABLE staging_area_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staging_area_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id) ON DELETE CASCADE
            )
        """);

        // 玩家背包缓存表
        conn.createStatement().execute("""
            CREATE TABLE player_inventories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schematic_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                material_id INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, player_name, material_id)
            )
        """);

        // 索引
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_area ON staging_area_inventory(staging_area_id)");
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_item ON staging_area_inventory(item_id)");
        conn.createStatement().execute(
            "CREATE UNIQUE INDEX idx_claim_unique ON claims(schematic_id, material_id, player_name)");
    }

    // ========== 基础 CRUD ==========

    @Test
    void insertAndQuerySchematic() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES ('s1', 'test', '/test.litematic', 'Player1')");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT name, uploaded_by, allow_self_claim FROM schematics WHERE id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("test", rs.getString("name"));
            assertEquals("Player1", rs.getString("uploaded_by"));
            assertEquals(1, rs.getInt("allow_self_claim"));
        }
    }

    @Test
    void insertOrIgnore_duplicateId_doesNotOverwrite() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path, file_hash) VALUES ('s1', 'original', '/test.litematic', 'hash1')");

        conn.createStatement().executeUpdate(
            "INSERT OR IGNORE INTO schematics (id, name, file_path, file_hash) VALUES ('s1', 'duplicate', '/test.litematic', 'hash2')");

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
            "INSERT INTO schematics (id, name, file_path, file_hash) VALUES ('s1', 'test', '/test.litematic', 'old_hash')");

        conn.createStatement().executeUpdate("DELETE FROM schematics WHERE id = 's1'");
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path, file_hash) VALUES ('s1', 'test', '/test.litematic', 'new_hash')");

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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) " +
            "VALUES ('s1', " + matId + ", 'player1', 'active')");

        try (var rs = conn.createStatement().executeQuery(
                "SELECT player_name, status FROM claims WHERE schematic_id = 's1'"))
        {
            assertTrue(rs.next());
            assertEquals("player1", rs.getString("player_name"));
            assertEquals("active", rs.getString("status"));
        }
    }

    @Test
    void claim_multiplePlayersSameMaterial() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 128)");
        int matId = lastInsertId();

        // 多个玩家认领同一材料
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'player1', 'active')");
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'player2', 'active')");

        // 验证有两个认领记录
        try (var ps = conn.prepareStatement(
                "SELECT COUNT(*) as total FROM claims WHERE material_id = ? AND status = 'active'"))
        {
            ps.setInt(1, matId);
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("total"));
            }
        }
    }

    @Test
    void claim_cascadeDeleteOnSchematic() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'player1', 'active')");

        conn.createStatement().executeUpdate("DELETE FROM schematics WHERE id = 's1'");

        assertEquals(0, countRows("claims"));
        assertEquals(0, countRows("material_entries"));
    }

    @Test
    void claim_uniqueConstraint_preventsDuplicates() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'player1', 'active')");

        // 同一玩家对同一材料不能重复认领
        assertThrows(SQLException.class, () ->
            conn.createStatement().executeUpdate(
                "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'player1', 'active')"));
    }

    // ========== Deputy Owners（副负责人） ==========

    @Test
    void deputyOwner_insertAndQuery() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) " +
            "VALUES ('s1', 'player1', " + matId + ", 10)");

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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();

        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) " +
            "VALUES ('s1', 'player1', " + matId + ", 10)");

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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
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
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");

        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES ('s1', 'minecraft:stone', 64)");
        int matId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO claims (schematic_id, material_id, player_name, status) VALUES ('s1', " + matId + ", 'p1', 'active')");
        conn.createStatement().executeUpdate(
            "INSERT INTO deputy_owners (schematic_id, player_name) VALUES ('s1', 'p2')");
        conn.createStatement().executeUpdate(
            "INSERT INTO player_inventories (schematic_id, player_name, material_id, count) VALUES ('s1', 'p1', " + matId + ", 10)");

        conn.createStatement().executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) " +
            "VALUES ('s1', 'area1', 'overworld', 0, 0, 0, 10, 10, 10)");
        int areaId = lastInsertId();
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");

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
        assertIndexExists("idx_claim_unique");
    }

    // ========== 事务安全 ==========

    @Test
    void transactionRollback_doesNotPersist() throws SQLException
    {
        conn.setAutoCommit(false);
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.rollback();
        assertEquals(0, countRows("schematics"));
    }

    @Test
    void transactionCommit_persists() throws SQLException
    {
        conn.setAutoCommit(false);
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
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
