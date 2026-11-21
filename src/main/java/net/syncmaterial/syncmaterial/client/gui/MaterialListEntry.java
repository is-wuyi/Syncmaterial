package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//? if >=26.2 {
import fi.dy.masa.malilib.util.data.ItemType;
//?} else {
/*import fi.dy.masa.malilib.util.ItemType;
*///?}
import net.minecraft.world.item.ItemStack;

public class MaterialListEntry implements net.syncmaterial.syncmaterial.client.PickupModeState.MaterialSnapshot
{
    private final int databaseId;
    private final ItemType item;
    private final long countTotal;
    private long countMissing;
    private final long countMismatched;
    private long countAvailable;
    private long stagingCount;
    private long warehouseCount;
    private long otherPlayersCount;
    private boolean currentPlayerClaimed;
    private boolean authoritativeTeamCounts;
    private net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView progressView;
    private List<ParticipantData> participants = new ArrayList<>();

    public record ParticipantData(String playerName, long count) {}

    public MaterialListEntry(int databaseId, ItemStack stack, long countTotal, long countMissing,
                             long countMismatched, long countAvailable)
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

    public long getCountTotal()
    {
        return this.countTotal;
    }

    public long getCountMissing()
    {
        return this.countMissing;
    }

    public void setCountMissing(long countMissing) {
        this.countMissing = countMissing;
    }

    public long getCountMismatched()
    {
        return this.countMismatched;
    }

    public long getCountAvailable()
    {
        return this.countAvailable;
    }

    public void setCountAvailable(long countAvailable)
    {
        this.countAvailable = countAvailable;
    }

    public long getStagingCount() { return stagingCount; }
    public void setStagingCount(long stagingCount) { this.stagingCount = stagingCount; }

    public long getWarehouseCount() { return warehouseCount; }
    public void setWarehouseCount(long warehouseCount) { this.warehouseCount = warehouseCount; }

    public long getOtherPlayersCount() { return otherPlayersCount; }
    public void setOtherPlayersCount(long otherPlayersCount) { this.otherPlayersCount = otherPlayersCount; }

    public boolean isCurrentPlayerClaimed() {
        return currentPlayerClaimed;
    }

    public void setCurrentPlayerClaimed(boolean claimed) {
        this.currentPlayerClaimed = claimed;
    }

    public List<ParticipantData> getParticipants() { return Collections.unmodifiableList(participants); }
    public void setParticipants(List<ParticipantData> participants) {
        this.participants = new ArrayList<>(participants);
        var player = net.minecraft.client.Minecraft.getInstance().player;
        String name = player == null ? "" : player.getName().getString();
        this.currentPlayerClaimed = this.participants.stream().anyMatch(p -> p.playerName().equals(name));
    }

    // ===== PickupModeState.MaterialSnapshot：为取货需求计算提供统一读取口径 =====

    @Override
    public String itemId() { return this.getStack().getItem().toString(); }

    @Override
    public long countTotal() { return this.countTotal; }

    @Override
    public long stagingCount() { return this.stagingCount; }

    /** 快照语义下的"我的数量"即背包可用数 */
    @Override
    public long myCount() { return this.countAvailable; }

    @Override
    public long warehouseCount() { return this.warehouseCount; }

    @Override
    public boolean claimedByCurrentPlayer() { return this.isCurrentPlayerClaimed(); }

    /** 饱和的观测是下界；不能把 Long.MAX_VALUE 绘制成精确总数。 */
    public String observedCountText(long count) {
        return progressView != null && progressView.teamObservedSaturated() && count == Long.MAX_VALUE
                ? "≥" + count : Long.toString(count);
    }

    public boolean hasAuthoritativeTeamCounts() {
        return this.authoritativeTeamCounts;
    }

    public void markAuthoritativeTeamCounts() {
        this.authoritativeTeamCounts = true;
    }

    public void setProgressView(net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView view) {
        this.progressView = view;
        if (view != null) this.authoritativeTeamCounts = true;
    }

    public net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView progressView(MaterialListBase list) {
        if (progressView != null) {
            if (list.isPreparationKnown() || !progressView.preparationKnown()) return progressView;
            return new net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView(
                    progressView.materialId(), progressView.itemId(), progressView.required(),
                    progressView.staging(), progressView.warehouse(), progressView.claimed(),
                    progressView.myObserved(), progressView.otherObserved(), progressView.teamMissing(),
                    progressView.pickupMissing(), false, false, false, progressView.teamObservedSaturated());
        }
        boolean known = list.isPreparationKnown();
        return new net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView(
                databaseId, itemId(), countTotal, stagingCount, warehouseCount,
                currentPlayerClaimed, countAvailable, otherPlayersCount, countMissing,
                net.syncmaterial.syncmaterial.api.ProgressFormulas.pickupMissing(
                        countTotal, stagingCount, countAvailable, warehouseCount),
                known && countTotal > 0 && stagingCount >= countTotal,
                known && countMissing == 0, known);
    }

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
