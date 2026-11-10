/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PackedPaletteTest {
    @Test
    void canonicalBitsUsesMinimumOfTwo() {
        assertEquals(2, PackedPalette.canonicalBits(1));
        assertEquals(2, PackedPalette.canonicalBits(2));
        assertEquals(2, PackedPalette.canonicalBits(4));
        assertEquals(3, PackedPalette.canonicalBits(5));
        assertEquals(8, PackedPalette.canonicalBits(256));
        assertEquals(9, PackedPalette.canonicalBits(257));
    }

    @Test
    void expectedLongCountRoundsUp() throws Exception {
        assertEquals(1L, PackedPalette.expectedLongCount(1L, 2, "r"));
        assertEquals(1L, PackedPalette.expectedLongCount(32L, 2, "r"));
        assertEquals(2L, PackedPalette.expectedLongCount(33L, 2, "r"));
        assertEquals(1L, PackedPalette.expectedLongCount(8L, 8, "r"));
        assertEquals(2L, PackedPalette.expectedLongCount(9L, 8, "r"));
    }

    @Test
    void countReadsEntriesSpanningTwoLongs() throws Exception {
        // 8-bit entries: index 7 starts at bit 56 and crosses into the next
        // long, which is the boundary case the layout check must cover.
        long[] storage = new long[2];
        storage[0] = 0x0706050403020100L;
        storage[1] = 0x0000000000000FL;
        int[] counts = new int[8];
        int[] expected = new int[8];
        for (int i = 0; i < 7; i++) {
            expected[i] = 1;
        }
        expected[7] = 1;

        PackedPalette.count(storage, 8L, 8, 8, counts, "r");
        assertArrayEquals(expected, counts);
    }

    @Test
    void countRejectsOutOfRangePaletteIndex() {
        int[] counts = new int[2];
        assertThrows(MalformedLitematicException.class, () ->
                PackedPalette.count(new long[] {2L}, 1L, 2, 2, counts, "r"));
    }

    @Test
    void expectedLongCountRejectsOverflow() {
        assertThrows(MalformedLitematicException.class, () ->
                PackedPalette.expectedLongCount(Long.MAX_VALUE, 8, "r"));
    }
}
