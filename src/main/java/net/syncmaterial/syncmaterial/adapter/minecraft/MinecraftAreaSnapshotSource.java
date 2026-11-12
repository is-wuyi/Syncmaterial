package net.syncmaterial.syncmaterial.adapter.minecraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.impl.event.lifecycle.LoadedChunksCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.mixin.ChunkAccessMixin;
import net.syncmaterial.syncmaterial.server.area.AreaSnapshotSource;
import net.syncmaterial.syncmaterial.server.area.ChunkScanCursor;
import net.syncmaterial.syncmaterial.server.area.ChunkScanFilter;
import net.syncmaterial.syncmaterial.server.area.ChunkScanSlice;
import net.syncmaterial.syncmaterial.server.area.ScanBudget;

/**
 * Passive Minecraft snapshot source. It only asks {@link net.minecraft.server.level.ServerChunkCache#getChunkNow},
 * iterates the existing block-entity map, and reads the chunk's pending-NBT position map. It
 * never calls {@code world.getBlockEntity}/{@code chunk.getBlockEntity}, never promotes
 * pending NBT, never starts loot generation and never mutates the world.
 *
 * <p>No chunk, block-entity map or block-entity reference is stored on a cursor: between
 * ticks only pure {@link ContainerKey} positions and copied item counts survive.</p>
 *
 * <p>Loaded chunks are discovered from Fabric's loaded-chunk cache so a source attached
 * after the initial world load still sees exactly the chunks that are currently loaded.
 * After attachment the scanner keeps its own set from chunk load/unload events.</p>
 *
 * <p>Every slice is honestly bounded: a stack read never walks more shulker contents than
 * the remaining slot budget (or the hard per-stack cap), discovery examines at most
 * {@link #MAX_CURSOR_CONTAINERS} block-entity positions and a chunk that cannot be
 * enumerated returns {@link ChunkScanSlice#incomplete} instead of pretending a partial
 * subset is a complete observation. A failed chunk is not retried every tick; the next
 * dirty/configuration/load event or the backoff expiry makes it eligible again.</p>
 */
public final class MinecraftAreaSnapshotSource implements AreaSnapshotSource {
    static final int MAX_CURSOR_CONTAINERS = 2048;
    static final int MAX_SLOTS_PER_CONTAINER = 1024;
    /** Hard cap for one stack's shulker contents; larger components are opaque. */
    static final int MAX_STACK_CONTENTS = 128;
    private static final String PENDING_WARNING = "方块实体尚未加载，内容未知";
    private static final String OPAQUE_WARNING = "容器战利品未生成，内容未知";
    private static final String OVERSIZED_WARNING = "容器槽位过多，未完整统计";
    private static final String READ_FAILED_WARNING = "容器读取失败，内容未知";

    private final MinecraftServer server;

    public MinecraftAreaSnapshotSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isAvailable() {
        return server != null;
    }

