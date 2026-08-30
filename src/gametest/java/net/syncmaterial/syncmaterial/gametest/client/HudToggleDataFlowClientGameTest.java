package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;

/**
 * HUD 开关与数据管线解耦回归测试：关掉 HUD 后取货需求量仍须持续更新。
 *
 * 复现的是重构前的真实缺陷：取货需求量原先在 MaterialListHudRenderer.render()
 * 里计算，而 render 只在 HUD 实际显示时才被调用 —— HUD 总闸一关，需求永不
 * 更新，格子高亮与仓库线框停在陈旧数据上，甚至从未初始化就完全不高亮。
 *
 * 这是"把数据更新挂在渲染回调上"这一类错误的通用防线：HUD 的两个开关
 * （总闸 HUD_ENABLED 与界面分闸 shouldRender）都只应影响画不画，不应影响
 * 任何数据是否被计算。
 */
public class HudToggleDataFlowClientGameTest implements FabricClientGameTest {

    /** PickupModeState 每 10 tick 重算一次，留足两个周期 */
    private static final int RECOMPUTE_WAIT_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-hud-toggle-" + System.nanoTime();
        boolean originalMasterSwitch = ctx.computeOnClient(client ->
            Configs.Generic.HUD_ENABLED.getBooleanValue());

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            // 材料：总量 64、备货区 0、背包 0、仓库 64 → 需求 = 64
            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "HUD Toggle Test",
                List.of(new MaterialEntry(1, new ItemStack(Items.DIRT), 64)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);

            // 把 HUD 两个开关都关掉，再开启取货模式并铺上需求数据
            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(false);
                var active = SyncMaterialClient.getActiveMaterialList();
                if (active != null) {
                    active.getHudRenderer().setShouldRender(false);
                    for (var entry : active.getMaterialsAll()) {
                        entry.setWarehouseCount(64);
                        entry.setParticipants(List.of(
                            new net.syncmaterial.syncmaterial.client.gui.MaterialListEntry
                                .ParticipantData(client.player.getName().getString(), 0)));
                    }
                }
                PickupModeState.setActive(true);
                PickupModeState.recompute(
                    active == null ? null : active.getMaterialsAll());
            });

            Map<String, Integer> initial = ctx.computeOnClient(client ->
                Map.copyOf(PickupModeState.getNeeds()));
            if (!Integer.valueOf(64).equals(initial.get("minecraft:dirt")))
                throw new AssertionError("HUD 关闭时初始需求量应为 64，实际为 " + initial);

            // 关键断言：HUD 全关的情况下改变数据，需求量仍须被 tick 重算。
            // 把仓库存量降到 10 → 需求应随之变为 10
            ctx.runOnClient(client -> {
                var active = SyncMaterialClient.getActiveMaterialList();
                if (active != null) {
                    for (var entry : active.getMaterialsAll()) {
                        entry.setWarehouseCount(10);
                    }
                }
            });
            ctx.waitTicks(RECOMPUTE_WAIT_TICKS);

            Map<String, Integer> updated = ctx.computeOnClient(client ->
                Map.copyOf(PickupModeState.getNeeds()));
            if (!Integer.valueOf(10).equals(updated.get("minecraft:dirt"))) {
                throw new AssertionError(
                    "HUD 关闭期间数据变化后需求量应更新为 10，实际为 " + updated
                        + "（数据更新被错误地挂在了 HUD 渲染上）");
            }

            // 反向确认：HUD 开关本身没被测试改坏，仍处于关闭态
            boolean stillHidden = ctx.computeOnClient(client ->
                !Configs.Generic.HUD_ENABLED.getBooleanValue());
            if (!stillHidden) {
                throw new AssertionError("测试期间 HUD 总闸被意外打开，本次断言不成立");
            }
        } finally {
            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(originalMasterSwitch);
                PickupModeState.clear();
                net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
            });
        }
    }
}
