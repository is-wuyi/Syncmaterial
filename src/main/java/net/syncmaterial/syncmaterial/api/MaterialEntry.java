package net.syncmaterial.syncmaterial.api;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public class MaterialEntry
{
    private final int databaseId;
    private final ItemStack stack;
    private final long countTotal;
    private long countMissing;
    private long countMismatched;
    private long countAvailable;

    public MaterialEntry(int databaseId, ItemStack stack, long countTotal)
    {
        this(databaseId, stack, countTotal, countTotal, 0, 0);
    }

    public MaterialEntry(int databaseId, ItemStack stack, long countTotal, long countMissing, long countMismatched, long countAvailable)
    {
        this.databaseId = databaseId;
        this.stack = Objects.requireNonNull(stack, "ItemStack cannot be null");
        this.countTotal = countTotal;
        this.countMissing = countMissing;
        this.countMismatched = countMismatched;
        this.countAvailable = countAvailable;
    }

    public int getDatabaseId() { return databaseId; }

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
        return this.stack.getHoverName().getString();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialEntry> PACKET_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaterialEntry::getDatabaseId,
            ItemStack.STREAM_CODEC, MaterialEntry::getStack,
            ByteBufCodecs.VAR_LONG, MaterialEntry::getCountTotal,
            ByteBufCodecs.VAR_LONG, MaterialEntry::getCountMissing,
            ByteBufCodecs.VAR_LONG, MaterialEntry::getCountMismatched,
            ByteBufCodecs.VAR_LONG, MaterialEntry::getCountAvailable,
            MaterialEntry::new
    );

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MaterialEntry that = (MaterialEntry) o;
        return ItemStack.isSameItem(this.stack, that.stack);
    }

    @Override
    public int hashCode()
    {
        return this.stack.getItem().hashCode();
    }
}
