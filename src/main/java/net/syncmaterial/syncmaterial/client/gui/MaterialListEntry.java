package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fi.dy.masa.malilib.util.data.ItemType;
import net.minecraft.world.item.ItemStack;

public class MaterialListEntry
{
    private final int databaseId;
    private final ItemType item;
    private final int countTotal;
    private int countMissing;
    private final int countMismatched;
    private int countAvailable;
    private int stagingCount;
    private int warehouseCount;
    private int otherPlayersCount;
    private List<ParticipantData> participants = new ArrayList<>();

    public record ParticipantData(String playerName, int count) {}

    public MaterialListEntry(int databaseId, ItemStack stack, int countTotal, int countMissing, int countMismatched, int countAvailable)
    {
        this.databaseId = databaseId;
        this.item = new ItemType(stack, true, false);
        this.countTotal = countTotal;
        this.countMissing = countMissing;
        this.countMismatched = countMismatched;
        this.countAvailable = countAvailable;
    }

    public int getDatabaseId() { return databaseId; }

    public ItemStack getStack()
    {
        return this.item.getStack();
    }

    public int getCountTotal()
    {
        return this.countTotal;
    }

    public int getCountMissing()
    {
        return this.countMissing;
    }

    public void setCountMissing(int countMissing) {
        this.countMissing = countMissing;
    }

    public int getCountMismatched()
    {
        return this.countMismatched;
    }

    public int getCountAvailable()
    {
        return this.countAvailable;
    }

    public void setCountAvailable(int countAvailable)
    {
        this.countAvailable = countAvailable;
    }

    public int getStagingCount() { return stagingCount; }
    public void setStagingCount(int stagingCount) { this.stagingCount = stagingCount; }

    public int getWarehouseCount() { return warehouseCount; }
    public void setWarehouseCount(int warehouseCount) { this.warehouseCount = warehouseCount; }

    public int getOtherPlayersCount() { return otherPlayersCount; }
    public void setOtherPlayersCount(int otherPlayersCount) { this.otherPlayersCount = otherPlayersCount; }

    public boolean isCurrentPlayerClaimed() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return false;
        String playerName = player.getName().getString();
        for (ParticipantData p : participants) {
            if (p.playerName().equals(playerName)) return true;
        }
        return false;
    }

    public List<ParticipantData> getParticipants() { return Collections.unmodifiableList(participants); }
    public void setParticipants(List<ParticipantData> participants) { this.participants = new ArrayList<>(participants); }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.item == null) ? 0 : this.item.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        MaterialListEntry other = (MaterialListEntry) obj;
        if (this.item == null)
        {
            if (other.item != null)
                return false;
        }
        else if (! this.item.equals(other.item))
            return false;
        return true;
    }
}
