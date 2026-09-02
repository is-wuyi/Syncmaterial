package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 仓库取货指示器端到端测试：真实仓库 + 真实箱子 + 真实订阅链路。
 *
 * 此前指示器从未用真实数据验证过：RefreshPickup 只断言"收到过容器响应"
 * 但测试里没有仓库（日志显示仓库集合为空），PickupHighlight 测的是世界
 * 散箱子（格子高亮路径）而非仓库订阅路径。指示器数据断流时静默不亮，
 * 唯一的暴露方式就是这条端到端。
 *
 * 场景：仓库内两个箱子——石头箱（64 石头）与泥土箱（64 泥土），
 * 材料清单需要 128 石头。取货模式下：
 * - 容器订阅响应应包含两个箱子的条目（内容正确性）
 * - 需求量 = min(128 - 备货区 0 - 背包 0, 仓库 64) = 64（仓库计数参与计算）
 * - 指示器过滤（filterContainersForPickup）应只亮石头箱——泥土箱
 *   不含需取物品必须被过滤掉（负例，防止"全亮不过滤"的退化）
 */
public class WarehousePickupIndicatorClientGameTest implements FabricClientGameTest {

    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "wh-indicator-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "WH Indicator Test", "/wh-indicator.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, STONE, 128);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            // ===== 服务端搭仓库：两箱货物 + 扫描 + 挂到原理图 =====
            BlockPos[] chestPositions = new BlockPos[2];
            int warehouseId = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayer("Player0");
                if (player == null) throw new AssertionError("Player0 不在线");
                BlockPos base = player.blockPosition();
                chestPositions[0] = placeChestWithItem(player.level(), base.offset(2, 0, 0), Items.STONE);
                chestPositions[1] = placeChestWithItem(player.level(), base.offset(4, 0, 0), Items.DIRT);

                var sam = SyncMaterial.getServerStagingAreaManager();
                int whId = sam.addWarehouse("Indicator WH", "minecraft:overworld",
                    base.getX() - 6, base.getY() - 2, base.getZ() - 6,
                    base.getX() + 6, base.getY() + 3, base.getZ() + 6);
                sam.rescanWarehouseAndMarkChunks(whId);
                // 引用先于打开 GUI 建立：初始状态的 warehouseCount 依赖它
                sam.addWarehouseReference(schematicId, whId);
                return whId;
            });
            ctx.waitTicks(5);

            // ===== 打开 GUI + 真实认领（需求量计算依赖认领状态）=====
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "WH Indicator Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.STONE), 128)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));

            // 等仓库计数回流到客户端条目（初始状态包或认领广播都会带）
            boolean warehouseSynced = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                MaterialListEntry entry = findEntry(gui, materialId);
                return entry != null && entry.getWarehouseCount() == 64
                    && entry.isCurrentPlayerClaimed();
            }));
            if (!warehouseSynced) {
                throw new AssertionError("仓库计数(64)未回流到客户端条目或认领未生效");
            }

            // ===== 开取货模式（真实按钮方法）→ 订阅 → 容器响应 =====
            ctx.runOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    gui.togglePickupMode();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            boolean containersArrived = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                var containers = StagingAreaRenderer.getInstance().getWarehouseContainers();
                return containers.size() == 2
                    && containers.stream().anyMatch(c -> c.itemIds().contains(STONE))
                    && containers.stream().anyMatch(c -> c.itemIds().contains(DIRT));
            }));
            if (!containersArrived) {
                var containers = ctx.computeOnClient(client ->
                    StagingAreaRenderer.getInstance().getWarehouseContainers().stream()
                        .map(c -> c.posX() + "," + c.posY() + "," + c.posZ() + ":" + c.itemIds())
                        .toList());
                throw new AssertionError("容器订阅响应内容不符，实际: " + containers);
            }

            // ===== 需求量：min(128 - 0 - 0, 64) = 64 =====
            Map<String, Integer> needs = ctx.computeOnClient(client ->
                Map.copyOf(PickupModeState.getNeeds()));
            if (!Integer.valueOf(64).equals(needs.get(STONE))) {
                throw new AssertionError("取货需求量应为 64（受仓库现有量封顶），实际为 " + needs);
            }

            // ===== 指示器本体：过滤后只剩石头箱 =====
            List<net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry> filtered =
                ctx.computeOnClient(client -> StagingAreaRenderer.filterContainersForPickup(
                    StagingAreaRenderer.getInstance().getWarehouseContainers(),
                    PickupModeState.getNeededItemIds()));

            if (filtered.size() != 1) {
                throw new AssertionError("指示器应只亮含需取物品的箱子（1 个），实际 " + filtered.size());
            }
            var lit = filtered.get(0);
            BlockPos stoneChest = chestPositions[0];
            if (lit.posX() != stoneChest.getX() || lit.posY() != stoneChest.getY()
                || lit.posZ() != stoneChest.getZ() || !lit.itemIds().contains(STONE)) {
                throw new AssertionError("指示器点亮的不是石头箱: pos=" + lit.posX() + "," + lit.posY()
                    + "," + lit.posZ() + " items=" + lit.itemIds());
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

    private static BlockPos placeChestWithItem(net.minecraft.world.level.Level world,
                                               BlockPos pos, net.minecraft.world.item.Item item) {
        world.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(item, 64));
            chest.setChanged();
        } else {
            throw new AssertionError("箱子放置失败: " + pos);
        }
        return pos;
    }

    private static MaterialListEntry findEntry(GuiMaterialList gui, int databaseId) {
        for (var entry : gui.getMaterialList().getMaterialsAll()) {
            if (entry.getDatabaseId() == databaseId) return entry;
        }
        return null;
    }

    private boolean waitForCondition(ClientGameTestContext ctx,
                                     java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
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
