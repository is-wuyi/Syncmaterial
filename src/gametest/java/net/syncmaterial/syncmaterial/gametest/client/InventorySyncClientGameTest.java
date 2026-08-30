package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 端到端背包同步回归测试：关闭材料列表后，背包变化仍须上报到服务端。
 *
 * 复现的是已实机确认的 bug：GuiMaterialList 关闭时调用
 * InventoryWatcher.clearContext()，导致 currentSchematicId 被清空、
 * tick 监听整体跳过，背包变化不再上报，服务端永久停在旧值。
 *
 * 这条链路（客户端背包 → InventoryUpdateC2SPacket → 服务端落库）此前
 * 无任何自动化覆盖：单测只能验纯函数，mock 测试只能验服务端 handler，
 * 唯有真实客户端 + 真实网络能覆盖整条链路。
 */
public class InventorySyncClientGameTest implements FabricClientGameTest {

    /** 等待背包更新落库的最长 tick 数（InventoryWatcher 每 20 tick 上报一次） */
    private static final int SYNC_TIMEOUT_TICKS = 80;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-inventory-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, database -> {
                database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Client Inventory Test", "/client-test.litematic", "Player0");
                database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    return result.getInt(1);
                }
            });

            // 打开材料列表：走真实路径，openMaterialListScreen 内部会通过
            // SyncMaterialList.setMaterialEntries 注册 InventoryWatcher 上下文
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Client Inventory Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.DIRT), 64)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);

            // 加入协作：服务端 handleContainerUpdate 只接受协作中玩家的上报，
            // 未协作的更新会被静默丢弃（设计如此，非 bug）
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));
            ctx.waitTicks(10);

            // 关闭界面 —— bug 的触发点。1.21.7 的 closeGui(boolean) 是 protected，
            // 用 vanilla close() 走关闭按钮对应的真实路径；此前实机确认过正是
            // 这条路径会误清 InventoryWatcher 上下文
            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiMaterialList gui) {
                    gui.close();
                }
            });
            ctx.waitTicks(10);

            boolean watcherAlive = ctx.computeOnClient(client -> InventoryWatcher.isWatching());
            if (!watcherAlive) {
                throw new AssertionError("关闭材料列表后背包监听被解除，背包变化将不再上报");
            }

            server.runCommand("give @a minecraft:dirt 4");
            int afterPickup = waitForCount(ctx, server, schematicId, materialId, 4);
            if (afterPickup != 4) {
                throw new AssertionError(
                    "关闭界面后拾取 4 个泥土，服务端应记录 4，实际为 " + afterPickup);
            }

            // 数量归零同样要上报，否则服务端会永久停在旧值
            server.runCommand("clear @a minecraft:dirt");
            int afterClear = waitForCount(ctx, server, schematicId, materialId, 0);
            if (afterClear != 0) {
                throw new AssertionError(
                    "清空泥土后服务端应记录 0，实际为 " + afterClear);
            }

            onDatabase(server, database -> {
                database.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> InventoryWatcher.clearContext());
        }
    }

    /**
     * 轮询等待服务端落库到期望值，避免固定等待造成的偶发失败。
     * 超时后返回最后读到的值，由调用方给出带实际值的断言信息。
     */
    private int waitForCount(ClientGameTestContext ctx, TestDedicatedServerContext server,
                             String schematicId, int materialId, int expected) {
        int last = -1;
        for (int elapsed = 0; elapsed < SYNC_TIMEOUT_TICKS; elapsed += 5) {
            ctx.waitTicks(5);
            last = queryCount(server, schematicId, materialId);
            if (last == expected) {
                return last;
            }
        }
        return last;
    }

    private int queryCount(TestDedicatedServerContext server, String schematicId, int materialId) {
        return onDatabase(server, database -> {
            try (var result = database.executeQuery(
                "SELECT count FROM player_inventories WHERE schematic_id = ? AND material_id = ?",
                schematicId, materialId)) {
                return result.next() ? result.getInt("count") : -1;
            }
        });
    }

    /**
     * 在服务端线程上访问数据库。SQLException 包成 RuntimeException：
     * runTest 不允许抛检查异常，而测试里的 SQL 失败本身就应当让测试红。
     */
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
