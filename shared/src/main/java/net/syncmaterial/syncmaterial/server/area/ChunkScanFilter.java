package net.syncmaterial.syncmaterial.server.area;

import java.util.List;
import java.util.Objects;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.ChunkKey;

/**
 * Pure (Minecraft-free) filter passed to a snapshot source for one chunk read. The source
 * may use the bounds to avoid materialising unrelated block entities; the scheduler still
 * filters the returned observations per area before committing them.
 */
public record ChunkScanFilter(String world, List<AreaBounds> bounds) {
    public ChunkScanFilter {
        Objects.requireNonNull(world, "world");
        bounds = List.copyOf(Objects.requireNonNull(bounds, "bounds"));
    }

    public static ChunkScanFilter empty(String world) {
        return new ChunkScanFilter(world, List.of());
    }

    public boolean accepts(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return world.equals(chunk.world());
    }

    public boolean accepts(int x, int y, int z) {
        for (AreaBounds bound : bounds) {
            if (bound.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsArea(int index, int x, int y, int z) {
        return bounds.get(index).contains(x, y, z);
    }
}
