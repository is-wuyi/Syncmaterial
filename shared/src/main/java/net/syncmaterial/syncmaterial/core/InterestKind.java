package net.syncmaterial.syncmaterial.core;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side interests a client can hold for a project or global scope.
 *
 * <p>The five kinds are deliberately small and ordered by bit value so a desired
 * interest set can travel as one bounded integer mask. The bit values are part of
 * the shared domain contract: MATERIALS=1, TEAM=2, AREA_CONFIG=4, OUTLINES=8,
 * WAREHOUSE_CONTENTS=16. New kinds must take a new bit instead of renumbering
 * existing ones, otherwise old masks would silently mean a different interest
 * set.</p>
 */
public enum InterestKind {
    MATERIALS(1),
    TEAM(1 << 1),
    AREA_CONFIG(1 << 2),
    OUTLINES(1 << 3),
    WAREHOUSE_CONTENTS(1 << 4);

    /** Mask of every currently defined kind. */
    public static final int ALL_MASK;

    static {
        int mask = 0;
        for (InterestKind kind : values()) {
            mask |= kind.bit;
        }
        ALL_MASK = mask;
    }

    private final int bit;

    InterestKind(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    /**
     * @throws IllegalArgumentException when {@code mask} contains a bit that is not a
     *                                  defined interest kind
     */
    public static Set<InterestKind> fromMask(int mask) {
        if ((mask & ~ALL_MASK) != 0) {
            throw new IllegalArgumentException("Unknown interest mask bits: 0x"
                    + Integer.toHexString(mask));
        }
        if (mask == 0) {
            return Set.of();
        }
        EnumSet<InterestKind> kinds = EnumSet.noneOf(InterestKind.class);
        for (InterestKind kind : values()) {
            if ((mask & kind.bit) != 0) {
                kinds.add(kind);
            }
        }
        return Collections.unmodifiableSet(kinds);
    }

    /** Combines every kind in {@code kinds} into one mask. */
    public static int mask(Collection<InterestKind> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        int mask = 0;
        for (InterestKind kind : kinds) {
            mask |= Objects.requireNonNull(kind, "kind").bit;
        }
        return mask;
    }

    /** Combines every kind in {@code kinds} into one mask. */
    public static int mask(InterestKind... kinds) {
        Objects.requireNonNull(kinds, "kinds");
        int mask = 0;
        for (InterestKind kind : kinds) {
            mask |= Objects.requireNonNull(kind, "kind").bit;
        }
        return mask;
    }
}
