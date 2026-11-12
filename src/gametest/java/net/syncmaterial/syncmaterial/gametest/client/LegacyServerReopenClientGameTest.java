package net.syncmaterial.syncmaterial.gametest.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.network.ProtocolHandshake;
import net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/** 同 JVM 实际 A 关服再开 B；关服前捕获的原 owner 回调与旧 world 不能污染 B。 */
public final class LegacyServerReopenClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        AtomicReference<LegacyServerContext> oldOwner = new AtomicReference<>();
        AtomicReference<ServerLevel> oldWorld = new AtomicReference<>();
        AtomicReference<LevelChunk> oldChunk = new AtomicReference<>();
        AtomicReference<ServerPlayer> oldPlayer = new AtomicReference<>();
        AtomicBoolean oldTaskRan = new AtomicBoolean();
        AtomicReference<Runnable> retainedCallback = new AtomicReference<>();
        String id = "lifecycle-reopen-" + System.nanoTime();
        try {
            try (var server = context.worldBuilder().createServer(); var connection = server.connect()) {
                context.waitTicks(10);
                server.computeOnServer(instance -> {
                    LegacyServerContext owner = LegacyServerRegistry.get(instance);
                    if (owner == null || !owner.isCurrent()) throw new AssertionError("A 上下文未安装");
                    ServerPlayer player = instance.getPlayerList().getPlayerByName("Player0");
                    if (player == null || !ProtocolHandshake.hasHandshaked(player)) throw new AssertionError("A 握手未完成");
                    oldOwner.set(owner);
                    oldPlayer.set(player);
                    oldWorld.set(player.level());
                    oldChunk.set(player.level().getChunkSource().getChunkNow(
                            net.syncmaterial.syncmaterial.compat.McCompat.chunkX(player.chunkPosition()),
                            net.syncmaterial.syncmaterial.compat.McCompat.chunkZ(player.chunkPosition())));
                    owner.setPlacementName(id, "A");
                    retainedCallback.set(owner.guardedTask(() -> {
                        oldTaskRan.set(true);
                        owner.staging().loadWarehousesFromDb();
                        owner.schematicRemoved(id);
                    }));
                    owner.subscriptions().subscribeMaterials(player, id);
                    owner.subscriptions().subscribeWarehouses(player, id, java.util.Set.of(123));
                    return null;
                });
            }
            LegacyServerContext original = oldOwner.get();
            if (original == null || original.isOpen() || original.database().isOpen()) {
                throw new AssertionError("A 服务器关闭后资源仍开放");
            }
            if (!original.placementNames().isEmpty() || !original.subscriptions().warehouseSubscribers().isEmpty()
                    || !original.subscriptions().projects(oldPlayer.get()).isEmpty()) {
                throw new AssertionError("A 关闭后名称或订阅仍驻留");
            }
            if (ProtocolHandshake.hasHandshaked(oldPlayer.get().getUUID())) throw new AssertionError("A 关闭后握手记录未清理");
            if (LegacyServerRegistry.get(original.server()) != null) throw new AssertionError("A 注册表未移除");

            try (var server = context.worldBuilder().createServer(); var connection = server.connect()) {
                context.waitTicks(10);
                int[] fixture = server.computeOnServer(instance -> {
                    try {
                        LegacyServerContext owner = LegacyServerRegistry.get(instance);
                        if (owner == null || !owner.isCurrent() || owner == original || owner.epoch().equals(original.epoch())) {
                            throw new AssertionError("B 未建立独立上下文");
                        }
                        ServerPlayer player = instance.getPlayerList().getPlayerByName("Player0");
                        if (player == null || !ProtocolHandshake.hasHandshaked(player)) throw new AssertionError("B 握手未完成");
                        owner.database().executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                                id, "B 重开", "/lifecycle-reopen.litematic", "Player0");
                        owner.database().executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, 'minecraft:stone', 128)", id);
                        int material;
                        try (var rows = owner.database().executeQuery("SELECT last_insert_rowid()")) {
                            if (!rows.next()) throw new AssertionError("B 材料 ID 不存在");
                            material = rows.getInt(1);
                        }
                        BlockPos position = player.blockPosition().offset(2, 0, 0);
                        player.level().setBlock(position, Blocks.BARREL.defaultBlockState(), 3);
                        if (!(player.level().getBlockEntity(position) instanceof BarrelBlockEntity barrel)) throw new AssertionError("B 容器未创建");
                        barrel.setItem(0, new ItemStack(Items.STONE, 64));
                        int warehouse = owner.staging().addWarehouse("B 仓库", "minecraft:overworld",
                                position.getX(), position.getY(), position.getZ(), position.getX(), position.getY(), position.getZ());
                        if (warehouse <= 0) throw new AssertionError("B 仓库未创建");
                        owner.staging().addWarehouseReference(id, warehouse);
                        owner.staging().rescanWarehouseAndMarkChunks(warehouse);
                        owner.collaboration().joinCollaboration(id, material, "Player0");
                        owner.setPlacementName(id, "B 重开");

                        retainedCallback.get().run();
                        original.execute(() -> oldTaskRan.set(true));
                        original.schematicChanged(id);
                        original.schematicRemoved(id);
                        original.tick();
                        original.staging().scheduleContainerScan(position, oldWorld.get());
                        original.staging().onContainerRemoved(position, oldWorld.get());
                        if (oldChunk.get() != null) original.staging().scanChunkForContainerAreas(oldChunk.get(), oldWorld.get());
                        owner.staging().scanChunkForContainerAreas(oldChunk.get(), oldWorld.get());
                        ModNetworkHandler.onPlayerDisconnect(oldPlayer.get());
                        LegacyServerRegistry.stop(original.server());
                        LegacyServerRegistry.close(original.server());

                        if (oldTaskRan.get()) throw new AssertionError("关闭的 A 任务仍执行");
                        if (!ProtocolHandshake.hasHandshaked(player)) throw new AssertionError("A 断线清理误删 B 握手");
                        if (!"B 重开".equals(owner.placementNames().get(id))) throw new AssertionError("A 回调修改了 B 的项目名称");
                        if (owner.staging().getWarehouseCountForMaterial(id, "minecraft:stone") != 64) {
                            throw new AssertionError("旧 A world/task 修改了 B 仓库计数");
                        }
                        return new int[]{material, warehouse};
                    } catch (java.sql.SQLException error) {
                        throw new IllegalStateException(error);
                    }
                });

                context.runOnClient(client -> ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(id)));
                context.waitForScreen(GuiMaterialList.class);
                boolean inventory = false;
                for (int tick = 0; tick < 100 && !inventory; tick += 5) {
                    context.waitTicks(5);
                    inventory = context.computeOnClient(client -> {
                        if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui) || !id.equals(gui.getSchematicId())) return false;
                        return gui.getMaterialList().getMaterialsAll().stream().anyMatch(entry -> entry.getDatabaseId() == fixture[0]
                                && entry.getWarehouseCount() == 64 && entry.isCurrentPlayerClaimed());
                    });
                }
                if (!inventory) throw new AssertionError("B 真实材料／仓库库存未通过网络回流");
                context.runOnClient(client -> ClientPlayNetworking.send(new WarehouseContainerRequestC2SPacket(id, true)));
                boolean detail = false;
                for (int tick = 0; tick < 100 && !detail; tick += 5) {
                    context.waitTicks(5);
                    detail = context.computeOnClient(client -> StagingAreaRenderer.getInstance().getWarehouseContainers().stream()
                            .anyMatch(container -> container.itemIds().contains("minecraft:stone")));
                }
                if (!detail) throw new AssertionError("B 仓库明细订阅未回流");
                server.computeOnServer(instance -> {
                    LegacyServerContext owner = LegacyServerRegistry.get(instance);
                    if (!owner.subscriptions().projects(instance.getPlayerList().getPlayerByName("Player0")).contains(id)) {
                        throw new AssertionError("B 材料订阅未隔离保留");
                    }
                    owner.staging().deleteWarehouse(fixture[1]);
                    try {
                        owner.database().deleteSchematicRecords(id);
                    } catch (java.sql.SQLException error) {
                        throw new IllegalStateException(error);
                    }
                    return null;
                });
            }
        } finally {
            context.runOnClient(client -> {
                ClientScreenAccess.showScreen(client, null);
                InventoryWatcher.clearContext();
                PickupModeState.clear();
                StagingAreaRenderer.getInstance().clearWarehouseContainers();
            });
        }
    }
}
