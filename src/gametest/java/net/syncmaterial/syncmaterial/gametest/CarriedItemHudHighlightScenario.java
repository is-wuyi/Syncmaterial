package net.syncmaterial.syncmaterial.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryScanner;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupHighlight;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialHudProjection;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/**
 * Backend-neutral carried-item scenario: real warehouse chest, real container click,
 * production scanner/HUD projection and the real render-Mixin highlight.
 *
 * <p>Subclasses provide only their genuine backend fixture (legacy SQLite/staging manager
 * or isolated Foundation store/area scanner); the UI, input and assertion sequence is
 * shared so both backends are tested against the same observable contract.</p>
 *
 * <p>The 64-stone stack is moved out by {@code context.getInput()} clicking slot 0.
 * After the server confirms it, the authoritative warehouse aggregate drops 192 → 128
 * and the team gap stays 0. The cursor sample must reduce only the local pickup need
 * 128 → 64, never the team facts. Highlight assertions are made after a later render
 * frame because the Mixin cache is only observable then.</p>
 */
public abstract class CarriedItemHudHighlightScenario {

    protected static final String STONE = "minecraft:stone";
    protected static final int REQUIRED = 128;
    protected static final int WAREHOUSE_STOCK = 192;
    protected static final int WAREHOUSE_AFTER_PICKUP = 128;

    public record Fixture(int materialId, int warehouseId) {}

    protected abstract String projectId();

    /** Creates the real project and warehouse for this backend. */
    protected abstract Fixture prepareFixture(ClientGameTestContext ctx, TestDedicatedServerContext server);

    protected abstract void cleanupFixture(TestDedicatedServerContext server, Fixture fixture);

