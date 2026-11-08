package net.syncmaterial.syncmaterial.core;

import java.util.regex.Pattern;

/** Validation helpers shared by the core records. */
final class CoreChecks {
    // Registry ids are namespaced ResourceLocation-style keys (minecraft:stone, modid:block/item).
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private CoreChecks() {
    }

    static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String requireRegistryId(String value, String field) {
        requireNonBlank(value, field);
        if (!REGISTRY_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a registry-like id: " + value);
        }
        return value;
    }
}
