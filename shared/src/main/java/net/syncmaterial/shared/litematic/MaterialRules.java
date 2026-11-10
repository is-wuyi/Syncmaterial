/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The material-conversion rules are a source-level port of behavior from
 * Litematica by masa (https://github.com/sakura-kyoko/litematica), licensed
 * under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 *
 * Inspected upstream: Litematica 0.28.5 (Maven/Modrinth id Fhq3KCI8) and the
 * 0.23.x LTS source tree. Modified for SyncMaterial: the platform supplies a
 * neutral fact record, so one rule implementation serves every target.
 */
package net.syncmaterial.shared.litematic;

/**
 * Converts one block-state fact record into the item rows Litematica's
 * material list would add for one occurrence of that state.
 *
 * <p>The branch order matches MaterialListUtils.convertStatesToStacks:
 * waterlogged blocks first contribute one water bucket, then the base state.
 * Flower pots and non-plain cauldrons contribute two item stacks. Pistons,
 * portals, upper door/bed/plant halves and non-source fluids resolve to
 * {@link BlockStateFacts.Kind#NONE} in the platform adapter and add
 * nothing.</p>
 */
public final class MaterialRules {
    private MaterialRules() {}

    /**
     * Resolve one state.
     *
     * @param facts       platform-supplied item ids and multiplier
     * @param occurrences number of times the state occurs in the region
     * @param context     region name for diagnostics
     * @param sink        aggregation sink
     */
    public static void resolve(BlockStateFacts facts, long occurrences,
                               String context, MaterialSink sink)
            throws MalformedLitematicException {
        if (occurrences <= 0) {
            return;
        }

        if (facts.waterloggedItemId() != null) {
            sink.add(facts.waterloggedItemId(), occurrences, context);
        }

        switch (facts.kind()) {
            case NONE:
                return;
            case FLOWER_POT:
                add(sink, facts.primaryItemId(), occurrences, context);
                add(sink, facts.secondaryItemId(), occurrences, context);
                return;
            case CAULDRON_LAVA:
            case CAULDRON_POWDER_SNOW:
                add(sink, facts.primaryItemId(), occurrences, context);
                add(sink, facts.secondaryItemId(), occurrences, context);
                return;
            case CAULDRON_WATER:
                add(sink, facts.primaryItemId(), occurrences, context);
                if (facts.secondaryItemId() != null && facts.cauldronLevel() > 0) {
                    // Level 1 = one potion, level 2 = two potions, level 3 =
                    // one water bucket. The adapter selects potion or bucket.
                    long perBlock = facts.cauldronLevel() == 2 ? 2L : 1L;
                    add(sink, facts.secondaryItemId(),
                            multiply(occurrences, perBlock, context,
                                    "cauldron contents"), context);
                }
                return;
            case ORDINARY:
                long count = facts.multiplier() == 1L
                        ? occurrences
                        : multiply(occurrences, facts.multiplier(), context,
                                "state item multiplier");
                add(sink, facts.primaryItemId(), count, context);
                return;
            default:
                throw new IllegalStateException("Unhandled state kind: " + facts.kind());
        }
    }

    private static void add(MaterialSink sink, String itemId, long count, String context)
            throws MalformedLitematicException {
        if (itemId != null && count > 0) {
            sink.add(itemId, count, context);
        }
    }

    static long multiply(long left, long right, String context, String what)
            throws MalformedLitematicException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new MalformedLitematicException(
                    "Region '" + context + "' " + what + " overflows", overflow);
        }
    }
}
