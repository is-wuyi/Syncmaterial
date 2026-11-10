package net.syncmaterial.syncmaterial.core;

import java.util.UUID;

/**
 * A player identity. {@code uuid} may be null when the name came from unresolved
 * metadata (for example a schematic uploader); it is never invented.
 */
public record PlayerIdentity(UUID uuid, String name) {
    public PlayerIdentity {
        CoreChecks.requireNonBlank(name, "name");
    }

    public boolean hasUuid() {
        return uuid != null;
    }
}
