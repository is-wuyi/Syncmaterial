package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/**
 * One physical inventory source: a dimension plus the position of the block entity that
 * owns the slots. Two areas covering the same key describe the same source.
 */
public record ContainerKey(String world, int x, int y, int z) {
    public ContainerKey {
        CoreChecks.requireNonBlank(world, "world");
    }

    public ChunkKey chunk() {
        return new ChunkKey(world, x >> 4, z >> 4);
    }

    public static ContainerKey fromBlock(String world, int x, int y, int z) {
        return new ContainerKey(world, x, y, z);
    }
}
