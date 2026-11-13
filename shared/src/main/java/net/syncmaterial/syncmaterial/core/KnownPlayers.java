package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/** Known identities needed for a management view, with bounded stable priority and explicit truncation. */
public record KnownPlayers(List<PlayerIdentity> players, boolean limited) {
    public KnownPlayers {
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        if (players.size() > TeamLimits.DIRECTORY) throw new IllegalArgumentException("Too many known players");
    }
}
