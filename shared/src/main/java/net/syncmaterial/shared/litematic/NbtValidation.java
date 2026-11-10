/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * Validation mirrors Minecraft's resource-location grammar so a palette Name
 * cannot inject command-style block-state syntax.
 */
package net.syncmaterial.shared.litematic;

/**
 * Strict text checks shared by every platform adapter.
 */
public final class NbtValidation {
    private NbtValidation() {}

    /**
     * Parse check matching {@code ResourceLocation/Identifier.tryParse}: an
     * optional lowercase namespace, a non-empty path, and no extra colon.
     */
    public static boolean isResourceLocation(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int colon = value.indexOf(':');
        String namespace;
        String path;
        if (colon >= 0) {
            if (colon != value.lastIndexOf(':')) {
                return false;
            }
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
            if (namespace.isEmpty() || path.isEmpty() || !isNamespace(namespace)) {
                return false;
            }
        } else {
            path = value;
        }
        return isPath(path);
    }

    /**
     * Property names and values are inserted into Minecraft's command-style
     * block-state descriptor. Reject characters that could terminate or extend
     * that descriptor so malformed NBT cannot smuggle a second state/NBT.
     */
    public static boolean isSafeDescriptorToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x20 || c == 0x7f
                    || c == '[' || c == ']' || c == '{' || c == '}'
                    || c == ',' || c == '=' || c == '"' || c == '\''
                    || c == '\\' || c == '#') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPath(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '_' && c != '.' && c != '-' && c != '/') {
                return false;
            }
        }
        return true;
    }
}
