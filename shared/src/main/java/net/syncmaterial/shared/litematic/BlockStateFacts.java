/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import java.util.Objects;

/**
 * The small set of facts the platform supplies for one block state. The
 * material rules themselves stay in {@link MaterialRules}; this record only
 * carries the resolved item ids and shape-specific multipliers so that new
 * Minecraft/Litematica versions do not need a parallel copy of the rule code.
 *
 * @param kind             which material rule branch applies
 * @param primaryItemId    main item id; required for every kind except NONE
 * @param secondaryItemId  potted content, cauldron bucket or potion; nullable
 * @param multiplier       per-state item multiplier (double slab, snow layers,
 *                         turtle eggs, pickles, candles, multiface faces)
 * @param cauldronLevel    water cauldron level 0..3; 0 for other kinds
 * @param waterloggedItemId item added for a waterlogged state, or null when the
 *                         state is not waterlogged
 */
public record BlockStateFacts(
        Kind kind,
        String primaryItemId,
        String secondaryItemId,
        long multiplier,
        int cauldronLevel,
        String waterloggedItemId) {

    public enum Kind {
        ORDINARY,
        FLOWER_POT,
        CAULDRON_LAVA,
        CAULDRON_POWDER_SNOW,
        CAULDRON_WATER,
        NONE
    }

    public BlockStateFacts {
        Objects.requireNonNull(kind, "kind");
        if (kind != Kind.NONE) {
            Objects.requireNonNull(primaryItemId, "primaryItemId");
        }
        if (multiplier < 0) {
            throw new IllegalArgumentException("multiplier must be >= 0");
        }
        if (cauldronLevel < 0 || cauldronLevel > 3) {
            throw new IllegalArgumentException("cauldronLevel must be 0..3");
        }
    }

    public static BlockStateFacts none() {
        return new BlockStateFacts(Kind.NONE, null, null, 0L, 0, null);
    }
}
