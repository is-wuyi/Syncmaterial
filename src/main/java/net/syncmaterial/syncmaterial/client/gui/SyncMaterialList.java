package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncMaterialList extends MaterialListBase {
    private final String schematicId;
    private final String title;
    private final Map<Integer, CollaborationStatusS2CPacket> collaborationStatusMap = new HashMap<>();
    private Runnable onStatusUpdate;

    public SyncMaterialList(String schematicId, String title) {
        this.schematicId = schematicId;
        this.title = title;
    }

    public void setOnStatusUpdate(Runnable callback) {
        this.onStatusUpdate = callback;
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
        this.collaborationStatusMap.clear();
        
        Map<String, Integer> itemIdToMaterialId = new HashMap<>();
        for (MaterialEntry entry : entries) {
            String itemId = entry.getStack().getItem().getRegistryEntry().getKey().map(k -> k.getValue().toString()).orElse("");
            itemIdToMaterialId.put(itemId, entry.getDatabaseId());
        }
        net.syncmaterial.syncmaterial.client.InventoryWatcher.setContext(schematicId, itemIdToMaterialId);
    }

    public void requestCollaborationStatus() {
        ClientPlayNetworking.send(new QueryMaterialStatusC2SPacket(schematicId));
    }

    public void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        collaborationStatusMap.put(status.materialId(), status);
        SyncMaterial.LOGGER.info("收到协作状态包: 材料 {} 协作组有 {} 人参与", status.materialId(), status.participants().size());
        for (var p : status.participants()) {
            SyncMaterial.LOGGER.info("  参与者 {} 持有 {} 个", p.playerName(), p.count());
        }
        updateEntriesWithCollaborationStatus();
        if (onStatusUpdate != null) {
            onStatusUpdate.run();
        }
    }

    private void updateEntriesWithCollaborationStatus() {
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (MaterialListEntry entry : entries) {
            CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
            if (status != null && !status.participants().isEmpty()) {
                int collected = status.stagingCount();
                for (var p : status.participants()) {
                    collected += p.count();
                }
                int remaining = Math.max(0, status.totalCount() - collected);
                SyncMaterial.LOGGER.info("更新材料 {} (dbId={}) 状态: total={}, staging={}, participants={}, collected={}, remaining={}",
                    entry.getStack().getName().getString(), entry.getDatabaseId(),
                    status.totalCount(), status.stagingCount(), status.participants().size(),
                    collected, remaining);
                this.setClaimStatus(entry, "剩余: " + remaining);
            } else {
                if (status != null && status.participants().isEmpty()) {
                    SyncMaterial.LOGGER.debug("材料 {} (dbId={}): participants 为空，显示'未认领'",
                        entry.getStack().getName().getString(), entry.getDatabaseId());
                }
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
            net.syncmaterial.syncmaterial.client.InventoryWatcher.forceUpdate();
        }
    }

    public boolean isCollaborating(MaterialListEntry entry) {
        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(MinecraftClient.getInstance().player.getGameProfile().getName()));
    }
}
