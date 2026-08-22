package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 5: 仓库相关数据库操作测试
 */
public class WarehouseDatabaseTest
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
                file_path TEXT NOT NULL,
                uploaded_by TEXT,
                allow_self_claim INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
            )
        """);
        conn.createStatement().execute("ALTER TABLE schematics ADD COLUMN file_hash TEXT DEFAULT ''");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS warehouses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER, y1 INTEGER, z1 INTEGER,
                x2 INTEGER, y2 INTEGER, z2 INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS schematic_warehouses (
                schematic_id TEXT NOT NULL,
                warehouse_id INTEGER NOT NULL,
                FOREIGN KEY (schematic_id) REFERENCES schematics(id) ON DELETE CASCADE,
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE CASCADE,
                UNIQUE(schematic_id, warehouse_id)
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS warehouse_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                warehouse_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE CASCADE
            )
        """);
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS container_inventory (
                area_id INTEGER NOT NULL,
                area_type TEXT NOT NULL,
                pos_x INTEGER, pos_y INTEGER, pos_z INTEGER,
                item_id TEXT NOT NULL,
                UNIQUE(area_id, pos_x, pos_y, pos_z, item_id)
            )
        """);
    }

    // ========== 仓库 CRUD ==========

    @Test
    void insertAndQueryWarehouse() throws SQLException
    {
        try (var ps = conn.prepareStatement(
                "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"))
        {
            ps.setString(1, "仓库A");
            ps.setString(2, "minecraft:overworld");
            ps.setInt(3, 100); ps.setInt(4, 64); ps.setInt(5, 100);
            ps.setInt(6, 200); ps.setInt(7, 80); ps.setInt(8, 200);
            ps.executeUpdate();
        }

        try (var rs = conn.createStatement().executeQuery("SELECT name, world FROM warehouses WHERE id = 1"))
        {
            assertTrue(rs.next());
            assertEquals("仓库A", rs.getString("name"));
            assertEquals("minecraft:overworld", rs.getString("world"));
        }
    }

    @Test
    void warehouseCascadeDelete_cleansInventoryAndReferences() throws SQLException
    {
        // 插入原理图和仓库
        conn.createStatement().executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库A', 'minecraft:overworld', 0, 0, 0, 10, 10, 10)");
        conn.createStatement().executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 1)");
        conn.createStatement().executeUpdate("INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (1, 'minecraft:stone', 64)");
        conn.createStatement().executeUpdate("INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");

        // 删除仓库（级联清理）
        conn.createStatement().executeUpdate("DELETE FROM warehouses WHERE id = 1");

        // 验证 warehouse_inventory 被级联删除
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM warehouse_inventory"))
        {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }

        // 验证 schematic_warehouses 被级联删除
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM schematic_warehouses"))
        {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }

        // container_inventory 没有外键约束，需要手动清理
        // （在 deleteWarehouse 方法中处理）
    }

    @Test
    void warehouseDelete_manualCleanup_removesContainerInventory() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库A', 'minecraft:overworld', 0, 0, 0, 10, 10, 10)");
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 8, 65, 8, 'minecraft:iron_ingot')");

        // 模拟 deleteWarehouse 的手动清理逻辑
        conn.createStatement().executeUpdate("DELETE FROM container_inventory WHERE area_id = 1 AND area_type = 'warehouse'");

        // 验证 container_inventory 已清理
        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM container_inventory WHERE area_id = 1"))
        {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    // ========== 原理图-仓库引用 ==========

    @Test
    void warehouseReference_uniqueConstraint() throws SQLException
    {
        conn.createStatement().executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库A', 'minecraft:overworld', 0, 0, 0, 10, 10, 10)");

        // 第一次插入成功
        conn.createStatement().executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 1)");

        // 第二次插入被 UNIQUE 约束拒绝（使用 INSERT OR IGNORE）
        conn.createStatement().executeUpdate("INSERT OR IGNORE INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 1)");

        try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM schematic_warehouses"))
        {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void getWarehousesForSchematic() throws SQLException
    {
        conn.createStatement().executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库A', 'minecraft:overworld', 0, 0, 0, 10, 10, 10)");
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库B', 'minecraft:overworld', 100, 0, 100, 200, 10, 200)");
        conn.createStatement().executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 1)");
        conn.createStatement().executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 2)");

        try (var ps = conn.prepareStatement(
                "SELECT w.id, w.name FROM warehouses w INNER JOIN schematic_warehouses sw ON w.id = sw.warehouse_id WHERE sw.schematic_id = ?"))
        {
            ps.setString(1, "s1");
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals("仓库A", rs.getString("name"));
                assertTrue(rs.next());
                assertEquals("仓库B", rs.getString("name"));
                assertFalse(rs.next());
            }
        }
    }

    // ========== warehouse_inventory ==========

    @Test
    void warehouseInventory_totalCount() throws SQLException
    {
        conn.createStatement().executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES ('仓库A', 'minecraft:overworld', 0, 0, 0, 10, 10, 10)");
        conn.createStatement().executeUpdate("INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (1, 'minecraft:stone', 64)");
        conn.createStatement().executeUpdate("INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (1, 'minecraft:stone', 64)");

        try (var ps = conn.prepareStatement("SELECT SUM(count) as total FROM warehouse_inventory WHERE warehouse_id = ? AND item_id = ?"))
        {
            ps.setInt(1, 1);
            ps.setString(2, "minecraft:stone");
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals(128, rs.getInt("total"));
            }
        }
    }

    // ========== container_inventory ==========

    @Test
    void containerInventory_upsertPattern() throws SQLException
    {
        // 插入容器明细
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:iron_ingot')");

        // 验证该箱子有两种物品
        try (var ps = conn.prepareStatement("SELECT item_id FROM container_inventory WHERE area_id = 1 AND pos_x = 5 AND pos_y = 65 AND pos_z = 5 ORDER BY item_id"))
        {
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals("minecraft:iron_ingot", rs.getString("item_id"));
                assertTrue(rs.next());
                assertEquals("minecraft:stone", rs.getString("item_id"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void containerInventory_diffUpdate() throws SQLException
    {
        // 模拟增量扫描：旧状态有 stone + iron_ingot，新状态只有 stone
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:iron_ingot')");

        // 删除旧记录
        conn.createStatement().executeUpdate("DELETE FROM container_inventory WHERE area_id = 1 AND pos_x = 5 AND pos_y = 65 AND pos_z = 5");

        // 插入新记录（只有 stone）
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");

        // 验证只剩 stone
        try (var ps = conn.prepareStatement("SELECT item_id FROM container_inventory WHERE area_id = 1 AND pos_x = 5 AND pos_y = 65 AND pos_z = 5"))
        {
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals("minecraft:stone", rs.getString("item_id"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void containerInventory_stagingAreaType() throws SQLException
    {
        // 验证 area_type 区分备货区和仓库
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'staging_area', 5, 65, 5, 'minecraft:stone')");
        conn.createStatement().executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:iron_ingot')");

        // 按类型查询
        try (var ps = conn.prepareStatement("SELECT item_id FROM container_inventory WHERE area_id = 1 AND area_type = 'warehouse'"))
        {
            try (var rs = ps.executeQuery())
            {
                assertTrue(rs.next());
                assertEquals("minecraft:iron_ingot", rs.getString("item_id"));
                assertFalse(rs.next());
            }
        }
    }
}
