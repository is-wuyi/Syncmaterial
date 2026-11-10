package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/** A claim row. Claims are non-exclusive membership records; row existence means membership. */
public record ClaimSnapshot(int materialId, PlayerIdentity player) {
    public ClaimSnapshot {
        if (materialId <= 0) {
            throw new IllegalArgumentException("materialId must be > 0");
        }
        Objects.requireNonNull(player, "player");
    }
}
