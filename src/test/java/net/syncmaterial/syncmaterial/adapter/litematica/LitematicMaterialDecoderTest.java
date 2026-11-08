package net.syncmaterial.syncmaterial.adapter.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicMaterialDecoderTest {
    @TempDir
    Path tempDir;

    private final LitematicMaterialDecoder decoder = new LitematicMaterialDecoder();

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    // ========== basic region/palette decoding ==========

    @Test
    void singlePaletteDecodesAllEntries() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        Path file = writeSchematic("single", region(2, 2, 2, paletteOf(stone),
                new int[8], null, true));

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 8L), counts);
    }

    @Test
    void twoPalettePackingIsCanonicalEvenWithBitsPerEntryZero() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        Path file = writeSchematic("two-palette", region(2, 2, 1,
                paletteOf(stone, cobble), new int[] {0, 1, 0, 1}, 0, true));

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 2L, itemId(Blocks.COBBLESTONE), 2L),
                counts);
    }

    @Test
    void twoPaletteWithNonCanonicalBitsFieldStillUsesPaletteWidth() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        Path file = writeSchematic("bits-ignored", region(2, 1, 1,
                paletteOf(stone, cobble), new int[] {0, 1}, 15, true));

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 1L, itemId(Blocks.COBBLESTONE), 1L),
                counts);
    }

    @Test
    void highPaletteDecodesAllIndices() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState[] paletteStates = new BlockState[70];
        int[] indices = new int[70];
        for (int i = 0; i < paletteStates.length; i++) {
            paletteStates[i] = stone;
            indices[i] = i;
        }
        Path file = writeSchematic("high-palette", region(70, 1, 1,
                paletteOf(paletteStates), indices, null, true));

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 70L), counts);
    }

    @Test
    void multipleRegionsSumEvenWhenTheyOverlap() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        CompoundTag first = region(2, 2, 2, paletteOf(stone), new int[8], null, true);
        CompoundTag second = region(2, 2, 2, paletteOf(stone), new int[8], null, true);
        Path file = writeSchematic("overlap", first, second);

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 16L), counts);
    }

    @Test
    void negativeSizeUsesAbsoluteExtent() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        Path file = writeSchematic("negative", region(-2, -3, -4,
                paletteOf(stone), new int[24], null, true));

        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 24L), counts);
    }

    @Test
    void airOnlyPaletteReturnsEmptyList() throws Exception {
        BlockState air = Blocks.AIR.defaultBlockState();
        Path file = writeSchematic("air", region(4, 4, 4, paletteOf(air),
                new int[64], null, true));

        assertTrue(decoder.decode(file).isEmpty());
    }

    @Test
    void tileEntityContainerContentsAreNotMaterialRows() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        CompoundTag region = region(1, 1, 1, paletteOf(stone), new int[1], null, true);
        ListTag tileEntities = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        CompoundTag diamond = new CompoundTag();
        diamond.putString("id", "minecraft:diamond");
        diamond.putInt("count", 64);
        diamond.putInt("Slot", 0);
        items.add(diamond);
        chest.put("Items", items);
        tileEntities.add(chest);
        region.put("TileEntities", tileEntities);

        Path file = writeSchematic("container", region);
        Map<String, Long> counts = byItem(decoder.decode(file));
        assertEquals(Map.of(itemId(Blocks.STONE), 1L), counts);
    }

    // ========== material rules ==========

    @Test
    void wallAndHangingSignsUseTheStandingItemId() {
        long wallSign = countOf(MaterialResolver.resolve(
                Blocks.OAK_WALL_SIGN.defaultBlockState(), 1), "minecraft:oak_sign");
        long standingSign = countOf(MaterialResolver.resolve(
                Blocks.OAK_SIGN.defaultBlockState(), 1), "minecraft:oak_sign");
        long wallHanging = countOf(MaterialResolver.resolve(
                Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState(), 1),
                "minecraft:oak_hanging_sign");
        long hanging = countOf(MaterialResolver.resolve(
                Blocks.OAK_HANGING_SIGN.defaultBlockState(), 1),
                "minecraft:oak_hanging_sign");

        assertEquals(1L, wallSign);
        assertEquals(1L, standingSign);
        assertEquals(1L, wallHanging);
        assertEquals(1L, hanging);

        // Two wall variants of the same family must accumulate to 2.
        long combined = countOf(MaterialResolver.resolve(
                Blocks.OAK_WALL_SIGN.defaultBlockState(), 2), "minecraft:oak_sign");
        assertEquals(2L, combined);
    }

    @Test
    void multiplierRulesAndWaterlogged() {
        BlockState doubleSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        assertEquals(10L, countOf(MaterialResolver.resolve(doubleSlab, 5),
                itemId(Blocks.OAK_SLAB)));

        BlockState snow = Blocks.SNOW.defaultBlockState()
                .setValue(BlockStateProperties.LAYERS, 3);
        assertEquals(12L, countOf(MaterialResolver.resolve(snow, 4), "minecraft:snow"));

        BlockState waterlogged = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        Map<String, Long> counts = byItem(MaterialResolver.resolve(waterlogged, 2));
        assertEquals(2L, counts.get("minecraft:water_bucket"));
        assertEquals(2L, counts.get(itemId(Blocks.OAK_STAIRS)));

        assertEquals(1L, countOf(MaterialResolver.resolve(
                Blocks.FARMLAND.defaultBlockState(), 1), "minecraft:dirt"));
        assertTrue(MaterialResolver.resolve(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 1).isEmpty());
    }

    // ========== malformed input ==========

    @Test
    void missingRegionsFails() throws Exception {
        Path file = writeRoot("missing-regions", new CompoundTag());
        assertMalformed(file);
    }

    @Test
    void missingSizeFails() throws Exception {
        CompoundTag region = region(2, 1, 1, paletteOf(Blocks.STONE.defaultBlockState()),
                new int[2], null, true);
        region.remove("Size");
        assertMalformed(writeSchematic("missing-size", region));
    }

    @Test
    void missingPaletteFails() throws Exception {
        CompoundTag region = region(2, 1, 1, paletteOf(Blocks.STONE.defaultBlockState()),
                new int[2], null, true);
        region.remove("BlockStatePalette");
        assertMalformed(writeSchematic("missing-palette", region));
    }

    @Test
    void missingBlockStatesFails() throws Exception {
        CompoundTag region = region(2, 1, 1, paletteOf(Blocks.STONE.defaultBlockState()),
                new int[2], null, true);
        region.remove("BlockStates");
        assertMalformed(writeSchematic("missing-blockstates", region));
    }

    @Test
    void canonicalBadBlockStatesLengthFails() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        CompoundTag region = region(2, 1, 1, paletteOf(stone, cobble),
                new int[] {0, 1}, null, true);
        region.put("BlockStates", new LongArrayTag(new long[0]));
        assertMalformed(writeSchematic("bad-short", region));

        CompoundTag longer = region(2, 1, 1, paletteOf(stone, cobble),
                new int[] {0, 1}, null, true);
        longer.put("BlockStates", new LongArrayTag(new long[2]));
        assertMalformed(writeSchematic("bad-long", longer));
    }

    @Test
    void volumeQuotaFailsBeforeAllocatingStorage() throws Exception {
        CompoundTag size = new CompoundTag();
        size.put("x", IntTag.valueOf(4096));
        size.put("y", IntTag.valueOf(4096));
        size.put("z", IntTag.valueOf(4096));
        CompoundTag region = new CompoundTag();
        region.put("Size", size);
        assertMalformed(writeSchematic("volume-quota", region));
    }

    @Test
    void unknownBlockFails() throws Exception {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "minecraft:definitely_not_a_block");
        assertMalformed(writeSchematic("unknown-block",
                regionWithRawPalette(1, 1, 1, List.of(entry), new int[1])));
    }

    @Test
    void invalidStateValueFails() throws Exception {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "minecraft:oak_stairs");
        CompoundTag properties = new CompoundTag();
        properties.putString("facing", "bogus");
        entry.put("Properties", properties);
        assertMalformed(writeSchematic("bad-value",
                regionWithRawPalette(1, 1, 1, List.of(entry), new int[1])));
    }

    @Test
    void injectedNameAndPropertySyntaxFails() throws Exception {
        CompoundTag injectedName = new CompoundTag();
        injectedName.putString("Name", "minecraft:stone[foo]");
        assertMalformed(writeSchematic("injected-name",
                regionWithRawPalette(1, 1, 1, List.of(injectedName), new int[1])));

        CompoundTag injectedProperty = new CompoundTag();
        injectedProperty.putString("Name", "minecraft:stone");
        CompoundTag properties = new CompoundTag();
        properties.putString("foo]", "bar");
        injectedProperty.put("Properties", properties);
        assertMalformed(writeSchematic("injected-property",
                regionWithRawPalette(1, 1, 1, List.of(injectedProperty), new int[1])));
    }

    @Test
    void outOfRangePaletteIndexFails() throws Exception {
        CompoundTag region = regionWithRawPalette(2, 1, 1,
                List.of(paletteEntry(Blocks.STONE.defaultBlockState())),
                new int[] {0, 1});
        assertMalformed(writeSchematic("bad-index", region));
    }

    private void assertMalformed(Path file) {
        assertThrows(LitematicMaterialDecoder.MalformedSchematicException.class,
                () -> decoder.decode(file));
    }

    // ========== NBT helpers ==========

    private Path writeSchematic(String name, CompoundTag... regions) throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag regionList = new CompoundTag();
        for (int i = 0; i < regions.length; i++) {
            regionList.put("r" + i, regions[i]);
        }
        root.put("Regions", regionList);
        return writeRoot(name, root);
    }

    private Path writeRoot(String name, CompoundTag root) throws Exception {
        Path file = tempDir.resolve(name + "-" + System.nanoTime() + ".litematic");
        NbtIo.writeCompressed(root, file);
        assertTrue(Files.isRegularFile(file));
        return file;
    }

    private static CompoundTag region(int sx, int sy, int sz, ListTag palette,
                                      int[] indices, Integer bitsField, boolean withPosition)
            throws Exception {
        int volume = Math.abs(sx) * Math.abs(sy) * Math.abs(sz);
        if (indices.length != volume) {
            throw new IllegalArgumentException("test indices must match region volume");
        }
        int bits = canonicalBits(palette.size());
        return regionWithRawPalette(sx, sy, sz, rawEntries(palette), indices,
                bitsField, withPosition, pack(indices, bits));
    }

    private static CompoundTag regionWithRawPalette(int sx, int sy, int sz,
                                                     List<CompoundTag> palette,
                                                     int[] indices) throws Exception {
        int bits = canonicalBits(palette.size());
        return regionWithRawPalette(sx, sy, sz, palette, indices, null,
                true, pack(indices, bits));
    }

    private static CompoundTag regionWithRawPalette(int sx, int sy, int sz,
                                                     List<CompoundTag> palette,
                                                     int[] indices, Integer bitsField,
                                                     boolean withPosition,
                                                     long[] storage) {
        CompoundTag size = new CompoundTag();
        size.put("x", IntTag.valueOf(sx));
        size.put("y", IntTag.valueOf(sy));
        size.put("z", IntTag.valueOf(sz));

        CompoundTag region = new CompoundTag();
        region.put("Size", size);
        if (withPosition) {
            CompoundTag position = new CompoundTag();
            position.put("x", IntTag.valueOf(0));
            position.put("y", IntTag.valueOf(0));
            position.put("z", IntTag.valueOf(0));
            region.put("Position", position);
        }
        ListTag paletteTag = new ListTag();
        for (CompoundTag entry : palette) {
            paletteTag.add(entry);
        }
        region.put("BlockStatePalette", paletteTag);
        if (bitsField != null) {
            region.put("BitsPerEntry", IntTag.valueOf(bitsField));
        }
        region.put("BlockStates", new LongArrayTag(storage));
        return region;
    }

    private static ListTag paletteOf(BlockState... states) {
        ListTag palette = new ListTag();
        for (BlockState state : states) {
            palette.add(NbtUtils.writeBlockState(state));
        }
        return palette;
    }

    private static List<CompoundTag> rawEntries(ListTag palette) {
        List<CompoundTag> entries = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            entries.add((CompoundTag) palette.get(i));
        }
        return entries;
    }

    private static CompoundTag paletteEntry(BlockState state) {
        return NbtUtils.writeBlockState(state);
    }

    private static int canonicalBits(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static long[] pack(int[] indices, int bits) {
        long[] storage = new long[(int) (((long) indices.length * bits + 63L) / 64L)];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < indices.length; i++) {
            long offset = (long) i * bits;
            int start = (int) (offset >>> 6);
            int startBit = (int) (offset & 63L);
            long value = indices[i] & mask;
            storage[start] |= value << startBit;
            if (startBit + bits > 64) {
                storage[start + 1] |= value >>> (64 - startBit);
            }
        }
        return storage;
    }

    private static Map<String, Long> byItem(List<MaterialCount> materials) {
        Map<String, Long> counts = new HashMap<>();
        for (MaterialCount material : materials) {
            counts.merge(material.itemId(), material.count(), Long::sum);
        }
        return counts;
    }

    private static long countOf(List<MaterialCount> materials, String itemId) {
        return byItem(materials).getOrDefault(itemId, 0L);
    }

    private static String itemId(Block block) {
        return itemId(block.asItem());
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
