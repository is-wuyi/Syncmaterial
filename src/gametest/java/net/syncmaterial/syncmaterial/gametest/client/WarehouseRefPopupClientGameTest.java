package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;

/**
 * Legacy-backed end-to-end entry for the production warehouse-reference popup.
 *
 * <p>The fixture is a real schematic row plus a real global warehouse row in the legacy
 * SQLite schema, and every authoritative assertion reads the legacy server APIs. The GUI
 * path is shared with the Foundation entry through
 * {@link WarehouseRefPopupLifecycleScenario}.</p>
 */
public final class WarehouseRefPopupClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        WarehouseRefPopupLifecycleScenario.run(context, new LegacyBackend());
    }

    private static final class LegacyBackend implements WarehouseRefPopupLifecycleScenario.Backend {
        @Override
        public int prepare(TestDedicatedServerContext server, String projectId) {
            return server.computeOnServer(instance -> {
                SchematicDatabase database = SyncMaterial.getSharedDatabase();
                if (database == null) {
                    throw new AssertionError("legacy 共享数据库未初始化");
                }
                StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
                if (manager == null) {
                    throw new AssertionError("legacy 备货区服务未初始化");
                }
                var player = instance.getPlayerList().getPlayers().get(0);
                try {
                    database.executeUpdate(
                            "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                            projectId, "弹出窗仓库引用夹具", "/popup-ref.litematic",
                            player.getName().getString());
                } catch (SQLException failure) {
                    throw new RuntimeException("插入原理图 fixture 失败", failure);
                }
                int x = player.getBlockX();
                int y = player.getBlockY();
                int z = player.getBlockZ();
                return manager.addWarehouse("弹出窗仓库引用夹具", McCompat.dimensionId(player.level()),
                        x, y, z, x + 2, y + 2, z + 2);
            });
        }

        @Override
        public boolean warehouseExists(TestDedicatedServerContext server, int warehouseId) {
            return server.computeOnServer(instance -> {
                StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
                return manager != null && manager.getAllWarehouses().stream()
                        .anyMatch(warehouse -> warehouse.id() == warehouseId);
            });
        }

        @Override
        public boolean projectReferences(TestDedicatedServerContext server, String projectId,
                                         int warehouseId) {
            return server.computeOnServer(instance -> {
                StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
                return manager != null && manager.getWarehousesForSchematic(projectId).stream()
                        .anyMatch(warehouse -> warehouse.id() == warehouseId);
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId, int warehouseId) {
            server.computeOnServer(instance -> {
                StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
                if (manager != null && warehouseId > 0) {
                    manager.removeWarehouseReference(projectId, warehouseId);
                    manager.deleteWarehouse(warehouseId);
                }
                SchematicDatabase database = SyncMaterial.getSharedDatabase();
                if (database != null) {
                    try {
                        database.deleteSchematicRecords(projectId);
                    } catch (SQLException failure) {
                        throw new RuntimeException("清理原理图 fixture 失败", failure);
                    }
                }
                return null;
            });
        }
    }
}
