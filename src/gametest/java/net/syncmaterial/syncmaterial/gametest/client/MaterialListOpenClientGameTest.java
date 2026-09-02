package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 材料清单打开链路端到端测试：MaterialStatsRequestC2SPacket →
 * 服务端查库（materials + claims + player_inventories）→
 * MaterialStatsResponseS2CPacket → receiver 路由 → GuiMaterialList 自动打开。
 *
 * 与既有测试的关键差异：此前的测试都手动调用 openMaterialListScreen
 * 直开 GUI，跳过了"请求 → 查库 → 响应 → 路由"整条链路；本测试只发
 * 一个真实请求包，GUI 必须由 receiver 自动打开，数据必须来自服务端 DB。
 *
 * 数据真实性断言：材料条目的 databaseId 是 DB 自增 rowid，客户端不可能
 * 预知——条目能对上即证明数据走了真实查库路径。
 *
 * 计算口径断言（handleMaterialStatsRequest + onCollaborationStatus 覆盖后
 * 的最终值）：
 * - dirt：另一玩家认领并持有 10 → otherPlayersCount=10、missing=54
 * - stone：无人认领 → missing=32（全缺）
 */
public class MaterialListOpenClientGameTest implements FabricClientGameTest {

    private static final String CLAIMER = "OtherPlayer";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "open-flow-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int dirtId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Open Flow Test", "/open-flow.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            int stoneId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:stone", 32);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            // 另一玩家的认领与持有量：走真实 CollaborationManager 实例
            // （内存缓存 + DB 双写，getCollaborationStatus 从内存缓存读 count）
            server.computeOnServer(s -> {
                var cm = SyncMaterial.getSharedCollaborationManager();
                if (cm == null || !cm.joinCollaboration(schematicId, dirtId, CLAIMER)) {
                    throw new AssertionError("预置认领失败");
                }
                cm.updatePlayerInventory(CLAIMER, schematicId, dirtId, 10);
                return null;
            });

            // ===== 唯一动作：发真实请求包，GUI 必须由 receiver 自动打开 =====
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new MaterialStatsRequestC2SPacket(schematicId)));

            ctx.waitForScreen(GuiMaterialList.class);

            // 数据断言：等 CollaborationStatus 包覆盖完条目后取最终值
            boolean dataOk = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;

                if (!schematicId.equals(gui.getSchematicId())) return false;
                if (!gui.isOwner() || !gui.isMainOwner()) return false;
                if (!"Player0".equals(gui.getOwnerName())) return false;
                if (!gui.isAllowSelfClaim()) return false;

                List<MaterialListEntry> entries = gui.getMaterialList().getMaterialsAll();
                if (entries.size() != 2) return false;

                MaterialListEntry dirt = findEntry(entries, dirtId);
                MaterialListEntry stone = findEntry(entries, stoneId);
                if (dirt == null || stone == null) return false;

                return dirt.getOtherPlayersCount() == 10
                    && dirt.getCountMissing() == 54
                    && stone.getOtherPlayersCount() == 0
                    && stone.getCountMissing() == 32;
            }));
            if (!dataOk) {
                String actual = ctx.computeOnClient(client ->
                    client.gui.screen() instanceof GuiMaterialList gui
                        ? describeEntries(gui) : "GUI 已关闭");
                throw new AssertionError("材料清单数据与预期不符: " + actual);
            }

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
            });
        }
    }

    private static MaterialListEntry findEntry(List<MaterialListEntry> entries, int databaseId) {
        for (var entry : entries) {
            if (entry.getDatabaseId() == databaseId) return entry;
        }
        return null;
    }

    private static String describeEntries(GuiMaterialList gui) {
        var sb = new StringBuilder("isOwner=").append(gui.isOwner())
            .append(", isMainOwner=").append(gui.isMainOwner())
            .append(", ownerName=").append(gui.getOwnerName())
            .append(", allowSelfClaim=").append(gui.isAllowSelfClaim())
            .append(", entries=[");
        for (var entry : gui.getMaterialList().getMaterialsAll()) {
            sb.append("{id=").append(entry.getDatabaseId())
                .append(", total=").append(entry.getCountTotal())
                .append(", missing=").append(entry.getCountMissing())
                .append(", available=").append(entry.getCountAvailable())
                .append(", others=").append(entry.getOtherPlayersCount())
                .append("}, ");
        }
        return sb.append("]").toString();
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
