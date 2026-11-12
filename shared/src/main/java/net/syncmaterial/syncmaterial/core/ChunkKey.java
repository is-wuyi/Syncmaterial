package net.syncmaterial.syncmaterial.core;

/**
 * Chunk coordinate in one world/dimension. Chunk coordinates are already block {@code >> 4};
 * use {@link ContainerKey#chunk()} to convert a block position.
 */
public record ChunkKey(String world, int x, int z) {
    public ChunkKey {
        CoreChecks.requireNonBlank(world, "world");
    }

    public static ChunkKey fromBlock(String world, int blockX, int blockZ) {
        return new ChunkKey(world, blockX >> 4, blockZ >> 4);
    }
}
