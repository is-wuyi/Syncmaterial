package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/** Authoritative roles; names are presentation, UUIDs are identity whenever known. */
public record TeamState(String projectId, PlayerIdentity owner, List<PlayerIdentity> deputies,
                        boolean allowSelfClaim, boolean allowNameAuthority) {
    /** Name-only authority is retained for the temporary legacy adapter and its fixtures. */
    public TeamState(String projectId, PlayerIdentity owner, List<PlayerIdentity> deputies, boolean allowSelfClaim) {
        this(projectId, owner, deputies, allowSelfClaim, true);
    }

    public TeamState {
        CoreChecks.requireNonBlank(projectId, "projectId");
        Objects.requireNonNull(owner, "owner");
        deputies = List.copyOf(Objects.requireNonNull(deputies, "deputies"));
        if (projectId.length() > TeamLimits.PROJECT_ID_LENGTH || owner.name().length() > TeamLimits.PLAYER_NAME_LENGTH
                || deputies.size() > TeamLimits.DEPUTIES
                || deputies.stream().anyMatch(deputy -> deputy.name().length() > TeamLimits.PLAYER_NAME_LENGTH)) {
            throw new IllegalArgumentException("Team state exceeds protocol bounds");
        }
    }

    public boolean isMainOwner(PlayerIdentity player) {
        return matches(owner, player);
    }

    public boolean isOwner(PlayerIdentity player) {
        return isMainOwner(player) || deputies.stream().anyMatch(deputy -> matches(deputy, player));
    }

    public List<String> deputyNames() {
        return deputies.stream().map(PlayerIdentity::name).toList();
    }

    private boolean matches(PlayerIdentity role, PlayerIdentity player) {
        if (player == null) return false;
        return role.uuid() != null ? role.uuid().equals(player.uuid())
                : allowNameAuthority && role.name().equalsIgnoreCase(player.name());
    }
}
