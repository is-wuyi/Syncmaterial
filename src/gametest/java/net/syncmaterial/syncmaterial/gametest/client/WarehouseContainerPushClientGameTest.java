package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
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
 * 仓库容器变化推送端到端测试：取货模式开着时，仓库箱子的内容变化
 * 必须自动反映到客户端指示器，无需手动刷新。
 *
 * 链路：箱子 markDirty() → BlockEntityMixin 打脏标记 → 服务端 tick 的
 * processDirtyContainers 扫描写库 → pushWarehouseContainerUpdate 推送
 * → 客户端容器缓存更新。此前只有服务端 DB 层断言，客户端"收到更新"
 * 从未验证过。
 *
 * 场景 A（自动出现）：仓库内的空箱被放入需取物品 → 线框数据自动长出
 * 该箱子（放入不需要的物品则不应出现——负例）。
 *
 * 场景 B（取满即灭）：背包拿满后需求清零（每 10 tick 用本地背包实测
 * 重算，不等网络往返）→ 过滤结果为空。此处曾抓住一次重构回归：纯函数
 * 把空需求集误当"非过滤"返回全部，表现为取满后仓库所有箱子常亮。
 */
public class WarehouseContainerPushClientGameTest implements FabricClientGameTest {

    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "wh-push-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "WH Push Test", "/wh-push.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, STONE, 128);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            BlockPos[] positions = new BlockPos[2];
            int warehouseId = server.computeOnServer(s -> {
                var player = s.getPlayerManager().getPlayer("Player0");
                if (player == null) throw new AssertionError("Player0 不在线");
                BlockPos base = player.getBlockPos();
                positions[0] = placeChestWithItem(player.getWorld(), base.add(2, 0, 0), Items.STONE);
                positions[1] = placeChestWithItem(player.getWorld(), base.add(-3, 0, 0), null);

                var sam = SyncMaterial.getServerStagingAreaManager();
                int whId = sam.addWarehouse("Push WH", "minecraft:overworld",
                    base.getX() - 6, base.getY() - 2, base.getZ() - 6,
                    base.getX() + 6, base.getY() + 3, base.getZ() + 6);
                sam.rescanWarehouseAndMarkChunks(whId);
                sam.addWarehouseReference(schematicId, whId);
                return whId;
            });
            ctx.waitTicks(5);

            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "WH Push Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.STONE), 128)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));

            boolean claimed = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.currentScreen instanceof GuiMaterialList gui)) return false;
                return gui.getMaterialList().getMaterialsAll().stream()
                    .anyMatch(e -> e.getDatabaseId() == materialId && e.isCurrentPlayerClaimed());
            }));
            if (!claimed) {
                throw new AssertionError("认领未生效，需求量无从计算");
            }

            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiMaterialList gui) {
                    gui.togglePickupMode();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            // ===== 初始：订阅响应只含石头箱（空箱无货不在明细表）=====
            boolean initialOk = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                describesContainer(StagingAreaRenderer.getInstance().getWarehouseContainers(), positions[0], STONE)));
            if (!initialOk) {
                throw new AssertionError("订阅初始响应未含石头箱: " + describeContainers(ctx));
            }

            // ===== 场景 A：空箱放入需要的物品 → 自动推送出现 =====
            server.computeOnServer(s -> {
                var world = s.getOverworld();
                if (world.getBlockEntity(positions[1]) instanceof ChestBlockEntity chest) {
                    chest.setStack(0, new ItemStack(Items.STONE, 64));
                    chest.markDirty();
                } else {
                    throw new AssertionError("空箱方块实体丢失: " + positions[1]);
                }
                return null;
            });

            boolean pushed = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                describesContainer(StagingAreaRenderer.getInstance().getWarehouseContainers(), positions[1], STONE)));
            if (!pushed) {
                throw new AssertionError("箱子内容变化后未自动推送到客户端: " + describeContainers(ctx));
            }

            // ===== 场景 A 负例：放入不需要的物品 → 不进指示器 =====
            server.computeOnServer(s -> {
                var world = s.getOverworld();
                if (world.getBlockEntity(positions[1]) instanceof ChestBlockEntity chest) {
                    chest.setStack(1, new ItemStack(Items.DIRT, 64));
                    chest.markDirty();
                }
                return null;
            });
            ctx.waitTicks(30);

            List<WarehouseContainerResponseS2CPacket.ContainerEntry> filteredAfterNegative =
                ctx.computeOnClient(client -> StagingAreaRenderer.filterContainersForPickup(
                    StagingAreaRenderer.getInstance().getWarehouseContainers(),
                    PickupModeState.getNeededItemIds()));
            if (filteredAfterNegative.size() != 2) {
                throw new AssertionError("两个含需取物品的箱子都应亮起，实际 "
                    + describeFiltered(filteredAfterNegative));
            }

            // ===== 场景 B：背包拿满 → 需求清零 → 取满即灭 =====
            server.runCommand("give @a minecraft:stone 128");

            boolean satisfied = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                !PickupModeState.getNeededItemIds().contains(STONE)));
            if (!satisfied) {
                throw new AssertionError("背包拿满 128 个后需求未清零: " + needsDescription(ctx));
            }

            int filteredWhenFull = ctx.computeOnClient(client ->
                StagingAreaRenderer.filterContainersForPickup(
                    StagingAreaRenderer.getInstance().getWarehouseContainers(),
                    PickupModeState.getNeededItemIds()).size());
            if (filteredWhenFull != 0) {
                throw new AssertionError("取满后指示器应全灭（空需求集 → 空），实际点亮 " + filteredWhenFull);
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
                client.setScreen(null);
                PickupModeState.clear();
                StagingAreaRenderer.getInstance().clearWarehouseContainers();
                InventoryWatcher.clearContext();
            });
        }
    }

    private static BlockPos placeChestWithItem(net.minecraft.server.world.ServerWorld world,
                                               BlockPos pos, net.minecraft.item.Item item) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            if (item != null) {
                chest.setStack(0, new ItemStack(item, 64));
            }
            chest.markDirty();
        } else {
            throw new AssertionError("箱子放置失败: " + pos);
        }
        return pos;
    }

    private static boolean describesContainer(List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers,
                                              BlockPos pos, String expectedItem) {
        return containers.stream().anyMatch(c ->
            c.posX() == pos.getX() && c.posY() == pos.getY() && c.posZ() == pos.getZ()
                && c.itemIds().contains(expectedItem));
    }

    private static String describeFiltered(List<WarehouseContainerResponseS2CPacket.ContainerEntry> filtered) {
        StringBuilder sb = new StringBuilder("[");
        for (var c : filtered) {
            sb.append(c.posX()).append(",").append(c.posY()).append(",").append(c.posZ())
                .append(":").append(c.itemIds()).append(" ");
        }
        return sb.append("]").toString();
    }

    private String describeContainers(ClientGameTestContext ctx) {
        var list = ctx.computeOnClient(client ->
            StagingAreaRenderer.getInstance().getWarehouseContainers());
        return describeFiltered(list);
    }

    private String needsDescription(ClientGameTestContext ctx) {
        return ctx.computeOnClient(client -> PickupModeState.getNeeds().toString());
    }

    private boolean waitForCondition(ClientGameTestContext ctx,
                                     java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 200; elapsed += 5) {
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
