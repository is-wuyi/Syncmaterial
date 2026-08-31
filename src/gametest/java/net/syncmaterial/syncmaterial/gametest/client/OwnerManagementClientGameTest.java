package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 右侧管理栏端到端测试（Litematica 双栏布局重构后的行为锁定）。
 *
 * 覆盖整条链路：右栏动作 → C2S 包 → 服务端数据库变更 → S2C 响应 →
 * receiver 路由 → 数据真身（GuiMaterialList）刷新 + 右栏重建。
 *
 * 玩家选择弹窗走真实网络流：打开时发的 PlayerListRequest 得到服务端
 * 真实响应，弹窗由 receiver 路由自动填充。REMOVE_DEPUTY 模式例外——
 * 名单由客户端当前副负责人预填（服务端玩家列表不含离线副负责人），
 * 测试验证它不打网络请求即已就绪。
 *
 * 测试钩子说明：toggleSelfClaim / removeDeputy / openTransfer /
 * openAddDeputy / openRemoveDeputies / selectPlayer / confirmSelection
 * 都与右栏按钮或条目点击是同一方法，不存在第二条被测路径。
 */
public class OwnerManagementClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-owner-mgmt-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            insertSchematic(server, schematicId, "Player0");

            // 打开材料列表（isOwner/isMainOwner=true，右栏在 initGui 里构建）
            ctx.runOnClient(client -> client.setScreen(new GuiMaterialList(
                schematicId, "OwnerMgmtTest", List.of(), true, true, "Player0", List.of(), true)));
            ctx.waitTicks(10);

            GuiMaterialList materialList = ctx.computeOnClient(client ->
                MinecraftClient.getInstance().currentScreen instanceof GuiMaterialList m ? m : null);
            if (materialList == null) {
                throw new AssertionError("材料列表界面未打开");
            }

            // ===== 1. 自行认领开关：右栏按钮 → 服务端翻转 → 响应回流刷新 =====
            ctx.runOnClient(client -> materialList.toggleSelfClaim());
            ctx.waitTicks(10);

            Boolean allowClaim = onDatabase(server, db -> db.getAllowSelfClaim(schematicId));
            if (!Boolean.FALSE.equals(allowClaim)) {
                throw new AssertionError("开关发包后服务端 allow_self_claim 应为 false，实际为 " + allowClaim);
            }
            // 兜底回调路径：currentScreen 是列表本身，updateOwnerState 重建右栏
            boolean clientKnows = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiMaterialList m && !m.isAllowSelfClaim()));
            if (!clientKnows) {
                throw new AssertionError("响应回流后客户端 allowSelfClaim 未刷新为 false");
            }

            // ===== 2. 添加副负责人：右栏按钮 → 选择弹窗（真实响应）→ 确认 =====
            ctx.runOnClient(client -> materialList.openAddDeputy());
            boolean listLoaded = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiPlayerSelectDialog s && s.hasLoadedPlayers()));
            if (!listLoaded) {
                throw new AssertionError("玩家选择弹窗未收到真实玩家列表响应");
            }

            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiPlayerSelectDialog s) {
                    s.selectPlayer("DeputyA");
                    s.confirmSelection();
                } else {
                    throw new AssertionError("玩家选择弹窗未打开");
                }
            });
            ctx.waitTicks(15);

            List<String> deputies = onDatabase(server, db -> db.getDeputyOwners(schematicId));
            if (!deputies.contains("DeputyA")) {
                throw new AssertionError("确认后服务端 deputy_owners 应含 DeputyA，实际为 " + deputies);
            }
            // 成功后回材料列表，数据真身已刷新
            Boolean backToList = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiMaterialList m
                    && m.getDeputyOwners().contains("DeputyA")));
            if (!backToList) {
                throw new AssertionError("添加副负责人成功后未回到材料列表或数据未刷新");
            }

            // ===== 3. 移除副负责人：右栏 × 按钮路径（无弹窗）=====
            ctx.runOnClient(client -> materialList.removeDeputy("DeputyA"));
            ctx.waitTicks(10);

            List<String> deputiesAfter = onDatabase(server, db -> db.getDeputyOwners(schematicId));
            if (deputiesAfter.contains("DeputyA")) {
                throw new AssertionError("移除后服务端 deputy_owners 仍含 DeputyA: " + deputiesAfter);
            }

            // ===== 4. 转让：弹窗单选语义 + 服务端主负责人变更 =====
            ctx.runOnClient(client -> materialList.openTransfer());
            boolean transferLoaded = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiPlayerSelectDialog s && s.hasLoadedPlayers()));
            if (!transferLoaded) {
                throw new AssertionError("转让弹窗未收到真实玩家列表响应");
            }

            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiPlayerSelectDialog s) {
                    s.selectPlayer("FirstPick");
                    s.selectPlayer("SecondPick"); // 单选：应替换而非叠加
                    List<String> selected = s.getSelectedPlayers();
                    if (selected.size() != 1 || !selected.contains("SecondPick")) {
                        throw new AssertionError("转让模式应为单选，实际选中: " + selected);
                    }
                    s.confirmSelection();
                } else {
                    throw new AssertionError("转让弹窗未打开");
                }
            });
            ctx.waitTicks(15);

            String uploadedBy = onDatabase(server, db -> db.getUploadedBy(schematicId));
            if (!"SecondPick".equals(uploadedBy)) {
                throw new AssertionError("转让后服务端主负责人应为 SecondPick，实际为 " + uploadedBy);
            }

            // 转让后本人不再是主负责人——回列表验证 isMainOwner 已翻转（右栏转让/移除按钮应消失）
            Boolean ownershipSynced = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiMaterialList m && !m.isMainOwner()));
            if (!ownershipSynced) {
                throw new AssertionError("转让后客户端 isMainOwner 未刷新为 false");
            }

            // ===== 5. 副负责人溢出：超上限折叠 + 批量移除弹窗（预设名单）=====
            // 先把主负责人改回 Player0（第 4 步已转让出去，服务端直改数据库恢复测试条件）
            onDatabase(server, db -> {
                db.executeUpdate("UPDATE schematics SET uploaded_by = 'Player0' WHERE id = ?", schematicId);
                return null;
            });
            // 重新打开界面（isMainOwner 按构造参数仍为 true，与真实场景一致：
            // 玩家刷新列表时服务端会下发最新负责人身份）
            ctx.runOnClient(client -> client.setScreen(new GuiMaterialList(
                schematicId, "OwnerMgmtTest", List.of(), true, true, "Player0", List.of(), true)));
            ctx.waitTicks(10);
            GuiMaterialList freshList = ctx.computeOnClient(client ->
                MinecraftClient.getInstance().currentScreen instanceof GuiMaterialList m ? m : null);

            // 服务端预置 6 个副负责人（超过平铺上限 4），刷新客户端数据
            onDatabase(server, db -> {
                for (String name : List.of("D1", "D2", "D3", "D4", "D5", "D6")) {
                    db.addDeputyOwner(schematicId, name);
                }
                return null;
            });
            ctx.runOnClient(client -> freshList.updateOwnerState("Player0",
                List.of("D1", "D2", "D3", "D4", "D5", "D6"), true));
            ctx.waitTicks(5);

            if (!freshList.hasDeputyOverflow()) {
                throw new AssertionError("6 个副负责人应触发溢出折叠");
            }

            // 批量移除弹窗：预设名单，不发请求即就绪
            ctx.runOnClient(client -> freshList.openRemoveDeputies());
            boolean presetReady = ctx.computeOnClient(client ->
                currentScreen() instanceof GuiPlayerSelectDialog s && s.hasLoadedPlayers());
            if (!presetReady) {
                throw new AssertionError("移除弹窗的预设副负责人名单未就绪");
            }

            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiPlayerSelectDialog s) {
                    s.selectPlayer("D5");
                    s.selectPlayer("D6");
                    s.confirmSelection();
                } else {
                    throw new AssertionError("移除弹窗未打开");
                }
            });
            ctx.waitTicks(15);

            List<String> deputiesFinal = onDatabase(server, db -> db.getDeputyOwners(schematicId));
            if (deputiesFinal.contains("D5") || deputiesFinal.contains("D6")) {
                throw new AssertionError("批量移除后服务端仍含 D5/D6: " + deputiesFinal);
            }
            if (deputiesFinal.size() != 4) {
                throw new AssertionError("批量移除后应剩 4 位副负责人，实际为 " + deputiesFinal);
            }

            // 清理
            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> client.setScreen(null));
        }
    }

    private static net.minecraft.client.gui.screen.Screen currentScreen() {
        return MinecraftClient.getInstance().currentScreen;
    }

    private void insertSchematic(TestDedicatedServerContext server, String schematicId, String uploadedBy) {
        onDatabase(server, db -> {
            db.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                schematicId, "Owner Mgmt Test", "/owner-mgmt.litematic", uploadedBy);
            return null;
        });
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
