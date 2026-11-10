/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * Region traversal, packed-palette counting, quota checks and material
 * aggregation follow the foundation decoder; the packed layout was aligned
 * with Litematica by masa (https://github.com/sakura-kyoko/litematica),
 * licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 */
package net.syncmaterial.shared.litematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Platform-independent .litematic material decoder.
 *
 * <p>Each region is decoded independently. Repeats and overlapping regions
 * are all counted; only the palette plus packed block-state array are read.
 * Tile entities are never decoded, therefore container contents are not
 * material rows. A malformed region fails the whole decode.</p>
 *
 * <p>The platform only supplies the native NBT view plus a palette/state
 * adapter; all region, quota, packed-bit and aggregation logic lives here.</p>
 */
public final class LitematicMaterialPipeline {
    private LitematicMaterialPipeline() {}

    public static <S> Map<String, Long> decode(NbtView root,
                                                  PaletteStateAdapter<S> adapter,
                                                  DecodeLimits limits)
            throws MalformedLitematicException {
        return decode(root, adapter, limits, null);
    }

    public static <S> Map<String, Long> decode(NbtView root,
                                                  PaletteStateAdapter<S> adapter,
                                                  DecodeLimits limits,
                                                  LitematicFormatPolicy formatPolicy)
            throws MalformedLitematicException {
        MaterialTotals totals = new MaterialTotals();
        decodeInto(root, adapter, limits, formatPolicy, totals);
        return totals.asMap();
    }

    public static <S> void decodeInto(NbtView root, PaletteStateAdapter<S> adapter,
                                      DecodeLimits limits, MaterialSink sink)
            throws MalformedLitematicException {
        decodeInto(root, adapter, limits, null, sink);
    }

    public static <S> void decodeInto(NbtView root, PaletteStateAdapter<S> adapter,
                                      DecodeLimits limits,
                                      LitematicFormatPolicy formatPolicy,
                                      MaterialSink sink)
            throws MalformedLitematicException {
        if (root == null || !root.isCompound()) {
            throw new MalformedLitematicException("Root NBT is not a compound");
        }
        if (formatPolicy != null) {
            formatPolicy.check(root);
        }

        NbtView regionsView = root.get("Regions");
        if (regionsView == null || !regionsView.isCompound()) {
            throw new MalformedLitematicException("Missing or non-compound Regions");
        }
        if (regionsView.size() == 0) {
            return;
        }
        if (regionsView.size() > limits.maxRegions()) {
            throw new MalformedLitematicException(
                    "Too many regions: " + regionsView.size());
        }

        long totalBlocks = 0L;

        for (String regionName : regionsView.keys()) {
            NbtView region = regionsView.get(regionName);
            if (region == null || !region.isCompound()) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' is not a compound");
            }

            long[] dimensions = readDimensions(region, regionName);
            long volume = multiplyChecked(dimensions[0], dimensions[1], regionName,
                    "size x*y");
            volume = multiplyChecked(volume, dimensions[2], regionName, "size x*y*z");
            if (volume > limits.maxTotalBlocks() - totalBlocks) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' volume " + volume
                                + " exceeds total cap " + limits.maxTotalBlocks()
                                + " (already counted " + totalBlocks + ")");
            }
            totalBlocks += volume;

            NbtView palette = requirePalette(region, regionName);
            int paletteSize = palette.size();
            if (paletteSize == 0) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' has an empty palette");
            }
            if (paletteSize > limits.maxPaletteEntries()) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' palette exceeds "
                                + limits.maxPaletteEntries() + " entries");
            }

            int bits = PackedPalette.canonicalBits(paletteSize);
            NbtView blockStatesView = requireBlockStates(region, regionName);
            long[] blockStates = blockStatesView.asLongArray();
            long expectedLongs = PackedPalette.expectedLongCount(volume, bits, regionName);
            if (blockStates.length != expectedLongs) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' BlockStates length is "
                                + blockStates.length + ", expected " + expectedLongs
                                + " (volume=" + volume + ", bits=" + bits + ")");
            }

            List<S> states = parsePalette(palette, adapter, regionName);
            int[] paletteCounts = new int[paletteSize];
            PackedPalette.count(blockStates, volume, bits, paletteSize,
                    paletteCounts, regionName);

            for (int i = 0; i < paletteSize; i++) {
                int count = paletteCounts[i];
                if (count == 0) {
                    continue;
                }
                BlockStateFacts facts = adapter.facts(states.get(i));
                if (facts == null) {
                    throw new IllegalStateException(
                            "Palette adapter returned null facts for region '"
                                    + regionName + "' entry " + i);
                }
                MaterialRules.resolve(facts, count, regionName, sink);
            }
        }
    }

    private static long[] readDimensions(NbtView region, String regionName)
            throws MalformedLitematicException {
        NbtView size = requireCompound(region, "Size", regionName);
        int x = requireInt(size, "x", regionName);
        int y = requireInt(size, "y", regionName);
        int z = requireInt(size, "z", regionName);

        // Litematica stores a signed Size and derives a positive extent from
        // Position/Size. Only the absolute extent matters for material rows.
        long width = absoluteDimension(x);
        long height = absoluteDimension(y);
        long length = absoluteDimension(z);
        if (width == 0L || height == 0L || length == 0L) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' has a zero-sized extent");
        }
        return new long[] {width, height, length};
    }

    private static long absoluteDimension(int value) {
        if (value == 0) {
            return 0L;
        }
        if (value == Integer.MIN_VALUE) {
            return 2_147_483_648L;
        }
        return Math.abs((long) value);
    }

    private static NbtView requirePalette(NbtView region, String regionName)
            throws MalformedLitematicException {
        NbtView palette = region.get("BlockStatePalette");
        if (palette == null) {
            // Older/legacy saves used the shorter name.
            palette = region.get("Palette");
        }
        if (palette == null || !palette.isList()) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' is missing a list palette");
        }
        return palette;
    }

    private static NbtView requireBlockStates(NbtView region, String regionName)
            throws MalformedLitematicException {
        NbtView blockStates = region.get("BlockStates");
        if (blockStates == null || !blockStates.isLongArray()) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' is missing a long-array BlockStates");
        }
        return blockStates;
    }

    private static <S> List<S> parsePalette(NbtView palette, PaletteStateAdapter<S> adapter,
                                            String regionName)
            throws MalformedLitematicException {
        List<S> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            NbtView entry = palette.get(i);
            NeutralBlockState state = readPaletteEntry(entry, regionName, i);
            states.add(adapter.parse(state, regionName, i));
        }
        return states;
    }

    private static NeutralBlockState readPaletteEntry(NbtView entry, String regionName,
                                                      int index)
            throws MalformedLitematicException {
        if (entry == null || !entry.isCompound()) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' palette[" + index
                            + "] is not a compound");
        }

        NbtView nameTag = entry.get("Name");
        String rawName = nameTag == null ? null : nameTag.asString();
        if (rawName == null) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' palette[" + index
                            + "] has no string Name");
        }
        if (!NbtValidation.isResourceLocation(rawName)) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' palette[" + index
                            + "] has an invalid block Name: " + rawName);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        NbtView propertiesTag = entry.get("Properties");
        if (propertiesTag != null) {
            if (!propertiesTag.isCompound()) {
                throw new MalformedLitematicException(
                        "Region '" + regionName + "' palette[" + index
                                + "] Properties is not a compound");
            }
            for (String property : propertiesTag.keys()) {
                if (!NbtValidation.isSafeDescriptorToken(property)) {
                    throw new MalformedLitematicException(
                            "Region '" + regionName + "' palette[" + index
                                    + "] has invalid property name '" + property + "'");
                }
                NbtView valueTag = propertiesTag.get(property);
                String value = valueTag == null ? null : valueTag.asString();
                if (value == null) {
                    throw new MalformedLitematicException(
                            "Region '" + regionName + "' palette[" + index
                                    + "] property '" + property + "' is not a string");
                }
                if (!NbtValidation.isSafeDescriptorToken(value)) {
                    throw new MalformedLitematicException(
                            "Region '" + regionName + "' palette[" + index
                                    + "] property '" + property
                                    + "' has invalid value '" + value + "'");
                }
                properties.put(property, value);
            }
        }
        return new NeutralBlockState(rawName, properties);
    }

    private static NbtView requireCompound(NbtView parent, String key, String context)
            throws MalformedLitematicException {
        NbtView tag = parent.get(key);
        if (tag == null || !tag.isCompound()) {
            throw new MalformedLitematicException(
                    "Missing or non-compound " + key + " in '" + context + "'");
        }
        return tag;
    }

    private static int requireInt(NbtView parent, String key, String context)
            throws MalformedLitematicException {
        NbtView tag = parent.get(key);
        Integer value = tag == null ? null : tag.asInt();
        if (value == null) {
            throw new MalformedLitematicException(
                    "Missing integer " + key + " in '" + context + "'");
        }
        return value;
    }

    private static long multiplyChecked(long left, long right, String regionName,
                                        String what)
            throws MalformedLitematicException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MalformedLitematicException(
                    "Region '" + regionName + "' " + what + " overflows", overflow);
        }
    }
}
