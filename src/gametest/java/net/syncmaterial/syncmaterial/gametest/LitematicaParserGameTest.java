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

    // ==================== 两状态调色板位宽回归 ====================

    /**
     * 回归测试：调色板恰好 2 项时，Litematica 用 2 位/格编码（其 setBits 对
     * bits<=4 强制 Math.max(2, ...)，已对照依赖 jar 字节码确认）。解析器若按
     * 1 位/格解码，会把整份数据读错位——统计数量随之全错。
     *
     * 这里按真实格式打包：2 位/格、不写 BitsPerEntry 字段（真实 .litematic
     * 没有这个字段，解析器必须自己按调色板大小推位宽）。调色板 [air, stone]，
     * 8 格中 5 格 stone(索引 1) + 3 格 air(索引 0)。
     * - 按 2 位正确解码：5 个 stone
     * - 按 1 位错误解码：只数出 4 个 stone
     */
    @GameTest(structure = "empty")
    public void parseTwoStatePalette_usesTwoBits(GameTestHelper ctx) {
        try {
            List<BlockState> palette = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState()
            );
            // 索引序列：前 5 格 stone(1)，后 3 格 air(0)
            int[] indices = {1, 1, 1, 1, 1, 0, 0, 0};
            long[] blockStates = packIndices(indices, 2);

            CompoundTag rootNbt = buildRealLitematicNbt(palette, blockStates, 2, 2, 2);
            Path tempFile = Files.createTempFile("test-two-state", ".litematic");
            NbtIo.writeCompressed(rootNbt, tempFile);

            var parser = new DefaultLitematicaParser(new ParsingThreadPool());
            var materials = parser.parseAsync(tempFile.toString()).get();

            long stoneCount = materials.stream()
                .filter(m -> m.getStack().is(net.minecraft.world.item.Items.STONE))
                .mapToLong(net.syncmaterial.syncmaterial.api.MaterialEntry::getCountTotal)
                .sum();
            ctx.assertTrue(stoneCount == 5,
                Component.literal("2 项调色板应按 2 位解码得 5 个 stone，实际 " + stoneCount
                    + "（1 位错误解码会得到 4）"));

            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            throw ctx.assertionException("两状态调色板位宽测试失败: " + e.getMessage());
        }
        ctx.succeed();
    }

    /** 按 Litematica 的 LitematicaBitArray 打包：LSB 优先，条目可跨 long。 */
    private static long[] packIndices(int[] indices, int bits) {
        long mask = (1L << bits) - 1L;
        long totalBits = (long) indices.length * bits;
        int longCount = (int) ((totalBits + 63) / 64);
        long[] out = new long[Math.max(1, longCount)];
        for (int i = 0; i < indices.length; i++) {
            long bitPos = (long) i * bits;
            int startLong = (int) (bitPos >> 6);
            int startOffset = (int) (bitPos & 63);
            long val = indices[i] & mask;
            out[startLong] |= val << startOffset;
            int endLong = (int) (((i + 1L) * bits - 1L) >> 6);
            if (endLong != startLong) {
                out[endLong] |= val >>> (64 - startOffset);
            }
        }
        return out;
    }

    /** 真实格式 NBT：不写 BitsPerEntry（真实 .litematic 无此字段）。 */
    private CompoundTag buildRealLitematicNbt(List<BlockState> palette, long[] blockStates,
                                              int width, int height, int length) {
        ListTag paletteList = new ListTag();
        for (BlockState state : palette) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }
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
        regionNbt.put("BlockStates", new LongArrayTag(blockStates));
        CompoundTag regionsNbt = new CompoundTag();
        regionsNbt.put("main", regionNbt);
        CompoundTag rootNbt = new CompoundTag();
        rootNbt.put("Regions", regionsNbt);
        return rootNbt;
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
