package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.adapter.minecraft.FoundationAreaEvents;
import net.syncmaterial.syncmaterial.adapter.minecraft.MinecraftAreaSnapshotSource;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.mixin.ChunkAccessMixin;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Real Foundation server scenarios: actual blocks in the live server world, the production
 * passive snapshot source, and the runtime scanner driven by real server ticks.
 *
 * <p>Each async scenario is expressed as a {@code succeedWhen} sequence. Waiting therefore
 * uses the game-test tick sequence (not a blocking join on a future that only completes on
 * the same server thread); a failed assertion inside the wait simply retries until the test
 * timeout and keeps the last diagnostic message.</p>
 */
public class FoundationServerGameTest {
    @GameTest(structure = "empty")
    public void runtimeUsesOnlyTheNewDatabase(GameTestHelper helper) {
        var runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        helper.assertTrue(runtime != null && runtime.isOpen(), Component.literal("新 runtime 应已初始化"));
        helper.assertTrue(SyncMaterial.getSharedDatabase() == null, Component.literal("新后端不能打开旧数据库"));
        helper.assertTrue(SyncMaterial.getServerStagingAreaManager() == null, Component.literal("首批不能启用旧扫描器"));
        helper.succeed();
    }

    @GameTest(structure = "empty")
    public void materialClaimLoopPreservesIdentityOnUpdate(GameTestHelper helper) {
        var runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        String id = "foundation-" + UUID.randomUUID();
        PlayerIdentity owner = new PlayerIdentity(UUID.randomUUID(), "Owner");
        PlayerIdentity participant = new PlayerIdentity(UUID.randomUUID(), "Participant");
        var before = new SchematicMetadata(id, "Before", Path.of("before.litematic"), "old", owner);
        try {
            runtime.store().ingest(before, List.of(new MaterialCount("minecraft:stone", 64)), runtime.interpretationId()).join();
            int materialId = runtime.store().findProject(id).join().orElseThrow().materials().get(0).id();
            helper.assertTrue(runtime.store().join(id, materialId, participant).join(), Component.literal("认领应成功"));
            runtime.connected(participant.uuid());
            runtime.reportInventory(id, materialId, participant, 32).join();
            helper.assertTrue(runtime.inventoryCount(participant.uuid(), id, materialId) == 32, Component.literal("在线持有量应可读取"));
            runtime.store().ingest(new SchematicMetadata(id, "After", Path.of("after.litematic"), "new", owner),
                    List.of(new MaterialCount("minecraft:stone", 128)), runtime.interpretationId()).join();
            var updated = runtime.store().snapshot(id).join().orElseThrow();
            helper.assertTrue(updated.project().materials().get(0).id() == materialId, Component.literal("更新不能改变保留物品的 ID"));
            helper.assertTrue(updated.claims().size() == 1, Component.literal("更新不能删除保留物品的认领"));
            runtime.disconnected(participant.uuid());
            helper.assertTrue(runtime.inventoryCount(participant.uuid(), id, materialId) == 0, Component.literal("离线立即不计库存"));
            helper.assertTrue(runtime.leave(id, materialId, participant).join(), Component.literal("退出应成功"));
            helper.assertTrue(runtime.store().claims(id).join().isEmpty(), Component.literal("退出后认领应为空"));
        } finally {
            runtime.store().removeProject(id).join();
        }
        helper.succeed();
    }

    // ==================================================================
    // Real container scans
    // ==================================================================

