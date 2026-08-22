package net.syncmaterial.syncmaterial.gametest;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;

/**
 * StagingAreaManager 容器扫描/聚合 GameTest。
 * 在真实世界中摆箱子 → 建区域 → 扫描 → 断言数据库统计，
 * 覆盖 rescanStagingArea / rescanWarehouseAndMarkChunks / 脏容器管线三条路径。
 */
public class StagingAreaScanGameTest {

    /** 在测试结构 (1,1,1) 相对位置放一个箱子并写入指定物品，返回绝对坐标 */
    private BlockPos placeChestWith(TestContext ctx, ItemStack... stacks) {
        BlockPos relative = new BlockPos(1, 1, 1);
        ctx.setBlockState(relative, Blocks.CHEST.getDefaultState());
        ChestBlockEntity chest = ctx.getBlockEntity(relative, ChestBlockEntity.class);
        if (chest == null) {
            throw ctx.createError("箱子方块实体未创建");
        }
        for (int i = 0; i < stacks.length; i++) {
            chest.setStack(i, stacks[i]);
        }
        return ctx.getAbsolutePos(relative);
    }

    private StagingAreaManager manager() {
        return SyncMaterial.getServerStagingAreaManager();
    }

    // ==================== 备货区同步重扫 ====================

    @GameTest(structure = "empty")
    public void stagingArea_rescanChestContents(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-scan-" + System.currentTimeMillis();
        int areaId = -1;

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Scan Test", "/test.litematic");

            BlockPos chestPos = placeChestWith(ctx,
                new ItemStack(Items.STONE, 32),
                new ItemStack(Items.STONE, 16),
                new ItemStack(Items.DIAMOND, 5));

            areaId = sam.addStagingArea(testId, "minecraft:overworld", "Scan Area",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);
            ctx.assertTrue(areaId > 0, Text.literal("备货区应创建成功"));

            sam.rescanStagingArea(areaId);

            // 同一箱内两叠石头应求和为 48
            ctx.assertEquals(48, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Text.literal("石头总数应为 48"));
            ctx.assertEquals(5, sam.getStagingCountForMaterial(testId, "minecraft:diamond"),
                Text.literal("钻石数应为 5"));
            ctx.assertEquals(0, sam.getStagingCountForMaterial(testId, "minecraft:dirt"),
                Text.literal("未放入的物品应为 0"));

        } catch (Exception e) {
            throw ctx.createError("备货区扫描测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.removeBlock(new BlockPos(1, 1, 1));
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingArea_shulkerInChest_counted(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-shulker-" + System.currentTimeMillis();
        int areaId = -1;

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Shulker Test", "/test.litematic");

            // 装有 64 钻石的潜影盒
            ItemStack shulker = new ItemStack(Items.PURPLE_SHULKER_BOX);
            shulker.set(DataComponentTypes.CONTAINER,
                ContainerComponent.fromStacks(List.of(new ItemStack(Items.DIAMOND, 64))));

            BlockPos chestPos = placeChestWith(ctx, shulker);

            areaId = sam.addStagingArea(testId, "minecraft:overworld", "Shulker Area",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);

            sam.rescanStagingArea(areaId);

            // 潜影盒内容物应递归计入
            ctx.assertEquals(64, sam.getStagingCountForMaterial(testId, "minecraft:diamond"),
                Text.literal("潜影盒内钻石应计入"));
            // 潜影盒本身也计一件
            ctx.assertEquals(1, sam.getStagingCountForMaterial(testId, "minecraft:purple_shulker_box"),
                Text.literal("潜影盒本身应计 1 件"));

        } catch (Exception e) {
            throw ctx.createError("潜影盒递归统计测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.removeBlock(new BlockPos(1, 1, 1));
        }
        ctx.complete();
    }

    @GameTest(structure = "empty")
    public void stagingArea_chestRemoved_countDrops(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-remove-" + System.currentTimeMillis();
        int areaId = -1;

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Remove Test", "/test.litematic");

            BlockPos chestPos = placeChestWith(ctx, new ItemStack(Items.STONE, 32));

            areaId = sam.addStagingArea(testId, "minecraft:overworld", "Remove Area",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);

            sam.rescanStagingArea(areaId);
            ctx.assertEquals(32, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Text.literal("拆除前石头应为 32"));

            // 拆除箱子后重扫，统计应归零
            ctx.removeBlock(new BlockPos(1, 1, 1));
            sam.rescanStagingArea(areaId);

            ctx.assertEquals(0, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Text.literal("箱子拆除后统计应归零"));

        } catch (Exception e) {
            throw ctx.createError("箱子移除统计测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
        }
        ctx.complete();
    }

    // ==================== 仓库扫描聚合 ====================

    @GameTest(structure = "empty")
    public void warehouse_scanAggregates(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-whscan-" + System.currentTimeMillis();
        int warehouseId = -1;

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Warehouse Scan Test", "/test.litematic");

            BlockPos chestPos = placeChestWith(ctx, new ItemStack(Items.IRON_INGOT, 7));

            warehouseId = sam.addWarehouse("Test Warehouse", "minecraft:overworld",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);
            ctx.assertTrue(warehouseId > 0, Text.literal("仓库应创建成功"));

            // 原理图引用仓库后，仓库库存才计入该原理图的统计
            sam.addWarehouseReference(testId, warehouseId);

            sam.rescanWarehouseAndMarkChunks(warehouseId);

            ctx.assertEquals(7, sam.getWarehouseCountForMaterial(testId, "minecraft:iron_ingot"),
                Text.literal("仓库铁锭数应为 7"));
            ctx.assertEquals(0, sam.getStagingCountForMaterial(testId, "minecraft:iron_ingot"),
                Text.literal("仓库物品不应计入备货区统计"));

        } catch (Exception e) {
            throw ctx.createError("仓库扫描测试失败: " + e.getMessage());
        } finally {
            try {
                if (warehouseId > 0) sam.deleteWarehouse(warehouseId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.removeBlock(new BlockPos(1, 1, 1));
        }
        ctx.complete();
    }

    // ==================== 脏容器管线（schedule → process → 延迟一 tick 生效） ====================

    @GameTest(structure = "empty")
    public void warehouse_dirtyPipeline_updatesContainerDetail(TestContext ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-dirty-" + System.currentTimeMillis();
        final int warehouseId[] = {-1};

        // 清理必须在断言回调内执行：waitAndRun 是非阻塞的，
        // 测试方法继续往下走会先于 tick 10 的断言删除 warehouse 明细
        Runnable cleanup = () -> {
            try {
                if (warehouseId[0] > 0) sam.deleteWarehouse(warehouseId[0]);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.removeBlock(new BlockPos(1, 1, 1));
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Dirty Pipeline Test", "/test.litematic");

            BlockPos chestPos = placeChestWith(ctx, new ItemStack(Items.GOLD_INGOT, 3));

            warehouseId[0] = sam.addWarehouse("Dirty Warehouse", "minecraft:overworld",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);
            sam.addWarehouseReference(testId, warehouseId[0]);

            // 手动驱动脏标记 + 批处理（生产中由 markDirty Mixin + 每 4 tick 定时器触发）
            sam.scheduleContainerScan(chestPos, (ServerWorld) ctx.getWorld());
            sam.processDirtyContainers();

            // 实际处理经 server.execute 延迟到下一 tick，等待后再断言
            final int whId = warehouseId[0];
            ctx.waitAndRun(10L, () -> {
                boolean found = false;
                try {
                    var entries = sam.getContainerEntriesForWarehouses(Set.of(whId));
                    for (var entry : entries) {
                        if (entry.posX() == chestPos.getX() && entry.posY() == chestPos.getY()
                            && entry.posZ() == chestPos.getZ()
                            && entry.itemIds().contains("minecraft:gold_ingot")) {
                            found = true;
                        }
                    }
                } catch (Exception e) {
                    cleanup.run();
                    throw ctx.createError("脏管线断言失败: " + e.getMessage());
                }
                cleanup.run();
                if (!found) {
                    throw ctx.createError("脏管线断言失败: container_inventory 应记录该箱子的金锭明细");
                }
                ctx.complete();
            });

        } catch (Exception e) {
            cleanup.run();
            throw ctx.createError("脏容器管线测试失败: " + e.getMessage());
        }
    }
}
