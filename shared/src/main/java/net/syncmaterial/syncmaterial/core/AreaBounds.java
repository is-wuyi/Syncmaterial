package net.syncmaterial.syncmaterial.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inclusive block bounds. The record keeps the original corners supplied by the caller;
 * min/max helpers use the normalized box so corner order does not matter.
 *
 * <p>Coordinates are limited to the real world range: {@code |x|,|z| <= 30,000,000} and
 * {@code |y| <= 4,096}. The normalized horizontal footprint is limited to 1,024 chunks in
 * total (for example 32x32 is accepted, 33x33 is rejected) so a scanner can bound its work
 * and {@code chunkKeys} can never materialize an unbounded list. Limits are checked with
 * {@code long} arithmetic, which also rejects {@link Integer#MIN_VALUE} without relying on
 * the overflowing {@code Math.abs(int)}.</p>
 */
public record AreaBounds(int x1, int y1, int z1, int x2, int y2, int z2) {
    /** Maximum total horizontal chunks of one area (all X/Z chunk pairs combined). */
    public static final int MAX_HORIZONTAL_CHUNKS = 1024;
    public static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    public static final int MAX_VERTICAL_COORDINATE = 4_096;

    public AreaBounds {
        requireXZ(x1, "x1");
        requireY(y1, "y1");
        requireXZ(z1, "z1");
        requireXZ(x2, "x2");
        requireY(y2, "y2");
        requireXZ(z2, "z2");

        long chunkWidth = ((long) (Math.max(x1, x2) >> 4)) - (Math.min(x1, x2) >> 4) + 1L;
        long chunkDepth = ((long) (Math.max(z1, z2) >> 4)) - (Math.min(z1, z2) >> 4) + 1L;
        long chunks = chunkWidth * chunkDepth;
        if (chunks > MAX_HORIZONTAL_CHUNKS) {
            throw new IllegalArgumentException("area exceeds " + MAX_HORIZONTAL_CHUNKS
                    + " chunks: " + chunkWidth + "x" + chunkDepth + " = " + chunks);
        }
    }

    private static void requireXZ(int value, String field) {
        if (Math.abs((long) value) > MAX_HORIZONTAL_COORDINATE) {
            throw new IllegalArgumentException(field + " is outside +/-" + MAX_HORIZONTAL_COORDINATE);
        }
    }

    private static void requireY(int value, String field) {
        if (Math.abs((long) value) > MAX_VERTICAL_COORDINATE) {
            throw new IllegalArgumentException(field + " is outside +/-" + MAX_VERTICAL_COORDINATE);
        }
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int minY() {
        return Math.min(y1, y2);
    }

    public int maxY() {
        return Math.max(y1, y2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
                && y >= minY() && y <= maxY()
                && z >= minZ() && z <= maxZ();
    }

    public boolean contains(ContainerKey container) {
        Objects.requireNonNull(container, "container");
        return contains(container.x(), container.y(), container.z());
    }

    /** Inclusive intersection test: touching corners/edges still overlap. */
    public boolean intersects(AreaBounds other) {
        Objects.requireNonNull(other, "other");
        return minX() <= other.maxX() && maxX() >= other.minX()
                && minY() <= other.maxY() && maxY() >= other.minY()
                && minZ() <= other.maxZ() && maxZ() >= other.minZ();
    }

    public int minChunkX() {
        return minX() >> 4;
    }

    public int maxChunkX() {
        return maxX() >> 4;
    }

    public int minChunkZ() {
        return minZ() >> 4;
    }

    public int maxChunkZ() {
        return maxZ() >> 4;
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkX() && chunkX <= maxChunkX()
                && chunkZ >= minChunkZ() && chunkZ <= maxChunkZ();
    }

    public long chunkCount() {
        long width = (long) maxChunkX() - minChunkX() + 1L;
        long depth = (long) maxChunkZ() - minChunkZ() + 1L;
        return width * depth;
    }

    /** All chunk keys covered by these bounds in one world. Large bounds should be budgeted by the caller. */
    public List<ChunkKey> chunkKeys(String world) {
        Objects.requireNonNull(world, "world");
        long count = chunkCount();
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("too many chunks to materialize: " + count);
        }

        List<ChunkKey> chunks = new ArrayList<>((int) count);
        for (int chunkX = minChunkX(); chunkX <= maxChunkX(); chunkX++) {
            for (int chunkZ = minChunkZ(); chunkZ <= maxChunkZ(); chunkZ++) {
                chunks.add(new ChunkKey(world, chunkX, chunkZ));
            }
        }
        return List.copyOf(chunks);
    }
}
