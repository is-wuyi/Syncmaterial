package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.gametest.client.ClientSessionLifecycleScenario;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/** Foundation real isolated-store fixture for the shared session lifecycle scenario. */
public final class FoundationClientSessionLifecycleClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientSessionLifecycleScenario.run(context, new Backend());
    }

    private static final class Backend implements ClientSessionLifecycleScenario.Backend {
        @Override
        public void prepare(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                var runtime = ServerRuntimeRegistry.get(instance);
                var player = instance.getPlayerList().getPlayers().get(0);
                runtime.store().ingest(new SchematicMetadata(projectId, "会话生命周期",
                                Path.of("session.litematic"), "session",
                                new PlayerIdentity(player.getUUID(), player.getName().getString())),
                        List.of(new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
                return null;
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                var runtime = ServerRuntimeRegistry.get(instance);
                if (runtime != null) runtime.store().removeProject(projectId).join();
                return null;
            });
        }
    }
}
