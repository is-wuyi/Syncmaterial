package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 仓库容器批量脏标记压力测试：一个 tick 内 200 个箱子同时 setChanged，
 * 服务端的 processDirtyContainers 必须全部消化且一个不漏地推送到客户端，
 * 不能出现丢失或重复推送导致的错乱。
 *
 * 为什么压这个点：
 * - BlockEntityMixin 把每次 markDirty/setChanged 转成脏标记，末影箱农场 /
 *   大型仓库在玩家填充时很容易"一 tick 几百个容器同时变脏"；
 * - processDirtyContainers 每 tick 处理快照，若实现遗漏或重复，指示器
 *   就会出现"这个箱子明明改了却不亮"或者"多余的箱子常亮"——此前只能靠
 *   玩家肉眼发现；
 * - 该路径是整个仓库取货指示器的真实性来源，压力假绿代价很高。
 *
 * 验收：全部 200 个箱子都在限定 tick 内出现在客户端容器列表，且数量
 * 恰好为 200、位置与放置坐标一一对应（不多不少）。
 */
public class StressContainerPushClientGameTest implements FabricClientGameTest {

    private static final String STONE = "minecraft:stone";
    private static final int CHEST_COUNT = 200;
    /** 每个箱子生成 5 tick 预算（200 箱 = 1000 tick / 50 秒），足够保守防止 CI 慢冷启动 */
    private static final int TIMEOUT_TICKS_PER_CHEST = 5;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "stress-push-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Stress Push Test", "/stress.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, STONE, 128);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            // 一次放 200 个空箱子（10×20 网格），都在仓库区域内
            List<BlockPos> positions = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayer("Player0");
                if (player == null) throw new AssertionError("Player0 不在线");
                BlockPos base = player.blockPosition();
                var world = player.level();
                var result = new java.util.ArrayList<BlockPos>();
                int index = 0;
                for (int dx = -4; dx <= 5; dx++) {
                    for (int dz = -9; dz <= 10 && index < CHEST_COUNT; dz++) {
                        BlockPos pos = base.offset(dx, 0, dz);
                        placeChest(world, pos);
                        result.add(pos);
                        index++;
                    }
                }
                return result;
            });
            if (positions.size() != CHEST_COUNT) {
                throw new AssertionError("箱子放置数应为 " + CHEST_COUNT + "，实际 " + positions.size());
            }

            int warehouseId = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayer("Player0");
                BlockPos base = player.blockPosition();
                var sam = SyncMaterial.getServerStagingAreaManager();
                int whId = sam.addWarehouse("Stress WH", "minecraft:overworld",
                    base.getX() - 8, base.getY() - 2, base.getZ() - 12,
                    base.getX() + 8, base.getY() + 3, base.getZ() + 12);
                sam.rescanWarehouseAndMarkChunks(whId);
                sam.addWarehouseReference(schematicId, whId);
                return whId;
            });
            ctx.waitTicks(5);

            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Stress Push Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.STONE), 128)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));
            boolean claimed = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                return gui.getMaterialList().getMaterialsAll().stream()
                    .anyMatch(e -> e.getDatabaseId() == materialId && e.isCurrentPlayerClaimed());
            }), 100);
            if (!claimed) throw new AssertionError("认领未生效");

            ctx.runOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    gui.togglePickupMode();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            // 初始：全部空箱，推送列表里不该有任何含 STONE 的容器
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> initial =
                ctx.computeOnClient(client ->
                    StagingAreaRenderer.getInstance().getWarehouseContainers());
            long initialWithStone = initial.stream()
                .filter(c -> c.itemIds().contains(STONE)).count();
            if (initialWithStone != 0) {
                throw new AssertionError("初始应为 0 个含石头的箱子，实际 " + initialWithStone);
            }

            // ===== 同一 tick 内给 200 个箱子都塞入石头并 setChanged =====
            server.computeOnServer(s -> {
                var world = s.overworld();
                for (BlockPos pos : positions) {
                    if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                        chest.setItem(0, new ItemStack(Items.STONE, 64));
                        chest.setChanged();
                    }
                }
                return null;
            });

            // ===== 断言：全部推送到位，一个不多一个不少 =====
            long deadlineTicks = 20 + (long) CHEST_COUNT * TIMEOUT_TICKS_PER_CHEST;
            long startTick = currentTick(ctx);
            boolean allArrived = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                var containers = StagingAreaRenderer.getInstance().getWarehouseContainers();
                return containers.stream()
                    .filter(c -> c.itemIds().contains(STONE))
                    .count() == CHEST_COUNT;
            }), deadlineTicks);
            long elapsed = currentTick(ctx) - startTick;
            System.out.println("[StressPush] " + CHEST_COUNT + " 个容器推送到位耗时 " + elapsed + " tick");

            if (!allArrived) {
                int arrived = ctx.computeOnClient(client ->
                    (int) StagingAreaRenderer.getInstance().getWarehouseContainers().stream()
                        .filter(c -> c.itemIds().contains(STONE)).count());
                throw new AssertionError(
                    "超时仍未推满：期望 " + CHEST_COUNT + "，实际 " + arrived
                        + "（耗时 " + elapsed + " tick，超预算 " + deadlineTicks + "）");
            }

            // 位置全对（每箱唯一）
            List<BlockPos> arrived = ctx.computeOnClient(client ->
                StagingAreaRenderer.getInstance().getWarehouseContainers().stream()
                    .filter(c -> c.itemIds().contains(STONE))
                    .map(c -> new BlockPos(c.posX(), c.posY(), c.posZ()))
                    .toList());
            if (arrived.size() != positions.size()) {
                throw new AssertionError("推送坐标数不匹配：" + arrived.size() + " vs " + positions.size());
            }
            if (!new java.util.HashSet<>(arrived).containsAll(positions)) {
                throw new AssertionError("推送坐标与放置坐标不完全一致（存在错位或重复）");
            }

            // 清理
            server.computeOnServer(s -> {
                SyncMaterial.getServerStagingAreaManager().deleteWarehouse(warehouseId);
                return null;
            });
            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM player_inventories WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> {
                client.setScreenAndShow(null);
                PickupModeState.clear();
                StagingAreaRenderer.getInstance().clearWarehouseContainers();
                InventoryWatcher.clearContext();
            });
        }
    }

    private static void placeChest(net.minecraft.server.level.ServerLevel world, BlockPos pos) {
        world.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (!(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            throw new AssertionError("箱子放置失败: " + pos);
        }
        chest.setChanged();
    }

    private long currentTick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(client -> client.player == null ? -1L : client.player.tickCount);
    }

    private boolean waitForCondition(ClientGameTestContext ctx,
                                     java.util.function.Supplier<Boolean> condition,
                                     long maxTicks) {
        for (long elapsed = 0; elapsed < maxTicks; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
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
