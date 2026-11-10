package net.syncmaterial.syncmaterial.core;

import java.util.List;
import java.util.Objects;

/**
 * Immutable project view. Lists are copied defensively; deputies are player names,
 * materials keep their stable persisted ids.
 *
 * <p>{@code interpretationId} is a cache/material-interpretation identity, not the
 * project identity. Project identity remains {@code id}; the pair
 * ({@code hash}, {@code interpretationId}) only says whether the stored material
 * rows may be reused for the same project.</p>
 */
public record ProjectSnapshot(
        String id,
        String name,
        String hash,
        PlayerIdentity owner,
        boolean allowSelfClaim,
        List<String> deputies,
        List<MaterialSnapshot> materials,
        long revision,
        String interpretationId
) {
    /** Compatibility constructor for callers/tests written before interpretations existed. */
    public ProjectSnapshot(String id, String name, String hash, PlayerIdentity owner, boolean allowSelfClaim,
                           List<String> deputies, List<MaterialSnapshot> materials, long revision) {
        this(id, name, hash, owner, allowSelfClaim, deputies, materials, revision,
                MaterialInterpretations.UNVERSIONED);
    }

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
        CoreChecks.requireNonBlank(interpretationId, "interpretationId");
    }

    public String ownerName() {
        return owner.name();
    }
}
