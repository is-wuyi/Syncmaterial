package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.engine.DefaultMaterialStatisticsEngine;

/**
 * 材料统计引擎（缓存 → 解析 → 统计流水线）测试：真实临时原理图驱动。
 */
public class MaterialStatisticsEngineTest {

    @TempDir
    Path tempDir;

    private DefaultMaterialStatisticsEngine engine;

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.shutdown();
    }

    /** 构造一个 count 个石头的一维原理图 */
    private Path writeStoneSchematic(int count) throws Exception {
        var root = new net.minecraft.nbt.NbtCompound();
        var palette = new net.minecraft.nbt.NbtList();
        palette.add(net.minecraft.nbt.NbtHelper.fromBlockState(Blocks.STONE.getDefaultState()));
        var size = new net.minecraft.nbt.NbtCompound();
        size.put("x", net.minecraft.nbt.NbtInt.of(count));
        size.put("y", net.minecraft.nbt.NbtInt.of(1));
        size.put("z", net.minecraft.nbt.NbtInt.of(1));
        var pos = new net.minecraft.nbt.NbtCompound();
        pos.put("x", net.minecraft.nbt.NbtInt.of(0));
        pos.put("y", net.minecraft.nbt.NbtInt.of(0));
        pos.put("z", net.minecraft.nbt.NbtInt.of(0));
        var region = new net.minecraft.nbt.NbtCompound();
        region.put("Size", size);
        region.put("Position", pos);
        region.put("BlockStatePalette", palette);
        region.put("BitsPerEntry", net.minecraft.nbt.NbtInt.of(1));
        region.put("BlockStates", new net.minecraft.nbt.NbtLongArray(new long[count / 64 + 1]));
        var regions = new net.minecraft.nbt.NbtCompound();
        regions.put("main", region);
        root.put("Regions", regions);

        Path file = tempDir.resolve("engine-" + count + ".litematic");
        net.minecraft.nbt.NbtIo.writeCompressed(root, file);
        return file;
    }

    private static long stoneCount(List<net.syncmaterial.syncmaterial.api.MaterialEntry> materials) {
        for (var m : materials) {
            if (m.getStack().isOf(Items.STONE)) return m.getCountTotal();
        }
        return 0;
    }

    @Test
    void parsesAndCaches_secondCallReturnsSameInstance() throws Exception {
        engine = new DefaultMaterialStatisticsEngine();
        Path file = writeStoneSchematic(5);

        var first = engine.requestMaterialsAsync(file.toString()).join();
        var second = engine.requestMaterialsAsync(file.toString()).join();

        assertEquals(5, stoneCount(first));
        assertSame(first, second, "文件未变时第二次应命中缓存返回同一实例");
    }

    @Test
    void invalidateCache_forcesReparse() throws Exception {
        engine = new DefaultMaterialStatisticsEngine();
        Path file = writeStoneSchematic(3);

        var first = engine.requestMaterialsAsync(file.toString()).join();
        engine.invalidateCache(file.toString());
        var second = engine.requestMaterialsAsync(file.toString()).join();

        assertEquals(3, stoneCount(first));
        assertNotSame(first, second, "失效后应重新解析");
        assertEquals(3, stoneCount(second));
    }

    @Test
    void clearAllCaches_forcesReparse() throws Exception {
        engine = new DefaultMaterialStatisticsEngine();
        Path file = writeStoneSchematic(2);

        var first = engine.requestMaterialsAsync(file.toString()).join();
        engine.clearAllCaches();
        var second = engine.requestMaterialsAsync(file.toString()).join();

        assertNotSame(first, second);
        assertEquals(2, stoneCount(second));
    }

    @Test
    void differentFiles_cachedIndependently() throws Exception {
        engine = new DefaultMaterialStatisticsEngine();
        Path small = writeStoneSchematic(2);
        Path large = writeStoneSchematic(9);

        assertEquals(2, stoneCount(engine.requestMaterialsAsync(small.toString()).join()));
        assertEquals(9, stoneCount(engine.requestMaterialsAsync(large.toString()).join()));
    }
}