    /**
     * Every chunk Fabric currently tracks as loaded. Uses Fabric's passive loaded-chunk
     * cache; no chunk is created, loaded or promoted by this call.
     */
    @Override
    public Set<ChunkKey> loadedChunks() {
        if (server == null) {
            return Set.of();
        }
        Set<ChunkKey> loaded = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level instanceof LoadedChunksCache cache)) {
                continue;
            }
            String world = McCompat.dimensionId(level);
            for (LevelChunk chunk : cache.fabric_getLoadedChunks()) {
                loaded.add(new ChunkKey(world, McCompat.chunkX(chunk.getPos()), McCompat.chunkZ(chunk.getPos())));
            }
        }
        return loaded;
    }

    @Override
    public boolean isChunkLoaded(ChunkKey chunk) {
        return chunkNow(chunk) != null;
    }

    @Override
    public ChunkScanSlice scanChunk(ChunkKey chunk, ChunkScanFilter filter, ChunkScanCursor cursor,
                                    ScanBudget budget) {
        LevelChunk levelChunk = chunkNow(chunk);
        if (levelChunk == null) {
            return ChunkScanSlice.notLoaded();
        }
        if (!filter.accepts(chunk)) {
            return ChunkScanSlice.complete(List.of(), 0, 0);
        }
        try {
            if (cursor == null) {
                cursor = buildCursor(chunk, filter, levelChunk.getBlockEntities(), pendingPositions(levelChunk));
            }
            return readExisting(chunk, cursor, budget, levelChunk.getBlockEntities(), pendingPositions(levelChunk));
        } catch (IncompleteChunkException error) {
            // The source is still loaded; never report notLoaded here. The scheduler owns
            // the retry backoff and abandons the tokens so the last-known rows stay stale.
            return ChunkScanSlice.incomplete(0, 0);
        }
    }

    @Override
    public ContainerReadResult readContainers(ChunkKey chunk, ChunkScanFilter filter,
                                              Set<ContainerKey> keys, ScanBudget budget) {
        LevelChunk levelChunk = chunkNow(chunk);
        if (levelChunk == null) {
            return ContainerReadResult.notLoaded();
        }
        return readSelected(chunk, filter, keys, budget, levelChunk.getBlockEntities(),
                pendingPositions(levelChunk));
    }

    static ChunkScanCursor buildCursor(ChunkKey chunkKey, ChunkScanFilter filter,
                                       Map<BlockPos, BlockEntity> existing, Set<BlockPos> pending) {
        long total = (long) existing.size() + pending.size();
        if (total > MAX_CURSOR_CONTAINERS) {
            // A chunk with more block-entity positions than the cursor cap cannot be
            // enumerated honestly. Do not invent marker containers: fail the slice and
            // let the scanner keep the last-known rows stale.
            throw new IncompleteChunkException("区块方块实体数量超过单次扫描上限: " + total);
        }
        List<ContainerKey> containers = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : existing.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!filter.accepts(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }
            BlockEntity blockEntity = entry.getValue();
            if (blockEntity instanceof Container && !blockEntity.isRemoved()) {
                containers.add(new ContainerKey(chunkKey.world(), pos.getX(), pos.getY(), pos.getZ()));
            }
        }

        List<ContainerKey> pendingKeys = new ArrayList<>();
        for (BlockPos pos : pending) {
            if (existing.containsKey(pos) || !filter.accepts(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }
            pendingKeys.add(new ContainerKey(chunkKey.world(), pos.getX(), pos.getY(), pos.getZ()));
        }
        return ChunkScanCursor.of(containers, pendingKeys);
    }

    static ChunkScanSlice readExisting(ChunkKey chunkKey, ChunkScanCursor cursor, ScanBudget budget,
                                       Map<BlockPos, BlockEntity> existing, Set<BlockPos> pending) {
        List<ContainerObservation> observations = new ArrayList<>();
        List<ContainerKey> containers = cursor.containers();
        List<ContainerKey> pendingKeys = cursor.pending();
        int containerIndex = cursor.containerIndex();
        int slotIndex = cursor.slotIndex();
        int pendingIndex = cursor.pendingIndex();
        int containersUsed = 0;
        int slotsUsed = 0;
        Map<String, Long> partial = new LinkedHashMap<>(cursor.partialCounts());

        while (containerIndex < containers.size()) {
            if (containersUsed >= budget.maxContainers() || slotsUsed >= budget.maxSlots()) {
                return slice(observations, cursorOf(containers, containerIndex, slotIndex, partial,
                        pendingKeys, pendingIndex), false, containersUsed, slotsUsed);
            }
            ContainerKey key = containers.get(containerIndex);
            BlockEntity blockEntity = existing.get(new BlockPos(key.x(), key.y(), key.z()));
            if (!(blockEntity instanceof Container container) || blockEntity.isRemoved()) {
                containerIndex++;
                slotIndex = 0;
                partial.clear();
                continue;
            }
            if (blockEntity instanceof RandomizableContainer randomizable
                    && randomizable.getLootTable() != null) {
                observations.add(new ContainerObservation(key, Map.of(), OPAQUE_WARNING));
                containersUsed++;
                containerIndex++;
                slotIndex = 0;
                partial.clear();
                continue;
            }
            ContainerRead read = readContainer(partial, container, slotIndex, budget.maxSlots() - slotsUsed);
            slotsUsed += read.slotsUsed();
            if (read.oversized()) {
                observations.add(new ContainerObservation(key, Map.of(), OVERSIZED_WARNING));
                containersUsed++;
                containerIndex++;
                slotIndex = 0;
                partial.clear();
                continue;
            }
            if (read.failed()) {
                observations.add(new ContainerObservation(key, Map.of(), READ_FAILED_WARNING));
                containersUsed++;
                containerIndex++;
                slotIndex = 0;
                partial.clear();
                continue;
            }
            if (!read.complete()) {
                return slice(observations, cursorOf(containers, containerIndex, read.nextSlot(), read.counts(),
                        pendingKeys, pendingIndex), false, containersUsed, slotsUsed);
            }
            observations.add(new ContainerObservation(key, read.counts(), ""));
            containersUsed++;
            containerIndex++;
            slotIndex = 0;
            partial = new LinkedHashMap<>();
        }

        while (pendingIndex < pendingKeys.size()) {
            if (containersUsed >= budget.maxContainers() || slotsUsed >= budget.maxSlots()) {
                return slice(observations, cursorOf(containers, containerIndex, slotIndex, partial,
                        pendingKeys, pendingIndex), false, containersUsed, slotsUsed);
            }
            observations.add(new ContainerObservation(pendingKeys.get(pendingIndex), Map.of(), PENDING_WARNING));
            pendingIndex++;
            containersUsed++;
        }
        return ChunkScanSlice.complete(observations, containersUsed, slotsUsed);
    }

    static ContainerReadResult readSelected(ChunkKey chunkKey, ChunkScanFilter filter,
                                            Set<ContainerKey> keys, ScanBudget budget,
                                            Map<BlockPos, BlockEntity> existing, Set<BlockPos> pending) {
        List<ContainerObservation> observations = new ArrayList<>();
        int containersUsed = 0;
        int slotsUsed = 0;
        for (ContainerKey key : keys) {
            if (!filter.accepts(key.x(), key.y(), key.z())) {
                continue;
            }
            if (containersUsed >= budget.maxContainers() || slotsUsed >= budget.maxSlots()) {
                return truncated(containersUsed, slotsUsed);
            }
            BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
            BlockEntity blockEntity = existing.get(pos);
            if (!(blockEntity instanceof Container container) || blockEntity.isRemoved()) {
                if (pending.contains(pos)) {
                    observations.add(new ContainerObservation(key, Map.of(), PENDING_WARNING));
                    containersUsed++;
                }
                continue;
            }
            if (blockEntity instanceof RandomizableContainer randomizable
                    && randomizable.getLootTable() != null) {
                observations.add(new ContainerObservation(key, Map.of(), OPAQUE_WARNING));
                containersUsed++;
                continue;
            }
            ContainerRead read = readContainer(Map.of(), container, 0, budget.maxSlots() - slotsUsed);
            slotsUsed += read.slotsUsed();
            if (read.oversized()) {
                observations.add(new ContainerObservation(key, Map.of(), OVERSIZED_WARNING));
                containersUsed++;
                continue;
            }
            if (read.failed()) {
                observations.add(new ContainerObservation(key, Map.of(), READ_FAILED_WARNING));
                containersUsed++;
                continue;
            }
            if (!read.complete()) {
                // The caller falls back to a full chunk scan; the actual work already spent
                // is reported so the per-tick budget is not under-counted.
                return truncated(containersUsed, slotsUsed);
            }
            observations.add(new ContainerObservation(key, read.counts(), ""));
            containersUsed++;
        }
        return new ContainerReadResult(observations, true, false, containersUsed, slotsUsed);
    }

    /**
     * Reads one container starting at {@code startSlot}. It never does more work than
     * {@code slotBudget}; an incomplete read returns the counts already gathered for the
     * completed outer slots and the slot where the next tick must resume.
     */
    static ContainerRead readContainer(Map<String, Long> partial, Container container,
                                       int startSlot, int slotBudget) {
        int size = containerSlots(container);
        if (size < 0 || size > MAX_SLOTS_PER_CONTAINER) {
            return ContainerRead.ofOversized();
        }
        Map<String, Long> counts = new LinkedHashMap<>(partial);
        int used = 0;
        int slot = startSlot;
        while (slot < size) {
            if (used >= slotBudget) {
                return ContainerRead.incomplete(counts, slot, used);
            }
            ItemStack stack;
            try {
                stack = container.getItem(slot);
            } catch (RuntimeException error) {
                // The current slot was attempted, so charge it as well as the completed ones.
                return ContainerRead.failed(used + 1);
            }
            StackRead read = readStack(stack, slotBudget - used);
            used += read.slotsUsed();
            if (read.oversized()) {
                return ContainerRead.oversized(Math.max(1, used));
            }
            if (!read.complete()) {
                return ContainerRead.incomplete(counts, slot, used);
            }
            merge(counts, read.counts());
            slot++;
        }
        return ContainerRead.complete(counts, used);
    }

    /**
     * Counts one item stack and its one level of shulker contents. The iterator is walked
     * lazily and every inner slot (empty or not) is charged to the slot budget, so a single
     * stack can never silently overshoot the remaining budget. An empty stack still costs
     * one slot. A stack whose contents exceed the hard cap becomes opaque instead of being
     * reported as a complete (but truncated) count.
     */
    static StackRead readStack(ItemStack stack, int remainingSlots) {
        if (remainingSlots < 1) {
            return StackRead.deferred(0);
        }
        if (stack == null || stack.isEmpty()) {
            return StackRead.complete(Map.of(), 1);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        accumulate(counts, stack);
        int used = 1;
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return StackRead.complete(counts, used);
        }
        ItemContainerContents contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents == null) {
            return StackRead.complete(counts, used);
        }
        Iterator<ItemStack> iterator = allContainerItems(contents).iterator();
        int inner = 0;
        while (iterator.hasNext()) {
            if (inner >= MAX_STACK_CONTENTS) {
                return StackRead.oversized(used);
            }
            if (used >= remainingSlots) {
                return StackRead.deferred(used);
            }
            ItemStack stored = iterator.next();
            inner++;
            used++;
            if (!stored.isEmpty()) {
                accumulate(counts, stored);
            }
        }
        return StackRead.complete(counts, used);
    }

    //? if >=26.2 {
    private static Stream<ItemStack> allContainerItems(ItemContainerContents contents) {
        return contents.allItemsCopyStream();
    }
    //?} else {
    /*private static Stream<ItemStack> allContainerItems(ItemContainerContents contents) {
        return contents.stream();
    }
    *///?}

    private static void merge(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Math::addExact);
        }
    }

    private static void accumulate(Map<String, Long> counts, ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        counts.merge(itemId, (long) stack.getCount(), Math::addExact);
    }

    private static int containerSlots(Container container) {
        try {
            return container.getContainerSize();
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private static ChunkScanSlice slice(List<ContainerObservation> observations, ChunkScanCursor next,
                                        boolean complete, int containersUsed, int slotsUsed) {
        return new ChunkScanSlice(observations, next, complete, true, containersUsed, slotsUsed);
    }

    private static ChunkScanCursor cursorOf(List<ContainerKey> containers, int containerIndex, int slotIndex,
                                            Map<String, Long> partial, List<ContainerKey> pending,
                                            int pendingIndex) {
        return new ChunkScanCursor(containers, containerIndex, slotIndex, partial, pending, pendingIndex);
    }

    private static ContainerReadResult truncated(int containersUsed, int slotsUsed) {
        return ContainerReadResult.truncatedNow(containersUsed, slotsUsed);
    }

    static Set<BlockPos> pendingPositions(LevelChunk chunk) {
        if (chunk instanceof ChunkAccessMixin access) {
            return access.syncmaterial$pendingBlockEntities().keySet();
        }
        return chunk.getBlockEntitiesPos();
    }

    static final class IncompleteChunkException extends RuntimeException {
        IncompleteChunkException(String message) {
            super(message);
        }
    }

    record ContainerRead(boolean complete, boolean oversized, boolean failed,
                         Map<String, Long> counts, int nextSlot, int slotsUsed) {
        static ContainerRead complete(Map<String, Long> counts, int slotsUsed) {
            return new ContainerRead(true, false, false, Map.copyOf(counts), 0, slotsUsed);
        }

        static ContainerRead incomplete(Map<String, Long> counts, int nextSlot, int slotsUsed) {
            return new ContainerRead(false, false, false, Map.copyOf(counts), nextSlot, slotsUsed);
        }

        static ContainerRead ofOversized() {
            return oversized(1);
        }

        static ContainerRead oversized(int slotsUsed) {
            return new ContainerRead(false, true, false, Map.of(), 0, slotsUsed);
        }

        static ContainerRead failed(int slotsUsed) {
            return new ContainerRead(false, false, true, Map.of(), 0, slotsUsed);
        }
    }

    record StackRead(boolean complete, boolean oversized, Map<String, Long> counts, int slotsUsed) {
        static StackRead complete(Map<String, Long> counts, int slotsUsed) {
            return new StackRead(true, false, Map.copyOf(counts), slotsUsed);
        }

        static StackRead deferred(int slotsUsed) {
            return new StackRead(false, false, Map.of(), slotsUsed);
        }

        static StackRead oversized(int slotsUsed) {
            return new StackRead(false, true, Map.of(), slotsUsed);
        }
    }

    private LevelChunk chunkNow(ChunkKey chunk) {
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!McCompat.dimensionId(level).equals(chunk.world())) {
                continue;
            }
            return level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        }
        return null;
    }
}
