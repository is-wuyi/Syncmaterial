/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The palette descriptor shape follows Litematica by masa
 * (https://github.com/sakura-kyoko/litematica), licensed under LGPL-3.0:
 * https://www.gnu.org/licenses/lgpl-3.0.html
 */
package net.syncmaterial.shared.litematic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-independent palette entry: a block registry id plus its string
 * state properties, exactly as stored in the litematic palette.
 *
 * <p>Property values remain strings here. Turning them into registry
 * properties is the platform adapter's job; keeping the raw text lets the
 * adapter preserve Minecraft's strict descriptor parser and property-count
 * check.</p>
 */
public record NeutralBlockState(String blockId, Map<String, String> properties) {
    public NeutralBlockState {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(properties, "properties");
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
