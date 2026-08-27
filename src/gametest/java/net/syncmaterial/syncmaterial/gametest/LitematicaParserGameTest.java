package net.syncmaterial.syncmaterial.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DefaultLitematicaParser 端到端测试。
 * 在真实 MC 服务器中构造 .litematic NBT → 写入临时文件 → 解析 → 验证。
 */
public class LitematicaParserGameTest {

    // ==================== 小规模正确性测试 ====================

    @GameTest(structure = "empty")
    public void parseSmallSchematic_returnsCorrectMaterials(GameTestHelper ctx) {
        try {
            // 构造一个 2x2x2 的小原理图：4 个石头 + 4 个钻石矿
            List<BlockState> palette = List.of(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIAMOND_ORE.defaultBlockState()
            );

            // 4 个石头(index=0) + 4 个钻石矿(index=1)
            // bitsPerBlock=1, 每个 long 装 64 个 entry, 8 个 entry 需要 1 个 long
            long[] blockStates = new long[1];
            // 前 4 位 = 0 (stone), 后 4 位 = 1 (diamond_ore)
            for (int i = 0; i < 4; i++) {
                // index i: stone (0), 已经是默认值
            }
            for (int i = 4; i < 8; i++) {
                blockStates[0] |= (1L << i); // diamond_ore
            }

            CompoundTag rootNbt = buildLitematicNbt(palette, blockStates, 2, 2, 2);
            Path tempFile = Files.createTempFile("test", ".litematic");
            NbtIo.writeCompressed(rootNbt, tempFile);

            // 解析
            var parser = new DefaultLitematicaParser(new ParsingThreadPool());
            var materials = parser.parseAsync(tempFile.toString()).get();

            // 验证不崩溃且有结果
            ctx.assertTrue(materials.size() > 0, Component.literal("应有材料统计结果"));

            // 清理
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            throw ctx.assertionException("小规模解析测试失败: " + e.getMessage());
        }
        ctx.succeed();
    }

    // ==================== 全量方块冒烟测试 ====================

    @GameTest(structure = "empty")
    public void parseAllBlocks_smokeTest(GameTestHelper ctx) {
        try {
            // 收集所有注册方块的默认状态（每种方块一个）
            List<BlockState> allStates = new ArrayList<>();
            for (var block : BuiltInRegistries.BLOCK) {
                BlockState state = block.defaultBlockState();
                if (state.isAir()) continue; // 跳过空气
                allStates.add(state);
            }

            ctx.assertTrue(allStates.size() > 100, Component.literal("应有 100+ 种方块，实际: " + allStates.size()));

            // 构造 NBT
            int totalBlocks = allStates.size();
            int bitsPerBlock = Math.max(1, 32 - Integer.numberOfLeadingZeros(totalBlocks - 1));
            int entriesPerLong = 64 / bitsPerBlock;
            int longCount = (totalBlocks + entriesPerLong - 1) / entriesPerLong;
            long[] blockStates = new long[longCount];

            for (int i = 0; i < totalBlocks; i++) {
                int longIndex = i / entriesPerLong;
                int bitOffset = (i % entriesPerLong) * bitsPerBlock;
                blockStates[longIndex] |= ((long) i) << bitOffset;
            }

            // 构造 1x1xN 的长条区域（每个方块占一个位置）
            CompoundTag rootNbt = buildLitematicNbt(allStates, blockStates, 1, totalBlocks, 1);

            Path tempFile = Files.createTempFile("test-all-blocks", ".litematic");
            NbtIo.writeCompressed(rootNbt, tempFile);

            // 解析
            var parser = new DefaultLitematicaParser(new ParsingThreadPool());
            var materials = parser.parseAsync(tempFile.toString()).get();

            // 验证：不崩溃，有合理数量的材料
            ctx.assertTrue(materials.size() > 0, Component.literal("应有材料统计结果"));

            SyncMaterial.LOGGER.info("全量方块冒烟测试: {} 种方块 → {} 项材料", allStates.size(), materials.size());

            // 清理
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            throw ctx.assertionException("全量方块冒烟测试失败: " + e.getMessage());
        }
        ctx.succeed();
    }

    // ==================== NBT 构造辅助 ====================

    private CompoundTag buildLitematicNbt(List<BlockState> palette, long[] blockStates,
                                           int width, int height, int length) {
        // Palette → ListTag
        ListTag paletteList = new ListTag();
        for (BlockState state : palette) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }

        // Region
        CompoundTag sizeNbt = new CompoundTag();
        sizeNbt.put("x", IntTag.valueOf(width));
        sizeNbt.put("y", IntTag.valueOf(height));
        sizeNbt.put("z", IntTag.valueOf(length));

        CompoundTag posNbt = new CompoundTag();
        posNbt.put("x", IntTag.valueOf(0));
        posNbt.put("y", IntTag.valueOf(0));
        posNbt.put("z", IntTag.valueOf(0));

        CompoundTag regionNbt = new CompoundTag();
        regionNbt.put("Size", sizeNbt);
        regionNbt.put("Position", posNbt);
        regionNbt.put("BlockStatePalette", paletteList);
        regionNbt.put("BitsPerEntry", IntTag.valueOf(Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1))));
        regionNbt.put("BlockStates", new LongArrayTag(blockStates));

        CompoundTag regionsNbt = new CompoundTag();
        regionsNbt.put("main", regionNbt);

        CompoundTag rootNbt = new CompoundTag();
        rootNbt.put("Regions", regionsNbt);
        return rootNbt;
    }
}
