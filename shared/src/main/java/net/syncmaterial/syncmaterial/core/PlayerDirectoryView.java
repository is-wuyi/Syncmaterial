package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/** Final player-directory projection. Limited results are displayed with an explicit warning. */
public record PlayerDirectoryView(List<PlayerDirectoryEntry> players, boolean limited) {
    public PlayerDirectoryView {
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        if (players.size() > TeamLimits.DIRECTORY) throw new IllegalArgumentException("Player directory exceeds limit");
    }
}
