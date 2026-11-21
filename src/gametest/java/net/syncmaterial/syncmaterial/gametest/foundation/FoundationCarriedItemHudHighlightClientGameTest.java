package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.gametest.CarriedItemHudHighlightScenario;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Foundation-backed carried-item acceptance fixture. Same production input/HUD
 * assertions as the legacy case, but the warehouse and project live in the isolated
 * Foundation writer and area scanner rather than the legacy SQLite/staging manager.
 */
public class FoundationCarriedItemHudHighlightClientGameTest extends CarriedItemHudHighlightScenario
        implements net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest {

    private static final String PROJECT = "foundation-carried-item-" + UUID.randomUUID();

    @Override
    public void runTest(ClientGameTestContext context) {
        runScenario(context);
    }

    @Override
    protected String projectId() {
        return PROJECT;
    }

    @Override
    protected Fixture prepareFixture(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        int materialId = server.computeOnServer(instance -> {
            ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
            var player = instance.getPlayerList().getPlayers().get(0);
            PlayerIdentity owner = new PlayerIdentity(player.getUUID(), player.getName().getString());
            runtime.store().ingest(new SchematicMetadata(
                            PROJECT, "Foundation carried item", Path.of("foundation-carried-item.litematic"),
                            "carried-item", owner),
                    List.of(new MaterialCount(STONE, REQUIRED)), runtime.interpretationId()).join();
            return runtime.store().findProject(PROJECT).join().orElseThrow()
                    .materials().get(0).id();
        });
        BlockPos chestPos = placeChestWithStone(server);
        int warehouseId = server.computeOnServer(instance -> {
            ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
            var level = instance.getPlayerList().getPlayers().get(0).level();
            int x = chestPos.getX();
            int y = chestPos.getY();
            int z = chestPos.getZ();
            int warehouse = runtime.store().addArea(null, AreaKind.WAREHOUSE,
                    new AreaSpec("Foundation carried warehouse", McCompat.dimensionId(level),
                            new AreaBounds(x, y, z, x, y, z))).join().id();
            runtime.store().addWarehouseReference(PROJECT, warehouse).join();
            // AreaScanService completions are drained by the server tick; joining here
            // would deadlock the server thread. The shared scenario waits for the
            // authoritative warehouse count instead.
            runtime.areas().rescan(PROJECT);
            runtime.requestBroadcast(PROJECT);
            return warehouse;
        });
        return new Fixture(materialId, warehouseId);
    }

    @Override
    protected void cleanupFixture(TestDedicatedServerContext server, Fixture fixture) {
        server.computeOnServer(instance -> {
            ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
            if (runtime == null) return null;
            runtime.store().removeArea(null, AreaKind.WAREHOUSE, fixture.warehouseId()).join();
            runtime.store().removeProject(PROJECT).join();
            return null;
        });
    }
}
