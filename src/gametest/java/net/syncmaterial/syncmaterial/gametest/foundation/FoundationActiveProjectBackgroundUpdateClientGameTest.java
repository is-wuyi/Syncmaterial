package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.ProjectInterestLeases;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Foundation active-project background update acceptance test.
 *
 * <p>Same consumer contract as the legacy counterpart: after the GUI is closed and
 * both HUD gates are off, the project view keeps exactly {@code MATERIALS|TEAM} and
 * still receives authoritative progress from the isolated Foundation runtime when a
 * real backpack changes and when a real staging chest is rescanned.</p>
 */
public class FoundationActiveProjectBackgroundUpdateClientGameTest implements FabricClientGameTest {

    private static final String PROJECT = "foundation-background-" + UUID.randomUUID();
    private static final PlayerIdentity PROJECT_OWNER =
            new PlayerIdentity(UUID.randomUUID(), "FoundationOwner");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean originalHud = ctx.computeOnClient(client ->
                Configs.Generic.HUD_ENABLED.getBooleanValue());
        int materialId = -1;
        int areaId = -1;
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
          try {
            ctx.waitTicks(20);
            materialId = server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                runtime.store().ingest(new SchematicMetadata(
                                PROJECT, "Foundation background", Path.of("foundation-background.litematic"),
                                "background", PROJECT_OWNER),
                        List.of(new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
                return runtime.store().findProject(PROJECT).join().orElseThrow()
                        .materials().get(0).id();
            });
            final int mid = materialId;

            ctx.waitFor(client -> ClientProtocolState.serverSupports(
                    ProtocolVersion.SCOPED_PROJECT_VIEWS));
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                    new MaterialStatsRequestC2SPacket(PROJECT)));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitFor(client -> ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
            waitForProjectProgress(ctx);

            GuiMaterialList gui = ctx.computeOnClient(client ->
                    (GuiMaterialList) ClientScreenAccess.currentScreen(client));
            SyncMaterialList list = ctx.computeOnClient(client -> gui.getMaterialList());
            MaterialListEntry entry = findEntry(ctx, mid);

            ctx.runOnClient(client -> list.claimEntry(entry));
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.isCurrentPlayerClaimed()))) {
                throw new AssertionError("前置认领未生效");
            }

            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(false);
                list.getHudRenderer().setShouldRender(false);
                if (ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList visible) {
                    visible.closeGui(false);
                }
            });
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    ClientScreenAccess.currentScreen(client) == null
                            && SyncMaterialClient.getActiveMaterialList() == list
                            && !list.getHudRenderer().getShouldRender()
                            && !Configs.Generic.HUD_ENABLED.getBooleanValue()))) {
                throw new AssertionError("关闭 GUI/HUD 后活跃项目或开关状态不符");
            }
            if (!waitFor(ctx, () -> hasBaseMask(server))) {
                throw new AssertionError("关闭 GUI 后 Foundation 项目兴趣不再是 MATERIALS|TEAM");
            }
            boolean storeAlive = ctx.computeOnClient(client ->
                    ProjectViewClient.current()
                            .flatMap(ProjectViewClient::projectStore)
                            .map(store -> store.valid() && store.progress().isPresent())
                            .orElse(false));
            if (!storeAlive) {
                throw new AssertionError("关闭 GUI/HUD 后 Foundation 项目视图存储失效");
            }

            // ===== 1. Real backpack update =====
            server.runCommand("give @a minecraft:dirt 4");
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.getCountAvailable() == 4 && entry.getCountMissing() == 60))) {
                throw new AssertionError("GUI/HUD 关闭时 Foundation 背包更新未回流: available="
                        + ctx.computeOnClient(client -> entry.getCountAvailable())
                        + ", missing=" + ctx.computeOnClient(client -> entry.getCountMissing()));
            }
            if (persistedInventory(server, mid) != 4) {
                throw new AssertionError("GUI/HUD 关闭时 Foundation 背包更新未写入服务端");
            }

            // ===== 2. Real chest update through the protocol-5 RESCAN action =====
            areaId = server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                var player = instance.getPlayerList().getPlayers().get(0);
                var level = player.level();
                BlockPos chestPos = player.blockPosition().offset(2, 0, 0);
                level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
                if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
                    throw new AssertionError("Foundation 背景更新测试箱子创建失败");
                }
                chest.setItem(0, new ItemStack(Items.DIRT, 10));
                chest.setChanged();
                int x = chestPos.getX();
                int y = chestPos.getY();
                int z = chestPos.getZ();
                return runtime.store().addArea(PROJECT, AreaKind.STAGING_AREA,
                        new AreaSpec("Foundation background area", McCompat.dimensionId(level),
                                new AreaBounds(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1))).join().id();
            });
            if (areaId <= 0) throw new AssertionError("Foundation 背景更新测试备货区创建失败");
            ctx.runOnClient(client -> {
                long request = ProjectViewClient.current().orElseThrow(() ->
                        new AssertionError("项目视图会话缺失")).rescan(PROJECT, 0);
                if (request <= 0) throw new AssertionError("协议 5 重扫请求未发送");
            });
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.getStagingCount() == 10 && entry.getCountMissing() == 50))) {
                throw new AssertionError("GUI/HUD 关闭时 Foundation 箱子更新未回流: staging="
                        + ctx.computeOnClient(client -> entry.getStagingCount())
                        + ", missing=" + ctx.computeOnClient(client -> entry.getCountMissing())
                        + ", warnings=" + ctx.computeOnClient(client -> gui.getFreshnessWarnings()));
            }
            Integer serverStaging = server.computeOnServer(instance -> {
                var snapshot = ServerRuntimeRegistry.get(instance).store().snapshot(PROJECT).join().orElseThrow();
                return snapshot.inventory().staging()
                        .getOrDefault("minecraft:dirt", 0L).intValue();
            });
            if (serverStaging != 10) {
                throw new AssertionError("Foundation 服务端备货区计数应为 10，实际为 " + serverStaging);
            }
            if (!ctx.computeOnClient(client -> !Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && !list.getHudRenderer().getShouldRender())) {
                throw new AssertionError("测试期间 HUD 开关被意外打开");
            }
          } finally {
            final int cleanupArea = areaId;
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return null;
                if (cleanupArea > 0) {
                    runtime.store().removeArea(PROJECT, AreaKind.STAGING_AREA, cleanupArea).join();
                }
                runtime.store().removeProject(PROJECT).join();
                return null;
            });
          }
        } finally {
            ctx.runOnClient(client -> {
                Configs.Generic.HUD_ENABLED.setBooleanValue(originalHud);
                ClientScreenAccess.showScreen(client, null);
                InventoryWatcher.clearContext();
            });
        }
    }

    private static boolean hasBaseMask(TestDedicatedServerContext server) {
        return Boolean.TRUE.equals(server.computeOnServer(instance -> {
            var views = ServerRuntimeRegistry.get(instance).views()
                    .views(PROJECT, InterestKind.MATERIALS);
            return views.size() == 1 && views.get(0).mask() == ProjectInterestLeases.BASE_MASK;
        }));
    }

    private static void waitForProjectProgress(ClientGameTestContext ctx) {
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("材料列表未收到 Foundation 协议 5 权威进度");
        }
    }

    private static MaterialListEntry findEntry(ClientGameTestContext ctx, int mid) {
        return ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) {
                throw new AssertionError("材料列表已关闭");
            }
            for (MaterialListEntry candidate : gui.getMaterialList().getMaterialsAll()) {
                if (candidate.getDatabaseId() == mid) return candidate;
            }
            throw new AssertionError("材料列表中找不到 id=" + mid);
        });
    }

    private static boolean waitFor(ClientGameTestContext ctx,
                                   java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 200; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }

    private static int persistedInventory(TestDedicatedServerContext server, int mid) {
        return server.computeOnServer(instance -> {
            ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
            var player = instance.getPlayerList().getPlayers().get(0);
            var identity = new PlayerIdentity(player.getUUID(), player.getName().getString());
            return runtime.store().inventory(PROJECT, identity).join()
                    .getOrDefault(mid, 0L).intValue();
        });
    }
}
