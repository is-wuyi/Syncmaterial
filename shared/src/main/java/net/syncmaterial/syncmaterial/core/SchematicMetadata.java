package net.syncmaterial.syncmaterial.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Project metadata reported by a parser. A blank {@code name} is allowed and is
 * replaced by a file-derived fallback when stored.
 */
public record SchematicMetadata(String id, String name, Path file, String hash, PlayerIdentity owner) {
    public SchematicMetadata {
        CoreChecks.requireNonBlank(id, "id");
        Objects.requireNonNull(file, "file");
        CoreChecks.requireNonBlank(hash, "hash");
        Objects.requireNonNull(owner, "owner");
        if (name == null) {
            name = "";
        }
    }
}
