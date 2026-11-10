/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

/**
 * Platform hook that turns neutral palette text into a native block state and
 * then exposes the small fact record used by {@link MaterialRules}.
 *
 * @param <S> opaque platform state type
 */
public interface PaletteStateAdapter<S> {
    /**
     * Parse one palette entry with the platform's strict block-state parser.
     * Implementations must reject unknown blocks, invalid properties and
     * trailing descriptor input.
     */
    S parse(NeutralBlockState state, String regionName, int paletteIndex)
            throws MalformedLitematicException;

    /**
     * Resolve material facts for an already parsed state. Must not return null.
     */
    BlockStateFacts facts(S state);
}
