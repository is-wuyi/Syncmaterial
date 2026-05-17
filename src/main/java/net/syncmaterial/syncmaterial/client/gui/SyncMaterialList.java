package net.syncmaterial.syncmaterial.client.gui;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.network.MaterialStatusS2CPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncMaterialList extends MaterialListBase {
    private final String title;
    private final Map<Integer, MaterialStatusS2CPacket.MaterialStatusEntry> materialStatusMap = new HashMap<>();

    public SyncMaterialList(String title) {
        this.title = title;
    }

    @Override
    public String getName() {
        return this.title;
    }

    @Override
    public String getTitle() {
        return this.title;
    }

    @Override
    public void reCreateMaterialList() {
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
    }

    public void updateMaterialStatus(List<MaterialStatusS2CPacket.MaterialStatusEntry> statuses) {
        materialStatusMap.clear();
        for (var status : statuses) {
            materialStatusMap.put(status.materialId(), status);
        }
        updateEntriesWithStatus();
    }

    public Map<Integer, MaterialStatusS2CPacket.MaterialStatusEntry> getMaterialStatusMap() {
        return materialStatusMap;
    }

    private void updateEntriesWithStatus() {
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (int i = 0; i < entries.size(); i++) {
            MaterialListEntry entry = entries.get(i);
            MaterialStatusS2CPacket.MaterialStatusEntry status = materialStatusMap.get(i + 1);
            if (status != null) {
                entry.setCountMissing(Math.max(0, status.totalCount() - status.claimedCount()));
            }
        }
        this.updateCounts();
    }
}
