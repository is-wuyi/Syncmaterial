package net.syncmaterial.syncmaterial.gametest;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
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
    private BlockPos placeChestWith(GameTestHelper ctx, ItemStack... stacks) {
        BlockPos relative = new BlockPos(1, 1, 1);
        ctx.setBlock(relative, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = ctx.getBlockEntity(relative, ChestBlockEntity.class);
        if (chest == null) {
            throw ctx.assertionException("箱子方块实体未创建");
        }
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        return ctx.absolutePos(relative);
    }

    private StagingAreaManager manager() {
        return SyncMaterial.getServerStagingAreaManager();
    }

    // ==================== 备货区同步重扫 ====================

    @GameTest(structure = "empty")
    public void stagingArea_rescanChestContents(GameTestHelper ctx) {
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
            ctx.assertTrue(areaId > 0, Component.literal("备货区应创建成功"));

            sam.rescanStagingArea(areaId);

            // 同一箱内两叠石头应求和为 48
            GameTestAssertions.assertEquals(ctx, 48, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Component.literal("石头总数应为 48"));
            GameTestAssertions.assertEquals(ctx, 5, sam.getStagingCountForMaterial(testId, "minecraft:diamond"),
                Component.literal("钻石数应为 5"));
            GameTestAssertions.assertEquals(ctx, 0, sam.getStagingCountForMaterial(testId, "minecraft:dirt"),
                Component.literal("未放入的物品应为 0"));

        } catch (Exception e) {
            throw ctx.assertionException("备货区扫描测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void stagingArea_shulkerInChest_counted(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-shulker-" + System.currentTimeMillis();
        int areaId = -1;

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Shulker Test", "/test.litematic");

            // 装有 64 钻石的潜影盒
            ItemStack shulker = new ItemStack(Blocks.DYED_SHULKER_BOX.purple());
            shulker.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));

            BlockPos chestPos = placeChestWith(ctx, shulker);

            areaId = sam.addStagingArea(testId, "minecraft:overworld", "Shulker Area",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);

            sam.rescanStagingArea(areaId);

            // 潜影盒内容物应递归计入
            GameTestAssertions.assertEquals(ctx, 64, sam.getStagingCountForMaterial(testId, "minecraft:diamond"),
                Component.literal("潜影盒内钻石应计入"));
            // 潜影盒本身也计一件
            GameTestAssertions.assertEquals(ctx, 1, sam.getStagingCountForMaterial(testId, "minecraft:purple_shulker_box"),
                Component.literal("潜影盒本身应计 1 件"));

        } catch (Exception e) {
            throw ctx.assertionException("潜影盒递归统计测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        }
        ctx.succeed();
    }

    @GameTest(structure = "empty")
    public void stagingArea_chestRemoved_countDrops(GameTestHelper ctx) {
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
            GameTestAssertions.assertEquals(ctx, 32, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Component.literal("拆除前石头应为 32"));

            // 拆除箱子后重扫，统计应归零
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
            sam.rescanStagingArea(areaId);

            GameTestAssertions.assertEquals(ctx, 0, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Component.literal("箱子拆除后统计应归零"));

        } catch (Exception e) {
            throw ctx.assertionException("箱子移除统计测试失败: " + e.getMessage());
        } finally {
            try {
                if (areaId > 0) sam.removeStagingArea(areaId, testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
        }
        ctx.succeed();
    }

    // ==================== 仓库扫描聚合 ====================

    @GameTest(structure = "empty")
    public void warehouse_scanAggregates(GameTestHelper ctx) {
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
            ctx.assertTrue(warehouseId > 0, Component.literal("仓库应创建成功"));

            // 原理图引用仓库后，仓库库存才计入该原理图的统计
            sam.addWarehouseReference(testId, warehouseId);

            sam.rescanWarehouseAndMarkChunks(warehouseId);

            GameTestAssertions.assertEquals(ctx, 7, sam.getWarehouseCountForMaterial(testId, "minecraft:iron_ingot"),
                Component.literal("仓库铁锭数应为 7"));
            GameTestAssertions.assertEquals(ctx, 0, sam.getStagingCountForMaterial(testId, "minecraft:iron_ingot"),
                Component.literal("仓库物品不应计入备货区统计"));

        } catch (Exception e) {
            throw ctx.assertionException("仓库扫描测试失败: " + e.getMessage());
        } finally {
            try {
                if (warehouseId > 0) sam.deleteWarehouse(warehouseId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        }
        ctx.succeed();
    }

    // ==================== 脏容器管线（schedule → process → 延迟一 tick 生效） ====================

    @GameTest(structure = "empty")
    public void warehouse_dirtyPipeline_updatesContainerDetail(GameTestHelper ctx) {
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
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
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
            sam.scheduleContainerScan(chestPos, ctx.getLevel());
            sam.processDirtyContainers();

            // 实际处理经 server.execute 延迟到下一 tick，等待后再断言
            final int whId = warehouseId[0];
            ctx.runAfterDelay(10L, () -> {
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
                    throw ctx.assertionException("脏管线断言失败: " + e.getMessage());
                }
                cleanup.run();
                if (!found) {
                    throw ctx.assertionException("脏管线断言失败: container_inventory 应记录该箱子的金锭明细");
                }
                ctx.succeed();
            });

        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("脏容器管线测试失败: " + e.getMessage());
        }
    }

    // ==================== 全自动管线（Mixin 感知 → 定时批处理 → 统计更新） ====================

    @GameTest(structure = "empty")
    public void stagingArea_autoPipeline_detectsItemAddition(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-auto-" + System.currentTimeMillis();
        final int[] areaId = {-1};

        // 与脏管线测试同理：waitAndRun 非阻塞，清理必须放进断言回调
        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Auto Pipeline Test", "/test.litematic");

            // 先圈区域，再放空箱子
            BlockPos chestPos = placeChestWith(ctx);
            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Auto Area",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);

            // 往箱子里放物品：BlockEntity.markDirty → Mixin 自动调度 →
            // 每 4 tick 的服务器钩子批处理 → 重扫统计。全程不手动调用任何扫描方法。
            ChestBlockEntity chest = ctx.getBlockEntity(new BlockPos(1, 1, 1), ChestBlockEntity.class);
            if (chest == null) {
                cleanup.run();
                throw ctx.assertionException("箱子方块实体未创建");
            }
            chest.setItem(0, new ItemStack(Items.STONE, 32));

            ctx.runAfterDelay(20L, () -> {
                int count = sam.getStagingCountForMaterial(testId, "minecraft:stone");
                cleanup.run();
                if (count != 32) {
                    throw ctx.assertionException("自动管线未在 20 tick 内更新统计（期望石头 32，实际 " + count + "）");
                }
                ctx.succeed();
            });

        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("自动管线测试失败: " + e.getMessage());
        }
    }

    // ==================== 跨区块区域的区块加载补扫 ====================

    /**
     * 场景：区域跨两个区块，箱子里物品早已放好（服务器重启后区块逐一加载补扫、
     * 或圈区时部分区块未加载的路径）。两个区块都补扫后，统计应包含两个区块的物品。
     * （曾暴露"单区块结果全量覆盖区域统计"的 bug，修复为增量合并 + 完成后全量校正）
     */
    @GameTest(structure = "empty")
    public void stagingArea_chunkLoadScan_crossChunk_keepsBothChunksItems(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-cross-" + System.currentTimeMillis();
        final int[] areaId = {-1};
        final BlockPos[] absBHolder = {null};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
            if (absBHolder[0] != null) ctx.getLevel().removeBlock(absBHolder[0], false);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Cross Chunk Test", "/test.litematic");

            ServerLevel world = ctx.getLevel();
            BlockPos absA = placeChestWith(ctx, new ItemStack(Items.STONE, 32));

            // 箱子 B 放到相邻区块（x 方向下一个区块的起点）。
            // 不用 GameTestHelper 的相对坐标换算（getRelativePos 与 getAbsolutePos 不互逆），
            // 直接用世界 API 放置，位置确定
            int bx = ((absA.getX() >> 4) + 1) << 4;
            BlockPos absB = new BlockPos(bx, absA.getY(), absA.getZ());
            absBHolder[0] = absB;
            if (world.getChunkSource().getChunkNow(bx >> 4, absB.getZ() >> 4) == null) {
                cleanup.run();
                throw ctx.assertionException("相邻区块未加载，测试环境不满足");
            }
            world.setBlock(absB, Blocks.CHEST.defaultBlockState(), 3);
            ChestBlockEntity chestB = (ChestBlockEntity) world.getBlockEntity(absB);
            if (chestB == null) {
                cleanup.run();
                throw ctx.assertionException("箱子 B 方块实体未创建");
            }
            chestB.setItem(0, new ItemStack(Items.DIAMOND, 5));

            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Cross Chunk Area",
                Math.min(absA.getX(), absB.getX()) - 1, absA.getY() - 1, absA.getZ() - 1,
                Math.max(absA.getX(), absB.getX()) + 1, absA.getY() + 1, absA.getZ() + 1);

            // 逐个区块补扫（模拟区块加载事件的顺序到达）
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absA.getX() >> 4, absA.getZ() >> 4), world);
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absB.getX() >> 4, absB.getZ() >> 4), world);

            // 断言必须先于清理：统计查询按原理图 JOIN，清理后永远是 0
            GameTestAssertions.assertEquals(ctx, 32, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Component.literal("区块 A 的石头应保留（跨区块区域不应被单区块结果覆盖）"));
            GameTestAssertions.assertEquals(ctx, 5, sam.getStagingCountForMaterial(testId, "minecraft:diamond"),
                Component.literal("区块 B 的钻石应计入"));
            cleanup.run();
            ctx.succeed();

        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("跨区块补扫测试失败: " + e.getMessage());
        }
    }

    // ==================== 数据新鲜度警告（区域未初始化 = 本次启动后未扫完全部区块） ====================

    /** 构造跨两区块的区域（箱子 A 所在区块 + 相邻区块），返回相邻区块起点 */
    private int nextChunkStartX(BlockPos absA) {
        return ((absA.getX() >> 4) + 1) << 4;
    }

    @GameTest(structure = "empty")
    public void stagingArea_partialChunks_freshnessWarningUntilComplete(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-fresh-" + System.currentTimeMillis();
        final int[] areaId = {-1};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Freshness Test", "/test.litematic");
            db.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                testId, "minecraft:stone", 100);
            int matId;
            try (var qr = db.executeQuery("SELECT last_insert_rowid()")) {
                qr.next();
                matId = qr.getInt(1);
            }

            ServerLevel world = ctx.getLevel();
            BlockPos absA = ctx.absolutePos(new BlockPos(1, 1, 1));
            int bx = nextChunkStartX(absA);
            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Fresh Area",
                absA.getX(), absA.getY(), absA.getZ(), bx, absA.getY(), absA.getZ());

            var cm = new net.syncmaterial.syncmaterial.server.CollaborationManager(db);
            cm.setStagingAreaManager(sam);

            // 只补扫第一个区块：区域未初始化，状态里应带过时警告
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absA.getX() >> 4, absA.getZ() >> 4), world);
            var status1 = cm.getCollaborationStatus(testId, matId);
            ctx.assertTrue(status1 != null, Component.literal("状态不应为 null"));
            boolean warned1 = status1.freshnessInfo().stream()
                .anyMatch(f -> "staging_area".equals(f.areaType()) && f.areaId() == areaId[0]);
            ctx.assertTrue(warned1, Component.literal("只扫了部分区块时应显示数据过时警告"));

            // 补扫第二个区块：区域初始化完成，警告应消失
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(bx >> 4, absA.getZ() >> 4), world);
            var status2 = cm.getCollaborationStatus(testId, matId);
            ctx.assertTrue(status2.freshnessInfo().stream().noneMatch(f -> f.areaId() == areaId[0]),
                Component.literal("全部区块扫完后过时警告应消失"));

            cleanup.run();
            ctx.succeed();
        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("新鲜度警告测试失败: " + e.getMessage());
        }
    }

    @GameTest(structure = "empty")
    public void stagingArea_resize_resetsInitialization(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-resize-" + System.currentTimeMillis();
        final int[] areaId = {-1};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Resize Test", "/test.litematic");

            ServerLevel world = ctx.getLevel();
            BlockPos absA = placeChestWith(ctx, new ItemStack(Items.STONE, 8));
            // 初始区域 1x1x1（单区块），重扫后应完成初始化
            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Resize Area",
                absA.getX(), absA.getY(), absA.getZ(), absA.getX(), absA.getY(), absA.getZ());
            sam.rescanStagingArea(areaId[0]);
            ctx.assertTrue(sam.isStagingAreaInitialized(areaId[0]),
                Component.literal("单区块区域重扫后应已初始化"));
            // 扩大到相邻区块：必须回到未初始化（否则新领土永远不会被补扫，也不会有警告）
            int bx = nextChunkStartX(absA);
            sam.updateStagingArea(areaId[0], testId, "Resize Area",
                absA.getX(), absA.getY(), absA.getZ(), bx, absA.getY(), absA.getZ());
            ctx.assertFalse(sam.isStagingAreaInitialized(areaId[0]),
                Component.literal("区域扩大后应重置为未初始化"));

            // 补扫两个区块后应重新完成初始化
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absA.getX() >> 4, absA.getZ() >> 4), world);
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(bx >> 4, absA.getZ() >> 4), world);
            ctx.assertTrue(sam.isStagingAreaInitialized(areaId[0]),
                Component.literal("扩大后的两个区块都扫过后应重新初始化"));

            cleanup.run();
            ctx.succeed();
        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("区域修改重置测试失败: " + e.getMessage());
        }
    }

    @GameTest(structure = "empty")
    public void areaAndWarehouse_remove_clearsInitState(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-rminit-" + System.currentTimeMillis();
        final int[] areaId = {-1};
        final int[] warehouseId = {-1};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                if (warehouseId[0] > 0) sam.deleteWarehouse(warehouseId[0]);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Remove Init Test", "/test.litematic");

            ServerLevel world = ctx.getLevel();
            BlockPos absA = placeChestWith(ctx, new ItemStack(Items.STONE, 8));

            // 备货区：初始化后删除，状态应清理
            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Rm Area",
                absA.getX(), absA.getY(), absA.getZ(), absA.getX(), absA.getY(), absA.getZ());
            sam.rescanStagingArea(areaId[0]);
            ctx.assertTrue(sam.isStagingAreaInitialized(areaId[0]), Component.literal("备货区应已初始化"));
            int removedAreaId = areaId[0];
            sam.removeStagingArea(removedAreaId, testId);
            areaId[0] = -1;
            ctx.assertFalse(sam.isStagingAreaInitialized(removedAreaId),
                Component.literal("删除后备货区初始化状态应清理"));

            // 仓库：初始化后删除，状态应清理
            warehouseId[0] = sam.addWarehouse("Rm Warehouse", "minecraft:overworld",
                absA.getX(), absA.getY(), absA.getZ(), absA.getX(), absA.getY(), absA.getZ());
            sam.rescanWarehouseAndMarkChunks(warehouseId[0]);
            ctx.assertTrue(sam.isWarehouseInitialized(warehouseId[0]), Component.literal("仓库应已初始化"));
            sam.deleteWarehouse(warehouseId[0]);
            ctx.assertFalse(sam.isWarehouseInitialized(warehouseId[0]),
                Component.literal("删除后仓库初始化状态应清理"));
            warehouseId[0] = -1;

            cleanup.run();
            ctx.succeed();
        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("删除清理测试失败: " + e.getMessage());
        }
    }

    /**
     * 回归：备货区和仓库的自增 ID 各自独立，同号时初始化状态不得互相串。
     * （修复前：备货区 1 初始化后，从未扫描的仓库 1 也被误判为已初始化）
     */
    @GameTest(structure = "empty")
    public void warehouse_idCollision_independentFromStagingArea(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-collide-" + System.currentTimeMillis();
        final int[] areaId = {-1};
        final int[] warehouseId = {-1};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                if (warehouseId[0] > 0) sam.deleteWarehouse(warehouseId[0]);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Collision Test", "/test.litematic");

            BlockPos absA = placeChestWith(ctx, new ItemStack(Items.STONE, 8));
            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Collide Area",
                absA.getX(), absA.getY(), absA.getZ(), absA.getX(), absA.getY(), absA.getZ());
            sam.rescanStagingArea(areaId[0]);
            ctx.assertTrue(sam.isStagingAreaInitialized(areaId[0]), Component.literal("备货区应已初始化"));

            // 用裸 SQL 插入一个与备货区同号的仓库（两张表自增独立，实际很容易同号）
            db.executeUpdate(
                "INSERT INTO warehouses (id, name, world) VALUES (?, ?, ?)",
                areaId[0], "Collide Warehouse", "minecraft:overworld");
            warehouseId[0] = areaId[0];
            sam.loadWarehousesFromDb();

            ctx.assertFalse(sam.isWarehouseInitialized(warehouseId[0]),
                Component.literal("同号仓库从未扫描，不应因同号备货区已初始化而被误判"));

            cleanup.run();
            ctx.succeed();
        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("ID 碰撞回归测试失败: " + e.getMessage());
        }
    }

    // ==================== 仓库侧的跨区块补扫（与备货区同机制的镜像验证） ====================

    @GameTest(structure = "empty")
    public void warehouse_chunkLoadScan_crossChunk_keepsBothChunksItems(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-whcross-" + System.currentTimeMillis();
        final int[] warehouseId = {-1};
        final BlockPos[] absBHolder = {null};

        Runnable cleanup = () -> {
            try {
                if (warehouseId[0] > 0) sam.deleteWarehouse(warehouseId[0]);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
            if (absBHolder[0] != null) ctx.getLevel().removeBlock(absBHolder[0], false);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Warehouse Cross Chunk Test", "/test.litematic");

            ServerLevel world = ctx.getLevel();
            BlockPos absA = placeChestWith(ctx, new ItemStack(Items.STONE, 32));

            int bx = nextChunkStartX(absA);
            BlockPos absB = new BlockPos(bx, absA.getY(), absA.getZ());
            absBHolder[0] = absB;
            if (world.getChunkSource().getChunkNow(bx >> 4, absB.getZ() >> 4) == null) {
                cleanup.run();
                throw ctx.assertionException("相邻区块未加载，测试环境不满足");
            }
            world.setBlock(absB, Blocks.CHEST.defaultBlockState(), 3);
            ChestBlockEntity chestB = (ChestBlockEntity) world.getBlockEntity(absB);
            if (chestB == null) {
                cleanup.run();
                throw ctx.assertionException("箱子 B 方块实体未创建");
            }
            chestB.setItem(0, new ItemStack(Items.IRON_INGOT, 7));

            warehouseId[0] = sam.addWarehouse("Cross Chunk Warehouse", "minecraft:overworld",
                Math.min(absA.getX(), absB.getX()) - 1, absA.getY() - 1, absA.getZ() - 1,
                Math.max(absA.getX(), absB.getX()) + 1, absA.getY() + 1, absA.getZ() + 1);
            sam.addWarehouseReference(testId, warehouseId[0]);

            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absA.getX() >> 4, absA.getZ() >> 4), world);
            sam.scanChunkForContainerAreas(
                world.getChunkSource().getChunkNow(absB.getX() >> 4, absB.getZ() >> 4), world);

            GameTestAssertions.assertEquals(ctx, 32, sam.getWarehouseCountForMaterial(testId, "minecraft:stone"),
                Component.literal("仓库跨区块：区块 A 的石头应保留"));
            GameTestAssertions.assertEquals(ctx, 7, sam.getWarehouseCountForMaterial(testId, "minecraft:iron_ingot"),
                Component.literal("仓库跨区块：区块 B 的铁锭应计入"));
            cleanup.run();
            ctx.succeed();

        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("仓库跨区块补扫测试失败: " + e.getMessage());
        }
    }

    // ==================== 新建仓库立即初始化 ====================

    /**
     * 复现实机问题：玩家站在已加载区块里圈选一个已有物品的箱子作为仓库。
     * 不调用 scheduleContainerScan/processDirtyContainers（即不做“取出再放回”动作），
     * 仅执行新建仓库后 handler 应调用的同步重扫路径。
     */
    @GameTest(structure = "empty")
    public void warehouse_createdInLoadedChunk_immediatelyInitializedAndCounted(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-wh-create-" + System.currentTimeMillis();
        final int[] warehouseId = {-1};

        Runnable cleanup = () -> {
            try {
                if (warehouseId[0] > 0) sam.deleteWarehouse(warehouseId[0]);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ctx.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Immediate Warehouse Test", "/test.litematic");

            BlockPos chestPos = placeChestWith(ctx,
                new ItemStack(Items.GOLD_INGOT, 7),
                new ItemStack(Items.DIAMOND, 3));

            warehouseId[0] = sam.addWarehouse("Immediate Warehouse", "minecraft:overworld",
                chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);
            sam.addWarehouseReference(testId, warehouseId[0]);

            // 生产中的 ADD_WAREHOUSE handler 必须立即调用此方法；测试刻意不走脏容器管线
            sam.rescanWarehouseAndMarkChunks(warehouseId[0]);

            ctx.assertTrue(sam.isWarehouseInitialized(warehouseId[0]),
                Component.literal("已加载区块中新建的仓库应立即完成初始化，不应显示数据可能过时"));
            GameTestAssertions.assertEquals(ctx, 7, sam.getWarehouseCountForMaterial(testId, "minecraft:gold_ingot"),
                Component.literal("新建仓库应立即统计已有的 7 个金锭，无需取出再放回"));
            GameTestAssertions.assertEquals(ctx, 3, sam.getWarehouseCountForMaterial(testId, "minecraft:diamond"),
                Component.literal("新建仓库应立即统计已有的 3 个钻石"));

            var containers = sam.getContainerEntriesForWarehouses(Set.of(warehouseId[0]));
            boolean detailPresent = containers.stream().anyMatch(entry ->
                entry.posX() == chestPos.getX()
                    && entry.posY() == chestPos.getY()
                    && entry.posZ() == chestPos.getZ()
                    && entry.itemIds().contains("minecraft:gold_ingot")
                    && entry.itemIds().contains("minecraft:diamond"));
            ctx.assertTrue(detailPresent,
                Component.literal("同步重扫应同时建立 container_inventory 明细，保证取货模式箱子高亮立即可用"));

            cleanup.run();
            ctx.succeed();
        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("新建仓库立即初始化测试失败: " + e.getMessage());
        }
    }

    // ==================== 双箱（大箱子）聚合 ====================

    @GameTest(structure = "empty")
    public void stagingArea_doubleChest_bothHalvesCounted(GameTestHelper ctx) {
        SchematicDatabase db = SyncMaterial.getSharedDatabase();
        StagingAreaManager sam = manager();
        String testId = "gt-dchest-" + System.currentTimeMillis();
        final int[] areaId = {-1};

        Runnable cleanup = () -> {
            try {
                if (areaId[0] > 0) sam.removeStagingArea(areaId[0], testId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", testId);
            } catch (Exception ignored) {}
            ServerLevel world = ctx.getLevel();
            BlockPos absA = ctx.absolutePos(new BlockPos(1, 1, 1));
            world.removeBlock(absA, false);
            world.removeBlock(absA.east(), false);
        };

        try {
            db.executeUpdate("INSERT INTO schematics (id, name, file_path) VALUES (?, ?, ?)",
                testId, "Double Chest Test", "/test.litematic");

            ServerLevel world = ctx.getLevel();
            BlockPos absA = ctx.absolutePos(new BlockPos(1, 1, 1));
            BlockPos absB = absA.east();
            var facing = net.minecraft.core.Direction.NORTH;
            world.setBlock(absA, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT)
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing), 3);
            world.setBlock(absB, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT)
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing), 3);

            ChestBlockEntity left = (ChestBlockEntity) world.getBlockEntity(absA);
            ChestBlockEntity right = (ChestBlockEntity) world.getBlockEntity(absB);
            if (left == null || right == null) {
                cleanup.run();
                throw ctx.assertionException("双箱方块实体未创建");
            }
            left.setItem(0, new ItemStack(Items.STONE, 16));
            right.setItem(0, new ItemStack(Items.STONE, 16));

            areaId[0] = sam.addStagingArea(testId, "minecraft:overworld", "Double Chest Area",
                absA.getX() - 1, absA.getY() - 1, absA.getZ() - 1,
                absB.getX() + 1, absB.getY() + 1, absB.getZ() + 1);
            sam.rescanStagingArea(areaId[0]);

            GameTestAssertions.assertEquals(ctx, 32, sam.getStagingCountForMaterial(testId, "minecraft:stone"),
                Component.literal("双箱两半各 16，合计应为 32 而非翻倍"));
            cleanup.run();
            ctx.succeed();

        } catch (Exception e) {
            cleanup.run();
            throw ctx.assertionException("双箱聚合测试失败: " + e.getMessage());
        }
    }
}
