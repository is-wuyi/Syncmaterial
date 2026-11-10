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
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;
import net.syncmaterial.shared.litematic.BlockStateFacts;
import net.syncmaterial.shared.litematic.DecodeLimits;
import net.syncmaterial.shared.litematic.LitematicFormatPolicy;
import net.syncmaterial.shared.litematic.LitematicMaterialPipeline;
import net.syncmaterial.shared.litematic.MalformedLitematicException;
import net.syncmaterial.shared.litematic.NeutralBlockState;
import net.syncmaterial.shared.litematic.PaletteStateAdapter;
import net.syncmaterial.syncmaterial.core.MaterialCount;

/**
 * Foundation-backend material decoder for current Litematica (.litematic)
 * files.
 *
 * <p>The decoder is intentionally independent of Litematica and MaLiLib and
 * only uses Minecraft's NBT/registry APIs, so it also works on a dedicated
 * server where neither optional client mod is installed.</p>
 *
 * <p>Region traversal, quota checks, packed-palette counting and material
 * aggregation live in the platform-independent shared pipeline. This class
 * only reads the native NBT tree and adapts palette text to
 * {@link BlockState} plus {@link BlockStateFacts}.</p>
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
    public static final long MAX_TOTAL_BLOCKS = DecodeLimits.DEFAULT_MAX_TOTAL_BLOCKS;

    /** Bound on region iteration for hostile, tiny-region files. */
    public static final int MAX_REGIONS = DecodeLimits.DEFAULT_MAX_REGIONS;

    /** Bound on palette entries; vanilla/modded palettes are far smaller. */
    public static final int MAX_PALETTE_ENTRIES = DecodeLimits.DEFAULT_MAX_PALETTE_ENTRIES;

    private static final DecodeLimits LIMITS = new DecodeLimits(
            MAX_TOTAL_BLOCKS, MAX_REGIONS, MAX_PALETTE_ENTRIES);

    private static final int CURRENT_DATA_VERSION =
            SharedConstants.getCurrentVersion().dataVersion().version();

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

        Map<String, Long> amounts;
        try {
            amounts = LitematicMaterialPipeline.decode(
                    McNbtView.of(root), PaletteAdapter.INSTANCE, LIMITS,
                    LitematicFormatPolicy.forCurrentVersion(CURRENT_DATA_VERSION));
        } catch (MalformedSchematicException alreadyTyped) {
            throw alreadyTyped;
        } catch (MalformedLitematicException malformed) {
            throw new MalformedSchematicException(malformed.getMessage(), malformed);
        }

        List<MaterialCount> result = new ArrayList<>(amounts.size());
        for (Map.Entry<String, Long> amount : amounts.entrySet()) {
            result.add(new MaterialCount(amount.getKey(), amount.getValue()));
        }
        return List.copyOf(result);
    }

    /**
     * Interpretation identifier for stored material rows. The shared
     * {@link MaterialDecoder} default is {@code unversioned}; this decoder
     * pins the actual Minecraft data version plus decoder and material-rule
     * revisions so a future version cannot silently reinterpret old rows.
     */
    public String interpretationId() {
        return "mc:" + CURRENT_DATA_VERSION + "/decoder:1/materials:1";
    }

    private enum PaletteAdapter implements PaletteStateAdapter<BlockState> {
        INSTANCE;

        @Override
        public BlockState parse(NeutralBlockState state, String regionName,
                                int paletteIndex) throws MalformedLitematicException {
            StringBuilder descriptor = new StringBuilder(state.blockId());
            int propertyCount = 0;
            if (!state.properties().isEmpty()) {
                descriptor.append('[');
                boolean first = true;
                for (var property : state.properties().entrySet()) {
                    if (!first) {
                        descriptor.append(',');
                    }
                    first = false;
                    propertyCount++;
                    descriptor.append(property.getKey()).append('=')
                            .append(property.getValue());
                }
                descriptor.append(']');
            }

            try {
                StringReader reader = new StringReader(descriptor.toString());
                BlockStateParser.BlockResult result =
                        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false);
                // parseForBlock intentionally leaves trailing input for command
                // callers; a palette entry must consume the whole descriptor.
                if (reader.canRead()) {
                    throw new MalformedSchematicException(
                            "Region '" + regionName + "' palette[" + paletteIndex
                                    + "] has trailing block-state input: " + descriptor);
                }
                if (result.properties().size() != propertyCount) {
                    throw new MalformedSchematicException(
                            "Region '" + regionName + "' palette[" + paletteIndex
                                    + "] property injection or merge detected: " + descriptor);
                }
                return result.blockState();
            } catch (CommandSyntaxException | RuntimeException error) {
                throw new MalformedSchematicException(
                        "Region '" + regionName + "' palette[" + paletteIndex
                                + "] has an unknown block or invalid state: " + descriptor,
                        error);
            }
        }

        @Override
        public BlockStateFacts facts(BlockState state) {
            return MaterialFactsResolver.facts(state);
        }
    }

    /** Thrown for any structural or semantic problem in the .litematic file. */
    public static final class MalformedSchematicException
            extends MalformedLitematicException {
        public MalformedSchematicException(String message) {
            super(message);
        }

        public MalformedSchematicException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
