/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LitematicMaterialPipelineTest {
    private static final DecodeLimits LIMITS = DecodeLimits.DEFAULT;

    @Test
    void singlePaletteDecodesAllEntries() throws Exception {
        NbtView root = schematic(region("single", 2, 2, 2,
                List.of(palette("minecraft:stone")), new int[8]));

        assertEquals(Map.of("minecraft:stone", 8L),
                LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS));
    }

    @Test
    void twoPalettePackingIsCanonical() throws Exception {
        NbtView root = schematic(region("two", 2, 2, 1,
                List.of(palette("minecraft:stone"), palette("minecraft:cobblestone")),
                new int[] {0, 1, 0, 1}));

        assertEquals(Map.of("minecraft:stone", 2L, "minecraft:cobblestone", 2L),
                LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS));
    }

    @Test
    void highPaletteCrossesLongBoundaries() throws Exception {
        List<Compound> entries = new ArrayList<>();
        int[] indices = new int[70];
        for (int i = 0; i < 70; i++) {
            entries.add(palette("minecraft:stone"));
            indices[i] = i;
        }
        NbtView root = schematic(region("wide", 70, 1, 1, entries, indices));

        assertEquals(Map.of("minecraft:stone", 70L),
                LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS));
    }

    @Test
    void multipleRegionsSumEvenWhenTheyOverlap() throws Exception {
        NbtView first = region("a", 2, 2, 2,
                List.of(palette("minecraft:stone")), new int[8]).toNbtView();
        NbtView second = region("b", 2, 2, 2,
                List.of(palette("minecraft:stone")), new int[8]).toNbtView();

        assertEquals(Map.of("minecraft:stone", 16L),
                LitematicMaterialPipeline.decode(schematic(first, second),
                        Adapter.INSTANCE, LIMITS));
    }

    @Test
    void negativeSizeUsesAbsoluteExtent() throws Exception {
        NbtView root = schematic(region("negative", -2, -3, -4,
                List.of(palette("minecraft:stone")), new int[24]));

        assertEquals(Map.of("minecraft:stone", 24L),
                LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS));
    }

    @Test
    void airOnlyPaletteReturnsEmptyList() throws Exception {
        NbtView root = schematic(region("air", 4, 4, 4,
                List.of(palette("minecraft:air")), new int[64]));

        assertTrue(LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS)
                .isEmpty());
    }

    @Test
    void tileEntitiesAreIgnored() throws Exception {
        Compound region = region("container", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]);
        List<NbtView> tileEntities = new ArrayList<>();
        tileEntities.add(TestNbt.compound()
                .put("id", "minecraft:chest")
                .put("Items", TestNbt.list(List.of(TestNbt.compound()
                        .put("id", "minecraft:diamond")
                        .put("count", 64)
                        .put("Slot", 0).build())))
                .build());
        region.put("TileEntities", TestNbt.list(tileEntities));

        assertEquals(Map.of("minecraft:stone", 1L),
                LitematicMaterialPipeline.decode(schematic(region.toNbtView()),
                        Adapter.INSTANCE, LIMITS));
    }

    @Test
    void materialCountsAggregateAcrossRegionsWithExactArithmetic() throws Exception {
        NbtView first = region("a", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]).toNbtView();
        NbtView second = region("b", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]).toNbtView();

        assertEquals(Map.of("minecraft:stone", 2L),
                LitematicMaterialPipeline.decode(schematic(first, second),
                        Adapter.INSTANCE, LIMITS));
    }

    // ===== malformed input =====

    @Test
    void missingRegionsFails() {
        NbtView root = TestNbt.compound().build();
        assertMalformed(root);
    }

    @Test
    void missingSizeFails() {
        Compound region = region("r", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]);
        region.remove("Size");
        assertMalformed(schematic(region.toNbtView()));
    }

    @Test
    void missingPaletteFails() {
        Compound region = region("r", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]);
        region.remove("BlockStatePalette");
        assertMalformed(schematic(region.toNbtView()));
    }

    @Test
    void missingBlockStatesFails() {
        Compound region = region("r", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]);
        region.remove("BlockStates");
        assertMalformed(schematic(region.toNbtView()));
    }

    @Test
    void badBlockStatesLengthFails() {
        Compound shortArray = region("short", 2, 1, 1,
                List.of(palette("minecraft:stone"), palette("minecraft:cobblestone")),
                new int[] {0, 1});
        shortArray.put("BlockStates", TestNbt.longs(new long[0]));
        assertMalformed(schematic(shortArray.toNbtView()));

        Compound longArray = region("long", 2, 1, 1,
                List.of(palette("minecraft:stone"), palette("minecraft:cobblestone")),
                new int[] {0, 1});
        longArray.put("BlockStates", TestNbt.longs(new long[2]));
        assertMalformed(schematic(longArray.toNbtView()));
    }

    @Test
    void volumeQuotaFailsBeforeReadingPalette() {
        Compound region = new Compound();
        region.put("Size", TestNbt.compound()
                .put("x", 4096).put("y", 4096).put("z", 4096).build());
        assertMalformed(schematic(region.toNbtView()));
    }

    @Test
    void regionAndPaletteCountLimitsFail() {
        NbtView twoRegions = schematic(
                region("a", 1, 1, 1, List.of(palette("minecraft:stone")), new int[1]),
                region("b", 1, 1, 1, List.of(palette("minecraft:stone")), new int[1]));
        assertThrows(MalformedLitematicException.class, () ->
                LitematicMaterialPipeline.decode(twoRegions, Adapter.INSTANCE,
                        new DecodeLimits(100L, 1, 1024)));

        NbtView twoPalette = schematic(region("a", 2, 1, 1,
                List.of(palette("minecraft:stone"), palette("minecraft:cobblestone")),
                new int[] {0, 1}));
        assertThrows(MalformedLitematicException.class, () ->
                LitematicMaterialPipeline.decode(twoPalette, Adapter.INSTANCE,
                        new DecodeLimits(100L, 1024, 1)));
    }

    @Test
    void outOfRangePaletteIndexFails() {
        NbtView root = schematic(region("bad-index", 2, 1, 1,
                List.of(palette("minecraft:stone")), new int[] {0, 1}));
        assertMalformed(root);
    }

    @Test
    void missingFormatVersionIsAcceptedButNewerVersionsFail() throws Exception {
        NbtView missingVersion = schematic(region("a", 1, 1, 1,
                List.of(palette("minecraft:stone")), new int[1]));
        LitematicFormatPolicy policy = LitematicFormatPolicy.forCurrentVersion(4000);
        // Synthetic/legacy fixtures without Version still decode.
        assertEquals(Map.of("minecraft:stone", 1L),
                LitematicMaterialPipeline.decode(missingVersion, Adapter.INSTANCE,
                        LIMITS, policy));

        NbtView futureDataVersion = TestNbt.compound()
                .put("MinecraftDataVersion", 5000)
                .put("Version", 7)
                .put("Regions", missingVersion.get("Regions"))
                .build();
        assertThrows(MalformedLitematicException.class, () ->
                LitematicMaterialPipeline.decode(futureDataVersion, Adapter.INSTANCE,
                        LIMITS, policy));

        NbtView futureSchema = TestNbt.compound()
                .put("Version", 8)
                .put("Regions", missingVersion.get("Regions"))
                .build();
        assertThrows(MalformedLitematicException.class, () ->
                LitematicMaterialPipeline.decode(futureSchema, Adapter.INSTANCE,
                        LIMITS, policy));

        NbtView toleratedGap = TestNbt.compound()
                .put("MinecraftDataVersion", 4099)
                .put("Version", 7)
                .put("Regions", missingVersion.get("Regions"))
                .build();
        assertEquals(Map.of("minecraft:stone", 1L),
                LitematicMaterialPipeline.decode(toleratedGap, Adapter.INSTANCE,
                        LIMITS, policy));
    }

    @Test
    void invalidBlockNameAndUnsafePropertyFail() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("foo]", "bar");
        List<Compound> entries = List.of(new Compound(
                "minecraft:stone", properties));

        Compound region = regionWithEntries(1, 1, 1, entries, new int[1]);
        assertMalformed(schematic(region.toNbtView()));

        Compound injectedName = regionWithEntries(1, 1, 1,
                List.of(new Compound("minecraft:stone[foo]", Map.of())), new int[1]);
        assertMalformed(schematic(injectedName.toNbtView()));
    }

    private static void assertMalformed(NbtView root) {
        assertThrows(MalformedLitematicException.class, () ->
                LitematicMaterialPipeline.decode(root, Adapter.INSTANCE, LIMITS));
    }

    // ===== fixtures =====

    private static NbtView schematic(Compound... regions) {
        Compound regionList = new Compound();
        for (int i = 0; i < regions.length; i++) {
            regionList.put("r" + i, regions[i].toNbtView());
        }
        return TestNbt.compound().put("Regions", regionList.toNbtView()).build();
    }

    private static NbtView schematic(NbtView... regions) {
        Compound regionList = new Compound();
        for (int i = 0; i < regions.length; i++) {
            regionList.put("r" + i, regions[i]);
        }
        return TestNbt.compound().put("Regions", regionList.toNbtView()).build();
    }

    private static Compound region(String name, int sx, int sy, int sz,
                                   List<Compound> paletteEntries, int[] indices) {
        return regionWithEntries(sx, sy, sz, paletteEntries, indices);
    }

    private static Compound regionWithEntries(int sx, int sy, int sz,
                                              List<Compound> paletteEntries,
                                              int[] indices) {
        int volume = Math.abs(sx) * Math.abs(sy) * Math.abs(sz);
        if (indices.length != volume) {
            throw new IllegalArgumentException("indices must match volume");
        }
        int bits = PackedPalette.canonicalBits(paletteEntries.size());
        List<NbtView> paletteList = new ArrayList<>();
        for (Compound entry : paletteEntries) {
            paletteList.add(entry.toNbtView());
        }

        Compound region = new Compound();
        region.put("Size", TestNbt.compound()
                .put("x", sx).put("y", sy).put("z", sz).build());
        region.put("BlockStatePalette", TestNbt.list(paletteList));
        region.put("BlockStates", TestNbt.longs(pack(indices, bits)));
        return region;
    }

    private static Compound palette(String blockId) {
        return new Compound(blockId, Map.of());
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

    private record Compound(Map<String, NbtView> children) {
        Compound() {
            this(new LinkedHashMap<>());
        }

        Compound(String blockId, Map<String, String> properties) {
            this(new LinkedHashMap<>());
            children.put("Name", TestNbt.string(blockId));
            if (!properties.isEmpty()) {
                Compound propertyTag = new Compound();
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    propertyTag.put(entry.getKey(), TestNbt.string(entry.getValue()));
                }
                children.put("Properties", propertyTag.toNbtView());
            }
        }

        void put(String key, NbtView value) {
            children.put(key, value);
        }

        void remove(String key) {
            children.remove(key);
        }

        NbtView toNbtView() {
            return TestNbt.compoundOf(children);
        }
    }

    private enum Adapter implements PaletteStateAdapter<String> {
        INSTANCE;

        @Override
        public String parse(NeutralBlockState state, String regionName, int paletteIndex) {
            return state.blockId();
        }

        @Override
        public BlockStateFacts facts(String blockId) {
            if ("minecraft:air".equals(blockId)) {
                return BlockStateFacts.none();
            }
            return new BlockStateFacts(BlockStateFacts.Kind.ORDINARY,
                    blockId, null, 1L, 0, null);
        }
    }
}
