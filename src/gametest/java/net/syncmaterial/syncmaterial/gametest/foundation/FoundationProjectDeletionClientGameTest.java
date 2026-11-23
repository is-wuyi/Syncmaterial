package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/** Real TEAM-only deletion delivery, with an independent global warehouse observer. */
public final class FoundationProjectDeletionClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String projectId = "foundation-delete-" + UUID.randomUUID();
        AtomicReference<ProjectViewClient.GlobalLease> globalLease = new AtomicReference<>();
        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                context.waitTicks(20);
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var player = instance.getPlayerList().getPlayers().get(0);
                    runtime.store().ingest(new SchematicMetadata(projectId, "Foundation deletion",
                                    Path.of("foundation-delete.litematic"), "delete",
                                    new PlayerIdentity(player.getUUID(), player.getName().getString())),
                            List.of(new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
                    return null;
                });
                context.waitFor(client -> ClientProtocolState.serverSupports(
                        ProtocolVersion.SCOPED_PROJECT_VIEWS));
                context.runOnClient(client -> {
                    ProjectViewClient view = ProjectViewClient.current()
                            .orElseThrow(() -> new AssertionError("删除测试缺少项目视图会话"));
                    globalLease.set(view.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit()));
                    if (view.openProject(projectId, InterestKind.TEAM.bit()) <= 0) {
                        throw new AssertionError("TEAM-only 项目视图未发送");
                    }
                });
                waitFor(context, () -> server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    var team = runtime.views().views(projectId, InterestKind.TEAM);
                    return team.size() == 1 && team.get(0).mask() == InterestKind.TEAM.bit()
                            && runtime.views().views(projectId, InterestKind.MATERIALS).isEmpty()
                            && runtime.subscribers(projectId).size() == 1
                            && runtime.views().views(ProjectViewScope.globalWarehouse(),
                            InterestKind.AREA_CONFIG).size() == 1;
                }));

                server.computeOnServer(instance -> {
                    ServerRuntimeRegistry.get(instance).removeProject(projectId).join();
                    return null;
                });

                waitFor(context, () -> server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    return runtime.views().views(projectId, InterestKind.TEAM).isEmpty()
                            && runtime.interests().projectCount() == 0
                            && runtime.subscribers(projectId).isEmpty()
                            && runtime.interests().globalScopeCount() == 1
                            && runtime.views().views(ProjectViewScope.globalWarehouse(),
                            InterestKind.AREA_CONFIG).size() == 1;
                }));
                waitFor(context, () -> context.computeOnClient(client ->
                        ProjectViewClient.current().map(view -> view.projectStore().isEmpty()
                                && view.globalStore().map(store -> store.valid()).orElse(false))
                                .orElse(false)));
            } finally {
                context.runOnClient(client -> {
                    ProjectViewClient.GlobalLease lease = globalLease.getAndSet(null);
                    if (lease != null) lease.close();
                    ProjectViewClient.current().ifPresent(view -> view.closeProject(true));
                });
                server.computeOnServer(instance -> {
                    var runtime = ServerRuntimeRegistry.get(instance);
                    if (runtime != null) runtime.store().removeProject(projectId).join();
                    return null;
                });
            }
        }
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier condition) {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (condition.getAsBoolean()) return;
            context.waitTicks(1);
        }
        throw new AssertionError("Foundation 项目删除场景等待超时");
    }
}
