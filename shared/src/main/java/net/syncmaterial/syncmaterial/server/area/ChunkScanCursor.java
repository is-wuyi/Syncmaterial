package net.syncmaterial.syncmaterial.server.area;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.syncmaterial.syncmaterial.core.ContainerKey;

/**
 * Resumable, Minecraft-free full-chunk scan cursor. It stores only positions and partial
 * item counts, never a chunk, block-entity map or block-entity reference. Partial counts
 * belong to the container currently being read when a per-tick slot budget cut it short.
 */
public record ChunkScanCursor(
        List<ContainerKey> containers,
        int containerIndex,
        int slotIndex,
        Map<String, Long> partialCounts,
        List<ContainerKey> pending,
        int pendingIndex
) {
    public ChunkScanCursor {
        containers = List.copyOf(Objects.requireNonNull(containers, "containers"));
        pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : Objects.requireNonNull(partialCounts, "partialCounts").entrySet()) {
            Long value = Objects.requireNonNull(entry.getValue(), "partial count");
            if (value < 0) {
                throw new IllegalArgumentException("partial count must be >= 0");
            }
            if (value != 0) {
                counts.put(Objects.requireNonNull(entry.getKey(), "item id"), value);
            }
        }
        partialCounts = Map.copyOf(counts);
        if (containerIndex < 0 || containerIndex > containers.size()) {
            throw new IllegalArgumentException("containerIndex out of range");
        }
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be >= 0");
        }
        if (pendingIndex < 0 || pendingIndex > pending.size()) {
            throw new IllegalArgumentException("pendingIndex out of range");
        }
    }

    public static ChunkScanCursor empty() {
        return new ChunkScanCursor(List.of(), 0, 0, Map.of(), List.of(), 0);
    }

    public static ChunkScanCursor of(List<ContainerKey> containers, List<ContainerKey> pending) {
        return new ChunkScanCursor(containers, 0, 0, Map.of(), pending, 0);
    }

    public boolean finished() {
        return containerIndex >= containers.size() && pendingIndex >= pending.size();
    }
}
