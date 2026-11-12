package net.syncmaterial.syncmaterial.core;

import java.util.Objects;

/**
 * A persisted area configuration row. {@code projectId} is null only for a global warehouse;
 * staging areas always belong to a project. {@code revision} changes on any update,
 * {@code geometryRevision} changes only when the world or bounds change, which invalidates
 * scan coverage from the previous geometry.
 */
public record AreaDefinition(
        int id,
        String projectId,
        AreaKind kind,
        String name,
        String world,
        AreaBounds bounds,
        long revision,
        long geometryRevision
) {
    public AreaDefinition {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be > 0");
        }
        Objects.requireNonNull(kind, "kind");
        CoreChecks.requireNonBlank(name, "name");
        CoreChecks.requireNonBlank(world, "world");
        Objects.requireNonNull(bounds, "bounds");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (geometryRevision < 0) {
            throw new IllegalArgumentException("geometryRevision must be >= 0");
        }
        if (projectId != null) {
            CoreChecks.requireNonBlank(projectId, "projectId");
        } else if (kind != AreaKind.WAREHOUSE) {
            throw new IllegalArgumentException("only a global warehouse may have a null projectId");
        }
    }

    public boolean isGlobalWarehouse() {
        return kind == AreaKind.WAREHOUSE && projectId == null;
    }
}
