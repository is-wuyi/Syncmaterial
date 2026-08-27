package net.syncmaterial.syncmaterial.engine.impl;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class StatisticsProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsProcessor.class);

    public List<MaterialEntry> process(LitematicaParser.ParsingResult result, boolean includeContainers) {
        Map<Block, Long> blockCountMap = new HashMap<>();
        Map<Item, Long> itemCountMap = new HashMap<>();
        long waterBucketCount = 0;
        long lavaBucketCount = 0;

        LOGGER.info("StatisticsProcessor: 输入 {} 个方块, {} 个TileEntity", 
            result.getGlobalBlockMap().size(), result.getGlobalTileEntityMap().size());

        // 1. 处理方块统计
        for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry : result.getGlobalBlockMap().entrySet()) {
            net.minecraft.world.level.block.state.BlockState state = entry.getValue();

            if (isInvalidBlock(state)) {
                continue;
            }

            Block block = state.getBlock();

            // Waterlogged 状态
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                waterBucketCount++;
                state = state.setValue(BlockStateProperties.WATERLOGGED, false);
            }

            // 水流转换：只统计 level=0 的水源块
            if (state.is(Blocks.WATER)) {
                int level = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL) 
                    ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL) : -1;
                if (level == 0) {
                    waterBucketCount++;
                }
                continue;
            }
            
            // 岩浆转换
            if (state.is(Blocks.LAVA)) {
                int level = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL) 
                    ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL) : -1;
                if (level == 0) {
                    lavaBucketCount++;
                }
                continue;
            }

            // 花盆拆分
            if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
                blockCountMap.merge(Blocks.FLOWER_POT, 1L, Long::sum);
                Block content = ((FlowerPotBlock) block).getPotted();
                if (content != null) {
                    blockCountMap.merge(content, 1L, Long::sum);
                }
                continue;
            }

            // 大釜拆分
            if (block instanceof AbstractCauldronBlock && block != Blocks.CAULDRON) {
                blockCountMap.merge(Blocks.CAULDRON, 1L, Long::sum);
                if (block == Blocks.LAVA_CAULDRON) {
                    lavaBucketCount++;
                } else if (block == Blocks.POWDER_SNOW_CAULDRON) {
                    itemCountMap.merge(Items.POWDER_SNOW_BUCKET, 1L, Long::sum);
                } else if (block == Blocks.WATER_CAULDRON) {
                    int level = state.getValue(LayeredCauldronBlock.LEVEL);
                    if (level == 3) {
                        waterBucketCount++;
                    } else if (level == 2) {
                        itemCountMap.merge(Items.POTION, 2L, Long::sum);
                    } else if (level == 1) {
                        itemCountMap.merge(Items.POTION, 1L, Long::sum);
                    }
                }
                continue;
            }

            // 计算倍率并聚合
            long multiplier = getMultiplierForState(state);
            if (multiplier > 0) {
                Block normalizedBlock = normalizeBlock(block);
                blockCountMap.merge(normalizedBlock, multiplier, Long::sum);
            }
        }

        LOGGER.info("StatisticsProcessor: 方块处理后, blockCountMap 大小: {}", blockCountMap.size());

        // 2. 处理容器
        if (includeContainers) {
            for (CompoundTag teNbt : result.getGlobalTileEntityMap().values()) {
                if (teNbt.contains("Items")) {
                    Tag itemsElement = teNbt.get("Items");
                    if (itemsElement instanceof ListTag itemsList) {
                        // 容器内物品不做统计
                    }
                }
            }
            LOGGER.info("StatisticsProcessor: TileEntity处理后, blockCountMap 大小: {}", blockCountMap.size());
        }

        // 3. 转换为 MaterialEntry
        List<MaterialEntry> entries = new ArrayList<>();

        if (waterBucketCount > 0) {
            entries.add(new MaterialEntry(0, new ItemStack(Items.WATER_BUCKET), waterBucketCount));
        }
        if (lavaBucketCount > 0) {
            entries.add(new MaterialEntry(0, new ItemStack(Items.LAVA_BUCKET), lavaBucketCount));
        }
        for (Map.Entry<Item, Long> entry : itemCountMap.entrySet()) {
            entries.add(new MaterialEntry(0, new ItemStack(entry.getKey()), entry.getValue()));
        }

        for (Map.Entry<Block, Long> entry : blockCountMap.entrySet()) {
            entries.add(new MaterialEntry(0, new ItemStack(entry.getKey().asItem()), entry.getValue()));
        }

        entries.sort(Comparator.comparing(MaterialEntry::getDisplayName));

        LOGGER.info("=== 材料统计结果 (共 {} 项) ===", entries.size());
        for (MaterialEntry entry : entries) {
            LOGGER.info("  {} x{}", entry.getDisplayName(), entry.getCountTotal());
        }
        LOGGER.info("===========================");

        return entries;
    }

    private Block normalizeBlock(Block block) {
        if (block == Blocks.OAK_WALL_SIGN) return Blocks.OAK_SIGN;
        if (block == Blocks.SPRUCE_WALL_SIGN) return Blocks.SPRUCE_SIGN;
        if (block == Blocks.BIRCH_WALL_SIGN) return Blocks.BIRCH_SIGN;
        if (block == Blocks.JUNGLE_WALL_SIGN) return Blocks.JUNGLE_SIGN;
        if (block == Blocks.ACACIA_WALL_SIGN) return Blocks.ACACIA_SIGN;
        if (block == Blocks.CHERRY_WALL_SIGN) return Blocks.CHERRY_SIGN;
        if (block == Blocks.DARK_OAK_WALL_SIGN) return Blocks.DARK_OAK_SIGN;
        if (block == Blocks.MANGROVE_WALL_SIGN) return Blocks.MANGROVE_SIGN;
        if (block == Blocks.BAMBOO_WALL_SIGN) return Blocks.BAMBOO_SIGN;
        if (block == Blocks.CRIMSON_WALL_SIGN) return Blocks.CRIMSON_SIGN;
        if (block == Blocks.WARPED_WALL_SIGN) return Blocks.WARPED_SIGN;
        
        return block;
    }

    private boolean isInvalidBlock(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON ||
            block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) {
            return true;
        }

        if (block instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        if (block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return true;
        }

        if (block instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        return false;
    }

    private long getMultiplierForState(BlockState state) {
        Block block = state.getBlock();

        if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return 2L;
        }

        if (block instanceof CandleBlock) {
            return state.getValue(BlockStateProperties.CANDLES);
        }

        if (block == Blocks.SEA_PICKLE) {
            return state.getValue(BlockStateProperties.PICKLES);
        }

        if (block == Blocks.TURTLE_EGG) {
            return state.getValue(BlockStateProperties.EGGS);
        }

        if (block == Blocks.SNOW) {
            return state.getValue(BlockStateProperties.LAYERS);
        }

        if (block instanceof MultifaceBlock) {
            return MultifaceBlock.availableFaces(state).size();
        }

        return 1L;
    }
}
