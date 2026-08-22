package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.StatisticsProcessor;

/**
 * StatisticsProcessor 纯逻辑测试。
 * 需要 Bootstrap 初始化 MC 注册表（和 PacketCodecTest 一样）。
 */
public class StatisticsProcessorTest {

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static LitematicaParser.ParsingResult resultOf(Map<BlockPos, BlockState> blocks) {
        return new LitematicaParser.ParsingResult() {
            public Map<BlockPos, BlockState> getGlobalBlockMap() { return blocks; }
            public Map<BlockPos, NbtCompound> getGlobalTileEntityMap() { return Map.of(); }
        };
    }

    private static long findCount(List<MaterialEntry> materials, Object itemOrBlock) {
        for (var entry : materials) {
            if (itemOrBlock instanceof Block b && entry.getStack().getItem() == b.asItem()) {
                return entry.getCountTotal();
            }
            if (itemOrBlock instanceof net.minecraft.item.Item i && entry.getStack().getItem() == i) {
                return entry.getCountTotal();
            }
        }
        return 0;
    }

    // ========== isInvalidBlock ==========

    @Test
    void door_upperHalf_filtered() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 下半门应该计入
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_DOOR.getDefaultState()
            .with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        // 上半门应该被过滤
        blocks.put(new BlockPos(0, 1, 0), Blocks.OAK_DOOR.getDefaultState()
            .with(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        // 只有下半门计入，1 个门
        assertEquals(1, findCount(materials, Blocks.OAK_DOOR));
    }

    @Test
    void bed_headPart_filtered() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.RED_BED.getDefaultState()
            .with(BedBlock.PART, BedPart.FOOT));
        blocks.put(new BlockPos(0, 0, 1), Blocks.RED_BED.getDefaultState()
            .with(BedBlock.PART, BedPart.HEAD));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.RED_BED));
    }

    @Test
    void tallPlant_upperHalf_filtered() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SUNFLOWER.getDefaultState()
            .with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER));
        blocks.put(new BlockPos(0, 1, 0), Blocks.SUNFLOWER.getDefaultState()
            .with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.SUNFLOWER));
    }

    @Test
    void pistonHead_filtered() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.PISTON_HEAD.getDefaultState());
        blocks.put(new BlockPos(0, 0, 1), Blocks.PISTON.getDefaultState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(0, findCount(materials, Blocks.PISTON_HEAD));
        assertEquals(1, findCount(materials, Blocks.PISTON));
    }

    // ========== getMultiplierForState ==========

    @Test
    void doubleSlab_countsAsTwo() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE_SLAB.getDefaultState()
            .with(SlabBlock.TYPE, SlabType.DOUBLE));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(2, findCount(materials, Blocks.STONE_SLAB));
    }

    @Test
    void singleSlab_countsAsOne() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE_SLAB.getDefaultState()
            .with(SlabBlock.TYPE, SlabType.BOTTOM));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.STONE_SLAB));
    }

    @Test
    void candle_multiple_countsCorrectly() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.CANDLE.getDefaultState()
            .with(Properties.CANDLES, 4));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(4, findCount(materials, Blocks.CANDLE));
    }

    @Test
    void snowLayer_multipleCounts() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SNOW.getDefaultState()
            .with(Properties.LAYERS, 7));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(7, findCount(materials, Blocks.SNOW));
    }

    @Test
    void seaPickle_multipleCounts() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.SEA_PICKLE.getDefaultState()
            .with(Properties.PICKLES, 3));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(3, findCount(materials, Blocks.SEA_PICKLE));
    }

    // ========== normalizeBlock ==========

    @Test
    void wallSign_normalizesToSign() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_WALL_SIGN.getDefaultState());
        blocks.put(new BlockPos(0, 0, 1), Blocks.OAK_SIGN.getDefaultState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        // 两种都应该归一化为 OAK_SIGN
        assertEquals(2, findCount(materials, Blocks.OAK_SIGN));
    }

    // ========== 含水/水源/岩浆 ==========

    @Test
    void waterlogged_addsWaterBucket() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 含水楼梯 → 水桶 + 楼梯
        blocks.put(new BlockPos(0, 0, 0), Blocks.OAK_STAIRS.getDefaultState()
            .with(Properties.WATERLOGGED, true));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
        assertEquals(1, findCount(materials, Blocks.OAK_STAIRS));
    }

    @Test
    void waterSource_addsWaterBucket() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 水源 (level=0) → 水桶
        blocks.put(new BlockPos(0, 0, 0), Blocks.WATER.getDefaultState()
            .with(Properties.LEVEL_15, 0));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
    }

    @Test
    void lavaSource_addsLavaBucket() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.LAVA.getDefaultState()
            .with(Properties.LEVEL_15, 0));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Items.LAVA_BUCKET));
    }

    // ========== 花盆/大釜拆分 ==========

    @Test
    void flowerPot_splitsIntoPotAndContent() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // 含仙人掌的花盆 → 花盆 + 仙人掌
        blocks.put(new BlockPos(0, 0, 0), Blocks.POTTED_CACTUS.getDefaultState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.FLOWER_POT));
        assertEquals(1, findCount(materials, Blocks.CACTUS));
    }

    @Test
    void emptyFlowerPot_doesNotSplit() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.FLOWER_POT.getDefaultState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.FLOWER_POT));
    }

    @Test
    void waterCauldron_level3_addsWaterBucket() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.WATER_CAULDRON.getDefaultState()
            .with(LeveledCauldronBlock.LEVEL, 3));

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.CAULDRON));
        assertEquals(1, findCount(materials, Items.WATER_BUCKET));
    }

    @Test
    void lavaCauldron_addsLavaBucket() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.LAVA_CAULDRON.getDefaultState());

        var processor = new StatisticsProcessor();
        var materials = processor.process(resultOf(blocks), false);

        assertEquals(1, findCount(materials, Blocks.CAULDRON));
        assertEquals(1, findCount(materials, Items.LAVA_BUCKET));
    }
}
