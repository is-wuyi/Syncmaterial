package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

/**
 * 断线清理回归测试：退出服务器后所有按服务器隔离的客户端状态必须归零。
 *
 * 这些状态都是静态单例，漏清的后果是"换服后带着上个服的数据"：
 * HUD 挂着旧材料清单、仓库线框渲染上个服的箱子、取货需求量陈旧导致
 * 高亮错误、InventoryWatcher 持旧 schematicId 朝新服发它不认识的包。
 *
 * 这类 bug 只在"连服 A → 断开 → 连服 B"的时序下暴露，单测无法覆盖
 * （静态状态 + 真实连接生命周期），必须靠真实客户端断开一次连接。
 */
public class DisconnectCleanupClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-disconnect-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer()) {
            try (var connection = server.connect()) {
                ctx.waitTicks(20);

                // 铺满所有按服务器隔离的状态，模拟"在服务器 A 用了一圈功能"
                ctx.runOnClient(client -> {
                    SyncMaterialClient.openMaterialListScreen(
                        schematicId, "Disconnect Cleanup Test",
                        List.of(new MaterialEntry(1, new ItemStack(Items.DIRT), 64)),
                        true, true, "Player0", List.of(), true);
                    PickupModeState.setActive(true);
                    StagingAreaRenderer.getInstance().updateWarehouseAreas(
                        List.of(new StagingAreaConfigResponseS2CPacket.AreaInfo(
                            1, "仓库A", 0, 64, 0, 5, 70, 5, "minecraft:overworld")),
                        List.of(1));
                });
                ctx.waitForScreen(GuiMaterialList.class);

                // 前置断言：状态确实被铺上了，否则后面的"已清空"是假绿
                boolean allSet = ctx.computeOnClient(client ->
                    SyncMaterialClient.getActiveMaterialList() != null
                        && InventoryWatcher.isWatching()
                        && PickupModeState.isActive()
                        && !StagingAreaRenderer.getInstance().getWarehouseAreas().isEmpty()
                        && ClientProtocolState.isUsable());
                if (!allSet) {
                    throw new AssertionError("测试前置条件未满足：客户端状态未全部就绪，"
                        + "无法验证断线清理（否则清空断言会假绿）");
                }
            }

            // 连接在此处关闭，DISCONNECT 回调应已触发
            ctx.waitTicks(20);

            String leaked = ctx.computeOnClient(client -> {
                StringBuilder sb = new StringBuilder();
                if (SyncMaterialClient.getActiveMaterialList() != null) {
                    sb.append("HUD 材料清单未清空；");
                }
                if (InventoryWatcher.isWatching()) {
                    sb.append("背包监听未解除（会朝新服发旧 schematicId）；");
                }
                if (PickupModeState.isActive() || !PickupModeState.getNeeds().isEmpty()) {
                    sb.append("取货模式状态未清空；");
                }
                if (!StagingAreaRenderer.getInstance().getWarehouseAreas().isEmpty()) {
                    sb.append("仓库线框数据未清空；");
                }
                if (!StagingAreaRenderer.getInstance().getWarehouseContainers().isEmpty()) {
                    sb.append("仓库容器数据未清空；");
                }
                if (StagingAreaSelector.getInstance().isActive()) {
                    sb.append("准星选区模式未退出；");
                }
                if (ClientProtocolState.isUsable()) {
                    sb.append("协议握手状态未重置；");
                }
                return sb.toString();
            });

            if (!leaked.isEmpty()) {
                throw new AssertionError("断线后客户端状态残留：" + leaked);
            }
        } finally {
            ctx.runOnClient(client -> {
                InventoryWatcher.clearContext();
                PickupModeState.clear();
            });
        }
    }
}
