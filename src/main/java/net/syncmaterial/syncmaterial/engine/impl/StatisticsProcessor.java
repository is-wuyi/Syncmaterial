package net.syncmaterial.syncmaterial.engine.impl;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.potion.Potions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.state.property.Properties;
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
        for (Map.Entry<BlockPos, BlockState> entry : result.getGlobalBlockMap().entrySet()) {
            BlockState state = entry.getValue();

            if (isInvalidBlock(state)) {
                continue;
            }

            Block block = state.getBlock();

            // Waterlogged 状态
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
                waterBucketCount++;
                state = state.with(Properties.WATERLOGGED, false);
            }

            // 水流转换：只统计 level=0 的水源块
            if (state.isOf(Blocks.WATER)) {
                int level = state.contains(net.minecraft.state.property.Properties.LEVEL_15) 
                    ? state.get(net.minecraft.state.property.Properties.LEVEL_15) : -1;
                if (level == 0) {
                    waterBucketCount++;
                }
                continue;
            }
            
            // 岩浆转换
            if (state.isOf(Blocks.LAVA)) {
                int level = state.contains(net.minecraft.state.property.Properties.LEVEL_15) 
                    ? state.get(net.minecraft.state.property.Properties.LEVEL_15) : -1;
                if (level == 0) {
                    lavaBucketCount++;
                }
                continue;
            }

            // 花盆拆分
            if (block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT) {
                blockCountMap.merge(Blocks.FLOWER_POT, 1L, Long::sum);
                Block content = ((FlowerPotBlock) block).getContent();
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
                    int level = state.get(LeveledCauldronBlock.LEVEL);
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
            for (NbtCompound teNbt : result.getGlobalTileEntityMap().values()) {
                if (teNbt.contains("Items")) {
                    NbtElement itemsElement = teNbt.get("Items");
                    if (itemsElement instanceof NbtList itemsList) {
                        for (int i = 0; i < itemsList.size(); i++) {
                            NbtElement itemElement = itemsList.get(i);
                            if (itemElement instanceof NbtCompound itemNbt) {
                                // TODO: 容器物品处理
                            }
                        }
                    }
                }
            }
            LOGGER.info("StatisticsProcessor: TileEntity处理后, blockCountMap 大小: {}", blockCountMap.size());
        }

        // 3. 转换为 MaterialEntry
        List<MaterialEntry> entries = new ArrayList<>();

        if (waterBucketCount > 0) {
            entries.add(new MaterialEntry(new ItemStack(Items.WATER_BUCKET), waterBucketCount));
        }
        if (lavaBucketCount > 0) {
            entries.add(new MaterialEntry(new ItemStack(Items.LAVA_BUCKET), lavaBucketCount));
        }
        for (Map.Entry<Item, Long> entry : itemCountMap.entrySet()) {
            entries.add(new MaterialEntry(new ItemStack(entry.getKey()), entry.getValue()));
        }

        for (Map.Entry<Block, Long> entry : blockCountMap.entrySet()) {
            entries.add(new MaterialEntry(new ItemStack(entry.getKey().asItem()), entry.getValue()));
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

        if (block instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        if (block instanceof BedBlock && state.get(BedBlock.PART) == BedPart.HEAD) {
            return true;
        }

        if (block instanceof TallPlantBlock && state.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        return false;
    }

    private long getMultiplierForState(BlockState state) {
        Block block = state.getBlock();

        if (block instanceof SlabBlock && state.get(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return 2L;
        }

        if (block instanceof CandleBlock) {
            return state.get(Properties.CANDLES);
        }

        if (block == Blocks.SEA_PICKLE) {
            return state.get(Properties.PICKLES);
        }

        if (block == Blocks.TURTLE_EGG) {
            return state.get(Properties.EGGS);
        }

        if (block == Blocks.SNOW) {
            return state.get(Properties.LAYERS);
        }

        if (block instanceof MultifaceGrowthBlock) {
            return MultifaceGrowthBlock.collectDirections(state).size();
        }

        return 1L;
    }
}