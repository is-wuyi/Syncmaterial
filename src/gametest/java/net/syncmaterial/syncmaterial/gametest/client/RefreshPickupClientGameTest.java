package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 刷新按钮与取货模式按钮端到端测试（GuiMaterialList 顶部按钮行的两条链路）。
 *
 * 链路 3（刷新）：refreshStagingAreas() → RescanStagingAreaC2SPacket →
 * 服务端重扫备货区 + broadcastAllMaterialStatus → RescanResponse →
 * receiver 路由 → onRescanResponse（lastRescanResult 置位）。
 * 需要预置一个备货区，否则服务端按"没有找到备货区"返回失败。
 *
 * 链路 4（取货模式）：togglePickupMode() → PickupModeState 翻转 +
 * WarehouseContainerRequestC2SPacket 订阅 → 服务端查仓库容器 →
 * WarehouseContainerResponse → receiver → renderer 容器缓存就绪。
 * 关闭时反向退订并清空缓存——标志位两段变化验证订阅与清理双向。
 */
public class RefreshPickupClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "refresh-pickup-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Refresh Pickup Test", "/refresh-pickup.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            // 预置一个备货区（刷新链路需要至少一个区域才走成功分支）
            int areaId = server.computeOnServer(s ->
                SyncMaterial.getServerStagingAreaManager()
                    .addStagingArea(schematicId, "minecraft:overworld", "Refresh Area",
                        0, 64, 0, 10, 70, 10));

            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Refresh Pickup Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.DIRT), 64)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            // ===== 链路 3：刷新按钮 =====
            ctx.runOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    if (!gui.refreshStagingAreas()) {
                        throw new AssertionError("刷新请求未发出（schematicId 为空？）");
                    }
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            boolean rescanOk = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                client.gui.screen() instanceof GuiMaterialList gui
                    && Boolean.TRUE.equals(gui.getLastRescanResult())));
            if (!rescanOk) {
                throw new AssertionError("刷新响应未回流或结果不是成功");
            }

            // ===== 链路 4：取货模式开关 =====
            ctx.runOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    gui.togglePickupMode();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            // 开启：本地状态 + 订阅响应回流（容器缓存就绪标志）
            boolean pickupOn = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                PickupModeState.isActive()
                    && StagingAreaRenderer.getInstance().hasWarehouseContainersLoaded()));
            if (!pickupOn) {
                throw new AssertionError(
                    "取货模式开启后本地状态未激活或容器订阅响应未回流: active="
                        + ctx.computeOnClient(client -> PickupModeState.isActive())
                        + ", loaded=" + ctx.computeOnClient(client ->
                            StagingAreaRenderer.getInstance().hasWarehouseContainersLoaded()));
            }

            // 关闭：状态退出 + 容器缓存清空
            ctx.runOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    gui.togglePickupMode();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });
            boolean pickupOff = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                !PickupModeState.isActive()
                    && !StagingAreaRenderer.getInstance().hasWarehouseContainersLoaded()));
            if (!pickupOff) {
                throw new AssertionError("取货模式关闭后状态或容器缓存未清理");
            }

            // 清理
            server.computeOnServer(s -> {
                SyncMaterial.getServerStagingAreaManager().removeStagingArea(areaId, schematicId);
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
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    gui.closeGui(false);
                }
                InventoryWatcher.clearContext();
                PickupModeState.clear();
            });
        }
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
