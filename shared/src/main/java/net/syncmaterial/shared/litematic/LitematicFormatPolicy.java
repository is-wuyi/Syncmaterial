/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

/**
 * Coarse litematic schema gate.
 *
 * <p>Synthetic fixtures and older writers often omit both
 * {@code MinecraftDataVersion} and {@code Version}; missing fields are
 * accepted and decoded with the current block-state names. When the fields are
 * present, a file that is clearly newer than this build must fail explicitly
 * instead of being silently reinterpreted. Older data versions are not
 * rejected: this decoder has no datafixer, so any renamed block/property still
 * fails as an explicit unknown palette entry.</p>
 *
 * @param currentDataVersion      current Minecraft data version
 * @param maxSchematicVersion     highest supported litematic schema version
 * @param maxFutureDataVersionGap tolerated forward data-version gap, matching
 *                                Litematica's 100-version warning boundary
 */
public record LitematicFormatPolicy(int currentDataVersion, int maxSchematicVersion,
                                    int maxFutureDataVersionGap) {
    public static final int LITEMATICA_SCHEMATIC_VERSION = 7;
    public static final int DEFAULT_FUTURE_DATA_VERSION_GAP = 100;

    public LitematicFormatPolicy {
        if (currentDataVersion <= 0) {
            throw new IllegalArgumentException("currentDataVersion must be > 0");
        }
        if (maxSchematicVersion <= 0) {
            throw new IllegalArgumentException("maxSchematicVersion must be > 0");
        }
        if (maxFutureDataVersionGap < 0) {
            throw new IllegalArgumentException("maxFutureDataVersionGap must be >= 0");
        }
    }

    public static LitematicFormatPolicy forCurrentVersion(int currentDataVersion) {
        return new LitematicFormatPolicy(currentDataVersion,
                LITEMATICA_SCHEMATIC_VERSION, DEFAULT_FUTURE_DATA_VERSION_GAP);
    }

    /**
     * Validate the root fields when present.
     */
    public void check(NbtView root) throws MalformedLitematicException {
        Integer dataVersion = optionalInt(root, "MinecraftDataVersion");
        if (dataVersion != null) {
            if (dataVersion <= 0) {
                throw new MalformedLitematicException(
                        "Invalid MinecraftDataVersion: " + dataVersion);
            }
            if (dataVersion > currentDataVersion + maxFutureDataVersionGap) {
                throw new MalformedLitematicException(
                        "Unsupported newer Minecraft data version " + dataVersion
                                + " (this build supports up to " + currentDataVersion
                                + " plus " + maxFutureDataVersionGap + ")");
            }
        }

        Integer version = optionalInt(root, "Version");
        if (version != null) {
            if (version < 1 || version > maxSchematicVersion) {
                throw new MalformedLitematicException(
                        "Unsupported litematic schema Version " + version
                                + " (supported 1.." + maxSchematicVersion + ")");
            }
        }
    }

    private static Integer optionalInt(NbtView root, String key)
            throws MalformedLitematicException {
        NbtView tag = root.get(key);
        if (tag == null) {
            return null;
        }
        Integer value = tag.asInt();
        if (value == null) {
            throw new MalformedLitematicException(
                    "Non-integer " + key + " in root");
        }
        return value;
    }
}
