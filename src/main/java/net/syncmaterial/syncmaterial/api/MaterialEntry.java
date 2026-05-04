package net.syncmaterial.syncmaterial.api;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.item.ItemStack;

import java.util.Objects;

public class MaterialEntry
{
    private final ItemStack stack;
    private final long countTotal;
    private long countMissing;
    private long countMismatched;
    private long countAvailable;

    public MaterialEntry(ItemStack stack, long countTotal)
    {
        this(stack, countTotal, countTotal, 0, 0);
    }

    public MaterialEntry(ItemStack stack, long countTotal, long countMissing, long countMismatched, long countAvailable)
    {
        this.stack = Objects.requireNonNull(stack, "ItemStack cannot be null");
        this.countTotal = countTotal;
        this.countMissing = countMissing;
        this.countMismatched = countMismatched;
        this.countAvailable = countAvailable;
    }

    public ItemStack getStack()
    {
        return this.stack;
    }

    public long getCountTotal()
    {
        return this.countTotal;
    }

    public long getCountMissing()
    {
        return this.countMissing;
    }

    public void setCountMissing(long countMissing)
    {
        this.countMissing = countMissing;
    }

    public long getCountMismatched()
    {
        return this.countMismatched;
    }

    public void setCountMismatched(long countMismatched)
    {
        this.countMismatched = countMismatched;
    }

    public long getCountAvailable()
    {
        return this.countAvailable;
    }

    public void setCountAvailable(long countAvailable)
    {
        this.countAvailable = countAvailable;
    }

    public String getDisplayName()
    {
        return this.stack.getName().getString();
    }

    public static final PacketCodec<RegistryByteBuf, MaterialEntry> PACKET_CODEC = PacketCodec.tuple(
            ItemStack.PACKET_CODEC, MaterialEntry::getStack,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountTotal,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountMissing,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountMismatched,
            PacketCodecs.VAR_LONG, MaterialEntry::getCountAvailable,
            MaterialEntry::new
    );

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MaterialEntry that = (MaterialEntry) o;
        return ItemStack.areEqual(this.stack, that.stack);
    }

    @Override
    public int hashCode()
    {
        return ItemStack.hashCode(this.stack);
    }
}