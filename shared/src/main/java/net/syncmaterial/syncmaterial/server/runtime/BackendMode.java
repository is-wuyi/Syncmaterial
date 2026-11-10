package net.syncmaterial.syncmaterial.server.runtime;

public final class BackendMode {
    private BackendMode() {}

    public static boolean isFoundationEnabled() {
        return "foundation".equals(System.getProperty("syncmaterial.backend", "legacy"));
    }
}
