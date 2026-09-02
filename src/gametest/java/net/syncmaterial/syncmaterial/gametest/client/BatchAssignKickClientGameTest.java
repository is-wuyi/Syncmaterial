package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 批量分配 / 按材料踢出端到端测试（负责人管理的核心多人操作）。
 *
 * 整条链路：勾选材料 → 玩家选择弹窗（真实 PlayerListResponse 填充）→
 * 确认 → C2S 包 → 服务端 owner 鉴权 + joinCollaboration/leaveCollaboration
 * → 逐材料广播 → 响应回流 applyBatchAssignResult/applyKickResult →
 * 客户端勾选清空 + 参与者状态刷新。
 *
 * 被分配方是在线假人（见 {@link MockBot}）：真实出现在玩家列表响应中
 * （断言"分配给在线玩家"是真实路径），被分配后必须实时收到协作状态
 * 广播（EmbeddedChannel 捕获验证）。
 */
public class BatchAssignKickClientGameTest implements FabricClientGameTest {

    private static final String BOT_NAME = "MockBot";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "assign-kick-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int dirtId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Assign Kick Test", "/assign-kick.litematic", "Player0");
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

            // 假人上线
            MockBot bot = MockBot.spawn(server, BOT_NAME);
            ctx.waitTicks(5);

            // Player0 以 owner 身份打开材料列表
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Assign Kick Test",
                List.of(
                    new MaterialEntry(dirtId, new ItemStack(Items.DIRT), 64),
                    new MaterialEntry(stoneId, new ItemStack(Items.STONE), 32)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            // ===== 分配：勾选 2 个材料 → 弹窗选假人 → 确认 =====
            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiMaterialList gui) {
                    gui.getSelectedMaterialIds().add(dirtId);
                    gui.getSelectedMaterialIds().add(stoneId);
                    gui.openAssignDialog();
                } else {
                    throw new AssertionError("材料列表未打开");
                }
            });

            boolean assignDialogReady = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                client.currentScreen instanceof GuiPlayerSelectDialog d && d.hasLoadedPlayers()));
            if (!assignDialogReady) {
                throw new AssertionError("分配弹窗未收到玩家列表响应");
            }

            // 在线假人必须出现在真实玩家列表响应中
            boolean botListed = ctx.computeOnClient(client ->
                client.currentScreen instanceof GuiPlayerSelectDialog d
                    && d.getAvailablePlayers().stream()
                        .anyMatch(p -> p.name().equals(BOT_NAME) && p.online()));
            if (!botListed) {
                throw new AssertionError("在线假人未出现在玩家选择弹窗中");
            }

            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiPlayerSelectDialog d) {
                    d.selectPlayer(BOT_NAME);
                    d.confirmSelection();
                } else {
                    throw new AssertionError("分配弹窗未打开");
                }
            });

            // 服务端：假人被分配 2 个材料
            int assignedClaims = waitForClaimCount(ctx, server, schematicId, BOT_NAME, 2);
            if (assignedClaims != 2) {
                throw new AssertionError("分配后 claims 应有假人 2 条记录，实际为 " + assignedClaims);
            }

            // 客户端：勾选清空 + 两个材料的参与者状态都刷新出假人
            boolean assignSynced = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.currentScreen instanceof GuiMaterialList gui)) return false;
                if (!gui.getSelectedMaterialIds().isEmpty()) return false;
                var dirtStatus = gui.getMaterialList().getCollaborationStatusFor(dirtId);
                var stoneStatus = gui.getMaterialList().getCollaborationStatusFor(stoneId);
                return hasParticipant(dirtStatus, BOT_NAME) && hasParticipant(stoneStatus, BOT_NAME);
            }));
            if (!assignSynced) {
                throw new AssertionError("分配后客户端勾选未清空或参与者状态未刷新");
            }

            // 假人：实时收到两个材料的协作状态广播
            if (!bot.receivedCollaborationStatus(dirtId) || !bot.receivedCollaborationStatus(stoneId)) {
                throw new AssertionError("假人未收到被分配材料的协作状态广播");
            }

            // ===== 踢出：重新勾选 → KICK 弹窗选假人 → 确认 =====
            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiMaterialList gui) {
                    gui.getSelectedMaterialIds().add(dirtId);
                    gui.getSelectedMaterialIds().add(stoneId);
                    gui.openKickDialog();
                } else {
                    throw new AssertionError("分配后未回到材料列表");
                }
            });

            boolean kickDialogReady = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                client.currentScreen instanceof GuiPlayerSelectDialog d && d.hasLoadedPlayers()));
            if (!kickDialogReady) {
                throw new AssertionError("踢出弹窗未收到玩家列表响应");
            }

            ctx.runOnClient(client -> {
                if (client.currentScreen instanceof GuiPlayerSelectDialog d) {
                    d.selectPlayer(BOT_NAME);
                    d.confirmSelection();
                } else {
                    throw new AssertionError("踢出弹窗未打开");
                }
            });

            // 服务端：认领记录清空
            int remainingClaims = waitForClaimCount(ctx, server, schematicId, BOT_NAME, 0);
            if (remainingClaims != 0) {
                throw new AssertionError("踢出后假人的认领记录应清空，实际为 " + remainingClaims);
            }

            // 客户端：两个材料的参与者都不再含假人
            boolean kickSynced = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.currentScreen instanceof GuiMaterialList gui)) return false;
                var dirtStatus = gui.getMaterialList().getCollaborationStatusFor(dirtId);
                var stoneStatus = gui.getMaterialList().getCollaborationStatusFor(stoneId);
                return dirtStatus != null && !hasParticipant(dirtStatus, BOT_NAME)
                    && stoneStatus != null && !hasParticipant(stoneStatus, BOT_NAME);
            }));
            if (!kickSynced) {
                throw new AssertionError("踢出后客户端参与者状态未刷新");
            }

            // 清理
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
                if (client.currentScreen instanceof GuiMaterialList gui) {
                    gui.close();
                }
                InventoryWatcher.clearContext();
            });
        }
    }

    private static boolean hasParticipant(net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket status,
                                          String playerName) {
        return status != null && status.participants().stream()
            .anyMatch(p -> p.playerName().equals(playerName));
    }

    /** 轮询等待假人的认领数到达期望值，超时返回最后读到的值 */
    private int waitForClaimCount(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                  String schematicId, String playerName, int expected) {
        int last = -1;
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            last = onDatabase(server, db -> {
                try (var rs = db.executeQuery(
                    "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND player_name = ? AND status = 'active'",
                    schematicId, playerName)) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            if (last == expected) {
                return last;
            }
        }
        return last;
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
