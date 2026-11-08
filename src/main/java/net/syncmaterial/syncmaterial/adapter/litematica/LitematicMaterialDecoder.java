/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * Packed-palette layout and the canonical palette bit-width rule follow
 * Litematica by masa (https://github.com/sakura-kyoko/litematica), licensed
 * under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * Inspected upstream: Litematica 0.28.5, jar id Fhq3KCI8, classes
 * LitematicaBlockStateContainer and LitematicaBitArray. Modified for
 * SyncMaterial: standalone NBT decoding, hard resource limits and explicit
 * malformed-input failures.
 */
package net.syncmaterial.syncmaterial.adapter.litematica;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.syncmaterial.syncmaterial.core.MaterialCount;

/**
 * Foundation-backend material decoder for current Litematica (.litematic)
 * files.
 *
 * <p>The decoder is intentionally independent of Litematica and MaLiLib and
 * only uses Minecraft's NBT/registry APIs, so it also works on a dedicated
 * server where neither optional client mod is installed.</p>
 *
 * <p>Counts follow Litematica 0.28.5's material list: every region is decoded
 * independently, repeats and overlapping regions are all counted (there is no
 * block-position de-duplication), and only the palette plus packed block-state
 * array are read. Tile entities are never decoded, therefore container
 * contents are not material rows.</p>
 *
 * <h2>Validation</h2>
 * Missing or malformed {@code Regions}, region {@code Size},
 * {@code BlockStatePalette} and {@code BlockStates} fail the whole decode.
 * Unknown blocks and invalid state properties also fail. A legitimate all-air
 * palette returns an empty list. The packed array must have exactly the length
 * implied by the canonical bit width and region volume; extra or missing longs
 * are rejected. A non-canonical {@code BitsPerEntry} field is ignored, because
 * Litematica derives the width from the palette size.
 */
public final class LitematicMaterialDecoder implements MaterialDecoder {
    /** NBT decompression/parse budget, matching the legacy parser's guard. */
    public static final long MAX_NBT_BYTES = 512L * 1024L * 1024L;

    /** Hard cap across all regions; overlapping regions still sum into this. */
    public static final long MAX_TOTAL_BLOCKS = 64_000_000L;

    /** Bound on region iteration for hostile, tiny-region files. */
    public static final int MAX_REGIONS = 65_536;

    /** Bound on palette entries; vanilla/modded palettes are far smaller. */
    public static final int MAX_PALETTE_ENTRIES = 1_048_576;

    /**
     * Decode one schematic. This method is synchronous; callers own scheduling.
     */
    @Override
    public List<MaterialCount> decode(Path schematicPath) throws Exception {
        Objects.requireNonNull(schematicPath, "schematicPath");
        if (!Files.isRegularFile(schematicPath)) {
            throw new IOException("Schematic is not a regular file: " + schematicPath);
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(schematicPath,
                    NbtAccounter.create(MAX_NBT_BYTES));
        } catch (NbtException quotaOrFormat) {
            throw new MalformedSchematicException(
                    "Invalid or over-quota NBT in " + schematicPath, quotaOrFormat);
        }

        Tag regionsTag = root.get("Regions");
        if (!(regionsTag instanceof CompoundTag regions)) {
            throw new MalformedSchematicException("Missing or non-compound Regions");
        }
        if (regions.isEmpty()) {
            return List.of();
        }
        if (regions.size() > MAX_REGIONS) {
            throw new MalformedSchematicException("Too many regions: " + regions.size());
        }

        Map<String, Long> totals = new TreeMap<>();
        long totalBlocks = 0L;

        for (String regionName : regions.keySet()) {
            Tag regionTag = regions.get(regionName);
            if (!(regionTag instanceof CompoundTag region)) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' is not a compound");
            }

            long[] dimensions = readDimensions(region, regionName);
            long volume = multiplyChecked(dimensions[0], dimensions[1], regionName, "size x*y");
            volume = multiplyChecked(volume, dimensions[2], regionName, "size x*y*z");
            if (volume > MAX_TOTAL_BLOCKS - totalBlocks) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' volume " + volume
                                + " exceeds total cap " + MAX_TOTAL_BLOCKS
                                + " (already counted " + totalBlocks + ")");
            }
            totalBlocks += volume;

            ListTag palette = requirePalette(region, regionName);
            int paletteSize = palette.size();
            if (paletteSize == 0) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' has an empty palette");
            }
            if (paletteSize > MAX_PALETTE_ENTRIES) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette exceeds "
                                + MAX_PALETTE_ENTRIES + " entries");
            }

            int bits = canonicalBits(paletteSize);
            long[] blockStates = requireBlockStates(region, regionName);
            long expectedLongs = expectedLongCount(volume, bits, regionName);
            if (blockStates.length != expectedLongs) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' BlockStates length is "
                                + blockStates.length + ", expected " + expectedLongs
                                + " (volume=" + volume + ", bits=" + bits + ")");
            }

            BlockState[] states = parsePalette(palette, regionName);
            int[] paletteCounts = new int[paletteSize];
            decodePacked(blockStates, volume, bits, paletteSize,
                    paletteCounts, regionName);

            for (int i = 0; i < paletteSize; i++) {
                int count = paletteCounts[i];
                if (count == 0) {
                    continue;
                }
                for (MaterialCount material : MaterialResolver.resolve(states[i], count)) {
                    addCount(totals, material, regionName);
                }
            }
        }

        List<MaterialCount> result = new ArrayList<>(totals.size());
        for (Map.Entry<String, Long> entry : totals.entrySet()) {
            result.add(new MaterialCount(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static long[] readDimensions(CompoundTag region, String regionName)
            throws MalformedSchematicException {
        CompoundTag size = requireCompound(region, "Size", regionName);
        int x = requireInt(size, "x", regionName);
        int y = requireInt(size, "y", regionName);
        int z = requireInt(size, "z", regionName);

        // Litematica stores a signed Size and derives a positive extent from
        // Position/Size. Only the absolute extent matters for material rows.
        long width = absoluteDimension(x);
        long height = absoluteDimension(y);
        long length = absoluteDimension(z);
        if (width == 0L || height == 0L || length == 0L) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' has a zero-sized extent");
        }
        return new long[] {width, height, length};
    }

    private static long absoluteDimension(int value) throws MalformedSchematicException {
        if (value == 0) {
            return 0L;
        }
        if (value == Integer.MIN_VALUE) {
            return 2_147_483_648L;
        }
        return Math.abs((long) value);
    }

    private static ListTag requirePalette(CompoundTag region, String regionName)
            throws MalformedSchematicException {
        Tag tag = region.get("BlockStatePalette");
        if (tag == null) {
            // Older/legacy saves used the shorter name.
            tag = region.get("Palette");
        }
        if (!(tag instanceof ListTag palette)) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' is missing a list palette");
        }
        return palette;
    }

    private static long[] requireBlockStates(CompoundTag region, String regionName)
            throws MalformedSchematicException {
        Tag tag = region.get("BlockStates");
        if (!(tag instanceof LongArrayTag blockStates)) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' is missing a long-array BlockStates");
        }
        return blockStates.getAsLongArray();
    }

    private static BlockState[] parsePalette(ListTag palette, String regionName)
            throws MalformedSchematicException {
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < states.length; i++) {
            Tag entryTag = palette.get(i);
            if (!(entryTag instanceof CompoundTag entry)) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette[" + i + "] is not a compound");
            }
            states[i] = parseState(entry, regionName, i);
        }
        return states;
    }

    private static BlockState parseState(CompoundTag entry, String regionName, int index)
            throws MalformedSchematicException {
        Tag nameTag = entry.get("Name");
        if (!(nameTag instanceof StringTag name)) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' palette[" + index + "] has no string Name");
        }

        String rawName = name.value();
        // NBT palette Name must itself be a valid resource location. Without
        // this check a name such as "stone[properties]" could inject parser
        // syntax even when the Properties compound is absent.
        if (Identifier.tryParse(rawName) == null) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' palette[" + index
                            + "] has an invalid block Name: " + rawName);
        }

        StringBuilder descriptor = new StringBuilder(rawName);
        int propertyCount = 0;
        Tag propertiesTag = entry.get("Properties");
        if (propertiesTag != null) {
            if (!(propertiesTag instanceof CompoundTag properties)) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette[" + index
                                + "] Properties is not a compound");
            }
            if (!properties.isEmpty()) {
                descriptor.append('[');
                boolean first = true;
                for (String property : properties.keySet()) {
                    if (!isSafeDescriptorToken(property)) {
                        throw new MalformedSchematicException(
                                "Region '" + regionName + "' palette[" + index
                                        + "] has invalid property name '" + property + "'");
                    }
                    Tag valueTag = properties.get(property);
                    if (!(valueTag instanceof StringTag value)) {
                        throw new MalformedSchematicException(
                                "Region '" + regionName + "' palette[" + index
                                        + "] property '" + property + "' is not a string");
                    }
                    if (!isSafeDescriptorToken(value.value())) {
                        throw new MalformedSchematicException(
                                "Region '" + regionName + "' palette[" + index
                                        + "] property '" + property
                                        + "' has invalid value '" + value.value() + "'");
                    }
                    if (!first) {
                        descriptor.append(',');
                    }
                    first = false;
                    propertyCount++;
                    descriptor.append(property).append('=').append(value.value());
                }
                descriptor.append(']');
            }
        }

        try {
            StringReader reader = new StringReader(descriptor.toString());
            BlockStateParser.BlockResult result =
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false);
            // parseForBlock intentionally leaves trailing input for command
            // callers; a palette entry must consume the whole descriptor.
            if (reader.canRead()) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette[" + index
                                + "] has trailing block-state input: " + descriptor);
            }
            if (result.properties().size() != propertyCount) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette[" + index
                                + "] property injection or merge detected: " + descriptor);
            }
            return result.blockState();
        } catch (MalformedSchematicException error) {
            throw error;
        } catch (CommandSyntaxException | RuntimeException error) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' palette[" + index
                            + "] has an unknown block or invalid state: " + descriptor,
                    error);
        }
    }

    /**
     * Property names and values are inserted into Minecraft's command-style
     * block-state descriptor. Reject characters that could terminate or extend
     * that descriptor so malformed NBT cannot smuggle a second state/NBT.
     */
    private static boolean isSafeDescriptorToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x20 || c == 0x7f
                    || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == ',' || c == '=' || c == '"' || c == '\''
                    || c == '\\' || c == '#') {
                return false;
            }
        }
        return true;
    }

    private static void decodePacked(long[] storage, long volume, int bits,
                                     int paletteSize, int[] counts, String regionName)
            throws MalformedSchematicException {
        long mask = (1L << bits) - 1L;
        for (long index = 0; index < volume; index++) {
            long bitOffset = index * bits;
            int startLong = (int) (bitOffset >>> 6);
            int startBit = (int) (bitOffset & 63L);
            int endLong = (int) (((index + 1L) * bits - 1L) >>> 6);

            if (startLong >= storage.length || endLong >= storage.length) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' block-state read exceeds array at index "
                                + index);
            }

            int paletteIndex;
            if (startLong == endLong) {
                paletteIndex = (int) ((storage[startLong] >>> startBit) & mask);
            } else {
                int endOffset = 64 - startBit;
                paletteIndex = (int) (((storage[startLong] >>> startBit)
                        | (storage[endLong] << endOffset)) & mask);
            }

            if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette index " + paletteIndex
                                + " at block " + index + " is out of range (palette size "
                                + paletteSize + ")");
            }
            counts[paletteIndex]++;
        }
    }

    private static void addCount(Map<String, Long> totals, MaterialCount material,
                                 String regionName) throws MalformedSchematicException {
        try {
            totals.merge(material.itemId(), material.count(), Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new MalformedSchematicException(
                    "Material count overflow for " + material.itemId()
                            + " in region '" + regionName + "'", overflow);
        }
    }

    private static CompoundTag requireCompound(CompoundTag parent, String key,
                                               String context) throws MalformedSchematicException {
        Tag tag = parent.get(key);
        if (!(tag instanceof CompoundTag compound)) {
            throw new MalformedSchematicException(
                    "Missing or non-compound " + key + " in '" + context + "'");
        }
        return compound;
    }

    private static int requireInt(CompoundTag parent, String key, String context)
            throws MalformedSchematicException {
        try {
            return parent.getInt(key).orElseThrow();
        } catch (java.util.NoSuchElementException error) {
            throw new MalformedSchematicException(
                    "Missing integer " + key + " in '" + context + "'", error);
        }
    }

    private static long multiplyChecked(long left, long right, String regionName, String what)
            throws MalformedSchematicException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MalformedSchematicException(
                    "Region '" + regionName + "' " + what + " overflows", overflow);
        }
    }

    private static int canonicalBits(int paletteSize) {
        // Exact LitematicaBlockStateContainer.createFrom expression. The
        // minimum is 2 even for a one-entry palette; BitsPerEntry is ignored.
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static long expectedLongCount(long volume, int bits, String regionName)
            throws MalformedSchematicException {
        long totalBits = multiplyChecked(volume, bits, regionName, "packed bit count");
        return (totalBits + 63L) / 64L;
    }

    /** Thrown for any structural or semantic problem in the .litematic file. */
    public static final class MalformedSchematicException extends IOException {
        public MalformedSchematicException(String message) {
            super(message);
        }

        public MalformedSchematicException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
