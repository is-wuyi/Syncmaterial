package net.syncmaterial.syncmaterial.server.area;

import java.util.Set;
import net.syncmaterial.syncmaterial.core.ChunkKey;

/**
 * Platform port that observes existing loaded chunks. Implementations are server-thread
 * confined and must never load a chunk or promote pending block-entity NBT.
 */
public interface AreaSnapshotSource {
    AreaSnapshotSource NONE = new AreaSnapshotSource() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Set<ChunkKey> loadedChunks() {
            return Set.of();
        }

        @Override
        public boolean isChunkLoaded(ChunkKey chunk) {
            return false;
        }

        @Override
        public ChunkScanSlice scanChunk(ChunkKey chunk, ChunkScanFilter filter,
                                        ChunkScanCursor cursor, ScanBudget budget) {
            return ChunkScanSlice.notLoaded();
        }

        @Override
        public ContainerReadResult readContainers(ChunkKey chunk, ChunkScanFilter filter,
                                                  Set<net.syncmaterial.syncmaterial.core.ContainerKey> keys,
                                                  ScanBudget budget) {
            return ContainerReadResult.notLoaded();
        }
    };

    boolean isAvailable();

    /** Snapshot of chunks currently loaded in all server dimensions. */
    Set<ChunkKey> loadedChunks();

    /** Pure check; must use the chunk source's getChunkNow (never load). */
    boolean isChunkLoaded(ChunkKey chunk);

    /**
     * Read up to the budget from the chunk's EXISTING block-entity map. Repeat with the
     * returned cursor until {@code complete} or until the chunk unloads.
     */
    ChunkScanSlice scanChunk(ChunkKey chunk, ChunkScanFilter filter, ChunkScanCursor cursor, ScanBudget budget);

    /**
     * Read only the listed dirty positions. Missing positions are reported by returning no
     * observation for them (the caller decides removal versus rescan). {@code truncated}
     * asks the caller to fall back to a full chunk scan. A truncated result must still
     * report the containers/slots it consumed through {@link ContainerReadResult#truncatedNow(int, int)}
     * so the scheduler's per-tick budget stays honest.
     */
    ContainerReadResult readContainers(ChunkKey chunk, ChunkScanFilter filter,
                                       Set<net.syncmaterial.syncmaterial.core.ContainerKey> keys,
                                       ScanBudget budget);

    /** Result of a selected-container dirty read. */
    record ContainerReadResult(java.util.List<net.syncmaterial.syncmaterial.core.ContainerObservation> observations,
                               boolean loaded, boolean truncated, int containersUsed, int slotsUsed) {
        public ContainerReadResult {
            observations = java.util.List.copyOf(observations);
            if (!loaded && !observations.isEmpty()) {
                throw new IllegalArgumentException("an unloaded chunk cannot produce observations");
            }
            if (containersUsed < 0 || slotsUsed < 0) {
                throw new IllegalArgumentException("usage must be >= 0");
            }
        }

        public static ContainerReadResult notLoaded() {
            return new ContainerReadResult(java.util.List.of(), false, false, 0, 0);
        }

        public static ContainerReadResult truncatedNow() {
            return truncatedNow(0, 0);
        }

        /**
         * Truncated selected read that already consumed source work. The scheduler charges
         * both usage counters before falling back to a full chunk scan.
         */
        public static ContainerReadResult truncatedNow(int containersUsed, int slotsUsed) {
            return new ContainerReadResult(java.util.List.of(), true, true, containersUsed, slotsUsed);
        }
    }
}
