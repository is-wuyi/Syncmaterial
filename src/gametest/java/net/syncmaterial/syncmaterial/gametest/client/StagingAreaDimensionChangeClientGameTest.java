package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * Legacy 后端的真实维度传送编辑/选区端到端入口。
 *
 * <p>夹具是 legacy SQLite 里的真实原理图行；权威读取走同一生产库，
 * 断言换维度丢弃的旧选区没有写入 staging_areas、新维度合法重选确实写入。</p>
 */
public final class StagingAreaDimensionChangeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        StagingAreaDimensionScenario.run(context, new LegacyBackend());
    }

    private static final class LegacyBackend implements StagingAreaDimensionScenario.Backend {
        @Override
        public void prepare(TestDedicatedServerContext server, String projectId) {
            onDatabase(server, database -> {
                database.executeUpdate(
                        "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                        projectId, "维度传送选区夹具", "/dim-selection.litematic", "Player0");
                database.executeUpdate(
                        "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                        projectId, "minecraft:stone", 1);
                return null;
            });
        }

        @Override
        public List<StagingAreaDimensionScenario.AreaRow> projectAreas(
                TestDedicatedServerContext server, String projectId) {
            return onDatabase(server, database -> {
                try (var result = database.executeQuery(
                        "SELECT id, name, world, x1, y1, z1, x2, y2, z2 FROM staging_areas "
                                + "WHERE schematic_id = ? ORDER BY id", projectId)) {
                    var rows = new java.util.ArrayList<StagingAreaDimensionScenario.AreaRow>();
                    while (result.next()) {
                        rows.add(new StagingAreaDimensionScenario.AreaRow(
                                result.getInt("id"), result.getString("name"), result.getString("world"),
                                result.getInt("x1"), result.getInt("y1"), result.getInt("z1"),
                                result.getInt("x2"), result.getInt("y2"), result.getInt("z2")));
                    }
                    return List.copyOf(rows);
                }
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId) {
            onDatabase(server, database -> {
                database.executeUpdate("DELETE FROM schematics WHERE id = ?", projectId);
                return null;
            });
        }

        private <T> T onDatabase(TestDedicatedServerContext server, SqlFunction<SchematicDatabase, T> action) {
            return server.computeOnServer(instance -> {
                try {
                    return action.apply(SyncMaterial.getSharedDatabase());
                } catch (SQLException failure) {
                    throw new RuntimeException("测试数据库操作失败", failure);
                }
            });
        }
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
