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
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.network.Phase4Handler;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 多人认领广播链路回归测试：双端（认领者 + 正在查看列表的旁观者）都必须
 * 实时收到协作状态广播，这是多人协作的核心语义。
 *
 * 角色分工（订阅者/参与者两条投递路径分别验证，不能互相顶替）：
 * - Player0 = 纯旁观者：打开材料列表成为订阅者，全程不发认领包。
 *   广播到达它只有订阅者路径一条 —— 变异实验证明若让 Player0 也认领，
 *   参与者路径会顶替订阅者路径，砍掉订阅者投递测试照样全绿（假绿）。
 * - 假人（见 {@link MockBot}）= 认领者：经真实 CollaborationManager 落库，
 *   广播按参与者名字解析到它。
 *
 * 广播触发用 Phase4Handler.broadcastAllMaterialStatus —— 备货区重扫、
 * 仓库变动等场景共用的真实服务端广播入口（内部逐材料调 broadcastStatus）。
 * 认领包的解码与 handler 层已由 BatchAssignKickClientGameTest 用真实
 * C2S 包覆盖，此处不重复。
 *
 * 断言：
 * (a) 假人出现在服务端玩家列表（placeNewPlayer 生效）
 * (b) 广播经参与者路径到达假人（EmbeddedChannel 捕获）
 * (c) 广播经订阅者路径到达 Player0：打开列表时的直发快照参与者为空，
 *     断言"收到含假人的状态"只能由之后的广播满足
 * (d) claims 表恰好 1 条认领（只有假人）
 */
public class MultiplayerClaimBroadcastClientGameTest implements FabricClientGameTest {

    private static final String BOT_NAME = "MockBot";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "claim-broadcast-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Claim Broadcast Test", "/claim-broadcast.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var result = db.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    return result.getInt(1);
                }
            });

            // Player0 以纯旁观者身份打开材料列表（订阅者，不认领）
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Claim Broadcast Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.DIRT), 64)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            // ===== 假人上线 + 认领 =====
            MockBot bot = MockBot.spawn(server, BOT_NAME);
            ctx.waitTicks(5);

            Boolean botOnline = server.computeOnServer(s ->
                s.getPlayerList().getPlayer(BOT_NAME) != null);
            if (!Boolean.TRUE.equals(botOnline)) {
                throw new AssertionError("假人未出现在服务端玩家列表，placeNewPlayer 未生效");
            }

            Boolean botJoined = server.computeOnServer(s -> {
                var cm = SyncMaterial.getSharedCollaborationManager();
                return cm != null && cm.joinCollaboration(schematicId, materialId, BOT_NAME);
            });
            if (!Boolean.TRUE.equals(botJoined)) {
                throw new AssertionError("假人认领失败（joinCollaboration 返回 false）");
            }

            // ===== 触发广播（真实服务端广播入口）=====
            server.computeOnServer(s -> {
                Phase4Handler.broadcastAllMaterialStatus(s, schematicId);
                return null;
            });

            // ===== (c) 旁观者 Player0 经订阅者路径收到含假人的状态 =====
            boolean player0GotStatus = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (client.gui.screen() instanceof GuiMaterialList gui) {
                    var status = gui.getMaterialList().getCollaborationStatusFor(materialId);
                    return status != null
                        && status.participants().stream().anyMatch(p -> p.playerName().equals(BOT_NAME));
                }
                return false;
            }));
            if (!player0GotStatus) {
                var lastStatus = ctx.computeOnClient(client ->
                    client.gui.screen() instanceof GuiMaterialList gui
                        ? gui.getMaterialList().getCollaborationStatusFor(materialId) : null);
                throw new AssertionError("旁观者 Player0 未经订阅者路径收到含假人认领的协作状态广播，当前状态: " + lastStatus);
            }

            // ===== (b) 假人经参与者路径收到同一条广播 =====
            // broadcastStatus 在服务端线程同步完成对全部接收者的发送，
            // (c) 已通过说明发送阶段结束，此处捕获结果必然就绪
            if (!bot.receivedCollaborationStatus(materialId)) {
                throw new AssertionError("假人未收到协作状态广播");
            }

            // ===== (d) 服务端落库：恰好 1 条认领（只有假人）=====
            Integer claimCount = onDatabase(server, db -> {
                try (var rs = db.executeQuery(
                    "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
                    schematicId, materialId)) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            if (claimCount == null || claimCount != 1) {
                throw new AssertionError("claims 表应只有假人 1 条认领，实际为 " + claimCount);
            }

            // 清理：假人移出玩家列表，测试数据删除
            bot.despawn(server);
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
