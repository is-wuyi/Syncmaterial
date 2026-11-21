package net.syncmaterial.syncmaterial.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestKindTest {
    @Test
    void bitValuesAreStableAndCoverEveryKind() {
        assertEquals(1, InterestKind.MATERIALS.bit());
        assertEquals(2, InterestKind.TEAM.bit());
        assertEquals(4, InterestKind.AREA_CONFIG.bit());
        assertEquals(8, InterestKind.OUTLINES.bit());
        assertEquals(16, InterestKind.WAREHOUSE_CONTENTS.bit());
        assertEquals(31, InterestKind.ALL_MASK);
    }

    @Test
    void maskAndFromMaskRoundTripEverySubset() {
        for (int mask = 0; mask <= InterestKind.ALL_MASK; mask++) {
            Set<InterestKind> kinds = InterestKind.fromMask(mask);
            assertEquals(mask, InterestKind.mask(kinds));
        }
        assertEquals(Set.of(), InterestKind.fromMask(0));
        assertEquals(EnumSet.allOf(InterestKind.class),
                InterestKind.fromMask(InterestKind.ALL_MASK));
    }

    @Test
    void maskFactoriesCombineKinds() {
        assertEquals(InterestKind.ALL_MASK,
                InterestKind.mask(EnumSet.allOf(InterestKind.class)));
        assertEquals(3, InterestKind.mask(InterestKind.MATERIALS, InterestKind.TEAM));
        assertEquals(0, InterestKind.mask(List.of()));
    }

    @Test
    void unknownOrNegativeMasksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> InterestKind.fromMask(1 << 5));
        assertThrows(IllegalArgumentException.class,
                () -> InterestKind.fromMask(InterestKind.ALL_MASK | 1 << 7));
        assertThrows(IllegalArgumentException.class, () -> InterestKind.fromMask(-1));
    }

    @Test
    void returnedSetsAreImmutable() {
        Set<InterestKind> kinds = InterestKind.fromMask(InterestKind.MATERIALS.bit());
        assertThrows(UnsupportedOperationException.class, () -> kinds.add(InterestKind.TEAM));
        assertTrue(kinds.contains(InterestKind.MATERIALS));
        assertFalse(kinds.contains(InterestKind.TEAM));
    }
}
