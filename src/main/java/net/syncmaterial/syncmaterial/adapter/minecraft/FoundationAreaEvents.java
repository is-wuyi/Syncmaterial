package net.syncmaterial.syncmaterial.adapter.minecraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.ChunkKey;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Server-thread bridge between native block-entity/chunk events and the runtime area
 * scanner. A removal is re-checked one tick later: a block entity that was replaced at the
 * same position, or a chunk that unloaded, must not delete the new source or the last-known
 * rows. Only the existing block-entity map is inspected; nothing is loaded or promoted.
 *
 * <p>Removal checks are coalesced per position and drained with a hard per-tick cap, and
 * only positions covered by a configured area are queued. A queue overflow is not allowed
 * to grow without bound: the position was already invalidated immediately, and the
 * periodic dirty-chunk reconciliation remains able to resolve it.</p>
 */
public final class FoundationAreaEvents {
    public static final int MAX_DRAIN_PER_TICK = 64;
    public static final int MAX_PENDING_REMOVALS = 4096;

    private static final ConcurrentHashMap<MinecraftServer, PendingRemovals> PENDING_REMOVALS =
            new ConcurrentHashMap<>();

    private FoundationAreaEvents() {
    }

    public static ChunkKey chunkKey(ServerLevel level, LevelChunk chunk) {
        return new ChunkKey(McCompat.dimensionId(level), McCompat.chunkX(chunk.getPos()),
                McCompat.chunkZ(chunk.getPos()));
    }

    public static ContainerKey containerKey(ServerLevel level, BlockPos pos) {
        return new ContainerKey(McCompat.dimensionId(level), pos.getX(), pos.getY(), pos.getZ());
    }

    public static void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        MinecraftServer server = level.getServer();
        //? if >=26.2 {
        if (server == null) {
            return;
        }
        //?}
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime != null) {
            runtime.areas().chunkLoaded(chunkKey(level, chunk));
        }
    }

    public static void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        MinecraftServer server = level.getServer();
        //? if >=26.2 {
        if (server == null) {
            return;
        }
        //?}
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime != null) {
            runtime.areas().chunkUnloaded(chunkKey(level, chunk));
        }
    }

    public static void containerChanged(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        //? if >=26.2 {
        if (server == null) {
            return;
        }
        //?}
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null) {
            return;
        }
        ContainerKey key = containerKey(level, pos);
        if (isInteresting(runtime, key)) {
            runtime.areas().containerChanged(key);
        }
    }

    /**
     * Immediately marks the position as changed (never deleted) so the old inventory is
     * fenced, then verifies one tick later what actually happened at that position.
     */
    public static void containerRemovalSuspected(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        //? if >=26.2 {
        if (server == null) {
            return;
        }
        //?}
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null) {
            return;
        }
        ContainerKey key = containerKey(level, pos);
        if (!isInteresting(runtime, key)) {
            return;
        }
        // A removed block entity may still be visible in the map later in this tick. Mark
        // it changed (not removed) first; the deferred check owns the delete decision.
        runtime.areas().containerChanged(key);
        PendingRemovals pending = PENDING_REMOVALS.computeIfAbsent(server, ignored -> new PendingRemovals());
        synchronized (pending) {
            if (pending.ready.size() + pending.next.size() >= MAX_PENDING_REMOVALS) {
                // Already invalidated above. Dropping the verification keeps the queue
                // bounded; a full chunk scan or a later event resolves the source.
                return;
            }
            pending.next.add(key);
        }
    }

    public static void drain(MinecraftServer server) {
        PendingRemovals pending = PENDING_REMOVALS.get(server);
        if (pending == null) {
            return;
        }
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null) {
            PENDING_REMOVALS.remove(server, pending);
            return;
        }
        List<ContainerKey> batch = new ArrayList<>(MAX_DRAIN_PER_TICK);
        synchronized (pending) {
            Iterator<ContainerKey> iterator = pending.ready.iterator();
            while (iterator.hasNext() && batch.size() < MAX_DRAIN_PER_TICK) {
                batch.add(iterator.next());
                iterator.remove();
            }
            if (!iterator.hasNext()) {
                // Entries queued during the current tick become visible on the next tick.
                pending.ready.addAll(pending.next);
                pending.next.clear();
            }
        }
        for (ContainerKey key : batch) {
            verifyRemoval(server, runtime, key);
        }
    }

    public static void clear(MinecraftServer server) {
        PENDING_REMOVALS.remove(server);
    }

    public static int pendingCount(MinecraftServer server) {
        PendingRemovals pending = PENDING_REMOVALS.get(server);
        if (pending == null) {
            return 0;
        }
        synchronized (pending) {
            return pending.ready.size() + pending.next.size();
        }
    }

    private static void verifyRemoval(MinecraftServer server, ServerRuntime runtime, ContainerKey key) {
        ServerLevel level = findLevel(server, key.world());
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(key.x() >> 4, key.z() >> 4);
        if (chunk == null) {
            // Unload keeps the last-known rows; the unload hook already marks coverage stale.
            return;
        }
        BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
        BlockEntity current = chunk.getBlockEntities().get(pos);
        if (current instanceof Container && !current.isRemoved()) {
            // Replacement or reload created a live source at the same position.
            runtime.areas().containerChanged(key);
            return;
        }
        if (current == null) {
            if (MinecraftAreaSnapshotSource.pendingPositions(chunk).contains(pos)) {
                // Pending NBT counts as unknown, never as a real empty container.
                runtime.areas().containerChanged(key);
            } else {
                runtime.areas().containerRemoved(key);
            }
            return;
        }
        // A removed or non-container block entity now occupies the position: the old
        // container is really gone, so delete it exactly.
        runtime.areas().containerRemoved(key);
    }

    private static boolean isInteresting(ServerRuntime runtime, ContainerKey key) {
        Optional<AreaCatalog> catalog = runtime.areas().configuration();
        if (catalog.isEmpty()) {
            // No area configuration is visible yet; startup scans cover configured areas.
            return false;
        }
        return !catalog.get().areasCovering(key.chunk()).isEmpty();
    }

    private static ServerLevel findLevel(MinecraftServer server, String world) {
        for (ServerLevel level : server.getAllLevels()) {
            if (McCompat.dimensionId(level).equals(world)) {
                return level;
            }
        }
        return null;
    }

    private static final class PendingRemovals {
        private final Set<ContainerKey> ready = new LinkedHashSet<>();
        private final Set<ContainerKey> next = new LinkedHashSet<>();
    }
}
