package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import net.syncmaterial.syncmaterial.network.JoinCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.LeaveCollaborationC2SPacket;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncMaterialList extends MaterialListBase {
    private final String schematicId;
    private String title;
    private boolean foundationBackend;
    private final Map<Integer, CollaborationStatusS2CPacket> collaborationStatusMap = new HashMap<>();
    private Runnable onStatusUpdate;
    private boolean allowSelfClaim = true;
    private boolean isOwner = false;

    /** 当前屏幕绑定的材料目录投递序号；未收到目录更新时为 -1。 */
    private long catalogRevision = -1;
    /** 已应用的最新区域库存快照序号；目录变化时重置。 */
    private long lastInventorySequence = -1;
    /** 是否收到过与当前目录匹配的 v3 权威库存快照。 */
    private boolean authoritativeInventory;
    /** v3 快照的 known 与 freshness 共同决定；旧协作状态不能改写。 */
    private boolean authoritativeKnown;
    private final Map<Integer, AreaCounts> authoritativeCounts = new HashMap<>();
    private List<CollaborationStatusS2CPacket.AreaFreshnessInfo> authoritativeFreshness = List.of();

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

    public void setTitle(String title) {
        this.title = title;
    }

    public void useFoundationBackend() {
        this.foundationBackend = true;
    }

    @Override
    public boolean isPreparationKnown() {
        // 旧服务端没有区域权威数据，保持既有行为；实验后端必须先收到 v3 快照，
        // 且快照的 known 为真（freshness 为空），才允许显示"已备齐"。
        return !this.foundationBackend || (this.authoritativeInventory && this.authoritativeKnown);
    }

    public String preparationWarning() {
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> source = this.authoritativeInventory
                ? this.authoritativeFreshness
                : this.collaborationStatusMap.values().stream()
                        .flatMap(status -> status.freshnessInfo().stream())
                        .toList();
        return source.stream()
                .map(CollaborationStatusS2CPacket.AreaFreshnessInfo::status)
                .filter(status -> status != null && !status.isBlank())
                .findFirst().orElse(fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.label.freshness_warning"));
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialEntries(entries, -1);
    }

    /**
     * 材料目录变化即失效此前的区域库存权威状态：旧快照的序号与计数都属于旧目录，
     * 不能继续参与"是否备齐"的判断，也不能被随后到达的旧协作状态短暂洗白。
     */
    public void setMaterialEntries(List<MaterialEntry> entries, long catalogRevision) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
        this.collaborationStatusMap.clear();
        this.catalogRevision = catalogRevision;
        this.invalidateAreaInventory();

        Map<String, Integer> itemIdToMaterialId = new HashMap<>();
        for (MaterialEntry entry : entries) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString();
            itemIdToMaterialId.put(itemId, entry.getDatabaseId());
        }
        net.syncmaterial.syncmaterial.client.InventoryWatcher.setContext(schematicId, itemIdToMaterialId);
    }

    public long getCatalogRevision() {
        return this.catalogRevision;
    }

    public long getLastAreaInventorySequence() {
        return this.lastInventorySequence;
    }

    public boolean hasAuthoritativeInventory() {
        return this.authoritativeInventory;
    }

    public boolean isAuthoritativeInventoryKnown() {
        return this.authoritativeInventory && this.authoritativeKnown;
    }

    public List<CollaborationStatusS2CPacket.AreaFreshnessInfo> getAreaFreshness() {
        return this.authoritativeFreshness;
    }

    /**
     * 应用 v3 区域库存快照。快照必须属于当前材料目录、序号更新、且材料 id 集合
     * 与当前列表完全一致（空目录也是合法全集）。任一条件不满足即整包丢弃，
     * 避免异步读取乱序覆盖较新的权威视图。
     *
     * @return 是否接受并应用
     */
    public boolean applyAreaInventorySnapshot(AreaInventorySnapshotS2CPacket snapshot) {
        if (snapshot == null || !this.schematicId.equals(snapshot.schematicId())
                || this.catalogRevision < 0 || snapshot.catalogRevision() != this.catalogRevision
                || snapshot.sequence() <= this.lastInventorySequence) {
            return false;
        }

        Set<Integer> expectedIds = new HashSet<>();
        for (MaterialListEntry entry : this.getMaterialsAll()) {
            expectedIds.add(entry.getDatabaseId());
        }
        Set<Integer> receivedIds = new HashSet<>();
        Map<Integer, AreaCounts> nextCounts = new HashMap<>();
        for (AreaInventorySnapshotS2CPacket.MaterialInventory material : snapshot.materials()) {
            // 未知材料（不属于当前目录）或重复 id 都视为整包不可信
            if (!expectedIds.contains(material.materialId())
                    || !receivedIds.add(material.materialId())) {
                return false;
            }
            nextCounts.put(material.materialId(),
                    new AreaCounts(material.stagingCount(), material.warehouseCount()));
        }
        if (!receivedIds.equals(expectedIds)) {
            return false;
        }

        this.authoritativeCounts.clear();
        this.authoritativeCounts.putAll(nextCounts);
        this.authoritativeInventory = true;
        // 防御恶意/畸形包：known 只有在该包没有 freshness 警告时才成立。
        this.authoritativeKnown = effectiveAuthoritativeKnown(snapshot.known(), snapshot.freshnessInfo());
        this.lastInventorySequence = snapshot.sequence();
        this.authoritativeFreshness = List.copyOf(snapshot.freshnessInfo());
        updateEntriesWithCollaborationStatus();
        if (onStatusUpdate != null) {
            onStatusUpdate.run();
        }
        return true;
    }

    /** 目录切换或断线时清空区域权威状态，回到"未知"而不是虚假的 0/已备齐。 */
    public void invalidateAreaInventory() {
        this.lastInventorySequence = -1;
        this.authoritativeInventory = false;
        this.authoritativeKnown = false;
        this.authoritativeCounts.clear();
        this.authoritativeFreshness = List.of();
    }

    /** known 只能在没有 freshness 警告时成立；畸形包也不能显示"已备齐"。 */
    static boolean effectiveAuthoritativeKnown(boolean packetKnown,
            List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessInfo) {
        return packetKnown && !AreaInventorySnapshotS2CPacket.hasFreshnessWarning(freshnessInfo);
    }

    private record AreaCounts(int staging, int warehouse) {
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
        // 旧协作状态只在还没有 v3 权威快照时提供新鲜度提示；收到空列表同样清除旧警告。
        // 已有权威快照后，旧包不得改写权威库存的"已知"状态或覆盖其 freshness。
        if (!this.authoritativeInventory
                && net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof GuiMaterialList gui) {
            gui.updateFreshnessWarnings(status.freshnessInfo());
        }
        if (onStatusUpdate != null) {
            onStatusUpdate.run();
        }
    }

    private void updateEntriesWithCollaborationStatus() {
        var player = Minecraft.getInstance().player;
        String myName = player != null ? player.getName().getString() : "";
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (MaterialListEntry entry : entries) {
            CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
            int allPlayersCount = 0;
            int myCount = 0;
            if (status != null) {
                for (var p : status.participants()) {
                    allPlayersCount += p.count();
                    if (p.playerName().equals(myName)) {
                        myCount = p.count();
                    }
                }
            }

            // v3 权威快照优先：旧协作状态包里的备货区/仓库计数一律不得覆盖它。
            AreaCounts authority = this.authoritativeInventory
                    ? this.authoritativeCounts.get(entry.getDatabaseId())
                    : null;
            int stagingCount = authority != null
                    ? authority.staging()
                    : (status != null ? status.stagingCount() : 0);
            int warehouseCount = authority != null
                    ? authority.warehouse()
                    : (status != null ? status.warehouseCount() : 0);
            int realMissing = (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                entry.getCountTotal(), stagingCount, warehouseCount, allPlayersCount);

            entry.setCountMissing(realMissing);
            entry.setCountAvailable(myCount);
            entry.setStagingCount(stagingCount);
            entry.setWarehouseCount(warehouseCount);
            entry.setOtherPlayersCount(allPlayersCount - myCount);
            entry.setParticipants(status != null
                    ? status.participants().stream()
                            .map(p -> new MaterialListEntry.ParticipantData(p.playerName(), p.count()))
                            .toList()
                    : java.util.Collections.emptyList());
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
            if (this.foundationBackend) {
                inventoryCounts = Map.of(entry.getDatabaseId(), inventoryCounts.getOrDefault(entry.getDatabaseId(), 0));
            }
            ClientPlayNetworking.send(new JoinCollaborationC2SPacket(schematicId, entry.getDatabaseId(), inventoryCounts));
        }
    }

    public boolean isCollaborating(MaterialListEntry entry) {
        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(Minecraft.getInstance().player.getName().getString()));
    }
}
