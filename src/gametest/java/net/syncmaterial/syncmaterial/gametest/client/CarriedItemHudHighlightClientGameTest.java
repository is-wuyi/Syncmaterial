package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.gametest.CarriedItemHudHighlightScenario;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * Legacy-backed carried-item acceptance fixture. The shared scenario performs all
 * real mouse input and HUD/highlight assertions; this class only creates the legacy
 * SQLite project and staging-manager warehouse.
 */
public class CarriedItemHudHighlightClientGameTest extends CarriedItemHudHighlightScenario
        implements net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest {

    private static final String PROJECT = "carried-item-" + System.nanoTime();

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
        int materialId = onDatabase(server, database -> {
            database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    PROJECT, "Carried Item Test", "/carried-item.litematic", "Owner");
            database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    PROJECT, STONE, REQUIRED);
            try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                result.next();
                return result.getInt(1);
            }
        });
        BlockPos chestPos = placeChestWithStone(server);
        int warehouseId = server.computeOnServer(instance -> {
            var manager = SyncMaterial.getServerStagingAreaManager();
            var level = instance.getPlayerList().getPlayerByName("Player0").level();
            int warehouse = manager.addWarehouse("Carried item warehouse",
                    McCompat.dimensionId(level),
                    chestPos.getX(), chestPos.getY(), chestPos.getZ(),
                    chestPos.getX(), chestPos.getY(), chestPos.getZ());
            if (warehouse <= 0) throw new AssertionError("光标测试仓库创建失败");
            manager.addWarehouseReference(PROJECT, warehouse);
            manager.rescanWarehouseAndMarkChunks(warehouse);
            if (manager.getWarehouseCountForMaterial(PROJECT, STONE) != WAREHOUSE_STOCK) {
                throw new AssertionError("光标测试仓库应统计 " + WAREHOUSE_STOCK + " 个石头，实际 "
                        + manager.getWarehouseCountForMaterial(PROJECT, STONE));
            }
            return warehouse;
        });
        return new Fixture(materialId, warehouseId);
    }

    @Override
    protected void cleanupFixture(TestDedicatedServerContext server, Fixture fixture) {
        server.computeOnServer(instance -> {
            SyncMaterial.getServerStagingAreaManager().deleteWarehouse(fixture.warehouseId());
            return null;
        });
        onDatabase(server, database -> {
            database.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM player_inventories WHERE schematic_id = ?", PROJECT);
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
