package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.StatisticsProcessor;

/**
 * StatisticsProcessor 纯逻辑测试。
 * 需要 Bootstrap 初始化 MC 注册表（和 StreamCodecTest 一样）。
 */
public class StatisticsProcessorTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    private static LitematicaParser.ParsingResult resultOf(Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
        return new LitematicaParser.ParsingResult() {
            public Map<BlockPos, net.minecraft.world.level.block.state.BlockState> getGlobalBlockMap() { return blocks; }
            public Map<BlockPos, CompoundTag> getGlobalTileEntityMap() { return Map.of(); }
        };
    }

    private static long findCount(List<MaterialEntry> materials, Object itemOrBlock) {
        for (var entry : materials) {
            if (itemOrBlock instanceof Block b && entry.getStack().getItem() == b.asItem()) {
                return entry.getCountTotal();
            }
            if (itemOrBlock instanceof net.minecraft.world.item.Item i && entry.getStack().getItem() == i) {
                return entry.getCountTotal();
            }
        }
        return 0;
    }

    // ========== isInvalidBlock ==========

    @Test
    void door_upperHalf_filtered() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        // 下半门应该计入
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        // 上半门应该被过滤
        blocks.put(new BlockPos(0, 1, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        // 只有下半门计入，1 个门
        assertEquals(1, findCount(materials, Blocks.OAK_DOOR));
    }

    @Test
    void bed_headPart_filtered() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.BED.red().defaultBlockState()
            .setValue(BedBlock.PART, BedPart.FOOT));
        blocks.put(new BlockPos(0, 0, 1), Blocks.BED.red().defaultBlockState()
            .setValue(BedBlock.PART, BedPart.HEAD));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.BED.red()));
    }

    @Test
    void tallPlant_upperHalf_filtered() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SUNFLOWER.defaultBlockState()
            .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        blocks.put(new BlockPos(0, 1, 0), Blocks.SUNFLOWER.defaultBlockState()
            .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.SUNFLOWER));
    }

    @Test
    void pistonHead_filtered() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.PISTON_HEAD.defaultBlockState());
        blocks.put(new BlockPos(0, 0, 1), Blocks.PISTON.defaultBlockState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(0, findCount(materials, Blocks.PISTON_HEAD));
        assertEquals(1, findCount(materials, Blocks.PISTON));
    }

    // ========== getMultiplierForState ==========

    @Test
    void doubleSlab_countsAsTwo() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.DOUBLE));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(2, findCount(materials, Blocks.STONE_SLAB));
    }

    @Test
    void singleSlab_countsAsOne() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.BOTTOM));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.STONE_SLAB));
    }

    @Test
    void candle_multiple_countsCorrectly() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.CANDLE.defaultBlockState()
            .setValue(BlockStateProperties.CANDLES, 4));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(4, findCount(materials, Blocks.CANDLE));
    }

    @Test
    void snowLayer_multipleCounts() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SNOW.defaultBlockState()
            .setValue(BlockStateProperties.LAYERS, 7));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(7, findCount(materials, Blocks.SNOW));
    }

    @Test
    void seaPickle_multipleCounts() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SEA_PICKLE.defaultBlockState()
            .setValue(BlockStateProperties.PICKLES, 3));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(3, findCount(materials, Blocks.SEA_PICKLE));
    }

    // ========== normalizeBlock ==========

    @Test
    void wallSign_normalizesToSign() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_WALL_SIGN.defaultBlockState());
        blocks.put(new BlockPos(0, 0, 1), Blocks.OAK_SIGN.defaultBlockState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        // 两种都应该归一化为 OAK_SIGN
        assertEquals(2, findCount(materials, Blocks.OAK_SIGN));
    }

    // ========== 含水/水源/岩浆 ==========

    @Test
    void waterlogged_addsWaterBucket() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        // 含水楼梯 → 水桶 + 楼梯
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
        assertEquals(1, findCount(materials, Blocks.OAK_STAIRS));
    }

    @Test
    void waterSource_addsWaterBucket() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        // 水源 (level=0) → 水桶
        blocks.put(new BlockPos(0, 0, 0), Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, 0));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
    }

    @Test
    void lavaSource_addsLavaBucket() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.LAVA.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, 0));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.LAVA_BUCKET));
    }

    // ========== 花盆/大釜拆分 ==========

    @Test
    void flowerPot_splitsIntoPotAndContent() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        // 含仙人掌的花盆 → 花盆 + 仙人掌
        blocks.put(new BlockPos(0, 0, 0), Blocks.POTTED_CACTUS.defaultBlockState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.FLOWER_POT));
        assertEquals(1, findCount(materials, Blocks.CACTUS));
    }

    @Test
    void emptyFlowerPot_doesNotSplit() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.FLOWER_POT.defaultBlockState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.FLOWER_POT));
    }

    @Test
    void waterCauldron_level3_addsWaterBucket() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 3));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.CAULDRON));
        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
    }

    @Test
    void lavaCauldron_addsLavaBucket() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.LAVA_CAULDRON.defaultBlockState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.CAULDRON));
        assertEquals(1, findCount(materials, Items.LAVA_BUCKET));
    }
}
