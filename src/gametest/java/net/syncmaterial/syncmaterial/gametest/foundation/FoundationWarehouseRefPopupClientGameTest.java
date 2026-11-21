package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.gametest.client.WarehouseRefPopupLifecycleScenario;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Foundation-backed end-to-end entry for the production warehouse-reference popup.
 *
 * <p>The fixture is ingested into the isolated Foundation writer store as a real project
 * plus a real global warehouse; authoritative reads come from the same store catalog.
 * The GUI path is shared with the legacy entry.</p>
 */
public final class FoundationWarehouseRefPopupClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        WarehouseRefPopupLifecycleScenario.run(context, new FoundationBackend());
    }

    private static final class FoundationBackend
            implements WarehouseRefPopupLifecycleScenario.Backend {
        @Override
        public int prepare(TestDedicatedServerContext server, String projectId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) {
                    throw new AssertionError("Foundation 运行时未初始化");
                }
                var player = instance.getPlayerList().getPlayers().get(0);
                runtime.store().ingest(new SchematicMetadata(
                                projectId, "弹出窗仓库引用夹具", Path.of("popup-ref.litematic"),
                                "popup-ref", new PlayerIdentity(player.getUUID(),
                                player.getName().getString())),
                        List.of(new MaterialCount("minecraft:dirt", 1)),
                        runtime.interpretationId()).join();
                int x = player.getBlockX();
                int y = player.getBlockY();
                int z = player.getBlockZ();
                return runtime.store().addArea(null, AreaKind.WAREHOUSE, new AreaSpec(
                                "弹出窗仓库引用夹具", McCompat.dimensionId(player.level()),
                                new AreaBounds(x, y, z, x + 2, y + 2, z + 2)))
                        .join().id();
            });
        }

        @Override
        public boolean warehouseExists(TestDedicatedServerContext server, int warehouseId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return false;
                AreaCatalog catalog = runtime.store().areaCatalog().join();
                return catalog.areas().stream().anyMatch(area -> area.id() == warehouseId
                        && area.kind() == AreaKind.WAREHOUSE && area.projectId() == null);
            });
        }

        @Override
        public boolean projectReferences(TestDedicatedServerContext server, String projectId,
                                         int warehouseId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return false;
                AreaCatalog catalog = runtime.store().areaCatalog().join();
                return catalog.referencedWarehouseIds(projectId).contains(warehouseId);
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId, int warehouseId) {
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return null;
                try {
                    if (warehouseId > 0) {
                        runtime.store().removeArea(null, AreaKind.WAREHOUSE, warehouseId).join();
                    }
                } finally {
                    runtime.store().removeProject(projectId).join();
                }
                return null;
            });
        }
    }
}
