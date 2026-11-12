package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/** Configuration payload for creating or updating an area. */
public record AreaSpec(String name, String world, AreaBounds bounds) {
    public AreaSpec {
        CoreChecks.requireNonBlank(name, "name");
        CoreChecks.requireNonBlank(world, "world");
        Objects.requireNonNull(bounds, "bounds");
    }
}