    @GameTest(structure = "empty", maxTicks = 700)
    public void nativeContainersCountTheirOwnHalves(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-halves");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        try {
            BlockPos first = placeChest(helper, blocks, 1, 1, 1,
                    new ItemStack(Items.STONE, 10), shulkerWith(Items.DIAMOND, 3));
            BlockPos second = placeChest(helper, blocks, 2, 1, 1, new ItemStack(Items.DIRT, 5));
            BlockPos third = placeChest(helper, blocks, 3, 1, 1, new ItemStack(Items.STONE, 2));
            BlockPos barrel = placeBarrel(helper, blocks, 4, 1, 1, new ItemStack(Items.DIRT, 1));
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(first, second, third, barrel)));
            runtime.areas().refreshConfiguration();
            await(helper, () -> {
                AreaInventoryView view = view(runtime, projectId);
                return view.known()
                        && count(view, "minecraft:stone") == 12
                        && count(view, "minecraft:dirt") == 6
                        && count(view, "minecraft:shulker_box") == 1
                        && count(view, "minecraft:diamond") == 3;
            }, "真实箱子/木桶/潜影盒/双箱两半未得到各自 27 格统计",
                    () -> cleanup(runtime, projectId, warehouses, blocks, helper.getLevel()));
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 900)
    public void denseShulkersSpanTickBudgets(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-dense-shulker");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        try {
            ItemStack[] stacks = new ItemStack[27];
            for (int slot = 0; slot < stacks.length; slot++) {
                stacks[slot] = shulkerWith(Items.STONE, 27);
            }
            BlockPos chest = placeChest(helper, blocks, 1, 1, 1, stacks);
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(chest)));
            runtime.areas().refreshConfiguration();
            await(helper, () -> {
                AreaInventoryView view = view(runtime, projectId);
                return view.known()
                        && count(view, "minecraft:shulker_box") == 27
                        && count(view, "minecraft:stone") == 27 * 27;
            }, "密集潜影盒的总槽位预算跨 tick 后统计不完整",
                    () -> cleanup(runtime, projectId, warehouses, blocks, helper.getLevel()));
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "syncmaterial-foundation-test:cross_chunk", maxTicks = 900)
    public void crossChunkLoadedSourcesAndOverlapDeduplicate(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-cross-chunk");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        try {
            BlockPos first = placeChest(helper, blocks, 1, 1, 1, new ItemStack(Items.STONE, 4));
            ServerLevel level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
            // Both sources belong to this test's structure. The native runner lays tests
            // out in rows as well as columns, so an external Z-adjacent fixture can collide.
            int otherChunkX = base.getX() >> 4;
            int otherChunkZ = (base.getZ() >> 4) + 1;
            BlockPos other = new BlockPos(base.getX(), base.getY(), (otherChunkZ << 4) + 1);
            var structure = helper.getBounds();
            if (!structure.contains(first.getX(), first.getY(), first.getZ())
                    || !structure.contains(other.getX(), other.getY(), other.getZ())
                    || (first.getZ() >> 4) == (other.getZ() >> 4)) {
                throw helper.assertionException("跨区块的两个箱子必须在本测试结构内且属于不同区块");
            }
            level.getChunk(otherChunkX, otherChunkZ);
            level.setBlockAndUpdate(other, Blocks.CHEST.defaultBlockState());
            ChestBlockEntity otherChest = (ChestBlockEntity) level.getBlockEntity(other);
            if (otherChest == null) {
                throw helper.assertionException("相邻区块的箱子方块实体未创建");
            }
            otherChest.setItem(0, new ItemStack(Items.STONE, 6));
            blocks.add(other);
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper,
                    () -> cleanup(runtime, projectId, warehouses, blocks, level));

            AreaBounds bounds = boundsAround(List.of(first, other));
            addStaging(runtime, projectId, dimension(helper), bounds);
            int warehouseId = runtime.store()
                    .addArea(null, AreaKind.WAREHOUSE, new AreaSpec("global-warehouse", dimension(helper), bounds))
                    .join().id();
            warehouses.add(warehouseId);
            runtime.store().addWarehouseReference(projectId, warehouseId).join();
            runtime.areas().refreshConfiguration();
            await(helper, () -> {
                AreaInventoryView view = view(runtime, projectId);
                if (!(view.known()
                        && count(view, "minecraft:stone") == 10
                        && view.warehouse().getOrDefault("minecraft:stone", 0L) == 0
                        && view.overlap())) {
                    throw helper.assertionException(Component.literal(
                            "跨区块贡献或备货区/仓库去重不正确 known=" + view.known()
                                    + " staging=" + view.staging() + " warehouse=" + view.warehouse()
                                    + " overlap=" + view.overlap() + " freshness=" + view.freshness()
                                    + " pending=" + runtime.areas().hasPendingWork()));
                }
                return true;
            }, "跨区块贡献或备货区/仓库去重不正确",
                    () -> cleanup(runtime, projectId, warehouses, blocks, helper.getLevel()));
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 800)
    public void creationObserverAndSetChangedAreReal(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-events");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        ChestBlockEntity[] chest = new ChestBlockEntity[1];
        int[] phase = {0};
        int[] quietTicks = {0};
        try {
            BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(base)));
            runtime.areas().refreshConfiguration();
            helper.succeedWhen(() -> {
                if (phase[0] == 0) {
                    if (!hasArea(runtime, projectId)) {
                        throw helper.assertionException("区域配置未在服务器 tick 中生效");
                    }
                    if (runtime.areas().hasPendingWork()) {
                        quietTicks[0] = 0;
                        throw helper.assertionException("等待空区块扫描完成");
                    }
                    if (++quietTicks[0] < 2) {
                        throw helper.assertionException("等待空区块扫描稳定");
                    }
                    chest[0] = placeChestEntity(helper, blocks, 1, 1, 1, new ItemStack(Items.STONE, 7));
                    phase[0] = 1;
                    throw helper.assertionException("等待创建观察者发现新箱子");
                }
                if (phase[0] == 1) {
                    AreaInventoryView view = view(runtime, projectId);
                    if (!(view.known() && count(view, "minecraft:stone") == 7)) {
                        throw helper.assertionException("新放置的箱子未被创建观察者发现");
                    }
                    chest[0].setItem(0, new ItemStack(Items.STONE, 9));
                    phase[0] = 2;
                    throw helper.assertionException("等待 setChanged 脏容器更新");
                }
                AreaInventoryView view = view(runtime, projectId);
                if (!(view.known() && count(view, "minecraft:stone") == 9)) {
                    throw helper.assertionException("setChanged 未触发脏容器更新");
                }
                cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 800)
    public void replacementAndRealDestructionUpdateExactly(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-replace");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] phase = {0};
        try {
            BlockPos chest = placeChest(helper, blocks, 1, 1, 1, new ItemStack(Items.STONE, 12));
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(chest)));
            runtime.areas().refreshConfiguration();
            helper.succeedWhen(() -> {
                AreaInventoryView view = view(runtime, projectId);
                if (phase[0] == 0) {
                    if (!(view.known() && count(view, "minecraft:stone") == 12)) {
                        throw helper.assertionException("初始箱子未扫描到");
                    }
                    helper.setBlock(new BlockPos(1, 1, 1), Blocks.BARREL);
                    BarrelBlockEntity barrel = helper.getBlockEntity(new BlockPos(1, 1, 1), BarrelBlockEntity.class);
                    if (barrel == null) {
                        throw helper.assertionException("替换后的木桶方块实体未创建");
                    }
                    barrel.setItem(0, new ItemStack(Items.DIRT, 4));
                    phase[0] = 1;
                    throw helper.assertionException("等待箱子替换为木桶");
                }
                if (phase[0] == 1) {
                    if (!(view.known()
                            && count(view, "minecraft:stone") == 0
                            && count(view, "minecraft:dirt") == 4)) {
                        throw helper.assertionException("箱子替换为木桶后旧库存未删除或新库存未写入");
                    }
                    helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
                    phase[0] = 2;
                    throw helper.assertionException("等待真实拆除");
                }
                if (!(view.known() && view.staging().isEmpty())) {
                    throw helper.assertionException("已加载区块内真实拆除后来源未精确删除");
                }
                cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 900)
    public void unloadKeepsLastKnownAndReloadReplaces(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-unload");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] phase = {0};
        try {
            ChestBlockEntity chest = placeChestEntity(helper, blocks, 1, 1, 1,
                    new ItemStack(Items.STONE, 5));
            BlockPos pos = chest.getBlockPos();
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(pos)));
            runtime.areas().refreshConfiguration();
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            helper.succeedWhen(() -> {
                AreaInventoryView view = view(runtime, projectId);
                if (phase[0] == 0) {
                    if (!(view.known() && count(view, "minecraft:stone") == 5)) {
                        throw helper.assertionException("卸载场景初始扫描未完成");
                    }
                    FoundationAreaEvents.chunkUnloaded(level, chunk);
                    phase[0] = 1;
                    throw helper.assertionException("等待卸载后的最近观测");
                }
                if (phase[0] == 1) {
                    if (!(!view.known() && count(view, "minecraft:stone") == 5)) {
                        throw helper.assertionException("区块卸载后必须保留最近观测并标记未知/过期");
                    }
                    chest.setItem(0, new ItemStack(Items.DIRT, 6));
                    FoundationAreaEvents.chunkLoaded(level, chunk);
                    phase[0] = 2;
                    throw helper.assertionException("等待重新加载后的替换");
                }
                if (phase[0] == 2) {
                    if (!(view.known()
                            && count(view, "minecraft:stone") == 0
                            && count(view, "minecraft:dirt") == 6)) {
                        throw helper.assertionException("区块重新加载后未用新内容替换最近观测");
                    }
                    MinecraftAreaSnapshotSource passive = new MinecraftAreaSnapshotSource(level.getServer());
                    ChunkKey far = new ChunkKey(dimension(helper), 2_000_000 >> 4, 2_000_000 >> 4);
                    if (level.getChunkSource().getChunkNow(far.x(), far.z()) != null
                            || passive.loadedChunks().contains(far)
                            || passive.isChunkLoaded(far)) {
                        throw helper.assertionException("被动 source 不得加载或报告远处未加载区块");
                    }
                    if (level.getChunkSource().getChunkNow(far.x(), far.z()) != null) {
                        throw helper.assertionException("被动 source 调用后远处区块仍不得被加载");
                    }
                    cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
                }
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "syncmaterial-foundation-test:isolated_chunk", maxTicks = 900)
    public void unreferencedWarehouseUnloadRetainsStaleCoverageUntilReload(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-unreferenced-unload");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] phase = {0};
        try {
            ChestBlockEntity chest = placeChestEntity(helper, blocks, 24, 1, 24,
                    new ItemStack(Items.STONE, 5));
            BlockPos pos = chest.getBlockPos();
            ServerLevel level = helper.getLevel();
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            int warehouseId = runtime.store().addArea(null, AreaKind.WAREHOUSE,
                    new AreaSpec("unreferenced-warehouse", dimension(helper), boundsAround(List.of(pos))))
                    .join().id();
            warehouses.add(warehouseId);
            runtime.store().addWarehouseReference(projectId, warehouseId).join();
            runtime.areas().refreshConfiguration();
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> {
                FoundationAreaEvents.chunkLoaded(level, chunk);
                cleanup(runtime, projectId, warehouses, blocks, level);
            });
            helper.succeedWhen(() -> {
                if (phase[0] == 0) {
                    AreaInventoryView initial = view(runtime, projectId);
                    if (!(initial.known() && initial.warehouse().getOrDefault("minecraft:stone", 0L) == 5)) {
                        throw helper.assertionException("仓库卸载场景初始扫描未完成");
                    }
                    runtime.store().removeWarehouseReference(projectId, warehouseId).join();
                    runtime.areas().refreshConfiguration();
                    phase[0] = 1;
                    throw helper.assertionException("等待仓库引用移除生效");
                }
                if (phase[0] == 1) {
                    if (runtime.areas().configuration().orElseThrow()
                            .referencedWarehouseIds(projectId).contains(warehouseId)
                            || runtime.areas().hasPendingWork()) {
                        throw helper.assertionException("仓库必须先停止被引用并完成此前扫描");
                    }
                    FoundationAreaEvents.chunkUnloaded(level, chunk);
                    phase[0] = 2;
                    throw helper.assertionException("等待无引用仓库的卸载覆盖标为过期");
                }
                if (phase[0] == 2) {
                    var coverage = runtime.store().areaScanStore().coverage(warehouseId).join();
                    ChunkKey unloaded = FoundationAreaEvents.chunkKey(level, chunk);
                    var unloadedCoverage = coverage.stream().filter(row -> row.chunk().equals(unloaded)).toList();
                    if (unloadedCoverage.size() != 1 || unloadedCoverage.get(0).state()
                            != net.syncmaterial.syncmaterial.server.storage.AreaScanStore.CoverageState.STALE) {
                        throw helper.assertionException("无项目引用不能跳过仓库卸载的过期标记");
                    }
                    if (coverage.stream().filter(row -> !row.chunk().equals(unloaded)).anyMatch(row -> row.state()
                            != net.syncmaterial.syncmaterial.server.storage.AreaScanStore.CoverageState.COMPLETE)) {
                        throw helper.assertionException("卸载一个区块不能使仍加载的邻区块覆盖失效");
                    }
                    runtime.store().addWarehouseReference(projectId, warehouseId).join();
                    runtime.areas().refreshConfiguration();
                    phase[0] = 3;
                    throw helper.assertionException("等待重新引用已卸载的仓库");
                }
                if (phase[0] == 3) {
                    if (!runtime.areas().configuration().orElseThrow()
                            .referencedWarehouseIds(projectId).contains(warehouseId)) {
                        throw helper.assertionException("重新添加仓库引用未生效");
                    }
                    AreaInventoryView stale = view(runtime, projectId);
                    if (stale.known() || !stale.hasWarnings()
                            || stale.warehouse().getOrDefault("minecraft:stone", 0L) != 5) {
                        throw helper.assertionException("重新引用不能把卸载前的数量当作新鲜库存");
                    }
                    chest.setItem(0, new ItemStack(Items.STONE, 7));
                    FoundationAreaEvents.chunkLoaded(level, chunk);
                    phase[0] = 4;
                    throw helper.assertionException("等待重新加载后的仓库扫描");
                }
                AreaInventoryView fresh = view(runtime, projectId);
                if (!(fresh.known() && fresh.warehouse().getOrDefault("minecraft:stone", 0L) == 7)) {
                    throw helper.assertionException("重新加载必须替换最近观测并恢复新鲜状态");
                }
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 500)
    public void unloadedAreaStaysUnknownWithoutLoadingChunks(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-far");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] waits = {0};
        try {
            addStaging(runtime, projectId, dimension(helper),
                    new AreaBounds(2_000_000, 64, 2_000_000, 2_000_001, 65, 2_000_001));
            runtime.areas().refreshConfiguration();
            helper.succeedWhen(() -> {
                if (!hasArea(runtime, projectId)) {
                    throw helper.assertionException("远处区域配置未生效");
                }
                if (++waits[0] < 3) {
                    throw helper.assertionException("等待覆盖查询稳定");
                }
                if (view(runtime, projectId).known()) {
                    throw helper.assertionException("未加载区块覆盖的区域必须保持未知");
                }
                if (helper.getLevel().getChunkSource()
                        .getChunkNow(2_000_000 >> 4, 2_000_000 >> 4) != null) {
                    throw helper.assertionException("扫描不得主动加载远处区块");
                }
                cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 600)
    public void lootChestStaysOpaqueAndUnopened(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-loot");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        try {
            ChestBlockEntity chest = placeChestEntity(helper, blocks, 1, 1, 1);
            ResourceKey<LootTable> loot = ResourceKey.create(Registries.LOOT_TABLE,
                    McCompat.parseId("minecraft:chests/simple_dungeon"));
            chest.setLootTable(loot, 123456789L);
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(chest.getBlockPos())));
            runtime.areas().refreshConfiguration();
            await(helper, () -> {
                AreaInventoryView view = view(runtime, projectId);
                if (view.known() || !view.hasWarnings()) {
                    throw helper.assertionException("战利品容器未报告未知/opaque");
                }
                if (!loot.equals(chest.getLootTable()) || chest.getLootTableSeed() != 123456789L) {
                    throw helper.assertionException("扫描不得解包或改变 loot key/seed");
                }
                if (!view.staging().isEmpty()) {
                    throw helper.assertionException("opaque 容器不得产生伪造的库存数字");
                }
                return true;
            }, "战利品容器未报告未知/opaque",
                    () -> cleanup(runtime, projectId, warehouses, blocks, helper.getLevel()));
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 600)
    public void pendingBlockEntityIsOpaqueAndNotInstantiated(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-pending");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        try {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (!(chunk instanceof ChunkAccessMixin access)) {
                throw helper.assertionException("ChunkAccess pending accessor 未应用");
            }
            access.syncmaterial$pendingBlockEntities().put(pos, new net.minecraft.nbt.CompoundTag());
            addStaging(runtime, projectId, dimension(helper), boundsAround(List.of(pos)));
            runtime.areas().refreshConfiguration();
            await(helper, () -> {
                AreaInventoryView view = view(runtime, projectId);
                if (view.known() || !view.hasWarnings()) {
                    throw helper.assertionException("待加载方块实体未报告为未知/opaque");
                }
                if (chunk.getBlockEntities().get(pos) != null) {
                    throw helper.assertionException("扫描不得实例化待加载的方块实体");
                }
                return true;
            }, "待加载方块实体未报告为未知/opaque", () -> {
                cleanup(runtime, projectId, warehouses, blocks, level);
                access.syncmaterial$pendingBlockEntities().remove(pos);
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 800)
    public void renameKeepsCoverageButGeometryChangeInvalidates(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-geometry");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] phase = {0};
        try {
            BlockPos chest = placeChest(helper, blocks, 1, 1, 1, new ItemStack(Items.STONE, 9));
            AreaBounds firstBounds = boundsAround(List.of(chest));
            var area = runtime.store().addArea(projectId, AreaKind.STAGING_AREA,
                    new AreaSpec("geometry", dimension(helper), firstBounds)).join();
            runtime.areas().refreshConfiguration();
            helper.succeedWhen(() -> {
                AreaInventoryView view = view(runtime, projectId);
                if (phase[0] == 0) {
                    if (!(view.known() && count(view, "minecraft:stone") == 9)) {
                        throw helper.assertionException("几何变更前初始扫描未完成");
                    }
                    runtime.store().renameArea(projectId, area.id(), "renamed").join();
                    runtime.areas().refreshConfiguration();
                    phase[0] = 1;
                    throw helper.assertionException("等待重命名后的同一覆盖");
                }
                if (phase[0] == 1) {
                    if (!(view.known() && count(view, "minecraft:stone") == 9)) {
                        throw helper.assertionException("重命名不得使旧覆盖失效");
                    }
                    BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
                    AreaBounds moved = new AreaBounds(base.getX() + 5, base.getY() - 1, base.getZ() - 1,
                            base.getX() + 6, base.getY() + 1, base.getZ() + 1);
                    runtime.store().updateArea(projectId, AreaKind.STAGING_AREA, area.id(),
                            new AreaSpec("geometry", dimension(helper), moved)).join();
                    runtime.areas().refreshConfiguration();
                    phase[0] = 2;
                    throw helper.assertionException("等待几何变更后的新扫描");
                }
                if (!(view.known() && view.staging().isEmpty())) {
                    throw helper.assertionException("几何变更后旧覆盖仍被当成可用的完整扫描");
                }
                cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 700)
    public void removalQueueIsBoundedAndInterestFiltered(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = createProject(runtime, "native-queue");
        List<BlockPos> blocks = new ArrayList<>();
        List<Integer> warehouses = new ArrayList<>();
        int[] phase = {0};
        try {
            BlockPos chest = placeChest(helper, blocks, 1, 1, 1, new ItemStack(Items.STONE, 1));
            BlockPos base = chest;
            AreaBounds bounds = new AreaBounds(base.getX() - 1, Math.min(0, base.getY() - 1), base.getZ() - 1,
                    base.getX() + 17, Math.max(70, base.getY() + 1), base.getZ() + 17);
            addStaging(runtime, projectId, dimension(helper), bounds);
            runtime.areas().refreshConfiguration();
            helper.succeedWhen(() -> {
                if (phase[0] == 0) {
                    if (!hasArea(runtime, projectId)) {
                        throw helper.assertionException("队列测试区域配置未生效");
                    }
                    phase[0] = 1;
                    throw helper.assertionException("等待区域配置稳定");
                }
                var server = helper.getLevel().getServer();
                if (phase[0] == 1) {
                    int before = FoundationAreaEvents.pendingCount(server);
                    FoundationAreaEvents.containerRemovalSuspected(helper.getLevel(),
                            new BlockPos(base.getX() + 10_000, base.getY(), base.getZ()));
                    if (FoundationAreaEvents.pendingCount(server) != before) {
                        throw helper.assertionException("区域外的拆除事件不得进入待验证队列");
                    }
                    for (int i = 0; i < FoundationAreaEvents.MAX_PENDING_REMOVALS + 1; i++) {
                        int x = base.getX() + (i % 16);
                        int z = base.getZ() + ((i / 16) % 16);
                        int y = i / 256;
                        FoundationAreaEvents.containerRemovalSuspected(helper.getLevel(), new BlockPos(x, y, z));
                    }
                    phase[0] = 2;
                    throw helper.assertionException("等待拆除队列填满");
                }
                int queued = FoundationAreaEvents.pendingCount(server);
                if (queued > FoundationAreaEvents.MAX_PENDING_REMOVALS) {
                    throw helper.assertionException("待验证拆除队列必须硬上限");
                }
                FoundationAreaEvents.drain(server);
                FoundationAreaEvents.drain(server);
                if (FoundationAreaEvents.pendingCount(server)
                        < queued - FoundationAreaEvents.MAX_DRAIN_PER_TICK) {
                    throw helper.assertionException("单次 tick 的拆除验证数量超过上限");
                }
                cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            });
        } catch (RuntimeException error) {
            cleanup(runtime, projectId, warehouses, blocks, helper.getLevel());
            throw error;
        }
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private static ServerRuntime runtime(GameTestHelper helper) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        if (runtime == null) {
            throw helper.assertionException("Foundation runtime 未初始化");
        }
        return runtime;
    }

    private static String dimension(GameTestHelper helper) {
        return McCompat.dimensionId(helper.getLevel());
    }

    private static String createProject(ServerRuntime runtime, String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        PlayerIdentity owner = new PlayerIdentity(UUID.randomUUID(), "Owner");
        SchematicMetadata metadata = new SchematicMetadata(id, prefix, Path.of(prefix + ".litematic"),
                "hash-" + id, owner);
        runtime.store().ingest(metadata, List.of(
                new MaterialCount("minecraft:stone", 64),
                new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
        return id;
    }

    private static int addStaging(ServerRuntime runtime, String projectId, String dimension, AreaBounds bounds) {
        return runtime.store().addArea(projectId, AreaKind.STAGING_AREA,
                new AreaSpec("staging", dimension, bounds)).join().id();
    }

    private static boolean hasArea(ServerRuntime runtime, String projectId) {
        return runtime.areas().configuration()
                .map(catalog -> catalog.areas().stream()
                        .anyMatch(area -> projectId.equals(area.projectId())))
                .orElse(false);
    }

    private static AreaInventoryView view(ServerRuntime runtime, String projectId) {
        return runtime.store().snapshot(projectId).join()
                .orElseThrow(() -> new IllegalStateException("project disappeared: " + projectId))
                .inventory();
    }

    private static long count(AreaInventoryView view, String itemId) {
        return view.staging().getOrDefault(itemId, 0L);
    }

    private static AreaBounds boundsAround(Collection<BlockPos> positions) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AreaBounds(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static ChestBlockEntity placeChestEntity(GameTestHelper helper, List<BlockPos> blocks,
                                                     int x, int y, int z, ItemStack... stacks) {
        BlockPos relative = new BlockPos(x, y, z);
        helper.setBlock(relative, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(relative, ChestBlockEntity.class);
        if (chest == null) {
            throw helper.assertionException("箱子方块实体未创建");
        }
        for (int slot = 0; slot < stacks.length; slot++) {
            chest.setItem(slot, stacks[slot]);
        }
        BlockPos absolute = helper.absolutePos(relative);
        if (!blocks.contains(absolute)) {
            blocks.add(absolute);
        }
        return chest;
    }

    private static BlockPos placeChest(GameTestHelper helper, List<BlockPos> blocks,
                                       int x, int y, int z, ItemStack... stacks) {
        return placeChestEntity(helper, blocks, x, y, z, stacks).getBlockPos();
    }

    private static BlockPos placeBarrel(GameTestHelper helper, List<BlockPos> blocks,
                                        int x, int y, int z, ItemStack... stacks) {
        BlockPos relative = new BlockPos(x, y, z);
        helper.setBlock(relative, Blocks.BARREL);
        BarrelBlockEntity barrel = helper.getBlockEntity(relative, BarrelBlockEntity.class);
        if (barrel == null) {
            throw helper.assertionException("木桶方块实体未创建");
        }
        for (int slot = 0; slot < stacks.length; slot++) {
            barrel.setItem(slot, stacks[slot]);
        }
        BlockPos absolute = helper.absolutePos(relative);
        blocks.add(absolute);
        return absolute;
    }

    private static ItemStack shulkerWith(net.minecraft.world.item.Item item, int count) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contents.add(new ItemStack(item, 1));
        }
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return shulker;
    }

    /**
     * Waits through real server ticks. The condition is retried by {@code succeedWhen}; a
     * throwing condition is "not ready yet", not an immediate test failure.
     */
    private static void await(GameTestHelper helper, BooleanSupplier condition, String message,
                              Runnable success) {
        helper.succeedWhen(() -> {
            if (!condition.getAsBoolean()) {
                throw helper.assertionException(Component.literal(message));
            }
            success.run();
        });
    }

    private static void cleanup(ServerRuntime runtime, String projectId, List<Integer> warehouses,
                                List<BlockPos> blocks, ServerLevel level) {
        try {
            if (projectId != null) {
                runtime.store().removeProject(projectId).join();
            }
        } catch (RuntimeException ignored) {
            // Best-effort fixture cleanup; the unique project id prevents cross-test reads.
        }
        for (Integer warehouseId : warehouses) {
            try {
                runtime.store().removeArea(null, AreaKind.WAREHOUSE, warehouseId).join();
            } catch (RuntimeException ignored) {
                // The runtime may already be closing when a delayed test finishes.
            }
        }
        for (BlockPos pos : blocks) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        try {
            runtime.areas().refreshConfiguration();
        } catch (RuntimeException ignored) {
            // Cleanup only.
        }
    }
}
