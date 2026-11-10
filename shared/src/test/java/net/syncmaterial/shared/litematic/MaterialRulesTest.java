/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialRulesTest {
    @Test
    void ordinaryStateMultipliesOccurrences() throws Exception {
        MaterialTotals totals = resolve(ordinary("minecraft:oak_slab", 2L, null), 5);
        assertEquals(Map.of("minecraft:oak_slab", 10L), totals.asMap());
    }

    @Test
    void waterloggedAddsBucketBeforeBaseState() throws Exception {
        MaterialTotals totals = resolve(ordinary("minecraft:oak_stairs", 1L,
                "minecraft:water_bucket"), 2);
        assertEquals(Map.of("minecraft:water_bucket", 2L,
                "minecraft:oak_stairs", 2L), totals.asMap());
    }

    @Test
    void flowerPotAddsPotAndPottedItem() throws Exception {
        MaterialTotals totals = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.FLOWER_POT, "minecraft:flower_pot",
                "minecraft:oak_sapling", 1L, 0, null), 1);
        assertEquals(Map.of("minecraft:flower_pot", 1L,
                "minecraft:oak_sapling", 1L), totals.asMap());
    }

    @Test
    void emptyFlowerPotAddsOnlyThePot() throws Exception {
        MaterialTotals totals = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.FLOWER_POT, "minecraft:flower_pot",
                null, 1L, 0, null), 1);
        assertEquals(Map.of("minecraft:flower_pot", 1L), totals.asMap());
    }

    @Test
    void lavaCauldronAddsCauldronAndBucket() throws Exception {
        MaterialTotals totals = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.CAULDRON_LAVA, "minecraft:cauldron",
                "minecraft:lava_bucket", 1L, 0, null), 3);
        assertEquals(Map.of("minecraft:cauldron", 3L,
                "minecraft:lava_bucket", 3L), totals.asMap());
    }

    @Test
    void waterCauldronLevelsUsePotionAndBucketCounts() throws Exception {
        MaterialTotals level1 = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.CAULDRON_WATER, "minecraft:cauldron",
                "minecraft:potion", 1L, 1, null), 1);
        assertEquals(Map.of("minecraft:cauldron", 1L, "minecraft:potion", 1L),
                level1.asMap());

        MaterialTotals level2 = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.CAULDRON_WATER, "minecraft:cauldron",
                "minecraft:potion", 1L, 2, null), 3);
        assertEquals(Map.of("minecraft:cauldron", 3L, "minecraft:potion", 6L),
                level2.asMap());

        MaterialTotals level3 = resolve(new BlockStateFacts(
                BlockStateFacts.Kind.CAULDRON_WATER, "minecraft:cauldron",
                "minecraft:water_bucket", 1L, 3, null), 2);
        assertEquals(Map.of("minecraft:cauldron", 2L,
                "minecraft:water_bucket", 2L), level3.asMap());
    }

    @Test
    void ignoredStateAddsNothing() throws Exception {
        MaterialTotals totals = resolve(BlockStateFacts.none(), 4);
        assertEquals(Map.of(), totals.asMap());
    }

    @Test
    void aggregationRejectsOverflow() {
        MaterialTotals totals = new MaterialTotals();
        assertThrows(MalformedLitematicException.class, () -> {
            totals.add("minecraft:stone", Long.MAX_VALUE, "r");
            totals.add("minecraft:stone", 1L, "r");
        });
    }

    private static BlockStateFacts ordinary(String item, long multiplier,
                                            String waterlogged) {
        return new BlockStateFacts(BlockStateFacts.Kind.ORDINARY, item, null,
                multiplier, 0, waterlogged);
    }

    private static MaterialTotals resolve(BlockStateFacts facts, long occurrences)
            throws Exception {
        MaterialTotals totals = new MaterialTotals();
        MaterialRules.resolve(facts, occurrences, "r", totals);
        return totals;
    }

}
