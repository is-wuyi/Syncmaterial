package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.Minecraft;
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
    public String getTitle() {
        return this.title;
    }

    public String getSchematicId() {
        return this.schematicId;
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
        this.collaborationStatusMap.clear();
        
        Map<String, Integer> itemIdToMaterialId = new HashMap<>();
        for (MaterialEntry entry : entries) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString();
            itemIdToMaterialId.put(itemId, entry.getDatabaseId());
        }
        net.syncmaterial.syncmaterial.client.InventoryWatcher.setContext(schematicId, itemIdToMaterialId);
    }

    public void requestCollaborationStatus() {
        ClientPlayNetworking.send(new QueryMaterialStatusC2SPacket(schematicId));
    }

    /** 测试钩子：读取指定材料最近一次广播的协作状态，未收到过返回 null。 */
    public CollaborationStatusS2CPacket getCollaborationStatusFor(int materialId) {
        return collaborationStatusMap.get(materialId);
    }

    public void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        collaborationStatusMap.put(status.materialId(), status);
        SyncMaterial.LOGGER.info("收到协作状态包: 材料 {} 协作组有 {} 人参与", status.materialId(), status.participants().size());
        for (var p : status.participants()) {
            SyncMaterial.LOGGER.info("  参与者 {} 持有 {} 个", p.playerName(), p.count());
        }
        updateEntriesWithCollaborationStatus();
        // Phase 5: 更新数据新鲜度警告（取最新的 freshnessInfo，所有材料共享同一份）
        if (!status.freshnessInfo().isEmpty() && Minecraft.getInstance().gui.screen() instanceof GuiMaterialList gui) {
            gui.updateFreshnessWarnings(status.freshnessInfo());
        }
        if (onStatusUpdate != null) {
            onStatusUpdate.run();
        }
    }

    private void updateEntriesWithCollaborationStatus() {
        String myName = Minecraft.getInstance().player.getName().getString();
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
                int realMissing = (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                    status.totalCount(), status.stagingCount(), status.warehouseCount(), allPlayersCount);

                entry.setCountMissing(realMissing);
                entry.setCountAvailable(myCount);
                entry.setStagingCount(status.stagingCount());
                entry.setWarehouseCount(status.warehouseCount());
                entry.setOtherPlayersCount(otherPlayersCount);
                entry.setParticipants(status.participants().stream()
                    .map(p -> new MaterialListEntry.ParticipantData(p.playerName(), p.count()))
                    .toList());
            } else {
                // 无人协作，缺失 = 总数 - 备货区 - 仓库（可能备货区/仓库有物品但无人认领）
                int stagingCount = status != null ? status.stagingCount() : 0;
                int warehouseCount = status != null ? status.warehouseCount() : 0;
                int realMissing = (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                    entry.getCountTotal(), stagingCount, warehouseCount, 0);
                entry.setCountMissing(realMissing);
                entry.setCountAvailable(0);
                entry.setStagingCount(stagingCount);
                entry.setWarehouseCount(warehouseCount);
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
        if (status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(Minecraft.getInstance().player.getName().getString()))) {
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
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(Minecraft.getInstance().player.getName().getString()));
    }
}
