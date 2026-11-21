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
    private volatile boolean foundationBackend;
    private final Map<Integer, CollaborationStatusS2CPacket> collaborationStatusMap = new HashMap<>();
    private Runnable onStatusUpdate;
    /** 目录已原子替换时通知选择层，即使材料 GUI 当前被弹窗遮住。 */
    private Runnable onMaterialCatalogChanged;
    private volatile boolean allowSelfClaim = true;
    private volatile boolean isOwner = false;
    private net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot projectProgress;
    private volatile boolean projectProgressCurrent;
    private final Map<Integer, Long> pendingClaims = new HashMap<>();

    public boolean isClaimPending(int materialId) {
        return pendingClaims.containsKey(materialId);
    }

    public void completeClaim(net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket packet) {
        if (!schematicId.equals(packet.scope().isProject() ? packet.scope().projectId() : "")) return;
        boolean completed = pendingClaims.entrySet().removeIf(entry ->
                entry.getValue() == packet.requestId()
                        && (packet.materialId() == 0 || entry.getKey() == packet.materialId()));
        if (completed && onStatusUpdate != null) onStatusUpdate.run();
    }

    /** 未收到结果时只清待处理标记，不预写认领，不自动重试。 */
    public boolean claimTimeout(long requestId) {
        boolean cleared = pendingClaims.values().removeIf(value -> value == requestId);
        if (cleared && onStatusUpdate != null) onStatusUpdate.run();
        return cleared;
    }

    /** 当前屏幕绑定的材料目录投递序号；未收到目录更新时为 -1。 */
    private volatile long catalogRevision = -1;
    private volatile long managementCatalogRevision = -1;
    private String managementCatalogName = "";
    private List<ManagementMaterial> managementCatalogMaterials = List.of();

    private record ManagementMaterial(int id, String itemId, long required) {}

    public void applyManagementCatalog(net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket update) {
        if (!this.schematicId.equals(update.schematicId()) || update.revision() <= this.managementCatalogRevision) return;
        this.managementCatalogRevision = update.revision();
        this.managementCatalogName = update.schematicName();
        this.managementCatalogMaterials = update.materials().stream()
                .map(material -> new ManagementMaterial(material.getDatabaseId(),
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material.getStack().getItem()).toString(),
                        material.getCountTotal()))
                .sorted(java.util.Comparator.comparingInt(ManagementMaterial::id)).toList();
    }

    /** Protocol-4 management retains its delivery token, separate from protocol-5 persisted revision. */
    public long getManagementCatalogRevision() {
        if (this.projectProgress == null) return this.catalogRevision;
        List<ManagementMaterial> current = this.projectProgress.materials().stream()
                .map(material -> new ManagementMaterial(material.materialId(), material.itemId(), material.required()))
                .toList();
        return this.managementCatalogName.equals(this.projectProgress.projectName())
                && this.managementCatalogMaterials.equals(current) ? this.managementCatalogRevision : -1;
    }

    /** 已应用的最新区域库存快照序号；目录变化时重置。 */
    private volatile long lastInventorySequence = -1;
    /** 是否收到过与当前目录匹配的 v3 权威库存快照。 */
    private volatile boolean authoritativeInventory;
    /** v3 快照的 known 与 freshness 共同决定；旧协作状态不能改写。 */
    private volatile boolean authoritativeKnown;
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

    public void setOnMaterialCatalogChanged(Runnable callback) {
        this.onMaterialCatalogChanged = callback;
    }

    public interface Binding {
        void close();
    }

    /** Attaches one screen without transferring the project model's lifetime to it. */
    public Binding bind(Runnable statusUpdate, Runnable catalogChanged) {
        this.onStatusUpdate = statusUpdate;
        this.onMaterialCatalogChanged = catalogChanged;
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            if (this.onStatusUpdate == statusUpdate) this.onStatusUpdate = null;
            if (this.onMaterialCatalogChanged == catalogChanged) this.onMaterialCatalogChanged = null;
        };
    }

    /** Releases screen callbacks while retaining the authoritative model for HUD/pickup. */
    public void clearBindings() {
        this.onStatusUpdate = null;
        this.onMaterialCatalogChanged = null;
    }

    public boolean hasAreaOverlap() {
        return this.projectProgress != null && this.projectProgress.areaOverlap();
    }

    private void notifyMaterialCatalogChanged() {
        if (this.onMaterialCatalogChanged != null) this.onMaterialCatalogChanged.run();
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
        if (projectProgress != null) return projectProgressCurrent && projectProgress.preparationKnown();
        return !this.foundationBackend || (this.authoritativeInventory && this.authoritativeKnown);
    }

    public String preparationWarning() {
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> source = this.authoritativeInventory
                ? this.authoritativeFreshness
                : this.collaborationStatusMap.values().stream()
                        .flatMap(status -> status.freshnessInfo().stream())
                        .toList();
        return AreaWarningLabels.summary(source);
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialEntries(entries, -1);
    }

    /**
     * 材料目录变化即失效此前的区域库存权威状态：旧快照的序号与计数都属于旧目录，
     * 不能继续参与"是否备齐"的判断，也不能被随后到达的旧协作状态短暂洗白。
     */
    public void setMaterialEntries(List<MaterialEntry> entries, long catalogRevision) {
        this.projectProgress = null;
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
        notifyMaterialCatalogChanged();
    }

    /** Atomically applies the shared team progress and this viewer's pickup/claim projection. */
    public boolean applyProjectProgress(net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot snapshot) {
        if (snapshot == null || !schematicId.equals(snapshot.projectId()) || Minecraft.getInstance().player == null) {
            return false;
        }
        if (projectProgress != null && snapshot.publicationSequence() <= projectProgress.publicationSequence()) {
            return false;
        }
        var viewer = new net.syncmaterial.syncmaterial.core.PlayerIdentity(
                Minecraft.getInstance().player.getUUID(), Minecraft.getInstance().player.getName().getString());
        var view = snapshot.viewFor(viewer);
        Map<Integer, MaterialListEntry> existing = new HashMap<>();
        for (MaterialListEntry entry : getMaterialsAll()) existing.put(entry.getDatabaseId(), entry);
        Map<Integer, net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView> rows = new HashMap<>();
        for (var row : view.materials()) rows.put(row.materialId(), row);
        List<MaterialListEntry> next = new java.util.ArrayList<>();
        List<PreparedProgressRow> prepared = new java.util.ArrayList<>();
        Map<String, Integer> context = new HashMap<>();
        for (var material : snapshot.materials()) {
            var row = rows.get(material.materialId());
            var itemId = net.syncmaterial.syncmaterial.compat.McCompat.tryParseId(material.itemId());
            if (row == null || itemId == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId)) {
                return false;
            }
            var stack = new net.minecraft.world.item.ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId));
            MaterialListEntry entry = existing.get(material.materialId());
            if (entry == null || entry.getCountTotal() != material.required()
                    || !entry.getStack().getItem().equals(stack.getItem())) {
                entry = new MaterialListEntry(material.materialId(), stack, material.required(), 0, 0, 0);
            }
            var participants = material.participants().stream()
                    .map(participant -> new MaterialListEntry.ParticipantData(
                            participant.player().name(), participant.effectiveHeld()))
                    .toList();
            prepared.add(new PreparedProgressRow(entry, row, participants));
            next.add(entry);
            context.put(material.itemId(), material.materialId());
        }

        // 完整验证并准备全部行后才修改旧条目，非法尾项不能留下半份新快照。
        for (var preparedRow : prepared) {
            MaterialListEntry entry = preparedRow.entry();
            var row = preparedRow.view();
            entry.setCountMissing(row.teamMissing());
            entry.setCountAvailable(row.myObserved());
            entry.setOtherPlayersCount(row.otherObserved());
            entry.setStagingCount(row.staging());
            entry.setWarehouseCount(row.warehouse());
            entry.setParticipants(preparedRow.participants());
            entry.setCurrentPlayerClaimed(row.claimed());
            entry.setProgressView(row);
        }

        setMaterialListEntries(next);
        this.projectProgress = snapshot;
        this.projectProgressCurrent = true;
        this.title = snapshot.projectName();
        this.catalogRevision = snapshot.catalogRevision();
        this.lastInventorySequence = snapshot.publicationSequence();
        this.foundationBackend = true;
        this.authoritativeInventory = true;
        this.authoritativeKnown = snapshot.preparationKnown();
        this.authoritativeFreshness = snapshot.areaWarnings().stream()
                .map(warning -> new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                        warning.kind().wireName(), warning.areaId(), warning.areaName(), warning.status()))
                .toList();
        net.syncmaterial.syncmaterial.client.InventoryWatcher.setContext(schematicId, context);
        net.syncmaterial.syncmaterial.client.InventoryWatcher.enablePeriodicReports();
        notifyMaterialCatalogChanged();
        if (onStatusUpdate != null) onStatusUpdate.run();
        return true;
    }

    private record PreparedProgressRow(MaterialListEntry entry,
            net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView view,
            List<MaterialListEntry.ParticipantData> participants) {}

    public boolean hasProjectProgress() {
        return this.projectProgress != null;
    }

    public void invalidateProjectProgress() {
        this.projectProgressCurrent = false;
        if (onStatusUpdate != null) onStatusUpdate.run();
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
        if (projectProgress != null) return false;
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

    private record AreaCounts(long staging, long warehouse) {
    }

    public void requestCollaborationStatus() {
        if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                    session.refreshProject(schematicId));
            return;
        }
        ClientPlayNetworking.send(new QueryMaterialStatusC2SPacket(schematicId));
    }

    /** 测试钩子：读取指定材料最近一次广播的协作状态，未收到过返回 null。 */
    public CollaborationStatusS2CPacket getCollaborationStatusFor(int materialId) {
        return collaborationStatusMap.get(materialId);
    }

    public void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        collaborationStatusMap.put(status.materialId(), status);
        if (projectProgress != null) return;
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
        if (projectProgress != null) return;
        var player = Minecraft.getInstance().player;
        String myName = player != null ? player.getName().getString() : "";
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (MaterialListEntry entry : entries) {
            CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
            long allPlayersCount = 0;
            long myCount = 0;
            if (status != null) {
                for (var p : status.participants()) {
                    allPlayersCount = Math.addExact(allPlayersCount, p.count());
                    if (p.playerName().equals(myName)) {
                        myCount = p.count();
                    }
                }
            }

            // v3 权威快照优先：旧协作状态包里的备货区/仓库计数一律不得覆盖它。
            AreaCounts authority = this.authoritativeInventory
                    ? this.authoritativeCounts.get(entry.getDatabaseId())
                    : null;
            long stagingCount = authority != null
                    ? authority.staging()
                    : (status != null ? status.stagingCount() : 0);
            long warehouseCount = authority != null
                    ? authority.warehouse()
                    : (status != null ? status.warehouseCount() : 0);
            long realMissing = net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                entry.getCountTotal(), stagingCount, warehouseCount, allPlayersCount);

            entry.setCountMissing(realMissing);
            entry.markAuthoritativeTeamCounts();
            entry.setCountAvailable(myCount);
            entry.setStagingCount(stagingCount);
            entry.setWarehouseCount(warehouseCount);
            entry.setOtherPlayersCount(net.syncmaterial.syncmaterial.api.ProgressFormulas
                    .saturatingSubtract(allPlayersCount, myCount));
            entry.setCurrentPlayerClaimed(myCount > 0 || status != null && status.participants().stream()
                    .anyMatch(p -> p.playerName().equals(myName)));
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
        if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            if (projectProgress == null) {
                // 能力已协商但首份权威目录尚未到达，不能退回无关联的旧 JOIN/LEAVE 包。
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                        fi.dy.masa.malilib.util.StringUtils.translate(
                                "syncmaterial.gui.hint.project_view_not_ready"));
                return;
            }
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session -> {
                long request;
                if (entry.isCurrentPlayerClaimed()) {
                    request = session.leave(schematicId, entry.getDatabaseId());
                } else if (allowSelfClaim || isOwner) {
                    long count = net.syncmaterial.syncmaterial.client.InventoryScanner
                            .liveCountsByItemId(Minecraft.getInstance().player)
                            .getOrDefault(entry.itemId(), 0L);
                    request = session.join(schematicId, entry.getDatabaseId(), List.of(
                            new net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket.InventoryCount(
                                    entry.getDatabaseId(), count)));
                } else {
                    request = -1;
                }
                if (request > 0) {
                    pendingClaims.put(entry.getDatabaseId(), request);
                    if (onStatusUpdate != null) onStatusUpdate.run();
                }
            });
            return;
        }

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
        if (projectProgress != null) return entry.isCurrentPlayerClaimed();
        CollaborationStatusS2CPacket status = collaborationStatusMap.get(entry.getDatabaseId());
        return status != null && status.participants().stream().anyMatch(p -> p.playerName().equals(Minecraft.getInstance().player.getName().getString()));
    }
}
