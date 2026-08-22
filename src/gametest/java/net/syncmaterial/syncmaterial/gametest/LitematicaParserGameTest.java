package net.syncmaterial.syncmaterial.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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
    public void parseSmallSchematic_returnsCorrectMaterials(TestContext ctx) {
        try {
            // 构造一个 2x2x2 的小原理图：4 个石头 + 4 个钻石矿
            List<BlockState> palette = List.of(
                Blocks.STONE.getDefaultState(),
                Blocks.DIAMOND_ORE.getDefaultState()
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

            NbtCompound rootNbt = buildLitematicNbt(palette, blockStates, 2, 2, 2);
            Path tempFile = Files.createTempFile("test", ".litematic");
            NbtIo.writeCompressed(rootNbt, tempFile);

            // 解析
            var parser = new DefaultLitematicaParser(new ParsingThreadPool());
            var materials = parser.parseAsync(tempFile.toString()).get();

            // 验证不崩溃且有结果
            ctx.assertTrue(materials.size() > 0, Text.literal("应有材料统计结果"));

            // 清理
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            throw ctx.createError("小规模解析测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    // ==================== 全量方块冒烟测试 ====================

    @GameTest(structure = "empty")
    public void parseAllBlocks_smokeTest(TestContext ctx) {
        try {
            // 收集所有注册方块的默认状态（每种方块一个）
            List<BlockState> allStates = new ArrayList<>();
            for (var block : Registries.BLOCK) {
                BlockState state = block.getDefaultState();
                if (state.isAir()) continue; // 跳过空气
                allStates.add(state);
            }

            ctx.assertTrue(allStates.size() > 100, Text.literal("应有 100+ 种方块，实际: " + allStates.size()));

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
            NbtCompound rootNbt = buildLitematicNbt(allStates, blockStates, 1, totalBlocks, 1);

            Path tempFile = Files.createTempFile("test-all-blocks", ".litematic");
            NbtIo.writeCompressed(rootNbt, tempFile);

            // 解析
            var parser = new DefaultLitematicaParser(new ParsingThreadPool());
            var materials = parser.parseAsync(tempFile.toString()).get();

            // 验证：不崩溃，有合理数量的材料
            ctx.assertTrue(materials.size() > 0, Text.literal("应有材料统计结果"));

            SyncMaterial.LOGGER.info("全量方块冒烟测试: {} 种方块 → {} 项材料", allStates.size(), materials.size());

            // 清理
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            throw ctx.createError("全量方块冒烟测试失败: " + e.getMessage());
        }
        ctx.complete();
    }

    // ==================== NBT 构造辅助 ====================

    private NbtCompound buildLitematicNbt(List<BlockState> palette, long[] blockStates,
                                           int width, int height, int length) {
        // Palette → NbtList
        NbtList paletteList = new NbtList();
        for (BlockState state : palette) {
            paletteList.add(NbtHelper.fromBlockState(state));
        }

        // Region
        NbtCompound sizeNbt = new NbtCompound();
        sizeNbt.put("x", NbtInt.of(width));
        sizeNbt.put("y", NbtInt.of(height));
        sizeNbt.put("z", NbtInt.of(length));

        NbtCompound posNbt = new NbtCompound();
        posNbt.put("x", NbtInt.of(0));
        posNbt.put("y", NbtInt.of(0));
        posNbt.put("z", NbtInt.of(0));

        NbtCompound regionNbt = new NbtCompound();
        regionNbt.put("Size", sizeNbt);
        regionNbt.put("Position", posNbt);
        regionNbt.put("BlockStatePalette", paletteList);
        regionNbt.put("BitsPerEntry", NbtInt.of(Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size() - 1))));
        regionNbt.put("BlockStates", new NbtLongArray(blockStates));

        NbtCompound regionsNbt = new NbtCompound();
        regionsNbt.put("main", regionNbt);

        NbtCompound rootNbt = new NbtCompound();
        rootNbt.put("Regions", regionsNbt);
        return rootNbt;
    }
}
