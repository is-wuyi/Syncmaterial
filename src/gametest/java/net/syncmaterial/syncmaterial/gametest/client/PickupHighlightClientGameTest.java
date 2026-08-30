package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.PickupHighlight;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 取货高亮端到端测试：真实打开箱子界面，断言 Mixin 实际点亮的格子数正确。
 *
 * 覆盖的盲区：Mixin 注入是否真的生效。@Inject 的 method 是字符串，写错了
 * 编译期不报错、运行时静默不注入 —— 变异实验证实过这种错误能让整套测试全绿
 * 逃逸（把 extractLabels 改成旧版的 drawForeground，单测无一失败）。
 * 这里读 PickupHighlight.lastHighlightedSlots()，为空即说明注入没生效。
 * 已用该变异验证本测试能抓住它。
 *
 * 顺带覆盖整条链路：材料清单 → 协作认领 → 取货模式 → 需求量 → 容器槽位选择 →
 * Mixin 渲染，任一环断裂高亮都会为空或数量不对。
 *
 * 不覆盖的部分（诚实记录，以免日后误判）：「取走一个后后面的物品闪一下」那个
 * 瞬态时序问题，本测试抓不住。因为 PickupModeState 的 tick 缓存最终会收敛到
 * 正确值，而端到端断言只能看最终态 —— 实测把 Mixin 的数据源改回读缓存
 * （即引入该 bug），本测试仍然通过。那一点由单测
 * PickupHighlightTest.takingOneAtATime_neverHighlightsExtraSlot 在算法层覆盖，
 * 它把需求量与容器内容逐步同步推进，能精确断言每一步的格子数。
 *
 * 场景：需求 128，箱子前三格各 64 个石头。
 * - 初始应点亮 0、1 两格（64+64 刚好覆盖 128），第 2 格不该亮
 * - 给玩家 64 个石头后需求降为 64，应收缩到只点亮第 0 格
 */
public class PickupHighlightClientGameTest implements FabricClientGameTest {

