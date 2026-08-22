package net.syncmaterial.syncmaterial.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicDatabase.QueryResult;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;

/**
 * SyncMaterial 服务端 GameTest。
 * 在真实 MC 服务器环境中测试核心业务逻辑。
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
    public void stagingAreaManagerInitialized(TestContext ctx) {
        ctx.assertTrue(
            SyncMaterial.getServerStagingAreaManager() != null,
            Text.literal("备货区管理器应该已初始化")
        );
        ctx.complete();
    }

    // ==================== CollaborationManager 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void collaboration_joinAndLeave(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        CollaborationManager cm = new CollaborationManager(db);
        String testId = "gt-collab-" + System.currentTimeMillis();

        try {
            // 准备数据：创建原理图 + 材料条目
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Collab Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 64);
            int matId;
            try (QueryResult qr = db.executeQuery("SELECT last_insert_rowid()")) {
                qr.next();
                matId = qr.getInt(1);
            }

            // 测试加入协作
            boolean joined = cm.joinCollaboration(testId, matId, "Player1");
            ctx.assertTrue(joined, Text.literal("加入协作应返回 true"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player1"), Text.literal("加入后应处于协作状态"));

            // 验证参与者列表
            var participants = cm.getParticipants(testId, matId);
            ctx.assertEquals(1, participants.size(), Text.literal("应有 1 个参与者"));
            ctx.assertEquals("Player1", participants.get(0), Text.literal("参与者应为 Player1"));

            // 第二个玩家加入
            cm.joinCollaboration(testId, matId, "Player2");
            ctx.assertEquals(2, cm.getParticipants(testId, matId).size(), Text.literal("应有 2 个参与者"));

            // 测试退出协作
            boolean left = cm.leaveCollaboration(testId, matId, "Player1");
            ctx.assertTrue(left, Text.literal("退出协作应返回 true"));
            ctx.assertFalse(cm.isCollaborating(testId, matId, "Player1"), Text.literal("退出后不应处于协作状态"));
            ctx.assertEquals(1, cm.getParticipants(testId, matId).size(), Text.literal("退出后应剩 1 个参与者"));

            // 清理
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("协作加入/退出测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void collaboration_updateInventory(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        CollaborationManager cm = new CollaborationManager(db);
        String testId = "gt-inv-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Inv Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:diamond", 10);
            int matId;
            try (QueryResult qr = db.executeQuery("SELECT last_insert_rowid()")) {
                qr.next();
                matId = qr.getInt(1);
            }

            // 更新玩家背包
            cm.updatePlayerInventory("Player1", testId, matId, 5);

            // 验证数据库持久化
            try (QueryResult qr = db.executeQuery(
                "SELECT count FROM player_inventories WHERE player_name = ? AND material_id = ?",
                "Player1", matId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询到背包记录"));
                ctx.assertEquals(5, qr.getInt("count"), Text.literal("背包数量应为 5"));
            }

            // 更新同一玩家同一材料的数量
            cm.updatePlayerInventory("Player1", testId, matId, 8);
            try (QueryResult qr = db.executeQuery(
                "SELECT count FROM player_inventories WHERE player_name = ? AND material_id = ?",
                "Player1", matId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询到更新后的背包记录"));
                ctx.assertEquals(8, qr.getInt("count"), Text.literal("更新后背包数量应为 8"));
            }

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("背包更新测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void collaboration_joinNonexistentMaterial_fails(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        CollaborationManager cm = new CollaborationManager(db);

        // 尝试加入不存在的材料
        boolean result = cm.joinCollaboration("nonexistent-uuid", 99999, "Player1");
        ctx.assertFalse(result, Text.literal("加入不存在的材料应返回 false"));
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void collaboration_multiplePlayersSameMaterial(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        CollaborationManager cm = new CollaborationManager(db);
        String testId = "gt-multi-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Multi Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 128);
            int matId;
            try (QueryResult qr = db.executeQuery("SELECT last_insert_rowid()")) {
                qr.next();
                matId = qr.getInt(1);
            }

            // 多个玩家加入同一材料
            cm.joinCollaboration(testId, matId, "Player1");
            cm.joinCollaboration(testId, matId, "Player2");
            cm.joinCollaboration(testId, matId, "Player3");

            var participants = cm.getParticipants(testId, matId);
            ctx.assertEquals(3, participants.size(), Text.literal("应有 3 个参与者"));

            // 一个玩家退出，其他玩家不受影响
            cm.leaveCollaboration(testId, matId, "Player2");
            ctx.assertEquals(2, cm.getParticipants(testId, matId).size(), Text.literal("退出后应剩 2 个参与者"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player1"), Text.literal("Player1 应仍在协作"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player3"), Text.literal("Player3 应仍在协作"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("多人协作测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void collaboration_getStatus_returnsCorrectData(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        CollaborationManager cm = new CollaborationManager(db);
        cm.setStagingAreaManager(sam);
        String testId = "gt-status-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Status Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 64);
            int matId;
            try (QueryResult qr = db.executeQuery("SELECT last_insert_rowid()")) {
                qr.next();
                matId = qr.getInt(1);
            }

            // 加入并更新背包
            cm.joinCollaboration(testId, matId, "Player1");
            cm.updatePlayerInventory("Player1", testId, matId, 10);

            // 获取协作状态
            var status = cm.getCollaborationStatus(testId, matId);
            ctx.assertTrue(status != null, Text.literal("协作状态不应为 null"));
            ctx.assertEquals(testId, status.schematicId(), Text.literal("schematicId 应匹配"));
            ctx.assertEquals(matId, status.materialId(), Text.literal("materialId 应匹配"));
            ctx.assertEquals(64, status.totalCount(), Text.literal("totalCount 应为 64"));
            ctx.assertEquals(1, status.participants().size(), Text.literal("应有 1 个参与者"));
            ctx.assertEquals("Player1", status.participants().get(0).playerName(), Text.literal("参与者应为 Player1"));
            ctx.assertEquals(10, status.participants().get(0).count(), Text.literal("参与者背包数应为 10"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("协作状态查询测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    // ==================== DatabaseQueryService 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void queryService_getMaterials_returnsCorrectData(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        DatabaseQueryService qs = new DatabaseQueryService(db);
        String testId = "gt-query-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Query Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 64);
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:diamond", 10);

            // 测试 getMaterials
            var materials = qs.getMaterials(testId);
            ctx.assertEquals(2, materials.size(), Text.literal("应有 2 种材料"));

            // 验证 MaterialEntry 数据
            boolean hasStone = false, hasDiamond = false;
            for (var entry : materials) {
                if ("minecraft:stone".equals(Registries.ITEM.getId(entry.getStack().getItem()).toString())) {
                    hasStone = true;
                    ctx.assertEquals(64L, entry.getCountTotal(), Text.literal("石头数量应为 64"));
                }
                if ("minecraft:diamond".equals(Registries.ITEM.getId(entry.getStack().getItem()).toString())) {
                    hasDiamond = true;
                    ctx.assertEquals(10L, entry.getCountTotal(), Text.literal("钻石数量应为 10"));
                }
            }
            ctx.assertTrue(hasStone, Text.literal("应包含石头"));
            ctx.assertTrue(hasDiamond, Text.literal("应包含钻石"));

            // 测试 schematicExists
            ctx.assertTrue(qs.schematicExists(testId), Text.literal("原理图应存在"));
            ctx.assertFalse(qs.schematicExists("nonexistent"), Text.literal("不存在的原理图应返回 false"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("材料查询测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void queryService_getMaterials_emptySchematic(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        DatabaseQueryService qs = new DatabaseQueryService(db);
        String testId = "gt-empty-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Empty Test", "/test.litematic");

            // 空原理图应返回空列表
            var materials = qs.getMaterials(testId);
            ctx.assertTrue(materials.isEmpty(), Text.literal("空原理图应返回空材料列表"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("空原理图查询测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    // ==================== StagingAreaManager 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void stagingArea_addAndRemove(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-staging-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Staging Test", "/test.litematic");

            // 添加备货区
            int areaId = sam.addStagingArea(testId, "minecraft:overworld", "Test Area",
                0, 64, 0, 10, 70, 10);
            ctx.assertTrue(areaId > 0, Text.literal("添加备货区应返回有效 ID"));

            // 验证数据库
            try (QueryResult qr = db.executeQuery(
                "SELECT name, world FROM staging_areas WHERE id = ?", areaId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询到备货区"));
                ctx.assertEquals("Test Area", qr.getString("name"), Text.literal("名称应匹配"));
            }

            // 验证内存缓存
            var areas = sam.getStagingAreas(testId);
            ctx.assertEquals(1, areas.size(), Text.literal("应有 1 个备货区"));
            ctx.assertEquals("Test Area", areas.get(0).name(), Text.literal("缓存名称应匹配"));

            // 删除备货区
            sam.removeStagingArea(areaId, testId);
            areas = sam.getStagingAreas(testId);
            ctx.assertTrue(areas.isEmpty(), Text.literal("删除后应无备货区"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("备货区增删测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingArea_multipleAreasForSchematic(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-multi-area-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Multi Area Test", "/test.litematic");

            sam.addStagingArea(testId, "minecraft:overworld", "Area 1", 0, 64, 0, 10, 70, 10);
            sam.addStagingArea(testId, "minecraft:overworld", "Area 2", 100, 64, 100, 110, 70, 110);

            var areas = sam.getStagingAreas(testId);
            ctx.assertEquals(2, areas.size(), Text.literal("应有 2 个备货区"));

            // 删除一个，另一个不受影响
            sam.removeStagingArea(areas.get(0).id(), testId);
            areas = sam.getStagingAreas(testId);
            ctx.assertEquals(1, areas.size(), Text.literal("删除一个后应剩 1 个"));

            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
        } catch (Exception e) {
            throw ctx.createError("多备货区测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingArea_cascadeDeleteOnSchematic(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-cascade-staging-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Cascade Staging", "/test.litematic");

            sam.addStagingArea(testId, "minecraft:overworld", "Area 1", 0, 64, 0, 10, 70, 10);

            // 删除原理图，备货区应级联删除（数据库层面）
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);

            try (QueryResult qr = db.executeQuery(
                "SELECT COUNT(*) FROM staging_areas WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Text.literal("应能查询级联删除结果"));
                ctx.assertEquals(0, qr.getInt(1), Text.literal("级联删除后应无备货区"));
            }
        } catch (Exception e) {
            throw ctx.createError("备货区级联删除测试失败: " + e.getMessage());
        }
        ctx.complete();
    }
}
