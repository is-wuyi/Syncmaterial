package net.syncmaterial.syncmaterial.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicDatabase.QueryResult;

/**
 * SyncMaterial 服务端 GameTest。
 * 在真实 MC 服务器环境中测试模组的核心功能。
 */
public class SyncMaterialGameTest {

    // ==================== 冒烟测试 ====================

    @GameTest(structure = "empty")
    public void serverStartsSuccessfully(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        ctx.assertTrue(db != null, Text.literal("数据库应该已初始化"));
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void databaseConnectionValid(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        ctx.assertTrue(db != null, Text.literal("数据库应该已初始化"));
        try (QueryResult qr = db.executeQuery("SELECT 1")) {
            ctx.assertTrue(qr.next(), Text.literal("应该能执行查询"));
            ctx.assertEquals(1, qr.getInt(1), Text.literal("查询结果应为 1"));
        } catch (Exception e) {
            throw ctx.createError("数据库查询失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingAreaManagerInitialized(TestContext ctx) {
        ctx.assertTrue(
            SyncMaterial.getServerStagingAreaManager() != null,
            Text.literal("备货区管理器应该已初始化")
        );
        ctx.complete();
    }

    // ==================== 数据库集成测试 ====================

    @GameTest(structure = "empty")
    public void databaseCrudOperations(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gametest-crud-" + System.currentTimeMillis();

        try {
            // 插入测试数据
            db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                testId, "GameTest Schematic", "/test/path.litematic", "GameTest"
            );

            // 查询验证
            try (QueryResult qr = db.executeQuery("SELECT name, uploaded_by FROM schematics WHERE id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应该能查询到插入的数据"));
                ctx.assertEquals("GameTest Schematic", qr.getString("name"), Text.literal("名称应匹配"));
                ctx.assertEquals("GameTest", qr.getString("uploaded_by"), Text.literal("上传者应匹配"));
            }

            // 更新
            db.executeUpdate("UPDATE schematics SET name = ? WHERE id = ?", "Updated Name", testId);
            try (QueryResult qr = db.executeQuery("SELECT name FROM schematics WHERE id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("更新后应能查询到"));
                ctx.assertEquals("Updated Name", qr.getString("name"), Text.literal("更新后名称应匹配"));
            }

            // 删除
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            try (QueryResult qr = db.executeQuery("SELECT COUNT(*) FROM schematics WHERE id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能执行 COUNT 查询"));
                ctx.assertEquals(0, qr.getInt(1), Text.literal("删除后应该没有数据"));
            }
        } catch (Exception e) {
            throw ctx.createError("数据库 CRUD 操作失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void materialEntriesCascadeDelete(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gametest-cascade-" + System.currentTimeMillis();

        try {
            // 创建测试原理图
            db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Cascade Test", "/test.litematic"
            );

            // 创建材料条目
            db.executeUpdate(
                "INSERT INTO material_entries (schematic_id, item_id, count_total) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 64
            );
            db.executeUpdate(
                "INSERT INTO material_entries (schematic_id, item_id, count_total) VALUES (?, ?, ?)",
                testId, "minecraft:diamond", 10
            );

            // 验证材料条目存在
            try (QueryResult qr = db.executeQuery(
                    "SELECT COUNT(*) FROM material_entries WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询材料条目数"));
                ctx.assertEquals(2, qr.getInt(1), Text.literal("应有 2 个材料条目"));
            }

            // 删除原理图（应该级联删除材料条目）
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);

            // 验证材料条目也被删除
            try (QueryResult qr = db.executeQuery(
                    "SELECT COUNT(*) FROM material_entries WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询级联删除后的材料条目数"));
                ctx.assertEquals(0, qr.getInt(1), Text.literal("级联删除后应该没有材料条目"));
            }
        } catch (Exception e) {
            throw ctx.createError("级联删除测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingAreaDatabaseOperations(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gametest-staging-" + System.currentTimeMillis();

        try {
            // 创建测试原理图
            db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Staging Test", "/test.litematic"
            );

            // 创建备货区
            db.executeUpdate(
                "INSERT INTO staging_areas (schematic_id, name, x1, y1, z1, x2, y2, z2, world) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                testId, "Test Area", 0, 64, 0, 10, 70, 10, "minecraft:overworld"
            );

            // 查询验证
            try (QueryResult qr = db.executeQuery(
                    "SELECT name, x1, y1, z1, x2, y2, z2, world FROM staging_areas WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应该能查询到备货区"));
                ctx.assertEquals("Test Area", qr.getString("name"), Text.literal("备货区名称应匹配"));
                ctx.assertEquals(0, qr.getInt("x1"), Text.literal("x1 应为 0"));
                ctx.assertEquals(64, qr.getInt("y1"), Text.literal("y1 应为 64"));
                ctx.assertEquals(10, qr.getInt("x2"), Text.literal("x2 应为 10"));
                ctx.assertEquals("minecraft:overworld", qr.getString("world"), Text.literal("世界应匹配"));
            }

            // 清理
            db.executeUpdate("DELETE FROM staging_areas WHERE schematic_id = ?", testId);
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("备货区数据库操作测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void ownerManagementOperations(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gametest-owner-" + System.currentTimeMillis();

        try {
            // 创建测试原理图（带负责人）
            db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by, allow_self_claim) VALUES (?, ?, ?, ?, ?)",
                testId, "Owner Test", "/test.litematic", "Owner1", 1
            );

            // 添加副负责人
            db.executeUpdate(
                "INSERT INTO deputy_owners (schematic_id, player_name) VALUES (?, ?)",
                testId, "Deputy1"
            );
            db.executeUpdate(
                "INSERT INTO deputy_owners (schematic_id, player_name) VALUES (?, ?)",
                testId, "Deputy2"
            );

            // 查询副负责人
            try (QueryResult qr = db.executeQuery(
                    "SELECT player_name FROM deputy_owners WHERE schematic_id = ?", testId)) {
                int count = 0;
                while (qr.next()) {
                    count++;
                }
                ctx.assertEquals(2, count, Text.literal("应该有 2 个副负责人"));
            }

            // 删除一个副负责人
            db.executeUpdate(
                "DELETE FROM deputy_owners WHERE schematic_id = ? AND player_name = ?",
                testId, "Deputy1"
            );

            // 验证只剩一个
            try (QueryResult qr = db.executeQuery(
                    "SELECT COUNT(*) FROM deputy_owners WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询副负责人数量"));
                ctx.assertEquals(1, qr.getInt(1), Text.literal("删除后应剩 1 个副负责人"));
            }

            // 清理
            db.executeUpdate("DELETE FROM deputy_owners WHERE schematic_id = ?", testId);
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("负责人管理操作测试失败: " + e.getMessage());
        }
        ctx.complete();
    }
}
