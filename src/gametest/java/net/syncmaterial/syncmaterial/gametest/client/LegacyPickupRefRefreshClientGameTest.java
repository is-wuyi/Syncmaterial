package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.gametest.client.PickupRefRefreshClientScenario.Fixture;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/** Legacy-backed protocol-4 pickup refresh, shared-reference and reconnect regression. */
public final class LegacyPickupRefRefreshClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        PickupRefRefreshClientScenario.run(context, new LegacyBackend());
    }

    private static final class LegacyBackend implements PickupRefRefreshClientScenario.Backend {
        @Override
        public Fixture prepare(TestDedicatedServerContext server, String fixtureId) {
            return server.computeOnServer(instance -> {
                LegacyServerContext owner = LegacyServerRegistry.get(instance);
                if (owner == null || owner.staging() == null || owner.database() == null) {
                    throw new AssertionError("legacy 取货刷新测试上下文未初始化");
                }
                var player = instance.getPlayerList().getPlayers().get(0);
                var level = player.level();
                String firstProject = fixtureId + "-a";
                String secondProject = fixtureId + "-b";
                try {
                    for (String projectId : Set.of(firstProject, secondProject)) {
                        owner.database().executeUpdate(
                                "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                                projectId, "旧取货引用刷新", "/legacy-pickup-ref.litematic",
                                player.getName().getString());
                    }
                } catch (SQLException failure) {
                    throw new RuntimeException("创建旧取货项目 fixture 失败", failure);
                }
                int x = player.getBlockX();
                int y = player.getBlockY() + 3;
                int z = player.getBlockZ();
                BlockPos firstPos = new BlockPos(x + 2, y, z + 2);
                BlockPos secondPos = new BlockPos(x + 5, y, z + 5);
                prepareChest(level, firstPos);
                prepareChest(level, secondPos);
                String world = McCompat.dimensionId(level);
                int firstWarehouse = owner.staging().addWarehouse("旧取货仓库A", world,
                        firstPos.getX(), y, firstPos.getZ(), firstPos.getX(), y, firstPos.getZ());
                int secondWarehouse = owner.staging().addWarehouse("旧取货仓库B", world,
                        secondPos.getX(), y, secondPos.getZ(), secondPos.getX(), y, secondPos.getZ());
                owner.staging().addWarehouseReference(firstProject, firstWarehouse);
                owner.staging().addWarehouseReference(secondProject, secondWarehouse);
                for (int warehouseId : Set.of(firstWarehouse, secondWarehouse)) {
                    owner.staging().rescanWarehouseAndMarkChunks(warehouseId);
                }
                for (BlockPos pos : Set.of(firstPos, secondPos)) {
                    owner.staging().scanChunkForContainerAreas(
                            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4), level);
                }
                return new Fixture(firstProject, secondProject, firstWarehouse, secondWarehouse,
                        firstPos, secondPos);
            });
        }

        @Override
        public boolean references(TestDedicatedServerContext server, String projectId, int warehouseId) {
            return server.computeOnServer(instance -> {
                LegacyServerContext owner = LegacyServerRegistry.get(instance);
                return owner != null && owner.staging().getWarehousesForSchematic(projectId).stream()
                        .anyMatch(warehouse -> warehouse.id() == warehouseId);
            });
        }

        @Override
        public boolean hasContainer(TestDedicatedServerContext server, String projectId, BlockPos pos) {
            return server.computeOnServer(instance -> {
                LegacyServerContext owner = LegacyServerRegistry.get(instance);
                if (owner == null) return false;
                Set<Integer> ids = owner.staging().getWarehousesForSchematic(projectId).stream()
                        .map(net.syncmaterial.syncmaterial.server.StagingAreaManager.Warehouse::id)
                        .collect(Collectors.toSet());
                return owner.staging().getContainerEntriesForWarehouses(ids, McCompat.dimensionId(instance.overworld()))
                        .stream().anyMatch(entry -> entry.posX() == pos.getX() && entry.posY() == pos.getY()
                                && entry.posZ() == pos.getZ() && entry.itemIds().contains("minecraft:dirt"));
            });
        }

        @Override
        public boolean pickupSubscriptionsEmpty(TestDedicatedServerContext server) {
            return server.computeOnServer(instance -> {
                LegacyServerContext owner = LegacyServerRegistry.get(instance);
                return owner != null && owner.subscriptions().warehouseSubscribers().isEmpty();
            });
        }

        @Override
        public void cleanup(TestDedicatedServerContext server, Fixture fixture) {
            server.computeOnServer(instance -> {
                LegacyServerContext owner = LegacyServerRegistry.get(instance);
                if (owner != null) {
                    owner.staging().deleteWarehouse(fixture.firstWarehouse());
                    owner.staging().deleteWarehouse(fixture.secondWarehouse());
                    try {
                        owner.database().deleteSchematicRecords(fixture.firstProject());
                        owner.database().deleteSchematicRecords(fixture.secondProject());
                    } catch (SQLException failure) {
                        throw new RuntimeException("清理旧取货项目 fixture 失败", failure);
                    }
                }
                instance.overworld().setBlockAndUpdate(fixture.firstContainer(), Blocks.AIR.defaultBlockState());
                instance.overworld().setBlockAndUpdate(fixture.secondContainer(), Blocks.AIR.defaultBlockState());
                return null;
            });
        }

        private static void prepareChest(net.minecraft.world.level.Level level, BlockPos pos) {
            level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                throw new AssertionError("旧取货真实箱子 fixture 创建失败");
            }
            chest.setItem(0, new ItemStack(Items.DIRT, 16));
            chest.setChanged();
        }
    }
}
