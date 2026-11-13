package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/** One known player, including unresolved offline metadata and the current online fact. */
public record PlayerDirectoryEntry(PlayerIdentity player, boolean online) {
    public PlayerDirectoryEntry {
        Objects.requireNonNull(player, "player");
        if (player.name().length() > TeamLimits.PLAYER_NAME_LENGTH || (online && !player.hasUuid())) {
            throw new IllegalArgumentException("Invalid player directory identity");
        }
    }
}
