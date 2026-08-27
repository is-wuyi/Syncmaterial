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

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
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
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
        pool = new ParsingThreadPool();
        parser = new DefaultLitematicaParser(pool);
    }

    @AfterAll
    static void tearDown() {
        pool.shutdown();
    }

    // ========== NBT 构造 ==========

    private static CompoundTag region(net.minecraft.world.level.block.state.BlockState state, int width, int height, int length,
            int posX, int posY, int posZ, int bitsPerEntryOverride) {
        var palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state));

        // 1 种方块 → 全部索引 0 → long 数组全 0 即可
        long longCount = (width * height * length) / 64 + 1;
        LongArrayTag blockStates = new LongArrayTag(new long[(int) longCount]);

        var size = new CompoundTag();
        size.put("x", IntTag.valueOf(width));
        size.put("y", IntTag.valueOf(height));
        size.put("z", IntTag.valueOf(length));
        var pos = new CompoundTag();
        pos.put("x", IntTag.valueOf(posX));
        pos.put("y", IntTag.valueOf(posY));
        pos.put("z", IntTag.valueOf(posZ));

        var region = new CompoundTag();
        region.put("Size", size);
        region.put("Position", pos);
        region.put("BlockStatePalette", palette);
        if (bitsPerEntryOverride > 0) {
            region.put("BitsPerEntry", IntTag.valueOf(bitsPerEntryOverride));
        }
        region.put("BlockStates", blockStates);
        return region;
    }

    private Path writeSchematic(java.util.Map<String, CompoundTag> regions) throws Exception {
        var regionsNbt = new CompoundTag();
        for (var entry : regions.entrySet()) {
            regionsNbt.put(entry.getKey(), entry.getValue());
        }
        var root = new CompoundTag();
        root.put("Regions", regionsNbt);
        Path file = tempDir.resolve("unit-" + System.nanoTime() + ".litematic");
        NbtIo.writeCompressed(root, file);
        return file;
    }

    private static long countOf(List<MaterialEntry> materials, Item item) {
        for (var m : materials) {
            if (m.getStack().is(item)) return m.getCountTotal();
        }
        return 0;
    }

    // ========== 正常解析 ==========

    @Test
    void singleRegion_exactCount() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.defaultBlockState(), 4, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(4, countOf(materials, Items.STONE));
    }

    @Test
    void multipleRegions_merged() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "a", region(Blocks.STONE.defaultBlockState(), 2, 1, 1, 0, 0, 0, 0),
            "b", region(Blocks.DIAMOND_BLOCK.defaultBlockState(), 3, 1, 1, 10, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(2, countOf(materials, Items.STONE));
        assertEquals(3, countOf(materials, Items.DIAMOND_BLOCK), "第二个区域应按 Position 偏移合并进来");
    }

    @Test
    void negativeSize_flippedStillCounted() throws Exception {
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.defaultBlockState(), -2, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertEquals(2, countOf(materials, Items.STONE), "负尺寸表示方向，取绝对值后仍应统计");
    }

    @Test
    void wrongBitsPerEntry_autoCorrected() throws Exception {
        // 故意写错的 bits（palette 只有 1 项，正确值为 1），解析器应自动修正
        Path file = writeSchematic(java.util.Map.of(
            "main", region(Blocks.STONE.defaultBlockState(), 2, 1, 1, 0, 0, 0, 20)));

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
        var root = new CompoundTag();
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
            "zero", region(Blocks.STONE.defaultBlockState(), 0, 1, 1, 0, 0, 0, 0)));

        var materials = parser.parseAsync(file.toString()).join();

        assertTrue(materials.isEmpty());
    }
}
