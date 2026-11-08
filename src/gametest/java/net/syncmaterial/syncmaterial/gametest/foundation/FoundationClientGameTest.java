package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

public class FoundationClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String projectId = "foundation-client-" + UUID.randomUUID();
        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                context.waitTicks(20);
                int materialId = server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    if (runtime == null || SyncMaterial.getSharedDatabase() != null) {
                        throw new AssertionError("The experiment must use its isolated runtime");
                    }
                    var player = instance.getPlayerList().getPlayers().get(0);
                    var metadata = new SchematicMetadata(projectId, "Foundation test", Path.of("foundation.litematic"),
                            "fixture", new PlayerIdentity(player.getUUID(), player.getName().getString()));
                    runtime.store().ingest(metadata, List.of(new MaterialCount("minecraft:dirt", 64))).join();
                    return runtime.store().findProject(projectId).join().orElseThrow().materials().get(0).id();
                });
                context.runOnClient(client -> ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(projectId)));
                context.waitForScreen(GuiMaterialList.class);
                waitFor(context, () -> context.computeOnClient(client -> {
                    if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                    var entries = gui.getMaterialList().getMaterialsAll();
                    return projectId.equals(gui.getSchematicId()) && entries.size() == 1
                            && entries.get(0).getDatabaseId() == materialId && entries.get(0).getCountTotal() == 64
                            && InventoryWatcher.hasPeriodicReports() && !gui.getMaterialList().isPreparationKnown();
                }));
                GuiMaterialList originalScreen = context.computeOnClient(client -> (GuiMaterialList) client.gui.screen());
                context.runOnClient(client -> originalScreen.getSelectedMaterialIds().add(materialId));
                context.runOnClient(client -> ClientPlayNetworking.send(
                        new JoinCollaborationC2SPacket(projectId, materialId, java.util.Map.of(materialId, 0))));
                waitFor(context, () -> server.computeOnServer(instance ->
                        ServerRuntimeRegistry.get(instance).store().claims(projectId).join().size() == 1));
                server.runCommand("give @a minecraft:dirt 4");
                waitFor(context, () -> server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    return runtime.store().inventory(projectId, new PlayerIdentity(player.getUUID(), player.getName().getString()))
                            .join().getOrDefault(materialId, 0L) == 4;
                }));
                context.waitTicks(250);
                if (server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    return runtime.inventoryCount(player.getUUID(), projectId, materialId);
                }) != 4L) {
                    throw new AssertionError("Unchanged online inventory must stay fresh through heartbeats");
                }
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    runtime.store().ingest(new SchematicMetadata(projectId, "Updated", Path.of("updated.litematic"),
                                    "updated", new PlayerIdentity(player.getUUID(), player.getName().getString())),
                            List.of(new MaterialCount("minecraft:dirt", 128), new MaterialCount("minecraft:stone", 32))).join();
                    runtime.requestBroadcast(projectId);
                    return null;
                });
                waitFor(context, () -> context.computeOnClient(client -> {
                    if (!(client.gui.screen() instanceof GuiMaterialList gui)) return false;
                    var entries = gui.getMaterialList().getMaterialsAll();
                    return client.gui.screen() == originalScreen && entries.size() == 2
                            && gui.getMaterialList().getTitle().equals("Updated")
                            && gui.getSelectedMaterialIds().contains(materialId)
                            && entries.stream().anyMatch(entry -> entry.getDatabaseId() == materialId
                            && entry.getCountTotal() == 128 && gui.getMaterialList().isCollaborating(entry));
                }));
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    runtime.store().ingest(new SchematicMetadata(projectId, "Renamed without content changes", Path.of("renamed.litematic"),
                                    "updated", new PlayerIdentity(player.getUUID(), player.getName().getString())), List.of()).join();
                    runtime.requestBroadcast(projectId);
                    return null;
                });
                waitFor(context, () -> context.computeOnClient(client -> client.gui.screen() == originalScreen
                        && originalScreen.getMaterialList().getTitle().equals("Renamed without content changes")));
                context.runOnClient(client -> ClientPlayNetworking.send(new LeaveCollaborationC2SPacket(projectId, materialId)));
                waitFor(context, () -> server.computeOnServer(instance ->
                        ServerRuntimeRegistry.get(instance).store().claims(projectId).join().isEmpty()));
            } finally {
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    if (runtime != null) runtime.store().removeProject(projectId).join();
                    return null;
                });
                context.runOnClient(client -> InventoryWatcher.clearContext());
            }
        } finally {
            context.runOnClient(client -> InventoryWatcher.clearContext());
        }
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier condition) {
        for (int tick = 0; tick < 200; tick += 5) {
            context.waitTicks(5);
            if (condition.getAsBoolean()) return;
        }
        throw new AssertionError("Foundation network flow did not settle");
    }
}