    /** 需求量：刻意设成两格整数倍，多点亮一格能被数量断言立刻抓住 */
    private static final int TOTAL_NEEDED = 128;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-pickup-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, database -> {
                database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Pickup Highlight Test", "/pickup-test.litematic", "Player0");
                database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:stone", TOTAL_NEEDED);
                try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                    result.next();
                    return result.getInt(1);
                }
            });

            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Pickup Highlight Test",
                List.of(new MaterialEntry(materialId, new ItemStack(Items.STONE), TOTAL_NEEDED)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);

            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new JoinCollaborationC2SPacket(schematicId, materialId, Map.of())));
            ctx.waitTicks(10);

            enablePickupMode(ctx);

            // 前置断言：需求量必须先算出来，否则后面「高亮为空」分不清是
            // Mixin 没注入还是需求本来就是空的
            Map<String, Integer> needs = ctx.computeOnClient(client ->
                Map.copyOf(PickupModeState.getNeeds()));
            if (!Integer.valueOf(TOTAL_NEEDED).equals(needs.get("minecraft:stone"))) {
                throw new AssertionError(
                    "前置条件不成立：取货需求量应为 " + TOTAL_NEEDED + "，实际为 " + needs);
            }

            // 关掉材料列表，模拟真实流程：玩家开完取货模式就去开箱子。
            // 取货状态与 HUD 数据挂在 HUD 生命周期上，关界面不该影响它们
            ctx.runOnClient(client -> client.setScreen(null));
            ctx.waitTicks(5);

            placeChestAndOpen(server);
            // 界面由服务端 openMenu 推来，客户端要等它落地；不能只 waitTicks，
            // 首次进服时区块加载与实体同步会拖慢这一步
            if (!waitForContainerScreen(ctx)) {
                throw new AssertionError("服务端 openMenu 后客户端未打开容器界面");
            }
            // 高亮在渲染时计算，等几帧确保注入点至少跑过一次
            ctx.waitTicks(10);

            Set<Integer> initial = ctx.computeOnClient(client ->
                Set.copyOf(PickupHighlight.lastHighlightedSlots()));
            if (initial.isEmpty()) {
                throw new AssertionError(
                    "打开箱子后没有任何格子被点亮 —— Mixin 注入未生效，或需求量未传达到渲染路径");
            }
            if (!Set.of(0, 1).equals(initial)) {
                throw new AssertionError(
                    "需求 " + TOTAL_NEEDED + "、每格 64 时应点亮第 0、1 格，实际点亮 " + initial);
            }

            // 需求变化后高亮必须随之收缩：拿到 64 个后只该点亮第 0 格。
            // 注意这断言的是最终态，不是瞬态时序（见类注释）
            server.runCommand("give @a minecraft:stone 64");
            boolean shrank = waitForHighlight(ctx, Set.of(0));
            Set<Integer> afterPickup = ctx.computeOnClient(client ->
                Set.copyOf(PickupHighlight.lastHighlightedSlots()));
            if (!shrank) {
                throw new AssertionError(
                    "背包已有 64 个石头后需求降为 64，应只点亮第 0 格，实际点亮 " + afterPickup);
            }
        } finally {
            ctx.runOnClient(client -> {
                client.setScreen(null);
                PickupModeState.clear();
                PickupHighlight.invalidate();
                net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
            });
        }
    }

    /**
     * 开启取货模式并铺上认领与仓库数据。
     *
     * 直接改 MaterialListEntry 而不走服务端广播：本测试的被测对象是
     * 「需求量 → 格子高亮」这一段，协作状态如何同步过来已由
     * InventorySyncClientGameTest 覆盖，此处不重复。
     */
    private void enablePickupMode(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            var active = SyncMaterialClient.getActiveMaterialList();
            if (active != null) {
                for (var entry : active.getMaterialsAll()) {
                    // 仓库有货才会产生取货需求（pickupMissing 以仓库存量为上限）
                    entry.setWarehouseCount(TOTAL_NEEDED);
                    entry.setParticipants(List.of(new MaterialListEntry.ParticipantData(
                        client.player.getName().getString(), 0)));
                }
            }
            PickupModeState.setActive(true);
            PickupModeState.recompute(active == null ? null : active.getMaterialsAll());
        });
        ctx.waitTicks(5);
    }

    /**
     * 在玩家脚下旁边放一个装满石头的箱子，并直接给该玩家打开它。
     *
     * 放置位置取玩家实际坐标而非硬编码：测试世界的出生点在 y=-60，
     * 硬编码 y=64 会把箱子放到空中未加载区块里，openMenu 拿不到 MenuProvider。
     *
     * 用服务端 openMenu 而非合成右键：右键要求站位与视线都对准方块，
     * 且会被本模组的选区交互拦截逻辑影响。openMenu 走的是与真实开箱同一条
     * ClientboundOpenScreen 路径，客户端收到的界面完全一样。
     */
    private void placeChestAndOpen(TestDedicatedServerContext server) {
        server.runOnServer(instance -> {
            var players = instance.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                throw new AssertionError("服务端没有已连接的玩家，无法打开容器界面");
            }
            var player = players.get(0);
            var level = player.getWorld();
            BlockPos chestPos = player.getBlockPos().add(1, 0, 0);

            level.setBlockState(chestPos, Blocks.CHEST.getDefaultState(), 3);
            if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                for (int slot = 0; slot < 3; slot++) {
                    chest.setStack(slot, new ItemStack(Items.STONE, 64));
                }
                chest.markDirty();
            } else {
                throw new AssertionError("箱子放置失败，位置 " + chestPos
                    + " 的方块实体不是 ChestBlockEntity");
            }

            var provider = level.getBlockState(chestPos).createScreenHandlerFactory(level, chestPos);
            if (provider == null) {
                throw new AssertionError("箱子未提供 MenuProvider，位置 " + chestPos);
            }
            player.openHandledScreen(provider);
        });
    }

    /** 轮询等待容器界面打开，返回是否等到 */
    private boolean waitForContainerScreen(ClientGameTestContext ctx) {
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            boolean open = ctx.computeOnClient(client ->
                client.currentScreen instanceof HandledScreen<?>);
            if (open) {
                return true;
            }
        }
        return false;
    }

    /** 高亮在渲染线程算，给它几帧收敛的时间 */
    private boolean waitForHighlight(ClientGameTestContext ctx, Set<Integer> expected) {
        for (int elapsed = 0; elapsed < 60; elapsed += 5) {
            ctx.waitTicks(5);
            Set<Integer> current = ctx.computeOnClient(client ->
                Set.copyOf(PickupHighlight.lastHighlightedSlots()));
            if (expected.equals(current)) {
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
