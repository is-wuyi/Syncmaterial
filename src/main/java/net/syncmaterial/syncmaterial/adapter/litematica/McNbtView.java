/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.syncmaterial.adapter.litematica;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.syncmaterial.shared.litematic.NbtView;

/**
 * Read-only {@link NbtView} over Minecraft's native tag tree.
 *
 * <p>Only the five kinds the litematic decoder needs are distinguished.
 * Wrapping is lazy and does not copy arrays or compounds, so the NBT quota
 * remains the only memory budget for the parsed tree.</p>
 */
final class McNbtView implements NbtView {
    private final Tag tag;

    private McNbtView(Tag tag) {
        this.tag = tag;
    }

    static McNbtView of(Tag tag) {
        return new McNbtView(tag);
    }

    @Override
    public Kind kind() {
        if (tag instanceof CompoundTag) {
            return Kind.COMPOUND;
        }
        if (tag instanceof ListTag) {
            return Kind.LIST;
        }
        if (tag instanceof StringTag) {
            return Kind.STRING;
        }
        if (tag instanceof IntTag) {
            return Kind.INT;
        }
        if (tag instanceof LongArrayTag) {
            return Kind.LONG_ARRAY;
        }
        return Kind.OTHER;
    }

    @Override
    public NbtView get(String key) {
        if (!(tag instanceof CompoundTag compound)) {
            return null;
        }
        Tag child = compound.get(key);
        return child == null ? null : new McNbtView(child);
    }

    @Override
    public List<String> keys() {
        if (!(tag instanceof CompoundTag compound)) {
            return List.of();
        }
        return new ArrayList<>(compound.keySet());
    }

    @Override
    public int size() {
        if (tag instanceof CompoundTag compound) {
            return compound.size();
        }
        if (tag instanceof ListTag list) {
            return list.size();
        }
        if (tag instanceof LongArrayTag array) {
            return array.size();
        }
        return 0;
    }

    @Override
    public NbtView get(int index) {
        if (!(tag instanceof ListTag list)) {
            return null;
        }
        return new McNbtView(list.get(index));
    }

    @Override
    public String asString() {
        return tag.asString().orElse(null);
    }

    @Override
    public Integer asInt() {
        return tag.asInt().orElse(null);
    }

    @Override
    public long[] asLongArray() {
        return tag.asLongArray().orElse(null);
    }
}
