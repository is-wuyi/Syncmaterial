package net.syncmaterial.syncmaterial.gametest.foundation;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.adapter.minecraft.FoundationAreaEvents;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.gametest.client.PickupRefRefreshClientScenario;
import net.syncmaterial.syncmaterial.gametest.client.PickupRefRefreshClientScenario.Fixture;
import net.syncmaterial.syncmaterial.network.FoundationNetworkAdapter;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/** Foundation-backed protocol-4 pickup refresh, shared-reference and reconnect regression. */
public final class FoundationPickupRefRefreshClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        PickupRefRefreshClientScenario.run(context, new FoundationBackend());
    }

    private static final class FoundationBackend implements PickupRefRefreshClientScenario.Backend {
        private UUID playerId;
        private long connection;
        private String firstProject;
        private String secondProject;

        @Override
        public Fixture prepare(TestDedicatedServerContext server, String fixtureId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) throw new AssertionError("Foundation 取货刷新测试运行时未初始化");
                var player = instance.getPlayerList().getPlayers().get(0);
                var level = player.level();
                playerId = player.getUUID();
                connection = runtime.connection(playerId);
                firstProject = fixtureId + "-a";
                secondProject = fixtureId + "-b";
                for (String projectId : List.of(firstProject, secondProject)) {
                    runtime.store().ingest(new SchematicMetadata(projectId, "Foundation旧取货引用刷新",
                                    Path.of(projectId + ".litematic"), "legacy-pickup-ref",
                                    new PlayerIdentity(playerId, player.getName().getString())),
                            List.of(new MaterialCount("minecraft:dirt", 16)), runtime.interpretationId()).join();
                }
                int x = player.getBlockX();
                int y = player.getBlockY() + 3;
                int z = player.getBlockZ();
                BlockPos firstPos = new BlockPos(x + 2, y, z + 2);
                BlockPos secondPos = new BlockPos(x + 5, y, z + 5);
                prepareBarrel(level, firstPos);
                prepareBarrel(level, secondPos);
                String world = McCompat.dimensionId(level);
                int firstWarehouse = runtime.store().addArea(null, AreaKind.WAREHOUSE, new AreaSpec(
                        "Foundation旧取货仓库A", world, new AreaBounds(firstPos.getX(), y, firstPos.getZ(),
                        firstPos.getX(), y, firstPos.getZ()))).join().id();
                int secondWarehouse = runtime.store().addArea(null, AreaKind.WAREHOUSE, new AreaSpec(
                        "Foundation旧取货仓库B", world, new AreaBounds(secondPos.getX(), y, secondPos.getZ(),
                        secondPos.getX(), y, secondPos.getZ()))).join().id();
                runtime.store().addWarehouseReference(firstProject, firstWarehouse).join();
                runtime.store().addWarehouseReference(secondProject, secondWarehouse).join();
                runtime.areas().refreshConfiguration();
                FoundationAreaEvents.containerChanged(level, firstPos);
                FoundationAreaEvents.containerChanged(level, secondPos);
                return new Fixture(firstProject, secondProject, firstWarehouse, secondWarehouse,
                        firstPos, secondPos);
            });
        }

        @Override
        public boolean references(TestDedicatedServerContext server, String projectId, int warehouseId) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                return runtime != null && runtime.store().areaCatalog().join()
                        .referencedWarehouseIds(projectId).contains(warehouseId);
            });
        }

        @Override
        public boolean hasContainer(TestDedicatedServerContext server, String projectId, BlockPos pos) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return false;
                return runtime.store().warehouseContainers(projectId, "minecraft:overworld").join().stream()
                        .anyMatch(observation -> observation.warning().isBlank()
                                && observation.key().x() == pos.getX() && observation.key().y() == pos.getY()
                                && observation.key().z() == pos.getZ()
                                && observation.counts().getOrDefault("minecraft:dirt", 0L) > 0);
            });
        }

        @Override
        public boolean pickupSubscriptionsEmpty(TestDedicatedServerContext server) {
            return server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime == null) return false;
                try {
                    Method delivery = FoundationNetworkAdapter.class.getDeclaredMethod("delivery", ServerRuntime.class);
                    delivery.setAccessible(true);
                    Object tracker = delivery.invoke(null, runtime);
                    Method subscribed = tracker.getClass().getDeclaredMethod("hasWarehouseSubscription",
                            UUID.class, long.class, String.class);
                    subscribed.setAccessible(true);
                    return !((boolean) subscribed.invoke(tracker, playerId, connection, firstProject))
                            && !((boolean) subscribed.invoke(tracker, playerId, connection, secondProject));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("无法读取 Foundation 旧取货断线状态", failure);
                }
            });
        }

        @Override
        public boolean areaEditSubscribesMaterialList() {
            return true;
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, Fixture fixture) {
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                if (runtime != null) {
                    runtime.store().removeArea(null, AreaKind.WAREHOUSE, fixture.firstWarehouse()).join();
                    runtime.store().removeArea(null, AreaKind.WAREHOUSE, fixture.secondWarehouse()).join();
                    runtime.store().removeProject(fixture.firstProject()).join();
                    runtime.store().removeProject(fixture.secondProject()).join();
                    runtime.requestBroadcast(fixture.firstProject());
                    runtime.requestBroadcast(fixture.secondProject());
                }
                instance.overworld().setBlockAndUpdate(fixture.firstContainer(), Blocks.AIR.defaultBlockState());
                instance.overworld().setBlockAndUpdate(fixture.secondContainer(), Blocks.AIR.defaultBlockState());
                return null;
            });
        }

        private static void prepareBarrel(net.minecraft.world.level.Level level, BlockPos pos) {
            level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            if (!(level.getBlockEntity(pos) instanceof Container container)) {
                throw new AssertionError("Foundation 旧取货真实桶 fixture 创建失败");
            }
            container.setItem(0, new ItemStack(Items.DIRT, 16));
            container.setChanged();
        }
    }
}
