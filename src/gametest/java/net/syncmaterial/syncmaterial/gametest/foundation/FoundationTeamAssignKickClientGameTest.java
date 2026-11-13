package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Native assignment/kick group flow. Materials are requested through the real packet,
 * the real {@link GuiMaterialList} is used, and every selection/confirmation goes
 * through {@link GuiPlayerSelectDialog}'s public UI hooks. Assignment is one scoped
 * command for all targets; kick is a sequential per-target group that only closes the
 * dialog after the whole group has an outcome.
 */
public class FoundationTeamAssignKickClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String projectId = "team-assign-client-" + UUID.randomUUID();
        PlayerIdentity targetA = new PlayerIdentity(UUID.randomUUID(), "AssignTargetA");
        PlayerIdentity targetB = new PlayerIdentity(UUID.randomUUID(), "AssignTargetB");
        PlayerIdentity sameName1 = new PlayerIdentity(UUID.randomUUID(), "SameNamePlayer");
        PlayerIdentity sameName2 = new PlayerIdentity(UUID.randomUUID(), "SameNamePlayer");
        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                context.waitTicks(20);
                Fixture fixture = server.computeOnServer(instance -> {
                    ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    PlayerIdentity owner = new PlayerIdentity(player.getUUID(), player.getName().getString());
                    runtime.store().ingest(new SchematicMetadata(projectId, "Team assign client",
                                    Path.of("team-assign-client.litematic"), "assign-client", owner),
                            List.of(new MaterialCount("minecraft:stone", 64),
                                    new MaterialCount("minecraft:dirt", 32)),
                            runtime.interpretationId()).join();
                    runtime.store().reconcilePlayers(List.of(targetA, targetB,
                            sameName1, sameName2)).join();
                    var snapshot = runtime.store().findProject(projectId).join().orElseThrow();
                    int stoneId = snapshot.materials().stream()
                            .filter(material -> material.itemId().equals("minecraft:stone"))
                            .findFirst().orElseThrow().id();
                    int dirtId = snapshot.materials().stream()
                            .filter(material -> material.itemId().equals("minecraft:dirt"))
                            .findFirst().orElseThrow().id();
                    return new Fixture(stoneId, dirtId);
                });
                context.runOnClient(client ->
                        ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(projectId)));
                context.waitForScreen(GuiMaterialList.class);
                GuiMaterialList gui = context.computeOnClient(client ->
                        (GuiMaterialList) ClientScreenAccess.currentScreen(client));
                waitFor(context, () -> context.computeOnClient(client ->
                        CurrentScreen.same(gui)
                                && gui.getTeamState() != null
                                && projectId.equals(gui.getTeamState().projectId())
                                && gui.isMainOwner()));

                // ===== 1. ASSIGN: one command for both materials and both targets =====
                context.runOnClient(client -> {
                    gui.getSelectedMaterialIds().add(fixture.stoneId());
                    gui.getSelectedMaterialIds().add(fixture.dirtId());
                    gui.openAssignDialog();
                });
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() instanceof GuiPlayerSelectDialog dialog
                                && dialog.hasLoadedPlayers()
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), targetA)
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), targetB)));
                context.runOnClient(client -> {
                    GuiPlayerSelectDialog dialog = (GuiPlayerSelectDialog) currentScreen();
                    dialog.selectPlayer(targetA);
                    dialog.selectPlayer(targetB);
                    if (dialog.getSelectedIdentities().size() != 2) {
                        throw new AssertionError("分配必须支持同时选择两个目标");
                    }
                    dialog.confirmSelection();
                });
                if (!context.computeOnClient(client -> currentScreen() instanceof GuiPlayerSelectDialog)) {
                    throw new AssertionError("分配整组回包前不得关闭选择弹窗");
                }
                waitFor(context, () -> server.computeOnServer(instance -> {
                    var claims = ServerRuntimeRegistry.get(instance).store().claims(projectId).join();
                    return claims.size() == 4
                            && claims.stream().anyMatch(claim -> claim.materialId() == fixture.stoneId()
                                    && claim.player().uuid().equals(targetA.uuid()))
                            && claims.stream().anyMatch(claim -> claim.materialId() == fixture.stoneId()
                                    && claim.player().uuid().equals(targetB.uuid()))
                            && claims.stream().anyMatch(claim -> claim.materialId() == fixture.dirtId()
                                    && claim.player().uuid().equals(targetA.uuid()))
                            && claims.stream().anyMatch(claim -> claim.materialId() == fixture.dirtId()
                                    && claim.player().uuid().equals(targetB.uuid()));
                }));
                waitFor(context, () -> context.computeOnClient(client ->
                        CurrentScreen.same(gui) && gui.getSelectedMaterialIds().isEmpty()));
                server.computeOnServer(instance -> {
                    ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                    if (!runtime.store().inventory(projectId, targetA).join().isEmpty()
                            || !runtime.store().inventory(projectId, targetB).join().isEmpty()) {
                        throw new AssertionError("分配不得伪造任何持久库存");
                    }
                    if (runtime.inventoryCount(targetA.uuid(), projectId, fixture.stoneId()) != 0
                            || runtime.inventoryCount(targetB.uuid(), projectId, fixture.dirtId()) != 0) {
                        throw new AssertionError("分配不得伪造任何运行时库存");
                    }
                    return null;
                });

                // ===== 2. KICK: sequential targets, dialog stays open for the whole group =====
                context.runOnClient(client -> {
                    gui.getSelectedMaterialIds().add(fixture.stoneId());
                    gui.getSelectedMaterialIds().add(fixture.dirtId());
                    gui.openKickDialog();
                });
                waitFor(context, () -> context.computeOnClient(client ->
                        currentScreen() instanceof GuiPlayerSelectDialog dialog
                                && dialog.hasLoadedPlayers()
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), targetA)
                                && containsIdentity(dialog.getAvailablePlayerIdentities(), targetB)));
                context.runOnClient(client -> {
                    GuiPlayerSelectDialog dialog = (GuiPlayerSelectDialog) currentScreen();
                    dialog.selectPlayer(targetA);
                    dialog.selectPlayer(targetB);
                    if (dialog.getSelectedIdentities().size() != 2) {
                        throw new AssertionError("踢出必须支持同时选择两个目标");
                    }
                    dialog.confirmSelection();
                });
                if (!context.computeOnClient(client -> currentScreen() instanceof GuiPlayerSelectDialog)) {
                    throw new AssertionError("逐目标踢出整组回包前不得关闭选择弹窗");
                }
                waitFor(context, () -> server.computeOnServer(instance ->
                        ServerRuntimeRegistry.get(instance).store().claims(projectId).join().isEmpty()));
                waitFor(context, () -> context.computeOnClient(client -> CurrentScreen.same(gui)));
                context.computeOnClient(client -> {
                    if (!gui.getSelectedMaterialIds().contains(fixture.stoneId())
                            || !gui.getSelectedMaterialIds().contains(fixture.dirtId())) {
                        throw new AssertionError("踢出结果不得丢失材料选择");
                    }
                    return null;
                });

                // ===== 3. Same-name directory rows keep UUID identity, string selection refuses =====
                context.runOnClient(client -> gui.openAssignDialog());
                waitFor(context, () -> context.computeOnClient(client -> {
                    if (!(currentScreen() instanceof GuiPlayerSelectDialog dialog) || !dialog.hasLoadedPlayers()) {
                        return false;
                    }
                    List<PlayerIdentity> rows = dialog.getAvailablePlayerIdentities().stream()
                            .filter(identity -> identity.name().equals("SameNamePlayer"))
                            .toList();
                    return rows.size() == 2
                            && rows.stream().map(PlayerIdentity::uuid).distinct().count() == 2;
                }));
                context.runOnClient(client -> {
                    GuiPlayerSelectDialog dialog = (GuiPlayerSelectDialog) currentScreen();
                    dialog.selectPlayer("SameNamePlayer");
                    if (!dialog.getSelectedIdentities().isEmpty()) {
                        throw new AssertionError("同名多 UUID 时按名字选择必须拒绝歧义，实际选中: "
                                + dialog.getSelectedIdentities());
                    }
                    dialog.selectPlayer(sameName1);
                    dialog.selectPlayer(sameName2);
                    List<PlayerIdentity> selected = dialog.getSelectedIdentities();
                    if (selected.size() != 2
                            || selected.stream().map(PlayerIdentity::uuid).distinct().count() != 2) {
                        throw new AssertionError("按身份选择同名玩家必须保留两条独立选择，实际: " + selected);
                    }
                    ClientScreenAccess.showScreen(net.minecraft.client.Minecraft.getInstance(), null);
                });
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

    private static boolean containsIdentity(List<PlayerIdentity> identities, PlayerIdentity expected) {
        return identities.stream().anyMatch(identity ->
                expected.uuid().equals(identity.uuid()) && expected.name().equals(identity.name()));
    }

    private static net.minecraft.client.gui.screens.Screen currentScreen() {
        return ClientScreenAccess.currentScreen(net.minecraft.client.Minecraft.getInstance());
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier condition) {
        for (int tick = 0; tick < 400; tick += 5) {
            context.waitTicks(5);
            if (condition.getAsBoolean()) return;
        }
        throw new AssertionError("团队分配/踢出客户端流程未在预期 tick 内完成");
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

    private static final class CurrentScreen {
        private CurrentScreen() {}

        static boolean same(GuiMaterialList gui) {
            return ClientScreenAccess.currentScreen(net.minecraft.client.Minecraft.getInstance()) == gui;
        }
    }

    private record Fixture(int stoneId, int dirtId) {}
}
