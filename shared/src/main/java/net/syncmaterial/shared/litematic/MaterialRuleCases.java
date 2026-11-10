/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * Descriptor list used by both the 1.21.x (Litematica 0.23.7) and 26.2
 * (Litematica 0.28.5) material-rule oracle runners. Keeping it as plain
 * command-style block-state text avoids duplicating the corpus per node and
 * avoids version-specific Blocks constants (for example colored beds).
 */
package net.syncmaterial.shared.litematic;

import java.util.List;

/**
 * One neutral corpus of block states that exercises every material-rule
 * branch: ordinary blocks, ignored states, upper halves, multipliers,
 * waterlogged conversion, cauldrons, flower pots and wall-sign aliases.
 */
public final class MaterialRuleCases {
    public static final List<String> BLOCK_STATE_DESCRIPTORS = List.of(
            "minecraft:stone",
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air",

            "minecraft:farmland",
            "minecraft:brown_mushroom_block",
            "minecraft:red_mushroom_block",
            "minecraft:piston_head",
            "minecraft:moving_piston",
            "minecraft:nether_portal",
            "minecraft:end_portal",
            "minecraft:end_gateway",

            "minecraft:oak_door[half=lower]",
            "minecraft:oak_door[half=upper]",
            "minecraft:red_bed[part=foot]",
            "minecraft:red_bed[part=head]",
            "minecraft:tall_grass[half=lower]",
            "minecraft:tall_grass[half=upper]",

            "minecraft:oak_slab[type=double]",
            "minecraft:snow[layers=5]",
            "minecraft:turtle_egg[eggs=3]",
            "minecraft:sea_pickle[pickles=4]",
            "minecraft:candle[candles=4]",
            "minecraft:oak_stairs[waterlogged=true]",
            "minecraft:glow_lichen[north=true,up=true]",

            "minecraft:water[level=0]",
            "minecraft:water[level=1]",
            "minecraft:lava[level=0]",
            "minecraft:lava[level=1]",

            "minecraft:flower_pot",
            "minecraft:potted_poppy",
            "minecraft:cauldron",
            "minecraft:lava_cauldron",
            "minecraft:powder_snow_cauldron",
            "minecraft:water_cauldron[level=1]",
            "minecraft:water_cauldron[level=2]",
            "minecraft:water_cauldron[level=3]",

            "minecraft:oak_sign",
            "minecraft:oak_wall_sign",
            "minecraft:oak_hanging_sign",
            "minecraft:oak_wall_hanging_sign"
    );

    private MaterialRuleCases() {}
}
