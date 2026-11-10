/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

/**
 * Receives resolved material rows. The context string (normally the region
 * name) is only used to make overflow diagnostics actionable.
 */
public interface MaterialSink {
    void add(String itemId, long count, String context) throws MalformedLitematicException;
}
