package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.gametest.client.StagingAreaDimensionScenario;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Foundation 后端的真实维度传送编辑/选区端到端入口。
 *
 * <p>夹具由 Foundation writer store 真实摄取；权威读取走同一 store 的 AreaCatalog，
 * 断言换维度丢弃的旧选区没有写入、新维度合法重选确实写入。</p>
 */
public final class FoundationStagingAreaDimensionChangeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        StagingAreaDimensionScenario.run(context, new FoundationBackend());
    }

    private static final class FoundationBackend implements StagingAreaDimensionScenario.Backend {
        @Override
        public void prepare(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) {
                    throw new AssertionError("Foundation 运行时未初始化");
                }
                var player = instance.getPlayerList().getPlayers().get(0);
                runtime.store().ingest(new SchematicMetadata(
                                projectId, "维度传送选区夹具", Path.of("dim-selection.litematic"),
                                "dim-selection", new PlayerIdentity(player.getUUID(),
                                player.getName().getString())),
                        List.of(new MaterialCount("minecraft:stone", 1)),
                        runtime.interpretationId()).join();
                return null;
            });
        }

        @Override
        public List<StagingAreaDimensionScenario.AreaRow> projectAreas(
                TestDedicatedServerContext server, String projectId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) {
                    throw new AssertionError("Foundation 运行时未初始化");
                }
                var catalog = runtime.store().areaCatalog().join();
                return catalog.areas().stream()
                        .filter(area -> AreaKind.STAGING_AREA == area.kind()
                                && projectId.equals(area.projectId()))
                        .map(area -> {
                            BlockPos pos1 = new BlockPos(area.bounds().x1(), area.bounds().y1(), area.bounds().z1());
                            BlockPos pos2 = new BlockPos(area.bounds().x2(), area.bounds().y2(), area.bounds().z2());
                            return new StagingAreaDimensionScenario.AreaRow(
                                    area.id(), area.name(), area.world(),
                                    pos1.getX(), pos1.getY(), pos1.getZ(),
                                    pos2.getX(), pos2.getY(), pos2.getZ());
                        })
                        .sorted(java.util.Comparator.comparingInt(StagingAreaDimensionScenario.AreaRow::id))
                        .toList();
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, String projectId) {
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) {
                    return null;
                }
                runtime.store().removeProject(projectId).join();
                return null;
            });
        }
    }
}
