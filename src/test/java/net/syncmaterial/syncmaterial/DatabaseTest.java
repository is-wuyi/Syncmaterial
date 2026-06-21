package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
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
                FOREIGN KEY (schematic_id) REFERENCES schematics(id)
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
                FOREIGN KEY (schematic_id) REFERENCES schematics(id)
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE staging_area_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staging_area_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (staging_area_id) REFERENCES staging_areas(id)
            )
        """);
        // 索引
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_area ON staging_area_inventory(staging_area_id)");
        conn.createStatement().execute(
            "CREATE INDEX idx_staging_inventory_item ON staging_area_inventory(item_id)");
    }

    // ========== 基础 CRUD ==========

    @Test
    void insertAndQuerySchematic() throws SQLException
    {
        try (var ps = conn.prepareStatement(
                "INSERT INTO schematics (id, name, material_count) VALUES (?, ?, ?)"))
        {
            ps.setString(1, "test-schematic-1");
            ps.setString(2, "测试原理图");
            ps.setInt(3, 10);
            ps.executeUpdate();
        }

        try (var ps = conn.prepareStatement("SELECT name, material_count FROM schematics WHERE id = ?"))
        {
            ps.setString(1, "test-schematic-1");
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals("测试原理图", rs.getString("name"));
                assertEquals(10, rs.getInt("material_count"));
            }
        }
    }

    @Test
    void cascadeDelete_removesAllRelatedData() throws SQLException
    {
        // 插入原理图
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");

        // 插入备货区
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) " +
            "VALUES ('s1', 'area1', 'overworld', 0, 0, 0, 10, 10, 10)");
        int areaId;
        try (var rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()"))
        {
            rs.next();
            areaId = rs.getInt(1);
        }

        // 插入备货区库存
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");

        // 插入材料条目
        conn.createStatement().executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) " +
            "VALUES ('s1', 'minecraft:stone', 128)");

        // 级联删除：先删库存 → 备货区 → 材料 → 原理图
        conn.createStatement().executeUpdate(
            "DELETE FROM staging_area_inventory WHERE staging_area_id IN " +
            "(SELECT id FROM staging_areas WHERE schematic_id = 's1')");
        conn.createStatement().executeUpdate(
            "DELETE FROM staging_areas WHERE schematic_id = 's1'");
        conn.createStatement().executeUpdate(
            "DELETE FROM material_entries WHERE schematic_id = 's1'");
        conn.createStatement().executeUpdate(
            "DELETE FROM schematics WHERE id = 's1'");

        // 验证全部清空
        assertEquals(0, countRows("schematics"));
        assertEquals(0, countRows("staging_areas"));
        assertEquals(0, countRows("staging_area_inventory"));
        assertEquals(0, countRows("material_entries"));
    }

    @Test
    void stagingInventoryQuery_correctCount() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_areas (schematic_id, name, world, x1, y1, z1, x2, y2, z2) " +
            "VALUES ('s1', 'area1', 'overworld', 0, 0, 0, 10, 10, 10)");
        int areaId;
        try (var rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()"))
        {
            rs.next();
            areaId = rs.getInt(1);
        }

        // 两个箱子各放 64 个石头
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");
        conn.createStatement().executeUpdate(
            "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) " +
            "VALUES (" + areaId + ", 'minecraft:stone', 64)");

        // 查询该备货区石头总数
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
    void indexExists() throws SQLException
    {
        try (var rs = conn.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_staging_inventory_area'"))
        {
            assertTrue(rs.next(), "idx_staging_inventory_area 索引应存在");
        }
        try (var rs = conn.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_staging_inventory_item'"))
        {
            assertTrue(rs.next(), "idx_staging_inventory_item 索引应存在");
        }
    }

    // ========== 事务安全 ==========

    @Test
    void transactionRollback_doesNotPersist() throws SQLException
    {
        conn.setAutoCommit(false);

        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");

        conn.rollback();

        assertEquals(0, countRows("schematics"), "回滚后不应有数据");
    }

    @Test
    void transactionCommit_persists() throws SQLException
    {
        conn.setAutoCommit(false);

        conn.createStatement().executeUpdate(
            "INSERT INTO schematics (id, name) VALUES ('s1', 'test')");

        conn.commit();

        assertEquals(1, countRows("schematics"), "提交后应有数据");
    }

    private int countRows(String table) throws SQLException
    {
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + table))
        {
            rs.next();
            return rs.getInt(1);
        }
    }
}
