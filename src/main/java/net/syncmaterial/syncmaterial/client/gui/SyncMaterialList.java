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
    private boolean allowSelfClaim = true;
    private boolean isOwner = false;

    public SyncMaterialList(String schematicId, String title) {
        this.schematicId = schematicId;
        this.title = title;
    }

    public void setAllowSelfClaim(boolean allowSelfClaim) {
        this.allowSelfClaim = allowSelfClaim;
    }

    public void setIsOwner(boolean isOwner) {
        this.isOwner = isOwner;
    }

    public boolean isAllowSelfClaim() { return allowSelfClaim; }
    public boolean isOwner() { return isOwner; }

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
        String myName = MinecraftClient.getInstance().player.getGameProfile().getName();
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (MaterialListEntry entry : entries) {
            CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
            if (status != null && (status.stagingCount() > 0 || !status.participants().isEmpty())) {
                // 计算所有玩家背包总量
                int allPlayersCount = 0;
                int myCount = 0;
                for (var p : status.participants()) {
                    allPlayersCount += p.count();
                    if (p.playerName().equals(myName)) {
                        myCount = p.count();
                    }
                }
                int otherPlayersCount = allPlayersCount - myCount;
                int realMissing = Math.max(0, status.totalCount() - status.stagingCount() - allPlayersCount);

                entry.setCountMissing(realMissing);
                entry.setCountAvailable(myCount);
                entry.setStagingCount(status.stagingCount());
                entry.setOtherPlayersCount(otherPlayersCount);
                entry.setParticipants(status.participants().stream()
                    .map(p -> new MaterialListEntry.ParticipantData(p.playerName(), p.count()))
                    .toList());
            } else {
                // 无人协作，缺失 = 总数 - 备货区（可能备货区有物品但无人认领）
                int stagingCount = status != null ? status.stagingCount() : 0;
                int realMissing = Math.max(0, entry.getCountTotal() - stagingCount);
                entry.setCountMissing(realMissing);
                entry.setCountAvailable(0);
                entry.setStagingCount(stagingCount);
                entry.setOtherPlayersCount(0);
                entry.setParticipants(java.util.Collections.emptyList());
            }
        }
        this.updateCounts();
    }

    @Override
    public void claimEntry(MaterialListEntry entry) {
        if (entry == null) return;

        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        if (status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(MinecraftClient.getInstance().player.getGameProfile().getName()))) {
            // 已认领 → 退出协作（任何时候都允许）
            ClientPlayNetworking.send(new LeaveCollaborationC2SPacket(schematicId, entry.getDatabaseId()));
        } else {
            // 未认领 → 检查是否允许自行认领
            if (!allowSelfClaim && !isOwner) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket(schematicId));
                return;
            }
            Map<Integer, Integer> inventoryCounts = net.syncmaterial.syncmaterial.client.InventoryWatcher.getCurrentCounts();
            ClientPlayNetworking.send(new JoinCollaborationC2SPacket(schematicId, entry.getDatabaseId(), inventoryCounts));
        }
    }

    public boolean isCollaborating(MaterialListEntry entry) {
        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(MinecraftClient.getInstance().player.getGameProfile().getName()));
    }
}