    protected final void runScenario(ClientGameTestContext ctx) {
        boolean originalHud = ctx.computeOnClient(client ->
                Configs.Generic.HUD_ENABLED.getBooleanValue());
        Fixture[] holder = new Fixture[1];
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
          try {
            ctx.waitTicks(20);
            holder[0] = prepareFixture(ctx, server);
            final Fixture fixture = holder[0];
            final int mid = fixture.materialId();
            final String projectId = projectId();

            ctx.waitFor(client -> ClientProtocolState.serverSupports(
                    ProtocolVersion.SCOPED_PROJECT_VIEWS));
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                    new MaterialStatsRequestC2SPacket(projectId)));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitFor(client -> ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
            waitForProjectProgress(ctx, projectId);

            GuiMaterialList gui = ctx.computeOnClient(client ->
                    (GuiMaterialList) ClientScreenAccess.currentScreen(client));
            SyncMaterialList list = ctx.computeOnClient(client -> gui.getMaterialList());

            // Baseline: warehouse alone covers the requirement, so team gap is 0 and
            // collection HUD has no row.
            if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
                MaterialListEntry current = entryIn(list, mid);
                return current.getWarehouseCount() == WAREHOUSE_STOCK
                        && current.getCountMissing() == 0
                        && current.progressView(list).teamMissing() == 0;
            }))) {
                throw new AssertionError("仓库基线未建立: warehouse="
                        + ctx.computeOnClient(client -> entryIn(list, mid).getWarehouseCount())
                        + ", missing=" + ctx.computeOnClient(client -> entryIn(list, mid).getCountMissing()));
            }

            ClientTestInput.pressMaterialRowButton(ctx, gui, mid,
                    "syncmaterial.gui.button.join_collaboration");
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("真实点击 JOIN 按钮后未进入 pending 状态");
            }
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entryIn(list, mid).isCurrentPlayerClaimed()))) {
                throw new AssertionError("前置认领未生效");
            }

            ctx.runOnClient(client -> gui.togglePickupMode());
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    PickupModeState.isActive() && SyncMaterialClient.livePickupNeeds()
                            .getOrDefault(STONE, 0L) == REQUIRED))) {
                throw new AssertionError("取货需求量未建立: needs="
                        + ctx.computeOnClient(client -> SyncMaterialClient.livePickupNeeds()));
            }
            long[] baseline = ctx.computeOnClient(client -> {
                MaterialListEntry current = entryIn(list, mid);
                List<ProjectProgressMaterialView> views = list.getMaterialsAll().stream()
                        .map(candidate -> candidate.progressView(list)).toList();
                MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                        views, true, InventoryScanner.liveCountsByItemId(client.player),
                        list.isPreparationKnown());
                MaterialHudProjection.Model collection = MaterialHudProjection.compute(
                        views, false, InventoryScanner.liveCountsByItemId(client.player),
                        list.isPreparationKnown());
                return new long[]{SyncMaterialClient.livePickupNeeds().getOrDefault(STONE, 0L),
                        current.getWarehouseCount(), current.progressView(list).teamMissing(),
                        pickup.rows().size() == 1 ? pickup.rows().get(0).count() : -1,
                        collection.rows().size()};
            });
            if (baseline[0] != REQUIRED || baseline[1] != WAREHOUSE_STOCK
                    || baseline[2] != 0 || baseline[3] != REQUIRED || baseline[4] != 0) {
                throw new AssertionError("取货/团队基线不符: need=" + baseline[0]
                        + ", warehouse=" + baseline[1] + ", teamMissing=" + baseline[2]
                        + ", pickupRows=" + baseline[3] + ", collectionRows=" + baseline[4]);
            }

            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(false);
                list.getHudRenderer().setShouldRender(false);
                gui.closeGui(false);
            });
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    ClientScreenAccess.currentScreen(client) == null
                            && SyncMaterialClient.getActiveMaterialList() == list))) {
                throw new AssertionError("关闭材料列表后活跃项目丢失");
            }

            openChest(ctx, server);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    ClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?>))) {
                throw new AssertionError("服务端 openMenu 后客户端未打开容器界面");
            }
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    Set.of(0, 1).equals(Set.copyOf(PickupHighlight.lastHighlightedSlots()))))) {
                throw new AssertionError("取货高亮初始应为 {0,1}，实际 "
                        + ctx.computeOnClient(client -> PickupHighlight.lastHighlightedSlots()));
            }

            // ===== Real mouse click takes slot 0 into the cursor =====
            ClientTestInput.pressContainerSlot(ctx, 0);
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    client.player != null && client.player.containerMenu != null
                            && client.player.containerMenu.getCarried().getItem() == Items.STONE
                            && client.player.containerMenu.getCarried().getCount() == 64))) {
                throw new AssertionError("真实点击箱子 0 号槽后光标未持有 64 石头: "
                        + ctx.computeOnClient(client -> client.player.containerMenu.getCarried()));
            }
            if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
                MaterialListEntry current = entryIn(list, mid);
                return current.getWarehouseCount() == WAREHOUSE_AFTER_PICKUP
                        && current.getCountMissing() == 0
                        && current.progressView(list).teamMissing() == 0;
            }))) {
                throw new AssertionError("取出箱子物品后仓库/团队缺口未更新: warehouse="
                        + ctx.computeOnClient(client -> entryIn(list, mid).getWarehouseCount())
                        + ", missing=" + ctx.computeOnClient(client -> entryIn(list, mid).getCountMissing())
                        + ", teamMissing=" + ctx.computeOnClient(client ->
                        entryIn(list, mid).progressView(list).teamMissing()));
            }

            // ===== Single client task: production scanner → needs → HUD model =====
            ctx.runOnClient(client -> {
                Map<String, Long> live = InventoryScanner.liveCountsByItemId(client.player);
                if (live.getOrDefault(STONE, 0L) != 64) {
                    throw new AssertionError("生产 InventoryScanner 未计入真实光标持物: " + live);
                }
                long liveNeed = SyncMaterialClient.livePickupNeeds().getOrDefault(STONE, 0L);
                if (liveNeed != 64) {
                    throw new AssertionError("真实光标持物后取货需求应降为 64，实际为 " + liveNeed);
                }
                MaterialListEntry current = entryIn(list, mid);
                List<ProjectProgressMaterialView> views = list.getMaterialsAll().stream()
                        .map(candidate -> candidate.progressView(list)).toList();
                MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                        views, true, live, list.isPreparationKnown());
                MaterialHudProjection.Model collection = MaterialHudProjection.compute(
                        views, false, live, list.isPreparationKnown());
                if (pickup.rows().size() != 1 || pickup.rows().get(0).count() != 64) {
                    throw new AssertionError("取货 HUD 行未即时反映真实光标持物: " + pickup.rows());
                }
                if (!collection.rows().isEmpty()) {
                    throw new AssertionError("仓库已覆盖需求时收集 HUD 不应出现团队缺口行: "
                            + collection.rows());
                }
                if (current.getWarehouseCount() != WAREHOUSE_AFTER_PICKUP
                        || current.getCountMissing() != 0
                        || current.progressView(list).teamMissing() != 0) {
                    throw new AssertionError("光标样本篡改了权威团队事实: warehouse="
                            + current.getWarehouseCount() + ", entryMissing="
                            + current.getCountMissing() + ", teamMissing="
                            + current.progressView(list).teamMissing());
                }
            });

            // The render Mixin produces lastHighlightedSlots; this is a later-frame assertion.
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    Set.of(1).equals(Set.copyOf(PickupHighlight.lastHighlightedSlots()))))) {
                throw new AssertionError("取出 0 号槽后高亮应在下一帧变为 {1}，实际 "
                        + ctx.computeOnClient(client -> PickupHighlight.lastHighlightedSlots()));
            }

            // ===== Click the now-empty slot to put the stack back =====
            ClientTestInput.pressContainerSlot(ctx, 0);
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    client.player.containerMenu.getCarried().isEmpty()
                            && SyncMaterialClient.livePickupNeeds().getOrDefault(STONE, 0L) == REQUIRED
                            && entryIn(list, mid).getWarehouseCount() == WAREHOUSE_STOCK
                            && entryIn(list, mid).progressView(list).teamMissing() == 0
                            && Set.of(0, 1).equals(
                            Set.copyOf(PickupHighlight.lastHighlightedSlots()))))) {
                throw new AssertionError("放回物品后需求/仓库/高亮未恢复: carried="
                        + ctx.computeOnClient(client -> client.player.containerMenu.getCarried())
                        + ", needs=" + ctx.computeOnClient(client -> SyncMaterialClient.livePickupNeeds())
                        + ", warehouse=" + ctx.computeOnClient(client -> entryIn(list, mid).getWarehouseCount())
                        + ", slots=" + ctx.computeOnClient(client -> PickupHighlight.lastHighlightedSlots()));
            }
            if (!ctx.computeOnClient(client -> !Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && !list.getHudRenderer().getShouldRender())) {
                throw new AssertionError("测试期间 HUD 开关被意外打开");
            }
          } finally {
            if (holder[0] != null) cleanupFixture(server, holder[0]);
          }
        } finally {
            ClientTestInput.releaseLeftMouse(ctx);
            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(originalHud);
                ClientScreenAccess.showScreen(client, null);
                PickupModeState.clear();
                PickupHighlight.invalidate();
                InventoryWatcher.clearContext();
            });
        }
    }

    /** Places the real chest used by both backends; subclasses configure their own area. */
    protected static BlockPos placeChestWithStone(TestDedicatedServerContext server) {
        return server.computeOnServer(instance -> {
            var player = instance.getPlayerList().getPlayerByName("Player0");
            if (player == null) throw new AssertionError("Player0 不在线");
            var level = player.level();
            BlockPos chestPos = player.blockPosition().offset(1, 0, 0);
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
                throw new AssertionError("光标测试箱子创建失败");
            }
            for (int slot = 0; slot < 3; slot++) {
                chest.setItem(slot, new ItemStack(Items.STONE, 64));
            }
            chest.setChanged();
            return chestPos;
        });
    }

    private static void openChest(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        server.computeOnServer(instance -> {
            var player = instance.getPlayerList().getPlayerByName("Player0");
            if (player == null) throw new AssertionError("Player0 不在线");
            var level = player.level();
            BlockPos chestPos = player.blockPosition().offset(1, 0, 0);
            var provider = level.getBlockState(chestPos).getMenuProvider(level, chestPos);
            if (provider == null) throw new AssertionError("仓库箱子未提供 MenuProvider");
            player.openMenu(provider);
            return null;
        });
    }

    private static void waitForProjectProgress(ClientGameTestContext ctx, String projectId) {
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return projectId.equals(gui.getMaterialList().getSchematicId())
                    && gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("材料列表未收到协议 5 权威进度");
        }
    }

    private static MaterialListEntry entryIn(SyncMaterialList list, int mid) {
        for (MaterialListEntry candidate : list.getMaterialsAll()) {
            if (candidate.getDatabaseId() == mid) return candidate;
        }
        throw new AssertionError("材料列表中找不到 id=" + mid);
    }

    private static boolean waitFor(ClientGameTestContext ctx,
                                   java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 200; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }
}
