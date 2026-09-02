package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.Phase4Handler;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 退出认领与材料列表退订链路端到端测试。
 *
 * 此前两条链路只有单测（handler mock 层）：玩家自己点"退出协作"的
 * LeaveCollaborationC2SPacket，以及原理图删除时发出的
 * MaterialListCloseC2SPacket，都从未在真实网络环境跑过。
 *
 * 链路 1（退出认领）：真实 Leave 包 → 服务端 leaveCollaboration 删认领
 * → 广播 → 客户端参与者列表刷新为空。
 *
 * 链路 2（删除退订）：clearActiveSchematic（SCHEMATIC_DELETED 响应的
 * 客户端处理入口，GUI 关闭按钮刻意不走这条路——HUD 生命周期长于 GUI，
 * 见 GuiMaterialList.close 注释）→ 发 MaterialListCloseC2SPacket →
 * 服务端 unsubscribeMaterialList → 订阅集合收缩。
 * 断言用服务端真实订阅状态（getSubscribedSchematics），不依赖客户端
 * 间接观察——退订后客户端本就该忽略广播，"没收到"证不了"没发"。
 */
public class LeaveAndUnsubscribeClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "leave-unsub-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Leave Unsub Test", "/leave-unsub.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            // Player0 打开材料列表：走真实路径，initGui 自动订阅
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Leave Unsub Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.DIRT), 64)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            // ===== 前置：服务端订阅已建立 =====
            if (!isSubscribed(server, schematicId)) {
                throw new AssertionError("打开材料列表后服务端订阅未建立");
            }

            // ===== 链路 1a：真实认领包（控制组，证明链路通）=====
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));

            boolean joined = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                var status = gui.getMaterialList().getCollaborationStatusFor(materialId);
                return status != null && status.participants().stream()
                    .anyMatch(p -> p.playerName().equals("Player0"));
            }));
            if (!joined) {
                throw new AssertionError("认领后客户端未收到含自己的协作状态（控制组失败，后续断言不可信）");
            }

            // ===== 链路 1b：真实退出包（此前无端到端覆盖）=====
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new LeaveCollaborationC2SPacket(schematicId, materialId)));

            boolean left = waitForCondition(ctx, () -> {
                int claims = activeClaimCount(server, schematicId, materialId);
                if (claims != 0) return false;
                return Boolean.TRUE.equals(ctx.computeOnClient(client -> {
                    if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                    var status = gui.getMaterialList().getCollaborationStatusFor(materialId);
                    return status != null && status.participants().isEmpty();
                }));
            });
            if (!left) {
                throw new AssertionError("退出认领后服务端认领未清零或客户端参与者列表未刷新");
            }

            // ===== 链路 2：删除退订（clearActiveSchematic → MaterialListCloseC2SPacket）=====
            ctx.runOnClient(client -> SyncMaterialClient.clearActiveSchematic(schematicId));

            boolean unsubscribed = waitForCondition(ctx, () ->
                !isSubscribed(server, schematicId));
            if (!unsubscribed) {
                throw new AssertionError("原理图删除清理后服务端订阅未收缩（MaterialListClose 链路断裂）");
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
                client.setScreenAndShow(null);
                InventoryWatcher.clearContext();
            });
        }
    }

    private boolean isSubscribed(TestDedicatedServerContext server, String schematicId) {
        return Boolean.TRUE.equals(server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayer("Player0");
            return player != null
                && Phase4Handler.getSubscribedSchematics(player).contains(schematicId);
        }));
    }

    private int activeClaimCount(TestDedicatedServerContext server, String schematicId, int materialId) {
        Integer count = onDatabase(server, db -> {
            try (var rs = db.executeQuery(
                "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
                schematicId, materialId)) {
                rs.next();
                return rs.getInt(1);
            }
        });
        return count == null ? -1 : count;
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
