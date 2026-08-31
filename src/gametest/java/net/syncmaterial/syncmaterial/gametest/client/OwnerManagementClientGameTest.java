package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiOwnerManagementDialog;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 负责人管理弹窗端到端测试（标准 MaLiLib 弹窗重构后的行为锁定）。
 *
 * 覆盖整条链路：管理弹窗按钮 → C2S 包 → 服务端数据库变更 → S2C 响应 →
 * receiver 路由回弹窗 → 数据真身（GuiMaterialList）刷新。
 *
 * 玩家选择弹窗走真实网络流：构造时发的 PlayerListRequest 会得到服务端
 * 真实响应（含在线的 Player0），弹窗由 receiver 路由自动填充 —— 不 mock
 * 任何环节。转让流程还验证单选语义（选第二个玩家应替换第一个）。
 *
 * 测试钩子说明：openTransfer / openAddDeputy / toggleSelfClaim /
 * removeDeputy / selectPlayer / confirmSelection 都与对应按钮的 lambda
 * 或条目点击是同一方法，不存在第二条被测路径。
 */
public class OwnerManagementClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-owner-mgmt-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            insertSchematic(server, schematicId, "Player0");

            // 打开材料列表（isOwner/isMainOwner=true，ownerName=Player0）
            ctx.runOnClient(client -> Minecraft.getInstance().setScreenAndShow(new GuiMaterialList(
                schematicId, "OwnerMgmtTest", List.of(), true, true, "Player0", List.of(), true)));
            ctx.waitTicks(10);

            GuiMaterialList materialList = ctx.computeOnClient(client ->
                Minecraft.getInstance().gui.screen() instanceof GuiMaterialList m ? m : null);
            if (materialList == null) {
                throw new AssertionError("材料列表界面未打开");
            }

            // ===== 1. 打开管理弹窗（真实路径）=====
            ctx.runOnClient(client ->
                fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiOwnerManagementDialog(materialList)));
            ctx.waitTicks(5);

            // ===== 2. 自行认领开关：发包 → 服务端翻转 → 响应回流刷新数据真身 =====
            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiOwnerManagementDialog d) d.toggleSelfClaim();
                else throw new AssertionError("管理弹窗未打开");
            });
            ctx.waitTicks(10);

            // 服务端：初始 true，翻转一次后应为 false
            Boolean allowClaim = onDatabase(server, db -> db.getAllowSelfClaim(schematicId));
            if (!Boolean.FALSE.equals(allowClaim)) {
                throw new AssertionError("开关发包后服务端 allow_self_claim 应为 false，实际为 " + allowClaim);
            }
            // 客户端：OwnerActionResponse 回流后数据真身也应刷新
            boolean clientKnows = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                !materialList.isAllowSelfClaim()));
            if (!clientKnows) {
                throw new AssertionError("响应回流后客户端 allowSelfClaim 未刷新为 false");
            }

            // ===== 3. 添加副负责人：弹窗 → 选择弹窗（真实响应）→ 选择 → 确认 =====
            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiOwnerManagementDialog d) d.openAddDeputy();
                else throw new AssertionError("管理弹窗未打开，无法打开添加副负责人");
            });
            // 等真实 PlayerListResponse 到达（服务端返回在线玩家列表）
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

            // 服务端：副负责人已入库
            List<String> deputies = onDatabase(server, db -> db.getDeputyOwners(schematicId));
            if (!deputies.contains("DeputyA")) {
                throw new AssertionError("确认后服务端 deputy_owners 应含 DeputyA，实际为 " + deputies);
            }
            // 客户端：成功响应应关闭选择弹窗并回到管理界面，数据真身刷新
            Boolean backToMgmt = waitForCondition(ctx, () -> ctx.computeOnClient(client ->
                currentScreen() instanceof GuiOwnerManagementDialog
                    && materialList.getDeputyOwners().contains("DeputyA")));
            if (!backToMgmt) {
                throw new AssertionError("添加副负责人成功后未回到管理界面或数据未刷新");
            }

            // ===== 4. 移除副负责人：× 按钮路径 =====
            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiOwnerManagementDialog d) d.removeDeputy("DeputyA");
                else throw new AssertionError("管理弹窗未打开，无法移除副负责人");
            });
            ctx.waitTicks(10);

            List<String> deputiesAfter = onDatabase(server, db -> db.getDeputyOwners(schematicId));
            if (deputiesAfter.contains("DeputyA")) {
                throw new AssertionError("移除后服务端 deputy_owners 仍含 DeputyA: " + deputiesAfter);
            }

            // ===== 5. 转让：选择弹窗单选语义 + 服务端主负责人变更 =====
            ctx.runOnClient(client -> {
                if (currentScreen() instanceof GuiOwnerManagementDialog d) d.openTransfer();
                else throw new AssertionError("管理弹窗未打开，无法打开转让");
            });
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

            // 服务端：主负责人已变更为最终选择的玩家
            String uploadedBy = onDatabase(server, db -> db.getUploadedBy(schematicId));
            if (!"SecondPick".equals(uploadedBy)) {
                throw new AssertionError("转让后服务端主负责人应为 SecondPick，实际为 " + uploadedBy);
            }

            // 清理
            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> Minecraft.getInstance().setScreenAndShow(null));
        }
    }

    private static Screen currentScreen() {
        return Minecraft.getInstance().gui.screen();
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
