/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * Packed-palette layout and the canonical palette bit-width rule follow
 * Litematica by masa (https://github.com/sakura-kyoko/litematica), licensed
 * under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * Inspected upstream: Litematica 0.28.5, classes LitematicaBlockStateContainer
 * and LitematicaBitArray. Modified for SyncMaterial: standalone validation,
 * hard resource limits and explicit malformed-input failures.
 */
package net.syncmaterial.shared.litematic;

/**
 * Canonical Litematica packed long-array decoding.
 *
 * <p>The bit width is derived from the palette size, never from the stored
 * {@code BitsPerEntry} field. The minimum is two bits even for a one-entry
 * palette. The stored long array must have exactly the length implied by the
 * canonical width and region volume; extra or missing longs are rejected.</p>
 */
public final class PackedPalette {
    private PackedPalette() {}

    /**
     * Exact LitematicaBlockStateContainer.createFrom expression.
     */
    public static int canonicalBits(int paletteSize) {
        if (paletteSize <= 0) {
            throw new IllegalArgumentException("paletteSize must be > 0");
        }
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    public static long expectedLongCount(long volume, int bits, String regionName)
            throws MalformedLitematicException {
        long totalBits = MaterialRules.multiply(volume, bits, regionName,
                "packed bit count");
        return (totalBits + 63L) / 64L;
    }

    /**
     * Count each palette index occurrence.
     *
     * @param storage     packed long array, already length-checked
     * @param volume      positive region volume
     * @param bits        canonical bit width
     * @param paletteSize number of palette entries
     * @param counts      output array of length {@code paletteSize}
     */
    public static void count(long[] storage, long volume, int bits, int paletteSize,
                             int[] counts, String regionName)
            throws MalformedLitematicException {
        long mask = (1L << bits) - 1L;
        for (long index = 0; index < volume; index++) {
            long bitOffset = index * bits;
            int startLong = (int) (bitOffset >>> 6);
            int startBit = (int) (bitOffset & 63L);
            int endLong = (int) (((index + 1L) * bits - 1L) >>> 6);

            if (startLong >= storage.length || endLong >= storage.length) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' block-state read exceeds array at index "
                                + index);
            }

            int paletteIndex;
            if (startLong == endLong) {
                paletteIndex = (int) ((storage[startLong] >>> startBit) & mask);
            } else {
                int endOffset = 64 - startBit;
                paletteIndex = (int) (((storage[startLong] >>> startBit)
                        | (storage[endLong] << endOffset)) & mask);
            }

            if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' palette index " + paletteIndex
                                + " at block " + index + " is out of range (palette size "
                                + paletteSize + ")");
            }
            counts[paletteIndex]++;
        }
    }
}
