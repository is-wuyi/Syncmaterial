package net.syncmaterial.syncmaterial.gametest.foundation;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Native protocol-4 owner management flow. The material list is opened by the real
 * {@link MaterialStatsRequestC2SPacket} path, not by constructing the screen, so the
 * initial team state push, authoritative area inventory and management dialogs all
 * exercise production routing.
 */
public class FoundationTeamOwnerClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String projectId = "team-owner-client-" + UUID.randomUUID();
        PlayerIdentity deputyA = new PlayerIdentity(UUID.randomUUID(), "TeamDeputyA");
        PlayerIdentity deputyB = new PlayerIdentity(UUID.randomUUID(), "TeamDeputyB");
        PlayerIdentity transferTarget = new PlayerIdentity(UUID.randomUUID(), "TeamTransferTarget");
        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                context.waitTicks(20);
                Fixture fixture = server.computeOnServer(instance -> createFixture(
                        instance, projectId, List.of(deputyA, deputyB, transferTarget)));
                context.runOnClient(client ->
                        ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(projectId)));
                context.waitForScreen(GuiMaterialList.class);
                GuiMaterialList gui = context.computeOnClient(client ->
                        (GuiMaterialList) ClientScreenAccess.currentScreen(client));
                waitFor(context, () -> context.computeOnClient(client ->
                        ClientScreenAccess.currentScreen(client) == gui
                                && gui.getTeamState() != null
                                && projectId.equals(gui.getTeamState().projectId())
                                && gui.isMainOwner() && gui.isOwner()));
                context.runOnClient(client ->
                        ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(projectId)));
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() == gui && areaAuthorityVisible(gui, fixture.stoneId())));
                long catalogRevision = context.computeOnClient(client ->
                        gui.getMaterialList().getCatalogRevision());
                int materialCount = context.computeOnClient(client ->
                        gui.getMaterialList().getMaterialsAll().size());
                context.runOnClient(client -> gui.getSelectedMaterialIds().add(fixture.stoneId()));
                List<String> ownerButtons = context.computeOnClient(client -> buttonLabels(gui));
                if (!ownerButtons.contains(StringUtils.translate("syncmaterial.gui.button.transfer"))) {
                    throw new AssertionError("主负责人右栏必须存在转让按钮: " + ownerButtons);
                }

                // ===== 1. Toggle self-claim: TEAM_STATE push must only change roles =====
                context.runOnClient(client -> gui.toggleSelfClaim());
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() == gui && !gui.isAllowSelfClaim()));
                waitFor(context, () -> server.computeOnServer(instance ->
                        !ServerRuntimeRegistry.get(instance).store()
                                .teamState(projectId).join().orElseThrow().allowSelfClaim()));
                context.computeOnClient(client -> {
                    if (currentScreen() != gui) {
                        throw new AssertionError("团队状态更新不得关闭或重开材料列表");
                    }
                    if (gui.getMaterialList().getCatalogRevision() != catalogRevision
                            || gui.getMaterialList().getMaterialsAll().size() != materialCount) {
                        throw new AssertionError("团队状态更新不得重置材料目录");
                    }
                    if (!gui.getSelectedMaterialIds().contains(fixture.stoneId())) {
                        throw new AssertionError("团队状态更新不得清空材料选择");
                    }
                    if (!areaAuthorityVisible(gui, fixture.stoneId())) {
                        throw new AssertionError("团队状态更新不得重置既有区域权威库存");
                    }
                    return null;
                });

                // ===== 2. ADD_DEPUTY group: dialog stays until the whole group completes =====
                context.runOnClient(client -> gui.openAddDeputy());
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() instanceof GuiPlayerSelectDialog dialog
                                && dialog.hasLoadedPlayers()
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), deputyA)
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), deputyB)));
                context.runOnClient(client -> {
                    GuiPlayerSelectDialog dialog = (GuiPlayerSelectDialog) currentScreen();
                    selectIdentity(dialog, deputyA);
                    selectIdentity(dialog, deputyB);
                    dialog.confirmSelection();
                });
                if (!context.computeOnClient(client -> currentScreen() instanceof GuiPlayerSelectDialog)) {
                    throw new AssertionError("多目标角色操作在回包前不得提前关闭选择弹窗");
                }
                waitFor(context, () -> server.computeOnServer(instance -> {
                    var state = ServerRuntimeRegistry.get(instance).store()
                            .teamState(projectId).join().orElseThrow();
                    return state.deputies().stream().anyMatch(value -> value.uuid().equals(deputyA.uuid()))
                            && state.deputies().stream().anyMatch(value -> value.uuid().equals(deputyB.uuid()));
                }));
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() == gui
                                && gui.getDeputyOwners().contains(deputyA.name())
                                && gui.getDeputyOwners().contains(deputyB.name())));

                // ===== 3. Right-panel remove =====
                context.runOnClient(client -> gui.removeDeputy(deputyA.name()));
                waitFor(context, () -> server.computeOnServer(instance -> {
                    var state = ServerRuntimeRegistry.get(instance).store()
                            .teamState(projectId).join().orElseThrow();
                    return state.deputies().size() == 1
                            && state.deputies().getFirst().uuid().equals(deputyB.uuid());
                }));
                waitFor(context, () -> context.computeOnClient(client ->
                        gui.getDeputyOwners().size() == 1
                                && gui.getDeputyOwners().getFirst().equals(deputyB.name())));

                // ===== 4. Transfer: single selection; role callbacks must not reset area authority =====
                context.runOnClient(client -> gui.openTransfer());
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() instanceof GuiPlayerSelectDialog dialog
                                && dialog.hasLoadedPlayers()
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), transferTarget)));
                context.runOnClient(client -> {
                    GuiPlayerSelectDialog dialog = (GuiPlayerSelectDialog) currentScreen();
                    selectIdentity(dialog, deputyB);
                    selectIdentity(dialog, transferTarget);
                    List<PlayerIdentity> selected = dialog.getSelectedIdentities();
                    if (selected.size() != 1 || !transferTarget.equals(selected.getFirst())) {
                        throw new AssertionError("转让必须单选且最后选择生效，实际: " + selected);
                    }
                    dialog.confirmSelection();
                });
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() == gui && !gui.isMainOwner() && !gui.isOwner()));
                waitFor(context, () -> context.computeOnClient(client -> {
                    PlayerIdentity currentOwner = gui.getOwnerIdentity();
                    return currentOwner != null && transferTarget.uuid().equals(currentOwner.uuid())
                            && transferTarget.name().equals(gui.getOwnerName());
                }));
                waitFor(context, () -> server.computeOnServer(instance ->
                        ServerRuntimeRegistry.get(instance).store().teamState(projectId)
                                .join().orElseThrow().isMainOwner(transferTarget)));
                context.computeOnClient(client -> {
                    List<String> labels = buttonLabels(gui);
                    if (labels.contains(StringUtils.translate("syncmaterial.gui.button.transfer"))
                            || labels.contains(StringUtils.translate("syncmaterial.gui.button.add_deputy"))) {
                        throw new AssertionError("失去主负责人后必须移除转让/添加副负责人按钮: " + labels);
                    }
                    if (gui.getMaterialList().getCatalogRevision() != catalogRevision
                            || gui.getMaterialList().getMaterialsAll().size() != materialCount) {
                        throw new AssertionError("转让角色回包不得重置材料目录");
                    }
                    if (!gui.getSelectedMaterialIds().contains(fixture.stoneId)
                            || !areaAuthorityVisible(gui, fixture.stoneId())) {
                        throw new AssertionError("转让角色回包不得重置选择或区域权威库存");
                    }
                    return null;
                });
                if (TeamManagementClient.getTeamState(projectId) == null) {
                    throw new AssertionError("客户端必须保留最终权威 TeamState");
                }
            } finally {
                cleanup(server, projectId);
            }
        } finally {
            context.runOnClient(client -> {
                ClientScreenAccess.showScreen(net.minecraft.client.Minecraft.getInstance(), null);
                InventoryWatcher.clearContext();
            });
        }
    }

    private static Fixture createFixture(net.minecraft.server.MinecraftServer instance, String projectId,
                                         List<PlayerIdentity> knownPlayers) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
        var player = instance.getPlayerList().getPlayers().get(0);
        PlayerIdentity owner = new PlayerIdentity(player.getUUID(), player.getName().getString());
        runtime.store().ingest(new SchematicMetadata(projectId, "Team owner client",
                        Path.of("team-owner-client.litematic"), "owner-client", owner),
                List.of(new MaterialCount("minecraft:stone", 64),
                        new MaterialCount("minecraft:dirt", 32)), runtime.interpretationId()).join();
        runtime.store().reconcilePlayers(knownPlayers).join();
        var snapshot = runtime.store().findProject(projectId).join().orElseThrow();
        int stoneId = snapshot.materials().stream()
                .filter(material -> material.itemId().equals("minecraft:stone"))
                .findFirst().orElseThrow().id();
        var level = player.level();
        int x = (player.getBlockX() >> 4) << 4;
        int z = (player.getBlockZ() >> 4) << 4;
        int y = player.getBlockY() + 3;
        BlockPos barrel = new BlockPos(x + 2, y, z + 2);
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        Container container = (Container) level.getBlockEntity(barrel);
        if (container == null) {
            throw new AssertionError("团队测试木桶方块实体未创建");
        }
        container.setItem(0, new ItemStack(Items.STONE, 16));
        runtime.store().addArea(projectId, AreaKind.STAGING_AREA,
                new AreaSpec("Team owner staging", McCompat.dimensionId(level),
                        new AreaBounds(x + 1, y, z + 1, x + 3, y, z + 3))).join();
        runtime.areas().refreshConfiguration();
        runtime.requestBroadcast(projectId);
        return new Fixture(projectId, stoneId, barrel);
    }

    private static boolean areaAuthorityVisible(GuiMaterialList gui, int materialId) {
        var entry = gui.getMaterialList().getMaterialsAll().stream()
                .filter(value -> value.getDatabaseId() == materialId)
                .findFirst().orElse(null);
        return entry != null && gui.getMaterialList().isPreparationKnown()
                && entry.getStagingCount() == 16;
    }

    private static boolean containsIdentity(List<PlayerIdentity> identities, PlayerIdentity expected) {
        return identities.stream().anyMatch(identity ->
                expected.uuid().equals(identity.uuid()) && expected.name().equals(identity.name()));
    }

    private static void selectIdentity(GuiPlayerSelectDialog dialog, PlayerIdentity identity) {
        dialog.selectPlayer(identity);
    }

    private static net.minecraft.client.gui.screens.Screen currentScreen() {
        return ClientScreenAccess.currentScreen(net.minecraft.client.Minecraft.getInstance());
    }

    private static List<String> buttonLabels(GuiBase gui) {
        try {
            Field buttonsField = GuiBase.class.getDeclaredField("buttons");
            buttonsField.setAccessible(true);
            List<?> buttons = (List<?>) buttonsField.get(gui);
            Field displayField = ButtonBase.class.getDeclaredField("displayString");
            displayField.setAccessible(true);
            List<String> labels = new ArrayList<>(buttons.size());
            for (Object button : buttons) {
                Object value = displayField.get(button);
                labels.add(value == null ? "" : value.toString());
            }
            return labels;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取管理栏按钮", failure);
        }
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier condition) {
        for (int tick = 0; tick < 400; tick += 5) {
            context.waitTicks(5);
            if (condition.getAsBoolean()) return;
        }
        throw new AssertionError("团队负责人客户端流程未在预期 tick 内完成");
    }

    private static void cleanup(TestDedicatedServerContext server, String projectId) {
        try {
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime != null) {
                    runtime.store().removeProject(projectId).join();
                    runtime.requestBroadcast(projectId);
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            // The dedicated test server may already be closing.
        }
    }

    private record Fixture(String projectId, int stoneId, BlockPos barrel) {}
}
