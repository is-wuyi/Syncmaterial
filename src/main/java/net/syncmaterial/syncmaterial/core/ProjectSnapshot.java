package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/**
 * Immutable project view. Lists are copied defensively; deputies are player names,
 * materials keep their stable persisted ids.
 */
public record ProjectSnapshot(
        String id,
        String name,
        String hash,
        PlayerIdentity owner,
        boolean allowSelfClaim,
        List<String> deputies,
        List<MaterialSnapshot> materials,
        long revision
) {
    public ProjectSnapshot {
        CoreChecks.requireNonBlank(id, "id");
        CoreChecks.requireNonBlank(hash, "hash");
        Objects.requireNonNull(owner, "owner");
        if (name == null) {
            name = "";
        }
        deputies = List.copyOf(Objects.requireNonNull(deputies, "deputies"));
        materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    public String ownerName() {
        return owner.name();
    }
}
