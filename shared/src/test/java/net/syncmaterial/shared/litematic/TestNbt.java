/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny in-memory {@link NbtView} used by the pure decoder tests. It mirrors the
 * five tag kinds the pipeline consumes and deliberately has no Minecraft
 * dependency.
 */
final class TestNbt implements NbtView {
    private final Kind kind;
    private final Map<String, NbtView> compound;
    private final List<NbtView> list;
    private final String string;
    private final Integer integer;
    private final long[] longs;

    private TestNbt(Kind kind, Map<String, NbtView> compound, List<NbtView> list,
                    String string, Integer integer, long[] longs) {
        this.kind = kind;
        this.compound = compound;
        this.list = list;
        this.string = string;
        this.integer = integer;
        this.longs = longs;
    }

    static Builder compound() {
        return new Builder();
    }

    static TestNbt compoundOf(Map<String, NbtView> children) {
        return new TestNbt(Kind.COMPOUND,
                Map.copyOf(children), null, null, null, null);
    }

    static TestNbt string(String value) {
        return new TestNbt(Kind.STRING, null, null, value, null, null);
    }

    static TestNbt integer(int value) {
        return new TestNbt(Kind.INT, null, null, null, value, null);
    }

    static TestNbt longs(long[] value) {
        return new TestNbt(Kind.LONG_ARRAY, null, null, null, null, value);
    }

    static TestNbt list(List<NbtView> values) {
        return new TestNbt(Kind.LIST, null, List.copyOf(values), null, null, null);
    }

    static TestNbt other() {
        return new TestNbt(Kind.OTHER, null, null, null, null, null);
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public NbtView get(String key) {
        return compound == null ? null : compound.get(key);
    }

    @Override
    public List<String> keys() {
        return compound == null ? List.of() : List.copyOf(compound.keySet());
    }

    @Override
    public int size() {
        if (compound != null) {
            return compound.size();
        }
        if (list != null) {
            return list.size();
        }
        return longs == null ? 0 : longs.length;
    }

    @Override
    public NbtView get(int index) {
        return list == null ? null : list.get(index);
    }

    @Override
    public String asString() {
        return string;
    }

    @Override
    public Integer asInt() {
        return integer;
    }

    @Override
    public long[] asLongArray() {
        return longs;
    }

    static final class Builder {
        private final Map<String, NbtView> children = new LinkedHashMap<>();

        Builder put(String key, NbtView value) {
            children.put(key, value);
            return this;
        }

        Builder put(String key, String value) {
            return put(key, TestNbt.string(value));
        }

        Builder put(String key, int value) {
            return put(key, TestNbt.integer(value));
        }

        TestNbt build() {
            return new TestNbt(Kind.COMPOUND,
                    Map.copyOf(children), null, null, null, null);
        }
    }
}
