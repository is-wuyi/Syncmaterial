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
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
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
                    runtime.store().ingest(metadata, List.of(new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
                    return runtime.store().findProject(projectId).join().orElseThrow().materials().get(0).id();
                });
                context.runOnClient(client -> ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(projectId)));
                context.waitForScreen(GuiMaterialList.class);
                waitFor(context, () -> context.computeOnClient(client -> {
                    if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
                    var entries = gui.getMaterialList().getMaterialsAll();
                    // 协议 2 的实验后端没有区域权威数据，必须保持"未知"而不是虚假的已备齐。
                    boolean protocolTooOld = ClientProtocolState.getServerProtocolVersion()
                            < ProtocolVersion.AREA_INVENTORY_SNAPSHOTS;
                    return projectId.equals(gui.getSchematicId()) && entries.size() == 1
                            && entries.get(0).getDatabaseId() == materialId && entries.get(0).getCountTotal() == 64
                            && InventoryWatcher.hasPeriodicReports()
                            && (!protocolTooOld || !gui.getMaterialList().isPreparationKnown());
                }));
                GuiMaterialList originalScreen = context.computeOnClient(client -> (GuiMaterialList) ClientScreenAccess.currentScreen(client));
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
                            List.of(new MaterialCount("minecraft:dirt", 128), new MaterialCount("minecraft:stone", 32)), runtime.interpretationId()).join();
                    runtime.requestBroadcast(projectId);
                    return null;
                });
                waitFor(context, () -> context.computeOnClient(client -> {
                    if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
                    var entries = gui.getMaterialList().getMaterialsAll();
                    return ClientScreenAccess.currentScreen(client) == originalScreen && entries.size() == 2
                            && gui.getMaterialList().getTitle().equals("Updated")
                            && gui.getSelectedMaterialIds().contains(materialId)
                            && entries.stream().anyMatch(entry -> entry.getDatabaseId() == materialId
                            && entry.getCountTotal() == 128 && gui.getMaterialList().isCollaborating(entry));
                }));
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    runtime.store().ingest(new SchematicMetadata(projectId, "Renamed without content changes", Path.of("renamed.litematic"),
                                    "updated", new PlayerIdentity(player.getUUID(), player.getName().getString())), List.of(), runtime.interpretationId()).join();
                    runtime.requestBroadcast(projectId);
                    return null;
                });
                waitFor(context, () -> context.computeOnClient(client -> ClientScreenAccess.currentScreen(client) == originalScreen
                        && originalScreen.getMaterialList().getTitle().equals("Renamed without content changes")));
                context.runOnClient(client -> ClientPlayNetworking.send(new LeaveCollaborationC2SPacket(projectId, materialId)));
                waitFor(context, () -> server.computeOnServer(instance ->
                        ServerRuntimeRegistry.get(instance).store().claims(projectId).join().isEmpty()));

                runRealAreaScenarios(context, server, originalScreen, projectId, materialId);

                long catalogRevision = context.computeOnClient(client -> originalScreen.getMaterialList().getCatalogRevision());
                if (ClientProtocolState.getServerProtocolVersion() >= ProtocolVersion.AREA_INVENTORY_SNAPSHOTS) {
                    if (catalogRevision < 0) {
                        throw new AssertionError("Protocol 3 client must have a catalog revision before authority snapshots");
                    }
                    runAuthorityScenarios(context, originalScreen, projectId, materialId, catalogRevision);
                }
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

    private static void runRealAreaScenarios(ClientGameTestContext context, TestDedicatedServerContext server,
                                             GuiMaterialList screen, String projectId, int materialId) {
        if (!ClientProtocolState.serverSupports(ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) {
            throw new AssertionError("The integrated Foundation server must support authoritative area snapshots");
        }
        AreaFixture fixture = server.computeOnServer(instance -> {
            var runtime = ServerRuntimeRegistry.get(instance);
            var level = instance.getPlayerList().getPlayers().get(0).level();
            int x = (instance.getPlayerList().getPlayers().get(0).getBlockX() >> 4) << 4;
            int z = (instance.getPlayerList().getPlayers().get(0).getBlockZ() >> 4) << 4;
            int y = instance.getPlayerList().getPlayers().get(0).getBlockY() + 3;
            BlockPos stagingPos = new BlockPos(x + 2, y, z + 2);
            BlockPos warehousePos = new BlockPos(x + 5, y, z + 5);
            level.setBlockAndUpdate(stagingPos, Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(warehousePos, Blocks.BARREL.defaultBlockState());
            ((Container) level.getBlockEntity(stagingPos)).setItem(0, new ItemStack(Items.DIRT, 32));
            ((Container) level.getBlockEntity(warehousePos)).setItem(0, new ItemStack(Items.DIRT, 16));
            String world = McCompat.dimensionId(level);
            var staging = runtime.store().addArea(projectId, AreaKind.STAGING_AREA,
                    new AreaSpec("Client staging", world, new AreaBounds(x + 1, y, z + 1, x + 3, y, z + 3))).join();
            var warehouse = runtime.store().addArea(null, AreaKind.WAREHOUSE,
                    new AreaSpec("Client warehouse " + projectId, world, new AreaBounds(x + 1, y, z + 1, x + 6, y, z + 6))).join();
            runtime.store().addWarehouseReference(projectId, warehouse.id()).join();
            return new AreaFixture(world, staging.id(), warehouse.id(), stagingPos, warehousePos);
        });
        try {
            context.runOnClient(client -> {
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(projectId, "LIST", -1, java.util.Optional.empty()));
                ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(projectId));
            });
            waitFor(context, () -> context.computeOnClient(client -> {
                var entry = screen.getMaterialList().getMaterialsAll().stream()
                        .filter(value -> value.getDatabaseId() == materialId).findFirst().orElseThrow();
                return screen.getMaterialList().isPreparationKnown() && entry.getStagingCount() == 32
                        && entry.getWarehouseCount() == 16 && entry.getCountMissing() == 80
                        && screen.getOverlapWarning() != null && screen.getFreshnessWarnings().isEmpty();
            }));
            if (!server.computeOnServer(instance -> {
                var view = ServerRuntimeRegistry.get(instance).store().snapshot(projectId).join().orElseThrow().inventory();
                return view.known() && view.overlap() && view.staging().getOrDefault("minecraft:dirt", 0L) == 32
                        && view.warehouse().getOrDefault("minecraft:dirt", 0L) == 16;
            })) throw new AssertionError("The client must display actual deduplicated storage observations");

            context.runOnClient(client -> {
                var entry = screen.getMaterialList().getMaterialsAll().stream()
                        .filter(value -> value.getDatabaseId() == materialId).findFirst().orElseThrow();
                int missing = entry.getCountMissing();
                screen.onRescanResponse(true, "Rescan complete");
                if (entry.getCountMissing() != missing) {
                    throw new AssertionError("Rescan response must not replace authoritative area progress with inventory-only counts");
                }
            });
            server.computeOnServer(instance -> {
                var level = instance.getPlayerList().getPlayers().get(0).level();
                ((Container) level.getBlockEntity(fixture.staging())).setItem(0, new ItemStack(Items.DIRT, 48));
                return null;
            });
            try {
                waitFor(context, () -> context.computeOnClient(client -> {
                    var entry = screen.getMaterialList().getMaterialsAll().stream()
                            .filter(value -> value.getDatabaseId() == materialId).findFirst().orElseThrow();
                    return screen.getMaterialList().isPreparationKnown() && entry.getStagingCount() == 48
                            && entry.getWarehouseCount() == 16 && entry.getCountMissing() == 64;
                }));
            } catch (AssertionError failure) {
                String clientState = context.computeOnClient(client -> {
                    var list = screen.getMaterialList();
                    var entry = list.getMaterialsAll().stream()
                            .filter(value -> value.getDatabaseId() == materialId).findFirst().orElseThrow();
                    return "known=" + list.isPreparationKnown() + ", staging=" + entry.getStagingCount()
                            + ", warehouse=" + entry.getWarehouseCount() + ", missing=" + entry.getCountMissing()
                            + ", revision=" + list.getCatalogRevision() + ", sequence=" + list.getLastAreaInventorySequence()
                            + ", warnings=" + list.getAreaFreshness();
                });
                String serverState = server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var inventory = runtime.store().snapshot(projectId).join().orElseThrow().inventory();
                    return "inventory=" + inventory + ", current=" + runtime.areas().isInventoryCurrent(projectId, inventory)
                            + ", pending=" + runtime.areas().hasPendingWork();
                });
                throw new AssertionError("Dirty area update did not settle; client " + clientState + "; server " + serverState, failure);
            }

            context.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(projectId,
                    "UPDATE_WAREHOUSE", fixture.warehouseId(), java.util.Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                    "Client warehouse " + projectId, fixture.warehouse().getX(), fixture.warehouse().getY(), fixture.warehouse().getZ(),
                    fixture.warehouse().getX(), fixture.warehouse().getY(), fixture.warehouse().getZ(),
                    java.util.Optional.of(fixture.world()))))));
            waitFor(context, () -> context.computeOnClient(client -> screen.getOverlapWarning() == null
                    && screen.getMaterialList().isPreparationKnown() && screen.getFreshnessWarnings().isEmpty()));

            server.computeOnServer(instance -> {
                instance.getPlayerList().getPlayers().get(0).level().setBlockAndUpdate(fixture.staging(), Blocks.AIR.defaultBlockState());
                return null;
            });
            waitFor(context, () -> context.computeOnClient(client -> {
                var entry = screen.getMaterialList().getMaterialsAll().stream()
                        .filter(value -> value.getDatabaseId() == materialId).findFirst().orElseThrow();
                return screen.getMaterialList().isPreparationKnown() && entry.getStagingCount() == 0
                        && entry.getWarehouseCount() == 16 && entry.getCountMissing() == 112;
            }));
        } finally {
            server.computeOnServer(instance -> {
                var runtime = ServerRuntimeRegistry.get(instance);
                var level = instance.getPlayerList().getPlayers().get(0).level();
                runtime.store().removeArea(projectId, AreaKind.STAGING_AREA, fixture.stagingId()).join();
                runtime.store().removeArea(null, AreaKind.WAREHOUSE, fixture.warehouseId()).join();
                level.setBlockAndUpdate(fixture.staging(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(fixture.warehouse(), Blocks.AIR.defaultBlockState());
                runtime.requestBroadcast(projectId);
                return null;
            });
            context.runOnClient(client -> ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(projectId)));
            waitFor(context, () -> context.computeOnClient(client -> screen.getMaterialList().isPreparationKnown()
                    && screen.getMaterialList().getMaterialsAll().stream()
                    .allMatch(entry -> entry.getStagingCount() == 0 && entry.getWarehouseCount() == 0)));
        }
    }

    private record AreaFixture(String world, int stagingId, int warehouseId, BlockPos staging, BlockPos warehouse) {}

    /** 客户端拒旧状态机单独校验，不代替上面的真实扫描与网络闭环。 */
    private static void runAuthorityScenarios(ClientGameTestContext context, GuiMaterialList screen,
                                              String projectId, int materialId, long catalogRevision) {
        long sequence = context.computeOnClient(client -> Math.addExact(screen.getMaterialList().getLastAreaInventorySequence(), 1000));
        context.runOnClient(client -> SyncMaterialClient.onAreaInventorySnapshot(
                new AreaInventorySnapshotS2CPacket(projectId, catalogRevision, sequence, false,
                        inventoryFor(screen, materialId, 0, 0),
                        List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                                "staging_area", 0, "staging", "uninitialized")))));
        waitFor(context, () -> context.computeOnClient(client -> {
            var list = screen.getMaterialList();
            var entry = list.getMaterialsAll().stream()
                    .filter(value -> value.getDatabaseId() == materialId).findFirst().orElse(null);
            return entry != null && !list.isPreparationKnown() && list.getAreaFreshness().size() == 1
                    && entry.getStagingCount() == 0 && entry.getWarehouseCount() == 0
                    && screen.getFreshnessWarnings().size() == 1;
        }));

        context.runOnClient(client -> SyncMaterialClient.onAreaInventorySnapshot(
                new AreaInventorySnapshotS2CPacket(projectId, catalogRevision, sequence + 1, true,
                        inventoryFor(screen, materialId, 48, 16), List.of())));
        waitFor(context, () -> context.computeOnClient(client -> {
            var list = screen.getMaterialList();
            var entry = list.getMaterialsAll().stream()
                    .filter(value -> value.getDatabaseId() == materialId).findFirst().orElse(null);
            return entry != null && list.isPreparationKnown() && entry.getStagingCount() == 48
                    && entry.getWarehouseCount() == 16 && screen.getFreshnessWarnings().isEmpty();
        }));

        // 后到的旧协作状态必须保留参与者，但不得把权威计数改回 0 或破坏 known。
        context.runOnClient(client -> SyncMaterialClient.onCollaborationStatus(
                new CollaborationStatusS2CPacket(projectId, materialId, 128, 0, 0,
                        List.of(new CollaborationStatusS2CPacket.ParticipantInfo("Alice", 5)), List.of())));
        waitFor(context, () -> context.computeOnClient(client -> {
            var list = screen.getMaterialList();
            var entry = list.getMaterialsAll().stream()
                    .filter(value -> value.getDatabaseId() == materialId).findFirst().orElse(null);
            return entry != null && list.isPreparationKnown() && entry.getStagingCount() == 48
                    && entry.getWarehouseCount() == 16 && entry.getOtherPlayersCount() == 5
                    && entry.getCountMissing() == 59;
        }));

        // 未知 + 警告：仍未备齐；随后权威空 freshness 必须清掉 GUI 警告。
        context.runOnClient(client -> SyncMaterialClient.onAreaInventorySnapshot(
                new AreaInventorySnapshotS2CPacket(projectId, catalogRevision, sequence + 2, false,
                        inventoryFor(screen, materialId, 10, 0),
                        List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                                "warehouse", 0, "warehouse", "stale")))));
        waitFor(context, () -> context.computeOnClient(client -> {
            var list = screen.getMaterialList();
            var entry = list.getMaterialsAll().stream()
                    .filter(value -> value.getDatabaseId() == materialId).findFirst().orElse(null);
            return entry != null && !list.isPreparationKnown()
                    && screen.getFreshnessWarnings().size() == 1 && entry.getStagingCount() == 10;
        }));

        context.runOnClient(client -> SyncMaterialClient.onAreaInventorySnapshot(
                new AreaInventorySnapshotS2CPacket(projectId, catalogRevision, sequence + 3, true,
                        inventoryFor(screen, materialId, 48, 16), List.of())));
        waitFor(context, () -> context.computeOnClient(client -> {
            var list = screen.getMaterialList();
            var entry = list.getMaterialsAll().stream()
                    .filter(value -> value.getDatabaseId() == materialId).findFirst().orElse(null);
            return entry != null && list.isPreparationKnown() && screen.getFreshnessWarnings().isEmpty()
                    && entry.getStagingCount() == 48 && entry.getWarehouseCount() == 16;
        }));
    }

    /** v3 快照必须覆盖当前目录的全部材料 id；仅被测材料带计数，其余为明确 0。 */
    private static List<AreaInventorySnapshotS2CPacket.MaterialInventory> inventoryFor(
            GuiMaterialList screen, int materialId, int staging, int warehouse) {
        return screen.getMaterialList().getMaterialsAll().stream()
                .map(entry -> new AreaInventorySnapshotS2CPacket.MaterialInventory(entry.getDatabaseId(),
                        entry.getDatabaseId() == materialId ? staging : 0,
                        entry.getDatabaseId() == materialId ? warehouse : 0))
                .toList();
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier condition) {
        for (int tick = 0; tick < 200; tick += 5) {
            context.waitTicks(5);
            if (condition.getAsBoolean()) return;
        }
        throw new AssertionError("Foundation network flow did not settle");
    }
}
