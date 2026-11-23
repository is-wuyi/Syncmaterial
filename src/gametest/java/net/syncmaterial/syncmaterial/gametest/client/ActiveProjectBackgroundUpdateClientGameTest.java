package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;
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
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * Legacy active-project background update acceptance test.
 *
 * <p>Scenario: the player opened a protocol-5 project, claimed one material, then
 * closed the material-list GUI and switched off both HUD gates. The project view must
 * remain subscribed with exactly {@code MATERIALS|TEAM}; the GUI and HUD are display
 * consumers only.</p>
 *
 * <p>With nothing visible, two real server-side changes must still flow back into the
 * active project list:</p>
 * <ol>
 *   <li>a real backpack change detected and reported by the client;</li>
 *   <li>a real chest in a newly configured staging area, scanned through the same
 *       protocol-5 RESCAN command used by the GUI refresh button.</li>
 * </ol>
 *
 * <p>This replaces no existing test: prior HUD tests mutate entry values directly and
 * therefore cannot prove that a closed GUI/HUD still receives authoritative data.</p>
 */
public class ActiveProjectBackgroundUpdateClientGameTest implements FabricClientGameTest {

    private static final String PROJECT = "background-update-" + System.nanoTime();
    private static final String DIRT = "minecraft:dirt";

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
            materialId = insertProject(server);
            final int mid = materialId;

            ctx.waitFor(client -> ClientProtocolState.serverSupports(
                    ProtocolVersion.SCOPED_PROJECT_VIEWS));
            ctx.runOnClient(client -> {
                if (!net.syncmaterial.syncmaterial.client.SyncMaterialClient.requestMaterialList(PROJECT)) {
                    throw new AssertionError("后台更新项目未建立");
                }
            });
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitFor(client -> ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
            waitForProjectProgress(ctx);

            GuiMaterialList gui = ctx.computeOnClient(client ->
                    (GuiMaterialList) ClientScreenAccess.currentScreen(client));
            SyncMaterialList list = ctx.computeOnClient(client -> gui.getMaterialList());
            MaterialListEntry entry = findEntry(ctx, mid);

            // Claim through the production row action so inventory reports are accepted.
            ctx.runOnClient(client -> list.claimEntry(entry));
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.isCurrentPlayerClaimed()))) {
                throw new AssertionError("前置认领未生效");
            }

            // Close the real GUI and disable both HUD gates.
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
                throw new AssertionError("关闭 GUI 后服务端项目兴趣不再是 MATERIALS|TEAM");
            }
            boolean storeAlive = ctx.computeOnClient(client ->
                    ProjectViewClient.current()
                            .flatMap(ProjectViewClient::projectStore)
                            .map(store -> store.valid() && store.progress().isPresent())
                            .orElse(false));
            if (!storeAlive) {
                throw new AssertionError("关闭 GUI/HUD 后项目视图存储失效，后续服务端更新无处落地");
            }

            // ===== 1. Real backpack update (client watcher -> server -> progress) =====
            server.runCommand("give @a minecraft:dirt 4");
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.getCountAvailable() == 4 && entry.getCountMissing() == 60))) {
                throw new AssertionError("GUI/HUD 关闭时背包更新未回流: available="
                        + ctx.computeOnClient(client -> entry.getCountAvailable())
                        + ", missing=" + ctx.computeOnClient(client -> entry.getCountMissing()));
            }
            if (persistedInventory(server, mid) != 4) {
                throw new AssertionError("GUI/HUD 关闭时背包更新未写入服务端");
            }

            // ===== 2. Real chest update through the protocol-5 RESCAN action =====
            areaId = server.computeOnServer(instance -> {
                var player = instance.getPlayerList().getPlayerByName("Player0");
                if (player == null) throw new AssertionError("Player0 不在线");
                var level = player.level();
                BlockPos chestPos = player.blockPosition().offset(2, 0, 0);
                level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
                if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
                    throw new AssertionError("背景更新测试箱子创建失败");
                }
                chest.setItem(0, new ItemStack(Items.DIRT, 10));
                chest.setChanged();
                return SyncMaterial.getServerStagingAreaManager().addStagingArea(
                        PROJECT, McCompat.dimensionId(level), "Background area",
                        chestPos.getX() - 1, chestPos.getY() - 1, chestPos.getZ() - 1,
                        chestPos.getX() + 1, chestPos.getY() + 1, chestPos.getZ() + 1);
            });
            if (areaId <= 0) throw new AssertionError("背景更新测试备货区创建失败");
            ctx.runOnClient(client -> {
                long request = ProjectViewClient.current().orElseThrow(() ->
                        new AssertionError("项目视图会话缺失")).rescan(PROJECT, 0);
                if (request <= 0) throw new AssertionError("协议 5 重扫请求未发送");
            });
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    entry.getStagingCount() == 10 && entry.getCountMissing() == 50))) {
                throw new AssertionError("GUI/HUD 关闭时箱子更新未回流: staging="
                        + ctx.computeOnClient(client -> entry.getStagingCount())
                        + ", missing=" + ctx.computeOnClient(client -> entry.getCountMissing()));
            }
            long serverStaging = server.computeOnServer(instance ->
                    SyncMaterial.getServerStagingAreaManager()
                            .getStagingCountForMaterial(PROJECT, DIRT));
            if (serverStaging != 10) {
                throw new AssertionError("服务端备货区计数应为 10，实际为 " + serverStaging);
            }
            if (!ctx.computeOnClient(client -> !Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && !list.getHudRenderer().getShouldRender())) {
                throw new AssertionError("测试期间 HUD 开关被意外打开");
            }
          } finally {
            final int cleanupArea = areaId;
            if (cleanupArea > 0) {
                server.computeOnServer(instance -> {
                    SyncMaterial.getServerStagingAreaManager().removeStagingArea(cleanupArea, PROJECT);
                    return null;
                });
            }
            if (materialId > 0) cleanupProject(server);
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
            var views = net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry.get(instance)
                    .views().views(PROJECT, net.syncmaterial.syncmaterial.core.InterestKind.MATERIALS);
            return views.size() == 1 && views.get(0).mask() == ProjectInterestLeases.BASE_MASK;
        }));
    }

    private static void waitForProjectProgress(ClientGameTestContext ctx) {
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("材料列表未收到协议 5 权威进度");
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
        return onDatabase(server, database -> {
            try (var result = database.executeQuery(
                    "SELECT count FROM player_inventories WHERE schematic_id = ? AND material_id = ?",
                    PROJECT, mid)) {
                return result.next() ? result.getInt("count") : -1;
            }
        });
    }

    private static int insertProject(TestDedicatedServerContext server) {
        return onDatabase(server, database -> {
            database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    PROJECT, "Background Update Test", "/background-update.litematic", "Owner");
            database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    PROJECT, DIRT, 64);
            try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private static void cleanupProject(TestDedicatedServerContext server) {
        onDatabase(server, database -> {
            database.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM player_inventories WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id IN "
                    + "(SELECT id FROM staging_areas WHERE schematic_id = ?)", PROJECT);
            database.executeUpdate("DELETE FROM staging_areas WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM schematics WHERE id = ?", PROJECT);
            return null;
        });
    }

    private static <T> T onDatabase(TestDedicatedServerContext server,
                                    SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (SQLException failure) {
                throw new RuntimeException("测试数据库操作失败", failure);
            }
        });
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
