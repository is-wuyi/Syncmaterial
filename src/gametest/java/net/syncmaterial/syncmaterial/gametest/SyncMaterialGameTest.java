package net.syncmaterial.syncmaterial.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicDatabase.QueryResult;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;

/**
 * SyncMaterial 服务端 GameTest。
 * 在真实 MC 服务器环境中测试核心业务逻辑。
 */
public class SyncMaterialGameTest {

    // ==================== 冒烟测试 ====================

    @GameTest(structure = "empty")
    public void serverStartsSuccessfully(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        ctx.assertTrue(db != null, Component.literal("数据库应该已初始化"));
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void stagingAreaManagerInitialized(GameTestHelper ctx) {
        ctx.assertTrue(
            SyncMaterial.getServerStagingAreaManager() != null,
            Component.literal("备货区管理器应该已初始化")
        );
        ctx.succeed();
    }

    // ==================== CollaborationManager 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void collaboration_joinAndLeave(GameTestHelper ctx) {
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
            ctx.assertTrue(joined, Component.literal("加入协作应返回 true"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player1"), Component.literal("加入后应处于协作状态"));

            // 验证参与者列表
            var participants = cm.getParticipants(testId, matId);
            ctx.assertTrue(java.util.Objects.equals(1, participants.size()), Component.literal("应有 1 个参与者"));
            ctx.assertTrue(java.util.Objects.equals("Player1", participants.get(0)), Component.literal("参与者应为 Player1"));

            // 第二个玩家加入
            cm.joinCollaboration(testId, matId, "Player2");
            ctx.assertTrue(java.util.Objects.equals(2, cm.getParticipants(testId, matId).size()), Component.literal("应有 2 个参与者"));

            // 测试退出协作
            boolean left = cm.leaveCollaboration(testId, matId, "Player1");
            ctx.assertTrue(left, Component.literal("退出协作应返回 true"));
            ctx.assertFalse(cm.isCollaborating(testId, matId, "Player1"), Component.literal("退出后不应处于协作状态"));
            ctx.assertTrue(java.util.Objects.equals(1, cm.getParticipants(testId, matId).size()), Component.literal("退出后应剩 1 个参与者"));

        } catch (Exception e) {
            throw ctx.assertionException("协作加入/退出测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void collaboration_updateInventory(GameTestHelper ctx) {
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
                ctx.assertTrue(qr.next(), Component.literal("应能查询到背包记录"));
                ctx.assertTrue(java.util.Objects.equals(5, qr.getInt("count")), Component.literal("背包数量应为 5"));
            }

            // 更新同一玩家同一材料的数量
            cm.updatePlayerInventory("Player1", testId, matId, 8);
            try (QueryResult qr = db.executeQuery(
                "SELECT count FROM player_inventories WHERE player_name = ? AND material_id = ?",
                "Player1", matId)) {
                ctx.assertTrue(qr.next(), Component.literal("应能查询到更新后的背包记录"));
                ctx.assertTrue(java.util.Objects.equals(8, qr.getInt("count")), Component.literal("更新后背包数量应为 8"));
            }

        } catch (Exception e) {
            throw ctx.assertionException("背包更新测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void collaboration_joinNonexistentMaterial_fails(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        CollaborationManager cm = new CollaborationManager(db);

        // 尝试加入不存在的材料
        boolean result = cm.joinCollaboration("nonexistent-uuid", 99999, "Player1");
        ctx.assertFalse(result, Component.literal("加入不存在的材料应返回 false"));
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void collaboration_multiplePlayersSameMaterial(GameTestHelper ctx) {
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
            ctx.assertTrue(java.util.Objects.equals(3, participants.size()), Component.literal("应有 3 个参与者"));

            // 一个玩家退出，其他玩家不受影响
            cm.leaveCollaboration(testId, matId, "Player2");
            ctx.assertTrue(java.util.Objects.equals(2, cm.getParticipants(testId, matId).size()), Component.literal("退出后应剩 2 个参与者"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player1"), Component.literal("Player1 应仍在协作"));
            ctx.assertTrue(cm.isCollaborating(testId, matId, "Player3"), Component.literal("Player3 应仍在协作"));

        } catch (Exception e) {
            throw ctx.assertionException("多人协作测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void collaboration_getStatus_returnsCorrectData(GameTestHelper ctx) {
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
            ctx.assertTrue(status != null, Component.literal("协作状态不应为 null"));
            ctx.assertTrue(java.util.Objects.equals(testId, status.schematicId()), Component.literal("schematicId 应匹配"));
            ctx.assertTrue(java.util.Objects.equals(matId, status.materialId()), Component.literal("materialId 应匹配"));
            ctx.assertTrue(java.util.Objects.equals(64, status.totalCount()), Component.literal("totalCount 应为 64"));
            ctx.assertTrue(java.util.Objects.equals(1, status.participants().size()), Component.literal("应有 1 个参与者"));
            ctx.assertTrue(java.util.Objects.equals("Player1", status.participants().get(0).playerName()), Component.literal("参与者应为 Player1"));
            ctx.assertTrue(java.util.Objects.equals(10, status.participants().get(0).count()), Component.literal("参与者背包数应为 10"));

        } catch (Exception e) {
            throw ctx.assertionException("协作状态查询测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    // ==================== DatabaseQueryService 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void queryService_getMaterials_returnsCorrectData(GameTestHelper ctx) {
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
            ctx.assertTrue(java.util.Objects.equals(2, materials.size()), Component.literal("应有 2 种材料"));

            // 验证 MaterialEntry 数据
            boolean hasStone = false, hasDiamond = false;
            for (var entry : materials) {
                if ("minecraft:stone".equals(BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString())) {
                    hasStone = true;
                    ctx.assertTrue(java.util.Objects.equals(64L, entry.getCountTotal()), Component.literal("石头数量应为 64"));
                }
                if ("minecraft:diamond".equals(BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString())) {
                    hasDiamond = true;
                    ctx.assertTrue(java.util.Objects.equals(10L, entry.getCountTotal()), Component.literal("钻石数量应为 10"));
                }
            }
            ctx.assertTrue(hasStone, Component.literal("应包含石头"));
            ctx.assertTrue(hasDiamond, Component.literal("应包含钻石"));

            // 测试 schematicExists
            ctx.assertTrue(qs.schematicExists(testId), Component.literal("原理图应存在"));
            ctx.assertFalse(qs.schematicExists("nonexistent"), Component.literal("不存在的原理图应返回 false"));

        } catch (Exception e) {
            throw ctx.assertionException("材料查询测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void queryService_getMaterials_emptySchematic(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        DatabaseQueryService qs = new DatabaseQueryService(db);
        String testId = "gt-empty-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Empty Test", "/test.litematic");

            // 空原理图应返回空列表
            var materials = qs.getMaterials(testId);
            ctx.assertTrue(materials.isEmpty(), Component.literal("空原理图应返回空材料列表"));

        } catch (Exception e) {
            throw ctx.assertionException("空原理图查询测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    // ==================== StagingAreaManager 业务逻辑测试 ====================

    @GameTest(structure = "empty")
    public void stagingArea_addAndRemove(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-staging-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Staging Test", "/test.litematic");

            // 添加备货区
            int areaId = sam.addStagingArea(testId, "minecraft:overworld", "Test Area",
                0, 64, 0, 10, 70, 10);
            ctx.assertTrue(areaId > 0, Component.literal("添加备货区应返回有效 ID"));

            // 验证数据库
            try (QueryResult qr = db.executeQuery(
                "SELECT name, world FROM staging_areas WHERE id = ?", areaId)) {
                ctx.assertTrue(qr.next(), Component.literal("应能查询到备货区"));
                ctx.assertTrue(java.util.Objects.equals("Test Area", qr.getString("name")), Component.literal("名称应匹配"));
            }

            // 验证内存缓存
            var areas = sam.getStagingAreas(testId);
            ctx.assertTrue(java.util.Objects.equals(1, areas.size()), Component.literal("应有 1 个备货区"));
            ctx.assertTrue(java.util.Objects.equals("Test Area", areas.get(0).name()), Component.literal("缓存名称应匹配"));

            // 删除备货区
            sam.removeStagingArea(areaId, testId);
            areas = sam.getStagingAreas(testId);
            ctx.assertTrue(areas.isEmpty(), Component.literal("删除后应无备货区"));

        } catch (Exception e) {
            throw ctx.assertionException("备货区增删测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void stagingArea_multipleAreasForSchematic(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-multi-area-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Multi Area Test", "/test.litematic");

            sam.addStagingArea(testId, "minecraft:overworld", "Area 1", 0, 64, 0, 10, 70, 10);
            sam.addStagingArea(testId, "minecraft:overworld", "Area 2", 100, 64, 100, 110, 70, 110);

            var areas = sam.getStagingAreas(testId);
            ctx.assertTrue(java.util.Objects.equals(2, areas.size()), Component.literal("应有 2 个备货区"));

            // 删除一个，另一个不受影响
            sam.removeStagingArea(areas.get(0).id(), testId);
            areas = sam.getStagingAreas(testId);
            ctx.assertTrue(java.util.Objects.equals(1, areas.size()), Component.literal("删除一个后应剩 1 个"));

        } catch (Exception e) {
            throw ctx.assertionException("多备货区测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void stagingArea_cascadeDeleteOnSchematic(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = SyncMaterial.getServerStagingAreaManager();
        String testId = "gt-cascade-staging-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Cascade Staging", "/test.litematic");

            sam.addStagingArea(testId, "minecraft:overworld", "Area 1", 0, 64, 0, 10, 70, 10);

            // 删除原理图，备货区应级联删除
            db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);

            try (QueryResult qr = db.executeQuery(
                "SELECT COUNT(*) FROM staging_areas WHERE schematic_id = ?", testId)) {
                ctx.assertTrue(qr.next(), Component.literal("应能查询级联删除结果"));
                ctx.assertTrue(java.util.Objects.equals(0, qr.getInt(1)), Component.literal("级联删除后应无备货区"));
            }

            // 注意：直接 SQL DELETE 不会触发 StagingAreaManager 的内存缓存刷新
            // 内存缓存一致性由 StagingAreaManager 自身的方法保证，此处只验证数据库级联
        } catch (Exception e) {
            throw ctx.assertionException("备货区级联删除测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    // ==================== Owner/Deputy 权限逻辑测试 ====================

    @GameTest(structure = "empty")
    public void owner_isMainOwner(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gt-owner-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                testId, "Owner Test", "/test.litematic", "Owner1");

            ctx.assertTrue(db.isMainOwner(testId, "Owner1"), Component.literal("上传者应为主负责人"));
            ctx.assertFalse(db.isMainOwner(testId, "OtherPlayer"), Component.literal("非上传者不应是主负责人"));

            // getUploadedBy 应返回上传者
            ctx.assertTrue(java.util.Objects.equals("Owner1", db.getUploadedBy(testId)), Component.literal("上传者应为 Owner1"));
        } catch (Exception e) {
            throw ctx.assertionException("主负责人测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void owner_addAndRemoveDeputy(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gt-deputy-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                testId, "Deputy Test", "/test.litematic", "Owner1");

            // 添加副负责人
            db.addDeputyOwner(testId, "Deputy1");
            db.addDeputyOwner(testId, "Deputy2");

            var deputies = db.getDeputyOwners(testId);
            ctx.assertTrue(java.util.Objects.equals(2, deputies.size()), Component.literal("应有 2 个副负责人"));

            // isOwner：主负责人和副负责人都是 owner
            ctx.assertTrue(db.isOwner(testId, "Owner1"), Component.literal("主负责人应是 owner"));
            ctx.assertTrue(db.isOwner(testId, "Deputy1"), Component.literal("副负责人应是 owner"));
            ctx.assertFalse(db.isOwner(testId, "RandomPlayer"), Component.literal("普通玩家不应是 owner"));

            // 移除副负责人
            db.removeDeputyOwner(testId, "Deputy1");
            deputies = db.getDeputyOwners(testId);
            ctx.assertTrue(java.util.Objects.equals(1, deputies.size()), Component.literal("移除后应剩 1 个副负责人"));
            ctx.assertFalse(db.isOwner(testId, "Deputy1"), Component.literal("被移除的副负责人不应再是 owner"));
        } catch (Exception e) {
            throw ctx.assertionException("副负责人测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void owner_transferOwnership(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gt-transfer-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                testId, "Transfer Test", "/test.litematic", "Owner1");

            // 转让负责人
            db.transferOwnership(testId, "Owner2");

            // 原负责人不再是主负责人
            ctx.assertFalse(db.isMainOwner(testId, "Owner1"), Component.literal("原负责人不应再是主负责人"));
            ctx.assertFalse(db.isOwner(testId, "Owner1"), Component.literal("原负责人不应再是 owner"));

            // 新负责人是主负责人
            ctx.assertTrue(db.isMainOwner(testId, "Owner2"), Component.literal("新负责人应是主负责人"));
            ctx.assertTrue(java.util.Objects.equals("Owner2", db.getUploadedBy(testId)), Component.literal("上传者应变为 Owner2"));
        } catch (Exception e) {
            throw ctx.assertionException("负责人转让测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void owner_toggleSelfClaim(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        String testId = "gt-claim-" + System.currentTimeMillis();

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by, allow_self_claim) VALUES (?, ?, ?, ?, ?)",
                testId, "Claim Test", "/test.litematic", "Owner1", 1);

            // 默认允许自行认领
            ctx.assertTrue(db.getAllowSelfClaim(testId), Component.literal("默认应允许自行认领"));

            // 关闭自行认领
            db.setAllowSelfClaim(testId, false);
            ctx.assertFalse(db.getAllowSelfClaim(testId), Component.literal("关闭后不应允许自行认领"));

            // 重新开启
            db.setAllowSelfClaim(testId, true);
            ctx.assertTrue(db.getAllowSelfClaim(testId), Component.literal("重新开启后应允许自行认领"));
        } catch (Exception e) {
            throw ctx.assertionException("自行认领开关测试失败: " + e.getMessage());
        } finally {
            try { db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId); } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    // ==================== InventoryWatcher.getShulkerContents 测试 ====================

    @GameTest(structure = "empty")
    public void shulkerContents_extractsItems(GameTestHelper ctx) {
        // 创建一个装有物品的潜影盒
        ItemStack shulker = new ItemStack(Blocks.DYED_SHULKER_BOX.purple());
        ItemContainerContents contents = ItemContainerContents.fromItems(List.of(
            new ItemStack(Items.DIAMOND, 64),
            new ItemStack(Items.STONE, 32)
        ));
        shulker.set(DataComponents.CONTAINER, contents);

        var result = InventoryWatcher.getShulkerContents(shulker);
        ctx.assertTrue(java.util.Objects.equals(2, result.size()), Component.literal("潜影盒应有 2 种物品"));

        boolean hasDiamond = false, hasStone = false;
        for (var item : result) {
            if (item.getItem() == Items.DIAMOND) {
                hasDiamond = true;
                ctx.assertTrue(java.util.Objects.equals(64, item.getCount()), Component.literal("钻石数量应为 64"));
            }
            if (item.getItem() == Items.STONE) {
                hasStone = true;
                ctx.assertTrue(java.util.Objects.equals(32, item.getCount()), Component.literal("石头数量应为 32"));
            }
        }
        ctx.assertTrue(hasDiamond, Component.literal("应包含钻石"));
        ctx.assertTrue(hasStone, Component.literal("应包含石头"));
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void shulkerContents_emptyShulker_returnsEmpty(GameTestHelper ctx) {
        ItemStack shulker = new ItemStack(Blocks.DYED_SHULKER_BOX.purple());
        var result = InventoryWatcher.getShulkerContents(shulker);
        ctx.assertTrue(result.isEmpty(), Component.literal("空潜影盒应返回空列表"));
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void shulkerContents_nonShulker_returnsEmpty(GameTestHelper ctx) {
        ItemStack stone = new ItemStack(Items.STONE);
        var result = InventoryWatcher.getShulkerContents(stone);
        ctx.assertTrue(result.isEmpty(), Component.literal("非潜影盒应返回空列表"));
        ctx.succeed();
    }
}
