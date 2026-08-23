package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;

/**
 * Litematica 解析器单元测试（GameTest 端到端的单测化 + 坏输入容错）。
 * NBT 构造是纯逻辑，无需服务器环境。
 */
public class LitematicaParserUnitTest {

    @TempDir
    Path tempDir;

    // 线程池是非 daemon 的，必须在 @AfterAll 显式关闭，否则测试 JVM 无法退出
    private static ParsingThreadPool pool;
    private static DefaultLitematicaParser parser;

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        pool = new ParsingThreadPool();
        parser = new DefaultLitematicaParser(pool);
    }

    @AfterAll
    static void tearDown() {
        pool.shutdown();
    }

    // ========== NBT 构造 ==========

    private static NbtCompound region(BlockState state, int width, int height, int length,
            int posX, int posY, int posZ, int bitsPerEntryOverride) {
        var palette = new NbtList();
        palette.add(NbtHelper.fromBlockState(state));

        // 1 种方块 → 全部索引 0 → long 数组全 0 即可
        long longCount = (width * height * length) / 64 + 1;
        NbtLongArray blockStates = new NbtLongArray(new long[(int) longCount]);

        var size = new NbtCompound();
        size.put("x", NbtInt.of(width));
        size.put("y", NbtInt.of(height));
        size.put("z", NbtInt.of(length));
        var pos = new NbtCompound();
        pos.put("x", NbtInt.of(posX));
        pos.put("y", NbtInt.of(posY));
        pos.put("z", NbtInt.of(posZ));

        var region = new NbtCompound();
        region.put("Size", size);
        region.put("Position", pos);
        region.put("BlockStatePalette", palette);
        if (bitsPerEntryOverride > 0) {
            region.put("BitsPerEntry", NbtInt.of(bitsPerEntryOverride));
        }
        region.put("BlockStates", blockStates);
        return region;
    }

    private Path writeSchematic(java.util.Map<String, NbtCompound> regions) throws Exception {
        var regionsNbt = new NbtCompound();
        for (var entry : regions.entrySet()) {
            regionsNbt.put(entry.getKey(), entry.getValue());
        }
        var root = new NbtCompound();
        root.put("Regions", regionsNbt);
        Path file = tempDir.resolve("unit-" + System.nanoTime() + ".litematic");
        NbtIo.writeCompressed(root, file);
        return file;
    }

    private static long countOf(List<MaterialEntry> materials, Item item) {
        for (var m : materials) {
            if (m.getStack().isOf(item)) return m.getCountTotal();
        }
        return 0;
    }

    // ========== 正常解析 ==========

    @Test
    void singleRegion_exactCount() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.getDefaultState(), 4, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(4, countOf(materials, Items.STONE));
    }

    @Test
    void multipleRegions_merged() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "a", region(Blocks.STONE.getDefaultState(), 2, 1, 1, 0, 0, 0, 0),
            "b", region(Blocks.DIAMOND_BLOCK.getDefaultState(), 3, 1, 1, 10, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(2, countOf(materials, Items.STONE));
        assertEquals(3, countOf(materials, Items.DIAMOND_BLOCK), "第二个区域应按 Position 偏移合并进来");
    }

    @Test
    void negativeSize_flippedStillCounted() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.getDefaultState(), -2, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(2, countOf(materials, Items.STONE), "负尺寸表示方向，取绝对值后仍应统计");
    }

    @Test
    void wrongBitsPerEntry_autoCorrected() throws Exception {
        // 故意写错的 bits（palette 只有 1 项，正确值为 1），解析器应自动修正
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.getDefaultState(), 2, 1, 1, 0, 0, 0, 20)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(2, countOf(materials, Items.STONE));
    }

    // ========== 坏输入容错 ==========

    @Test
    void missingFile_futureFails() {
        Path missing = tempDir.resolve("不存在.litematic");

        assertThrows(CompletionException.class,
            () -> parser.parseAsync(missing.toString()).join(),
            "文件不存在应以异常完成 future，而不是挂起或返回空");
    }

    @Test
    void garbageFile_futureFails() throws Exception {
        Path garbage = tempDir.resolve("garbage.litematic");
        Files.writeString(garbage, "这不是 gzip 也不是 NBT");

        assertThrows(CompletionException.class,
            () -> parser.parseAsync(garbage.toString()).join(),
            "损坏文件应以异常完成 future");
    }

    @Test
    void missingRegions_returnsEmptyList() throws Exception {
        var root = new NbtCompound();
        root.putString("MinecraftDataVersion", "未知格式");
        Path file = tempDir.resolve("no-regions.litematic");
        NbtIo.writeCompressed(root, file);

        var materials = parser.parseAsync(file.toString()).join();

        assertTrue(materials.isEmpty(), "缺 Regions 的 NBT 应返回空列表而非崩溃");
    }

    @Test
    void emptyRegionSize_skipped() throws Exception {
        // 尺寸为 0 的区域应跳过（warning 日志），不影响整体解析
        Path file = writeSchematic(java.util.Map.of(
            "zero", region(Blocks.STONE.getDefaultState(), 0, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertTrue(materials.isEmpty());
    }
}
