/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 *
 * The region layout checked by this neutral view follows Litematica by masa
 * (https://github.com/sakura-kyoko/litematica), licensed under LGPL-3.0:
 * https://www.gnu.org/licenses/lgpl-3.0.html
 */
package net.syncmaterial.shared.litematic;

import java.util.List;

/**
 * Minimal read-only NBT view used by the platform-independent decoder.
 *
 * <p>The platform wraps its native NBT tree; pure tests can implement this
 * interface with an in-memory map. The decoder only needs compounds, lists,
 * strings, ints and long arrays. Anything else is represented as
 * {@link Kind#OTHER} and is rejected where a specific kind is required.</p>
 */
public interface NbtView {
    enum Kind {
        COMPOUND,
        LIST,
        STRING,
        INT,
        LONG_ARRAY,
        OTHER
    }

    Kind kind();

    default boolean isCompound() {
        return kind() == Kind.COMPOUND;
    }

    default boolean isList() {
        return kind() == Kind.LIST;
    }

    default boolean isString() {
        return kind() == Kind.STRING;
    }

    default boolean isInt() {
        return kind() == Kind.INT;
    }

    default boolean isLongArray() {
        return kind() == Kind.LONG_ARRAY;
    }

    /**
     * Look up a compound child.
     *
     * @return child view, or {@code null} when the key is absent
     */
    NbtView get(String key);

    /**
     * Compound keys in the platform's native iteration order. Empty for
     * non-compounds.
     */
    List<String> keys();

    /**
     * Number of entries for compounds/lists and number of longs for long
     * arrays. Zero for scalars.
     */
    int size();

    /**
     * List element at {@code index}, or {@code null} when out of range or when
     * this view is not a list.
     */
    NbtView get(int index);

    /**
     * String value, or {@code null} when this view is not a string.
     */
    String asString();

    /**
     * Int value, or {@code null} when this view is not an int.
     */
    Integer asInt();

    /**
     * Long-array payload, or {@code null} when this view is not a long array.
     */
    long[] asLongArray();
}
