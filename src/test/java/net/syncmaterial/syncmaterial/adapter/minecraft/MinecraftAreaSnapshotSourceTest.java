package net.syncmaterial.syncmaterial.adapter.minecraft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.resources.ResourceKey;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.server.area.ChunkScanCursor;
import net.syncmaterial.syncmaterial.server.area.ChunkScanFilter;
import net.syncmaterial.syncmaterial.server.area.ChunkScanSlice;
import net.syncmaterial.syncmaterial.server.area.ScanBudget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure bound checks for the passive snapshot source. Real LevelChunk access is covered by
 * FoundationServerGameTest; these tests pin the budget/overflow/opaque rules themselves.
 */
class MinecraftAreaSnapshotSourceTest {
    private static final String WORLD = "minecraft:overworld";
    private static final ChunkKey CHUNK = new ChunkKey(WORLD, 0, 0);
    private static final ChunkScanFilter FILTER =
            new ChunkScanFilter(WORLD, List.of(new AreaBounds(0, 0, 0, 128, 128, 128)));

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @Test
    void buildCursorRejectsOversizedChunkWithoutInventingMarkers() {
        Map<BlockPos, BlockEntity> existing = new HashMap<>();
        for (int i = 0; i < MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS + 1; i++) {
            existing.put(new BlockPos(i, 64, 0), chest(i));
        }
        assertThrows(MinecraftAreaSnapshotSource.IncompleteChunkException.class,
                () -> MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, Set.of()));
    }

    @Test
    void buildCursorCountsPendingPositionsAgainstTheSameHardCap() {
        Map<BlockPos, BlockEntity> existing = new HashMap<>();
        for (int i = 0; i < MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS; i++) {
            existing.put(new BlockPos(i, 64, 0), chest(i));
        }
        Set<BlockPos> pending = Set.of(new BlockPos(-1, 64, -1));
        assertThrows(MinecraftAreaSnapshotSource.IncompleteChunkException.class,
                () -> MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, pending));
    }

    @Test
    void overflowRecoversAfterSourcesShrinkWithoutFakeUnload() {
        ChunkScanFilter wide = new ChunkScanFilter(WORLD,
                List.of(new AreaBounds(0, 0, 0, 2048, 128, 1)));
        Map<BlockPos, BlockEntity> existing = new HashMap<>();
        for (int i = 0; i < MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS + 1; i++) {
            existing.put(new BlockPos(i, 64, 0), chest(i));
        }
        assertThrows(MinecraftAreaSnapshotSource.IncompleteChunkException.class,
                () -> MinecraftAreaSnapshotSource.buildCursor(CHUNK, wide, existing, Set.of()));

        existing.remove(new BlockPos(0, 64, 0));
        ChunkScanCursor cursor = MinecraftAreaSnapshotSource.buildCursor(CHUNK, wide, existing, Set.of());
        assertEquals(MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS, cursor.containers().size());

        ChunkScanSlice slice = MinecraftAreaSnapshotSource.readExisting(
                CHUNK, cursor, new ScanBudget(16, 256), existing, Set.of());
        int observed = slice.observations().size();
        int guard = 0;
        ChunkScanCursor next = slice.next();
        while (!slice.complete() && guard++ < MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS) {
            slice = MinecraftAreaSnapshotSource.readExisting(
                    CHUNK, next, new ScanBudget(16, 256), existing, Set.of());
            observed += slice.observations().size();
            next = slice.next();
        }
        assertTrue(slice.complete(), "source must become fresh again after the chunk shrinks");
        assertEquals(MinecraftAreaSnapshotSource.MAX_CURSOR_CONTAINERS, observed);
    }

    @Test
    void buildCursorSkipsRemovedAndNonContainerEntities() {
        Map<BlockPos, BlockEntity> existing = new LinkedHashMap<>();
        ChestBlockEntity live = chest(1);
        existing.put(live.getBlockPos(), live);
        ChestBlockEntity removed = chest(2);
        removed.setRemoved();
        existing.put(removed.getBlockPos(), removed);
        Set<BlockPos> pending = Set.of(new BlockPos(3, 64, 0));

        ChunkScanCursor cursor = MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, pending);
        assertEquals(List.of(new ContainerKey(WORLD, 1, 64, 0)), cursor.containers());
        assertEquals(List.of(new ContainerKey(WORLD, 3, 64, 0)), cursor.pending());
    }

    @Test
    void buildCursorUsesEntrySetAndFiltersOutsideBounds() {
        Map<BlockPos, BlockEntity> existing = new LinkedHashMap<>();
        existing.put(new BlockPos(1, 64, 0), chest(1));
        existing.put(new BlockPos(999, 64, 0), chest(999));
        ChunkScanCursor cursor = MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, Set.of());
        assertEquals(List.of(new ContainerKey(WORLD, 1, 64, 0)), cursor.containers());
    }

    @Test
    void fullScanCapsSlotsAndResumesAtTheSameOuterSlot() {
        ChestBlockEntity chest = chest(1);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STONE, 3));
        }
        Map<BlockPos, BlockEntity> existing = Map.of(chest.getBlockPos(), chest);
        ChunkScanCursor cursor = MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, Set.of());

        ChunkScanSlice first = MinecraftAreaSnapshotSource.readExisting(
                CHUNK, cursor, new ScanBudget(16, 10), existing, Set.of());
        assertFalse(first.complete());
        assertNotNull(first.next());
        assertTrue(first.slotsUsed() <= 10, "slice must not overshoot its slot budget");
        int spent = first.slotsUsed();
        int guard = 0;
        ChunkScanCursor next = first.next();
        while (!first.complete() && guard++ < 100) {
            first = MinecraftAreaSnapshotSource.readExisting(
                    CHUNK, next, new ScanBudget(16, 10), existing, Set.of());
            assertTrue(first.slotsUsed() <= 10, "every resumed slice stays within budget");
            spent += first.slotsUsed();
            next = first.next();
        }
        assertTrue(first.complete(), "cursor must eventually finish");
        assertEquals(chest.getContainerSize(), spent);
        assertEquals(1, first.observations().size());
        assertEquals(chest.getContainerSize() * 3L,
                first.observations().get(0).counts().get("minecraft:stone"));
    }

    @Test
    void denseShulkerContentsAreChargedToTheSliceBudget() {
        ChestBlockEntity chest = chest(1);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, shulkerWith(Items.STONE, 27));
        }
        Map<BlockPos, BlockEntity> existing = Map.of(chest.getBlockPos(), chest);
        ChunkScanCursor cursor = MinecraftAreaSnapshotSource.buildCursor(CHUNK, FILTER, existing, Set.of());

        ChunkScanSlice slice = MinecraftAreaSnapshotSource.readExisting(
                CHUNK, cursor, new ScanBudget(16, 256), existing, Set.of());
        int guard = 0;
        ChunkScanCursor next = slice.next();
        while (!slice.complete() && guard++ < 100) {
            assertTrue(slice.slotsUsed() <= 256, "shulker contents must not overshoot the budget");
            slice = MinecraftAreaSnapshotSource.readExisting(
                    CHUNK, next, new ScanBudget(16, 256), existing, Set.of());
            next = slice.next();
        }
        assertTrue(slice.complete());
        ContainerObservation observation = slice.observations().get(0);
        assertEquals(27L, observation.counts().get("minecraft:shulker_box"));
        assertEquals(27L * 27L, observation.counts().get("minecraft:stone"));
    }

    @Test
    void stackReadDefersWithoutPartialCountsWhenBudgetRunsOut() {
        ItemStack shulker = shulkerWith(Items.STONE, 27);
        MinecraftAreaSnapshotSource.StackRead read = MinecraftAreaSnapshotSource.readStack(shulker, 5);
        assertFalse(read.complete());
        assertFalse(read.oversized());
        assertTrue(read.slotsUsed() <= 5);
        assertTrue(read.counts().isEmpty());
    }

    @Test
    void oversizedStackBecomesOpaqueInsteadOfTruncatedCounts() {
        ItemStack shulker = shulkerWith(Items.STONE, MinecraftAreaSnapshotSource.MAX_STACK_CONTENTS + 1);
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, shulker);
        MinecraftAreaSnapshotSource.ContainerRead read =
                MinecraftAreaSnapshotSource.readContainer(Map.of(), container, 0, 4096);
        assertTrue(read.oversized());
        assertTrue(read.slotsUsed() > 0);
    }

    @Test
    void oversizedContainerBecomesOpaqueWithoutReadingSlots() {
        SimpleContainer container = new SimpleContainer(MinecraftAreaSnapshotSource.MAX_SLOTS_PER_CONTAINER + 1);
        MinecraftAreaSnapshotSource.ContainerRead read =
                MinecraftAreaSnapshotSource.readContainer(Map.of(), container, 0, 4096);
        assertTrue(read.oversized());
        assertFalse(read.complete());
    }

    @Test
    void dirtyReadReportsActualUsageWhenItMustFallBackToFullScan() {
        ChestBlockEntity first = chest(1);
        ChestBlockEntity second = chest(2);
        for (int slot = 0; slot < first.getContainerSize(); slot++) {
            first.setItem(slot, new ItemStack(Items.STONE, 1));
            second.setItem(slot, new ItemStack(Items.DIRT, 1));
        }
        Map<BlockPos, BlockEntity> existing = Map.of(
                first.getBlockPos(), first, second.getBlockPos(), second);
        Set<ContainerKey> keys = new LinkedHashSet<>(List.of(
                new ContainerKey(WORLD, 1, 64, 0), new ContainerKey(WORLD, 2, 64, 0)));

        var result = MinecraftAreaSnapshotSource.readSelected(
                CHUNK, FILTER, keys, new ScanBudget(16, 30), existing, Set.of());
        assertTrue(result.loaded());
        assertTrue(result.truncated());
        assertTrue(result.slotsUsed() > 0, "spent work must not be reported as 0");
        assertTrue(result.slotsUsed() <= 30);
        assertTrue(result.observations().isEmpty(),
                "the caller falls back to a full scan; partial selected observations are not applied");
    }

    @Test
    void pendingPositionIsOpaqueAndNeverReadAsAnEmptyContainer() {
        Set<ContainerKey> keys = Set.of(new ContainerKey(WORLD, 7, 64, 0));
        var result = MinecraftAreaSnapshotSource.readSelected(
                CHUNK, FILTER, keys, new ScanBudget(16, 256), Map.of(),
                Set.of(new BlockPos(7, 64, 0)));
        assertEquals(1, result.observations().size());
        ContainerObservation observation = result.observations().get(0);
        assertTrue(observation.counts().isEmpty());
        assertFalse(observation.warning().isEmpty());
        assertEquals(1, result.containersUsed());
    }

    @Test
    void lootContainerIsOpaqueAndKeepsLootKeyAndSeed() {
        ChestBlockEntity chest = chest(1);
        ResourceKey<LootTable> loot = ResourceKey.create(Registries.LOOT_TABLE,
                McCompat.parseId("minecraft:chests/simple_dungeon"));
        chest.setLootTable(loot, 987654321L);
        Map<BlockPos, BlockEntity> existing = Map.of(chest.getBlockPos(), chest);
        Set<ContainerKey> keys = Set.of(new ContainerKey(WORLD, 1, 64, 0));

        var result = MinecraftAreaSnapshotSource.readSelected(
                CHUNK, FILTER, keys, new ScanBudget(16, 256), existing, Set.of());
        assertEquals(1, result.observations().size());
        assertTrue(result.observations().get(0).counts().isEmpty());
        assertEquals(loot, chest.getLootTable());
        assertEquals(987654321L, chest.getLootTableSeed());
    }

    @Test
    void emptyStackCostsOneSlotEvenWhenItCarriesNoItems() {
        SimpleContainer container = new SimpleContainer(3);
        MinecraftAreaSnapshotSource.ContainerRead read =
                MinecraftAreaSnapshotSource.readContainer(Map.of(), container, 0, 2);
        assertFalse(read.complete());
        assertEquals(2, read.slotsUsed());
        assertEquals(2, read.nextSlot());
    }

    @Test
    void partialCountsResumeWithoutDoubleCounting() {
        SimpleContainer container = new SimpleContainer(4);
        for (int slot = 0; slot < 4; slot++) {
            container.setItem(slot, new ItemStack(Items.STONE, 2));
        }
        Map<String, Long> partial = new LinkedHashMap<>();
        partial.put("minecraft:stone", 4L);
        MinecraftAreaSnapshotSource.ContainerRead read =
                MinecraftAreaSnapshotSource.readContainer(partial, container, 2, 2);
        assertTrue(read.complete());
        assertEquals(8L, read.counts().get("minecraft:stone"));
        assertEquals(2, read.slotsUsed());
    }

    private static ChestBlockEntity chest(int x) {
        return new ChestBlockEntity(new BlockPos(x, 64, 0), Blocks.CHEST.defaultBlockState());
    }

    private static ItemStack shulkerWith(net.minecraft.world.item.Item item, int count) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stacks.add(new ItemStack(item, 1));
        }
        shulker.set(net.minecraft.core.component.DataComponents.CONTAINER,
                ItemContainerContents.fromItems(stacks));
        return shulker;
    }
}
