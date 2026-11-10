/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import java.io.IOException;

/**
 * Thrown for any structural, semantic or quota problem in a .litematic file.
 * A failed decode must never be silently replaced by a partial material list.
 */
public class MalformedLitematicException extends IOException {
    public MalformedLitematicException(String message) {
        super(message);
    }

    public MalformedLitematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
