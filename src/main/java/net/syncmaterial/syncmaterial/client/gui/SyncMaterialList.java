package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncMaterialList extends MaterialListBase {
    private final String schematicId;
    private final String title;
    private final Map<Integer, CollaborationStatusS2CPacket> collaborationStatusMap = new HashMap<>();

    public SyncMaterialList(String schematicId, String title) {
        this.schematicId = schematicId;
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

    public String getSchematicId() {
        return this.schematicId;
    }

    @Override
    public void reCreateMaterialList() {
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
        
        Map<String, Integer> itemIdToMaterialId = new HashMap<>();
        for (MaterialEntry entry : entries) {
            String itemId = entry.getStack().getItem().getRegistryEntry().getKey().map(k -> k.getValue().toString()).orElse("");
            itemIdToMaterialId.put(itemId, entry.getDatabaseId());
        }
        net.syncmaterial.syncmaterial.client.InventoryWatcher.setContext(schematicId, itemIdToMaterialId);
    }

    public void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        collaborationStatusMap.put(status.materialId(), status);
        updateEntriesWithCollaborationStatus();
    }

    private void updateEntriesWithCollaborationStatus() {
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (MaterialListEntry entry : entries) {
            CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
            if (status != null) {
                int collected = status.stagingCount();
                for (var p : status.participants()) {
                    collected += p.count();
                }
                int remaining = Math.max(0, status.totalCount() - collected);
                this.setClaimStatus(entry, "剩余: " + remaining);
            } else {
                this.setClaimStatus(entry, "未认领");
            }
        }
        this.updateCounts();
    }

    @Override
    public void claimEntry(MaterialListEntry entry) {
        if (entry == null) return;

        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        if (status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(MinecraftClient.getInstance().player.getGameProfile().getName()))) {
            ClientPlayNetworking.send(new LeaveCollaborationC2SPacket(schematicId, entry.getDatabaseId()));
        } else {
            ClientPlayNetworking.send(new JoinCollaborationC2SPacket(schematicId, entry.getDatabaseId()));
        }
    }

    public boolean isCollaborating(MaterialListEntry entry) {
        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(MinecraftClient.getInstance().player.getGameProfile().getName()));
    }
}
