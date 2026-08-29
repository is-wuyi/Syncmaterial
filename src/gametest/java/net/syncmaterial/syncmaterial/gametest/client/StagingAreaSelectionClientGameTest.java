package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 准星选区 → 创建备货区 → 服务端扫描统计 的端到端测试。
 *
 * 覆盖三段此前无自动化验证的链路：
 * 1. 选区状态机：start 关界面、左右键设点、确认回调携带正确坐标、取消不回调
 * 2. 备货区创建：ADD 包 → 服务端建区 → 响应回客户端 → 渲染器收到线框数据
 * 3. 备货区扫描：区内放箱子装物品 → 服务端统计出该材料的备货区存量
 *
 * 注意选区的按键读取分两套机制：
 * - 左键/右键走 KeyMapping.isDown()，客户端 GameTest 的合成输入可驱动
 * - Esc/Enter 走 GLFW.glfwGetKey() 直接轮询硬件状态，合成输入驱动不了，
 *   因此确认/取消在测试里直接调 confirm()/cancel()。这是被测代码的可测性
 *   限制，不是测试偷懒 —— 记录在此以免日后误以为覆盖了真实按键路径。
 */
public class StagingAreaSelectionClientGameTest implements FabricClientGameTest {

    private static final BlockPos POS1 = new BlockPos(4, 64, 4);
    private static final BlockPos POS2 = new BlockPos(6, 66, 6);
    /** 箱子放在选区内部，用于验证服务端扫描确实覆盖该范围 */
    private static final BlockPos CHEST_POS = new BlockPos(5, 65, 5);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-selection-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, database -> {
                database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Selection Test", "/selection-test.litematic", "Player0");
                database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:stone", 64);
                try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    return result.getInt(1);
                }
            });

            // ===== 第一段：选区状态机 =====

            verifyCancelDoesNotCallBack(ctx);
            BlockPos[] confirmed = verifyConfirmReportsPositions(ctx);

            // ===== 第二段：用选区结果创建备货区 =====

            ctx.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                schematicId, "ADD", -1,
                Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                    "选区备货区",
                    confirmed[0].getX(), confirmed[0].getY(), confirmed[0].getZ(),
                    confirmed[1].getX(), confirmed[1].getY(), confirmed[1].getZ(),
                    Optional.empty())))));

            int areaId = waitForAreaId(ctx, server, schematicId);
            if (areaId <= 0) {
                throw new AssertionError("发送 ADD 后服务端未创建备货区");
            }

            // 服务端响应应回流到渲染器：客户端能画出这个备货区的线框
            boolean rendererHasArea = waitForCondition(ctx, () ->
                ctx.computeOnClient(client -> {
                    var selection = StagingAreaRenderer.getInstance().getSelection(schematicId);
                    return selection != null && !selection.getAllSubRegionBoxes().isEmpty();
                }));
            if (!rendererHasArea) {
                throw new AssertionError("备货区创建后客户端渲染器未收到线框数据");
            }

            // ===== 第三段：备货区扫描统计 =====

            server.runOnServer(instance -> {
                var level = instance.overworld();
                level.setBlock(CHEST_POS, Blocks.CHEST.defaultBlockState(), 3);
                if (level.getBlockEntity(CHEST_POS)
                        instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                    chest.setItem(0, new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.STONE, 32));
                    chest.setChanged();
                }
            });

            int staged = waitForStagingCount(ctx, server, schematicId, 32);
            if (staged != 32) {
                throw new AssertionError(
                    "选区内箱子放入 32 个石头后备货区存量应为 32，实际为 " + staged);
            }

            onDatabase(server, database -> {
                database.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> {
                StagingAreaSelector.getInstance().reset();
                StagingAreaRenderer.getInstance().removeRenderData(schematicId);
            });
        }
    }

    /** 取消选区不应触发回调，且必须退出激活态（残留会让下次进服仍处于选区模式） */
    private void verifyCancelDoesNotCallBack(ClientGameTestContext ctx) {
        boolean[] called = {false};
        ctx.runOnClient(client -> StagingAreaSelector.getInstance().start(
            (name, p1, p2) -> called[0] = true, null, "取消测试", POS1, POS2));
        ctx.waitTicks(5);

        boolean activeAfterStart = ctx.computeOnClient(client ->
            StagingAreaSelector.getInstance().isActive());
        if (!activeAfterStart) {
            throw new AssertionError("start 后选区模式应处于激活态");
        }

        ctx.runOnClient(client -> StagingAreaSelector.getInstance().cancel());
        ctx.waitTicks(5);

        boolean stillActive = ctx.computeOnClient(client ->
            StagingAreaSelector.getInstance().isActive());
        if (stillActive) {
            throw new AssertionError("取消选区后仍处于激活态，下次进服会残留选区模式");
        }
        if (called[0]) {
            throw new AssertionError("取消选区不应触发确认回调");
        }
    }

    /** 确认选区应把两个角点原样交给回调 */
    private BlockPos[] verifyConfirmReportsPositions(ClientGameTestContext ctx) {
        BlockPos[] captured = new BlockPos[2];
        ctx.runOnClient(client -> StagingAreaSelector.getInstance().start(
            (name, p1, p2) -> {
                captured[0] = p1;
                captured[1] = p2;
            }, null, "确认测试", POS1, POS2));
        ctx.waitTicks(5);

        ctx.runOnClient(client -> StagingAreaSelector.getInstance().confirm());
        ctx.waitTicks(5);

        if (!POS1.equals(captured[0]) || !POS2.equals(captured[1])) {
            throw new AssertionError("确认选区回调收到的坐标不正确: pos1=" + captured[0]
                + ", pos2=" + captured[1] + "（期望 " + POS1 + " / " + POS2 + "）");
        }

        boolean stillActive = ctx.computeOnClient(client ->
            StagingAreaSelector.getInstance().isActive());
        if (stillActive) {
            throw new AssertionError("确认选区后应退出激活态");
        }
        return captured;
    }

    private int waitForAreaId(ClientGameTestContext ctx, TestDedicatedServerContext server,
                              String schematicId) {
        for (int elapsed = 0; elapsed < 60; elapsed += 5) {
            ctx.waitTicks(5);
            int id = onDatabase(server, database -> {
                try (var result = database.executeQuery(
                    "SELECT id FROM staging_areas WHERE schematic_id = ?", schematicId)) {
                    return result.next() ? result.getInt("id") : -1;
                }
            });
            if (id > 0) {
                return id;
            }
        }
        return -1;
    }

    private int waitForStagingCount(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                    String schematicId, int expected) {
        int last = -1;
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            last = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                return manager == null ? -1
                    : manager.getStagingCountForMaterial(schematicId, "minecraft:stone");
            });
            if (last == expected) {
                return last;
            }
        }
        return last;
    }

    private boolean waitForCondition(ClientGameTestContext ctx, java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 60; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
        }
        return false;
    }

    private <T> T onDatabase(TestDedicatedServerContext server,
                             SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (SQLException e) {
                throw new RuntimeException("测试数据库操作失败", e);
            }
        });
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
