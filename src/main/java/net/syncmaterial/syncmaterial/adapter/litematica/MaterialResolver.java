/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The material-conversion rules are a source-level port of behavior from
 * Litematica by masa (https://github.com/sakura-kyoko/litematica), licensed
 * under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * Inspected upstream: Litematica 0.28.5, jar id Fhq3KCI8, classes
 * fi.dy.masa.litematica.materials.MaterialCache and MaterialListUtils, plus
 * the 0.23.x LTS source tree. Modified for SyncMaterial: no Litematica/MaLiLib
 * runtime dependency, item-id output only, and explicit validation instead of
 * silently returning air.
 */
package net.syncmaterial.syncmaterial.adapter.litematica;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.syncmaterial.shared.litematic.MalformedLitematicException;
import net.syncmaterial.shared.litematic.MaterialRules;
import net.syncmaterial.shared.litematic.MaterialTotals;
import net.syncmaterial.syncmaterial.core.MaterialCount;

/**
 * Converts one Minecraft block state to the item rows Litematica's material
 * list would add for one occurrence of that state.
 *
 * <p>The rule branches and multiplication live in the shared
 * {@link MaterialRules} helper; this class only resolves the state-specific
 * facts (item ids and multipliers) for the active platform version. The
 * independent Foundation client test compares both rule families with
 * Litematica's public material-list output.</p>
 *
 * <p>This class deliberately does not reference any Litematica or MaLiLib
 * class, so the dedicated server can run without those optional mods.</p>
 */
public final class MaterialResolver {
    private MaterialResolver() {}

    /**
     * Resolve one decoded block state.
     *
     * @param state       a validated block state from a schematic palette
     * @param occurrences number of times the state occurs in the schematic
     * @return item rows, already multiplied by {@code occurrences}; never null
     */
    public static List<MaterialCount> resolve(BlockState state, long occurrences) {
        Objects.requireNonNull(state, "state");
        if (occurrences < 0) {
            throw new IllegalArgumentException("occurrences must be >= 0");
        }
        if (occurrences == 0) {
            return List.of();
        }

        MaterialTotals totals = new MaterialTotals();
        try {
            MaterialRules.resolve(MaterialFactsResolver.facts(state), occurrences,
                    "state", totals);
        } catch (MalformedLitematicException overflow) {
            throw new IllegalArgumentException(overflow.getMessage(), overflow);
        }

        Map<String, Long> amounts = totals.asMap();
        List<MaterialCount> result = new ArrayList<>(amounts.size());
        for (Map.Entry<String, Long> amount : amounts.entrySet()) {
            result.add(new MaterialCount(amount.getKey(), amount.getValue()));
        }
        return List.copyOf(result);
    }
}
