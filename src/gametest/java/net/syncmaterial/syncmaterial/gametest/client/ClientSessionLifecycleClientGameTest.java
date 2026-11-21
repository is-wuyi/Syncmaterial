package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/** Legacy real SQLite fixture for the shared session lifecycle scenario. */
public final class ClientSessionLifecycleClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientSessionLifecycleScenario.run(context, new Backend());
    }

    private static final class Backend implements ClientSessionLifecycleScenario.Backend {
        @Override
        public void prepare(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                try {
                    SchematicDatabase database = SyncMaterial.getSharedDatabase();
                    database.executeUpdate(
                            "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                            projectId, "会话生命周期", "/session.litematic", "Player0");
                    database.executeUpdate(
                            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                            projectId, "minecraft:dirt", 64);
                } catch (SQLException failure) {
                    throw new RuntimeException("创建 legacy 会话项目失败", failure);
                }
                return null;
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                try {
                    SchematicDatabase database = SyncMaterial.getSharedDatabase();
                    if (database != null) database.deleteSchematicRecords(projectId);
                } catch (SQLException failure) {
                    throw new RuntimeException("清理 legacy 会话项目失败", failure);
                }
                return null;
            });
        }
    }
}
