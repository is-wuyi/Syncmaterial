package net.syncmaterial.syncmaterial.core;

/** Actions of the project-scoped team management protocol. */
public enum TeamAction {
    TRANSFER,
    ADD_DEPUTY,
    REMOVE_DEPUTY,
    TOGGLE_SELF_CLAIM,
    ASSIGN,
    KICK,
    PLAYERS,
    TEAM_STATE;

    public boolean requiresMainOwner() {
        return this == TRANSFER || this == ADD_DEPUTY || this == REMOVE_DEPUTY;
    }

    public boolean usesMaterials() {
        return this == ASSIGN || this == KICK;
    }

    public boolean changesTeam() {
        return requiresMainOwner() || this == TOGGLE_SELF_CLAIM;
    }
}
