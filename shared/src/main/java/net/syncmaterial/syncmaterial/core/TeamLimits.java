package net.syncmaterial.syncmaterial.core;

/** Bounds shared by the protocol codec and the team application. */
public final class TeamLimits {
    public static final int PROJECT_ID_LENGTH = 100;
    public static final int PLAYER_NAME_LENGTH = 64;
    public static final int MESSAGE_LENGTH = 512;
    public static final int MATERIALS = 1024;
    public static final int TARGETS = 128;
    public static final int ASSIGNMENTS = 8192;
    public static final int DEPUTIES = 1024;
    public static final int DIRECTORY = 8192;
    public static final int IN_FLIGHT = 16;

    private TeamLimits() {}
}
