/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exact material aggregation. Item ids are sorted so repeated decodes produce
 * a stable map; every merge uses {@link Math#addExact} so a hostile file cannot
 * wrap a count into a smaller value.
 */
public final class MaterialTotals implements MaterialSink {
    private final TreeMap<String, Long> totals = new TreeMap<>();

    @Override
    public void add(String itemId, long count, String context) throws MalformedLitematicException {
        if (count <= 0) {
            return;
        }
        try {
            totals.merge(itemId, count, Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new MalformedLitematicException(
                    "Material count overflow for " + itemId
                            + " in region '" + context + "'", overflow);
        }
    }

    /**
     * Immutable item-id to count view in stable registry-id order.
     */
    public Map<String, Long> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(totals));
    }
}
