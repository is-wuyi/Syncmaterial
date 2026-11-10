/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

/**
 * Hard decode budgets. The defaults match the foundation decoder's published
 * limits; callers may pass a smaller test budget but should not silently raise
 * them for untrusted uploads.
 *
 * @param maxTotalBlocks    cap across all regions; overlapping regions sum
 * @param maxRegions        bound on region iteration
 * @param maxPaletteEntries bound on one region's palette size
 */
public record DecodeLimits(long maxTotalBlocks, int maxRegions, int maxPaletteEntries) {
    public static final long DEFAULT_MAX_TOTAL_BLOCKS = 64_000_000L;
    public static final int DEFAULT_MAX_REGIONS = 65_536;
    public static final int DEFAULT_MAX_PALETTE_ENTRIES = 1_048_576;

    public static final DecodeLimits DEFAULT = new DecodeLimits(
            DEFAULT_MAX_TOTAL_BLOCKS, DEFAULT_MAX_REGIONS, DEFAULT_MAX_PALETTE_ENTRIES);

    public DecodeLimits {
        if (maxTotalBlocks <= 0) {
            throw new IllegalArgumentException("maxTotalBlocks must be > 0");
        }
        if (maxRegions <= 0) {
            throw new IllegalArgumentException("maxRegions must be > 0");
        }
        if (maxPaletteEntries <= 0) {
            throw new IllegalArgumentException("maxPaletteEntries must be > 0");
        }
    }
}
