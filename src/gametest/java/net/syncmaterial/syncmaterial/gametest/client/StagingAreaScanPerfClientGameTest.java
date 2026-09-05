package net.syncmaterial.syncmaterial.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 备货区扫描性能基准测试：真实放 100 个满箱、执行 rescanStagingArea，
 * 测一次全量扫描的墙钟耗时并断言上限。
 *
 * 为什么需要它：备货区扫描是"玩家一按刷新就在服务端主线程跑"的路径，
 * 没有任何异步保护。10×10 箱区在真实玩家基地里只是"小号备货区"，
 * 却没人知道它到底吃多少毫秒——吃多了就会卡 TPS。
 *
 * 断言策略：
 * - 上限用极宽松的 2000ms（CI 慢机也能过），目的是防"重构后翻 10 倍"
 *   这种级数回归，不是微优化；
 * - 耗时以 System.out 打印进 CI 日志，长期观察趋势。
 */
public class StagingAreaScanPerfClientGameTest implements FabricClientGameTest {

    private static final int CHEST_COUNT = 100;
    private static final long PERF_LIMIT_MS = 2000;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "perf-scan-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Perf Scan Test", "/perf.litematic", "Player0");
                return null;
            });

            // 放 10×10 = 100 个满备战箱
            server.computeOnServer(s -> {
                var player = s.getPlayerManager().getPlayer("Player0");
                if (player == null) throw new AssertionError("Player0 不在线");
                BlockPos base = player.getBlockPos();
                var world = player.getWorld();
                for (int dx = -5; dx <= 4; dx++) {
                    for (int dz = -5; dz <= 4; dz++) {
                        BlockPos pos = base.add(dx, 0, dz);
                        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
                        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                            chest.setStack(0, new ItemStack(Items.STONE, 64));
                            chest.markDirty();
                        } else {
                            throw new AssertionError("箱子放置失败: " + pos);
                        }
                    }
                }
                return null;
            });
            ctx.waitTicks(5);

            // 创建备货区（覆盖 100 箱）并计时 rescan
            long scanMs = server.computeOnServer(s -> {
                var player = s.getPlayerManager().getPlayer("Player0");
                BlockPos base = player.getBlockPos();
                var sam = SyncMaterial.getServerStagingAreaManager();
                int areaId = sam.addStagingArea(schematicId, "minecraft:overworld", "perf_area",
                    base.getX() - 6, base.getY() - 2, base.getZ() - 6,
                    base.getX() + 6, base.getY() + 3, base.getZ() + 6);

                long t0 = System.nanoTime();
                sam.rescanStagingArea(areaId);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                sam.removeStagingArea(areaId, schematicId);
                return ms;
            });

            System.out.println("[PerfScan] " + CHEST_COUNT + " 箱全量扫描耗时 " + scanMs + " ms");
            if (scanMs > PERF_LIMIT_MS) {
                throw new AssertionError(
                    "备货区扫描超预算：" + scanMs + " ms（上限 " + PERF_LIMIT_MS + " ms）");
            }

            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM staging_areas WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> {
                client.setScreen(null);
                StagingAreaRenderer.getInstance().clearWarehouseContainers();
                InventoryWatcher.clearContext();
            });
        }
    }

    private <T> T onDatabase(TestDedicatedServerContext server,
                             SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (Exception e) {
                throw new RuntimeException("测试数据库操作失败", e);
            }
        });
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws Exception;
    }
}
