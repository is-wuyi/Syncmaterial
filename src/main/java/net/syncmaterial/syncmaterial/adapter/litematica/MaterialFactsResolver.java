/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The material-conversion facts are aligned with Litematica by masa
 * (https://github.com/sakura-kyoko/litematica), licensed under LGPL-3.0:
 * https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * 1.21.x targets ship Litematica 0.23.7, whose material list resolves an
 * ordinary build item through AbstractBlock#getCloneItemStack ("pick stack").
 * 0.28.5 (26.2) resolves it through Block#asItem, so wall signs need the
 * standing-variant item alias and flower pots use getPotted() directly.
 * Both branches are exercised by the platform oracle test; the shared rule
 * code is not duplicated.
 */
package net.syncmaterial.syncmaterial.adapter.litematica;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import net.syncmaterial.shared.litematic.BlockStateFacts;

/**
 * Resolves the platform facts consumed by the shared material rules.
 *
 * <p>This class contains no aggregation or multiplication loop: it only maps
 * one native state to {@link BlockStateFacts}. The small version conditional
 * selects the Litematica rule family; all other code is shared between the
 * 1.21.x and 26.2 targets.</p>
 */
final class MaterialFactsResolver {
//? if >=26.2 {
    private static final boolean PICK_STACK_RULES = false;
//?} else {
    private static final boolean PICK_STACK_RULES = true;
//?}

    private static final Map<String, Item> ITEM_ID_CACHE = new HashMap<>();

    private MaterialFactsResolver() {}

    static BlockStateFacts facts(BlockState state) {
        String waterloggedItemId = null;
        if (isWaterlogged(state)) {
            waterloggedItemId = itemId(Items.WATER_BUCKET);
            state = state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
        }
        return PICK_STACK_RULES
                ? pickStackFacts(state, waterloggedItemId)
                : asItemFacts(state, waterloggedItemId);
    }

    // ===== Litematica 0.28.5 / 26.2 =====

    private static BlockStateFacts asItemFacts(BlockState state, String waterloggedItemId) {
        Block block = state.getBlock();

        if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
            String potted = ((FlowerPotBlock) block).getPotted() == Blocks.AIR
                    ? null
                    : itemId(((FlowerPotBlock) block).getPotted().asItem());
            return new BlockStateFacts(BlockStateFacts.Kind.FLOWER_POT,
                    itemId(Blocks.FLOWER_POT.asItem()), potted, 1L, 0,
                    waterloggedItemId);
        }

        BlockStateFacts cauldron = cauldronFacts(state, waterloggedItemId);
        if (cauldron != null) {
            return cauldron;
        }

        BlockStateFacts override = overrideFacts(state, waterloggedItemId, false);
        if (override != null) {
            return override;
        }

        String item = itemId(itemForBlock(block));
        if (item == null) {
            return none(waterloggedItemId);
        }
        return new BlockStateFacts(BlockStateFacts.Kind.ORDINARY, item, null,
                stackSizeForState(state), 0, waterloggedItemId);
    }

    // ===== Litematica 0.23.7 / 1.21.x =====

    private static BlockStateFacts pickStackFacts(BlockState state, String waterloggedItemId) {
        Block block = state.getBlock();

        if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
            // MaterialCache#getItems uses the flower pot's own clone stack,
            // which is the potted block item (or the pot itself when empty).
            return new BlockStateFacts(BlockStateFacts.Kind.FLOWER_POT,
                    itemId(Blocks.FLOWER_POT.asItem()), cloneItemId(state),
                    1L, 0, waterloggedItemId);
        }

        BlockStateFacts cauldron = cauldronFacts(state, waterloggedItemId);
        if (cauldron != null) {
            return cauldron;
        }

        BlockStateFacts override = overrideFacts(state, waterloggedItemId, true);
        if (override != null) {
            return override;
        }

        String item = cloneItemId(state);
        if (item == null) {
            return none(waterloggedItemId);
        }
        return new BlockStateFacts(BlockStateFacts.Kind.ORDINARY, item, null,
                stackSizeForState(state), 0, waterloggedItemId);
    }

    /**
     * Cauldron branches are identical in both inspected Litematica versions.
     */
    private static BlockStateFacts cauldronFacts(BlockState state, String waterloggedItemId) {
        Block block = state.getBlock();
        if (!(block instanceof AbstractCauldronBlock) || block == Blocks.CAULDRON) {
            return null;
        }
        String cauldronItem = itemId(Blocks.CAULDRON.asItem());
        if (block instanceof LavaCauldronBlock) {
            return new BlockStateFacts(BlockStateFacts.Kind.CAULDRON_LAVA,
                    cauldronItem, itemId(Items.LAVA_BUCKET), 1L, 0,
                    waterloggedItemId);
        }
        if (block == Blocks.POWDER_SNOW_CAULDRON) {
            return new BlockStateFacts(BlockStateFacts.Kind.CAULDRON_POWDER_SNOW,
                    cauldronItem, itemId(Items.POWDER_SNOW_BUCKET), 1L, 0,
                    waterloggedItemId);
        }
        if (block == Blocks.WATER_CAULDRON) {
            int level = state.getValue(LayeredCauldronBlock.LEVEL);
            String contents = level == 3
                    ? itemId(Items.WATER_BUCKET)
                    : (level > 0 ? itemId(Items.POTION) : null);
            return new BlockStateFacts(BlockStateFacts.Kind.CAULDRON_WATER,
                    cauldronItem, contents, 1L, level, waterloggedItemId);
        }
        // A custom cauldron subclass has no special branch in MaterialCache,
        // so it falls through to the normal item path.
        return null;
    }

    /**
     * Port of MaterialCache#getStateToItemOverride. {@code null} means "no
     * override"; otherwise the returned facts already encode the override.
     */
    private static BlockStateFacts overrideFacts(BlockState state,
                                                 String waterloggedItemId,
                                                 boolean pickStackRules) {
        Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON
                || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                || block == Blocks.END_GATEWAY) {
            return none(waterloggedItemId);
        }
        if (block == Blocks.FARMLAND) {
            return new BlockStateFacts(BlockStateFacts.Kind.ORDINARY,
                    itemId(Blocks.DIRT.asItem()), null, 1L, 0,
                    waterloggedItemId);
        }
        if (block == Blocks.LAVA) {
            return state.getValue(LiquidBlock.LEVEL) == 0
                    ? new BlockStateFacts(BlockStateFacts.Kind.ORDINARY,
                            itemId(Items.LAVA_BUCKET), null, 1L, 0,
                            waterloggedItemId)
                    : none(waterloggedItemId);
        }
        if (block == Blocks.WATER) {
            return state.getValue(LiquidBlock.LEVEL) == 0
                    ? new BlockStateFacts(BlockStateFacts.Kind.ORDINARY,
                            itemId(Items.WATER_BUCKET), null, 1L, 0,
                            waterloggedItemId)
                    : none(waterloggedItemId);
        }
        if (block instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return none(waterloggedItemId);
        }
        if (block instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return none(waterloggedItemId);
        }
        if (block instanceof DoublePlantBlock
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return none(waterloggedItemId);
        }
        // Brown/red mushroom blocks use the same item id in both rule sets,
        // so the ordinary path already produces the correct result.
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
        String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (!blockId.endsWith(fromSuffix)) {
            return Items.AIR;
        }
        String itemId = blockId.substring(0, blockId.length() - fromSuffix.length())
                + toSuffix;
        return itemById(itemId);
    }

    /**
     * The 1.21.x and 26.2 registry key types have different simple names, so
     * lookup by string avoids a version-specific identifier import.
     */
    private static Item itemById(String id) {
        Item cached = ITEM_ID_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().equals(id)) {
                ITEM_ID_CACHE.put(id, item);
                return item;
            }
        }
        ITEM_ID_CACHE.put(id, Items.AIR);
        return Items.AIR;
    }

    private static ItemStack cloneItemStack(BlockState state) {
        //? if >=26.2 {
        return new ItemStack(state.getBlock().asItem());
        //?} else {
        /*return state.getCloneItemStack(new MaterialStateView(state), net.minecraft.core.BlockPos.ZERO, false);
        *///?}
    }

    private static String cloneItemId(BlockState state) {
        ItemStack stack = cloneItemStack(state);
        return stack.isEmpty() ? null : itemId(stack.getItem());
    }

    private static String itemId(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static BlockStateFacts none(String waterloggedItemId) {
        return new BlockStateFacts(BlockStateFacts.Kind.NONE, null, null,
                0L, 0, waterloggedItemId);
    }
}
