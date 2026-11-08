/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The material-conversion rules are a source-level port of behavior from
 * Litematica by masa (https://github.com/sakura-kyoko/litematica), licensed
 * under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * Inspected upstream: Litematica 0.28.5, jar id Fhq3KCI8, classes
 * fi.dy.masa.litematica.materials.MaterialCache and MaterialListUtils.
 * Modified for SyncMaterial: no Litematica/MaLiLib runtime dependency, item-id
 * output only, and explicit validation instead of silently returning air.
 */
package net.syncmaterial.syncmaterial.adapter.litematica;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LavaCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreadeableBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.syncmaterial.syncmaterial.core.MaterialCount;

/**
 * Converts one Minecraft block state to the item rows Litematica 0.28.5's
 * material list would add for one occurrence of that state.
 *
 * <p>These rules are bytecode-aligned with the actual 0.28.5 jar (Maven
 * Modrinth id {@code Fhq3KCI8}), not the bundled 1.21.8 LTS source tree. The
 * bytecode establishes the branch structure; the independent Foundation client
 * test also compares these special-block families with Litematica's public
 * material-list output. Modded block behavior is not exhaustively covered. In particular:</p>
 *
 * <ul>
 *   <li>waterlogged states add one water bucket and then use the
 *       non-waterlogged base state (MaterialListUtils);</li>
 *   <li>flower pots and non-plain cauldrons return multiple item stacks
 *       (MaterialCache#requiresMultipleItems / #getItems);</li>
 *   <li>piston heads, moving pistons and portals are ignored; farmland
 *       becomes dirt; upper door/bed/double-plant halves are ignored;</li>
 *   <li>double slabs, snow layers, turtle eggs, sea pickles, candles and
 *       spreadable multifaces multiply the item count;</li>
 *   <li>ordinary blocks use only {@code Block#asItem()} and no NBT or
 *       inventory contents. Potion components are intentionally not modeled:
 *       the foundation store only records item ids.</li>
 * </ul>
 *
 * <p>This class deliberately does not reference any Litematica or MaLiLib
 * class, so the dedicated server can run without those optional mods.</p>
 */
public final class MaterialResolver {
    private MaterialResolver() {}

    /**
     * Resolve one decoded block state.
     *
     * @param state      a validated block state from a schematic palette
     * @param occurrences number of times the state occurs in the schematic
     * @return item rows, already multiplied by {@code occurrences}; never null
     */
    public static List<MaterialCount> resolve(BlockState state, long occurrences) {
        Objects.requireNonNull(state, "state");
        if (occurrences < 0) {
            throw new IllegalArgumentException("occurrences must be >= 0");
        }
        if (occurrences == 0) {
            return List.of();
        }

        List<MaterialCount> result = new ArrayList<>(4);

        // MaterialListUtils.convertStatesToStacks: a waterlogged block first
        // contributes one water bucket, then contributes its base state.
        if (isWaterlogged(state)) {
            add(result, Items.WATER_BUCKET, occurrences);
            state = state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
        }

        addStateItems(result, state, occurrences);
        return List.copyOf(result);
    }

    private static void addStateItems(List<MaterialCount> result, BlockState state, long occurrences) {
        Block block = state.getBlock();

        if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
            // MaterialCache#getItems: pot itself and the potted content, one
            // item each, unless the pot is empty.
            add(result, Blocks.FLOWER_POT.asItem(), occurrences);
            Block potted = ((FlowerPotBlock) block).getPotted();
            if (potted != Blocks.AIR) {
                add(result, itemForBlock(potted), occurrences);
            }
            return;
        }

        if (block instanceof AbstractCauldronBlock && block != Blocks.CAULDRON) {
            // MaterialCache#getItems, matching the 0.28.5 bytecode branch
            // order. Cauldron plus its bucket/potion contents.
            if (block instanceof LavaCauldronBlock) {
                add(result, Blocks.CAULDRON.asItem(), occurrences);
                add(result, Items.LAVA_BUCKET, occurrences);
                return;
            }
            if (block == Blocks.POWDER_SNOW_CAULDRON) {
                add(result, Blocks.CAULDRON.asItem(), occurrences);
                add(result, Items.POWDER_SNOW_BUCKET, occurrences);
                return;
            }
            if (block == Blocks.WATER_CAULDRON) {
                add(result, Blocks.CAULDRON.asItem(), occurrences);
                int level = state.getValue(LayeredCauldronBlock.LEVEL);
                if (level == 1) {
                    add(result, Items.POTION, occurrences);
                } else if (level == 2) {
                    add(result, Items.POTION, multiply(occurrences, 2L));
                } else if (level == 3) {
                    add(result, Items.WATER_BUCKET, occurrences);
                }
                return;
            }
            // A custom cauldron subclass for which MaterialCache has no
            // special branch, so it falls through to the normal item path.
            addRequiredBuildItem(result, state, occurrences);
            return;
        }

        addRequiredBuildItem(result, state, occurrences);
    }

    private static void addRequiredBuildItem(List<MaterialCount> result, BlockState state, long occurrences) {
        Item item = stateToItemOverride(state);
        if (item == Items.AIR) {
            return;
        }
        if (item == null) {
            item = itemForBlock(state.getBlock());
        }
        if (item == Items.AIR) {
            return;
        }

        long stackSize = stackSizeForState(state);
        if (stackSize <= 0) {
            return;
        }
        add(result, item, multiply(occurrences, stackSize));
    }

    /**
     * Port of MaterialCache#getStateToItemOverride. {@code null} means "no
     * override, use Block#asItem"; {@link Items#AIR} means "explicitly none".
     */
    private static Item stateToItemOverride(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON
                || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.END_GATEWAY) {
            return Items.AIR;
        }
        if (block == Blocks.FARMLAND) {
            return Blocks.DIRT.asItem();
        }
        if (block == Blocks.BROWN_MUSHROOM_BLOCK) {
            return Blocks.BROWN_MUSHROOM_BLOCK.asItem();
        }
        if (block == Blocks.RED_MUSHROOM_BLOCK) {
            return Blocks.RED_MUSHROOM_BLOCK.asItem();
        }
        if (block == Blocks.LAVA) {
            return state.getValue(LiquidBlock.LEVEL) == 0 ? Items.LAVA_BUCKET : Items.AIR;
        }
        if (block == Blocks.WATER) {
            return state.getValue(LiquidBlock.LEVEL) == 0 ? Items.WATER_BUCKET : Items.AIR;
        }
        if (block instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return Items.AIR;
        }
        if (block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return Items.AIR;
        }
        if (block instanceof DoublePlantBlock
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return Items.AIR;
        }
        return null;
    }

    /** Port of MaterialCache#overrideStackSize. */
    private static long stackSizeForState(BlockState state) {
        Block block = state.getBlock();

        if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return 2L;
        }
        if (block == Blocks.SNOW) {
            return state.getValue(SnowLayerBlock.LAYERS);
        }
        if (block instanceof TurtleEggBlock) {
            return state.getValue(TurtleEggBlock.EGGS);
        }
        if (block instanceof SeaPickleBlock) {
            return state.getValue(SeaPickleBlock.PICKLES);
        }
        if (block instanceof CandleBlock) {
            return state.getValue(CandleBlock.CANDLES);
        }
        if (block instanceof MultifaceSpreadeableBlock) {
            return MultifaceBlock.availableFaces(state).size();
        }
        return 1L;
    }

    private static boolean isWaterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Vanilla wall signs/wall hanging signs are registered without their own
     * block items. Litematica's material list treats a wall variant as the
     * same item as its standing variant, so fall back to the corresponding
     * item registry id when {@code Block#asItem()} reports air.
     */
    private static Item itemForBlock(Block block) {
        Item item = block.asItem();
        if (item != Items.AIR) {
            return item;
        }

        if (block instanceof WallHangingSignBlock) {
            return aliasedItem(block, "_wall_hanging_sign", "_hanging_sign");
        }
        if (block instanceof WallSignBlock) {
            return aliasedItem(block, "_wall_sign", "_sign");
        }
        return item;
    }

    private static Item aliasedItem(Block block, String fromSuffix, String toSuffix) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        String path = blockId.getPath();
        if (!path.endsWith(fromSuffix)) {
            return Items.AIR;
        }
        Identifier itemId = Identifier.fromNamespaceAndPath(blockId.getNamespace(),
                path.substring(0, path.length() - fromSuffix.length()) + toSuffix);
        return BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.AIR);
    }

    private static void add(List<MaterialCount> result, Item item, long count) {
        if (item == null || item == Items.AIR || count <= 0) {
            return;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        result.add(new MaterialCount(id.toString(), count));
    }

    private static long multiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("material count overflow", overflow);
        }
    }
}
