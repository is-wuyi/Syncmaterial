package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * Phase 5: 仓库相关数据库操作测试。
 * 使用真实 SchematicDatabase（临时文件库），schema 与生产完全一致。
 * StagingAreaManager 的仓库 CRUD 业务方法在 GameTest 中覆盖（依赖服务器环境）。
 */
public class WarehouseDatabaseTest
{
    @TempDir
    Path tempDir;

    private SchematicDatabase db;

    @BeforeEach
    void setUp()
    {
        db = new SchematicDatabase();
        db.initialize(tempDir.resolve("warehouse-test.db").toString());
    }

    @AfterEach
    void tearDown()
    {
        if (db != null) db.close();
    }

    private int insertWarehouse(String name) throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES (?, ?, 0, 0, 0, 10, 10, 10)",
            name, "minecraft:overworld");
        try (var rs = db.executeQuery("SELECT last_insert_rowid()"))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countRows(String sql, Object... params) throws SQLException
    {
        try (var rs = db.executeQuery(sql, params))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ========== 仓库 CRUD ==========

    @Test
    void insertAndQueryWarehouse() throws SQLException
    {
        int id = insertWarehouse("仓库A");

        try (var rs = db.executeQuery("SELECT name, world FROM warehouses WHERE id = ?", id))
        {
            assertTrue(rs.next());
            assertEquals("仓库A", rs.getString("name"));
            assertEquals("minecraft:overworld", rs.getString("world"));
        }
    }

    @Test
    void warehouseCascadeDelete_cleansInventoryAndReferences() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        int warehouseId = insertWarehouse("仓库A");
        db.executeUpdate(
            "INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', ?)", warehouseId);
        db.executeUpdate(
            "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, 'minecraft:stone', 64)", warehouseId);
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, 'warehouse', 5, 65, 5, 'minecraft:stone')",
            warehouseId);

        db.executeUpdate("DELETE FROM warehouses WHERE id = ?", warehouseId);

        assertEquals(0, countRows("SELECT COUNT(*) FROM warehouse_inventory"),
            "warehouse_inventory 应被级联删除");
        assertEquals(0, countRows("SELECT COUNT(*) FROM schematic_warehouses"),
            "schematic_warehouses 应被级联删除");
        // container_inventory 没有外键约束，需要手动清理（deleteWarehouse 中处理）
    }

    @Test
    void warehouseDelete_manualCleanup_removesContainerInventory() throws SQLException
    {
        int warehouseId = insertWarehouse("仓库A");
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, 'warehouse', 5, 65, 5, 'minecraft:stone')",
            warehouseId);
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, 'warehouse', 8, 65, 8, 'minecraft:iron_ingot')",
            warehouseId);

        // 模拟 deleteWarehouse 的手动清理逻辑
        db.executeUpdate(
            "DELETE FROM container_inventory WHERE area_id = ? AND area_type = 'warehouse'", warehouseId);

        assertEquals(0, countRows("SELECT COUNT(*) FROM container_inventory WHERE area_id = ?", warehouseId));
    }

    // ========== 跨维度迁移（beta.18） ==========

    @Test
    void updateWarehouse_withWorld_migratesDimension() throws SQLException
    {
        var manager = new net.syncmaterial.syncmaterial.server.StagingAreaManager(db);
        int id = manager.addWarehouse("仓库A", "minecraft:overworld", 0, 0, 0, 10, 10, 10);

        manager.updateWarehouse(id, "仓库A", "minecraft:the_nether", 100, 20, 100, 110, 30, 110);

        try (var rs = db.executeQuery("SELECT world, x1, y1 FROM warehouses WHERE id = ?", id))
        {
            assertTrue(rs.next());
            assertEquals("minecraft:the_nether", rs.getString("world"),
                "传入 world 时应连同坐标一起迁移维度，否则坐标与维度自相矛盾");
            assertEquals(100, rs.getInt("x1"));
            assertEquals(20, rs.getInt("y1"));
        }

        var reloaded = manager.getAllWarehouses().stream()
            .filter(w -> w.id() == id).findFirst().orElseThrow();
        assertEquals("minecraft:the_nether", reloaded.world(),
            "内存缓存也必须同步为新维度，否则线框按旧维度过滤会渲染不出来");
    }

    @Test
    void updateWarehouse_withoutWorld_keepsDimension() throws SQLException
    {
        var manager = new net.syncmaterial.syncmaterial.server.StagingAreaManager(db);
        int id = manager.addWarehouse("仓库A", "minecraft:the_end", 0, 0, 0, 10, 10, 10);

        manager.updateWarehouse(id, "仓库A", null, 5, 5, 5, 15, 15, 15);

        try (var rs = db.executeQuery("SELECT world, x1 FROM warehouses WHERE id = ?", id))
        {
            assertTrue(rs.next());
            assertEquals("minecraft:the_end", rs.getString("world"),
                "world 传 null 表示只改坐标，维度必须保持原值");
            assertEquals(5, rs.getInt("x1"));
        }
    }

    @Test
    void updateStagingArea_withWorld_migratesDimension() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        var manager = new net.syncmaterial.syncmaterial.server.StagingAreaManager(db);
        int id = manager.addStagingArea("s1", "minecraft:overworld", "备货区A", 0, 0, 0, 10, 10, 10);

        manager.updateStagingArea(id, "s1", "备货区A", "minecraft:the_nether", 50, 60, 70, 55, 65, 75);

        try (var rs = db.executeQuery("SELECT world, x1 FROM staging_areas WHERE id = ?", id))
        {
            assertTrue(rs.next());
            assertEquals("minecraft:the_nether", rs.getString("world"));
            assertEquals(50, rs.getInt("x1"));
        }
    }

    @Test
    void updateStagingArea_withoutWorld_keepsDimension() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        var manager = new net.syncmaterial.syncmaterial.server.StagingAreaManager(db);
        int id = manager.addStagingArea("s1", "minecraft:the_end", "备货区A", 0, 0, 0, 10, 10, 10);

        manager.updateStagingArea(id, "s1", "备货区A", null, 1, 2, 3, 4, 5, 6);

        try (var rs = db.executeQuery("SELECT world, x1 FROM staging_areas WHERE id = ?", id))
        {
            assertTrue(rs.next());
            assertEquals("minecraft:the_end", rs.getString("world"));
            assertEquals(1, rs.getInt("x1"));
        }
    }

    // ========== 原理图-仓库引用 ==========

    @Test
    void warehouseReference_uniqueConstraint() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        int warehouseId = insertWarehouse("仓库A");

        db.executeUpdate(
            "INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', ?)", warehouseId);
        // 重复引用使用 INSERT OR IGNORE（对应 addWarehouseReference）
        db.executeUpdate(
            "INSERT OR IGNORE INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', ?)", warehouseId);

        assertEquals(1, countRows("SELECT COUNT(*) FROM schematic_warehouses"));
    }

    @Test
    void getWarehousesForSchematic_joinQuery() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO schematics (id, name, file_path) VALUES ('s1', 'test', '/test.litematic')");
        insertWarehouse("仓库A");
        insertWarehouse("仓库B");
        db.executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 1)");
        db.executeUpdate("INSERT INTO schematic_warehouses (schematic_id, warehouse_id) VALUES ('s1', 2)");

        // 与 StagingAreaManager.getWarehousesForSchematic 相同的 JOIN
        try (var rs = db.executeQuery(
                "SELECT w.id, w.name FROM warehouses w INNER JOIN schematic_warehouses sw ON w.id = sw.warehouse_id WHERE sw.schematic_id = ? ORDER BY w.id",
                "s1"))
        {
            assertTrue(rs.next());
            assertEquals("仓库A", rs.getString("name"));
            assertTrue(rs.next());
            assertEquals("仓库B", rs.getString("name"));
            assertFalse(rs.next());
        }
    }

    // ========== warehouse_inventory ==========

    @Test
    void warehouseInventory_totalCount() throws SQLException
    {
        int warehouseId = insertWarehouse("仓库A");
        db.executeUpdate(
            "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, 'minecraft:stone', 64)", warehouseId);
        db.executeUpdate(
            "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, 'minecraft:stone', 64)", warehouseId);

        assertEquals(128, countRows(
            "SELECT SUM(count) FROM warehouse_inventory WHERE warehouse_id = ? AND item_id = 'minecraft:stone'",
            warehouseId));
    }

    // ========== container_inventory ==========

    @Test
    void containerInventory_multipleItemsPerContainer() throws SQLException
    {
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:stone')");
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:iron_ingot')");

        assertEquals(2, countRows(
            "SELECT COUNT(*) FROM container_inventory WHERE area_id = 1 AND pos_x = 5 AND pos_y = 65 AND pos_z = 5"));
    }

    @Test
    void containerInventory_samePosDifferentAreaType_keptSeparate() throws SQLException
    {
        // area_type 区分备货区与仓库，同 ID 同坐标互不干扰
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'staging_area', 5, 65, 5, 'minecraft:stone')");
        db.executeUpdate(
            "INSERT INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (1, 'warehouse', 5, 65, 5, 'minecraft:iron_ingot')");

        try (var rs = db.executeQuery(
                "SELECT item_id FROM container_inventory WHERE area_id = 1 AND area_type = 'warehouse'"))
        {
            assertTrue(rs.next());
            assertEquals("minecraft:iron_ingot", rs.getString("item_id"));
            assertFalse(rs.next());
        }
    }
}
