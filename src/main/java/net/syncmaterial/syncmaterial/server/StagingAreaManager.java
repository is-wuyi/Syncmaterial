package net.syncmaterial.syncmaterial.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

public class StagingAreaManager {
    private final SchematicDatabase database;
    private final Map<String, List<StagingArea>> stagingAreasBySchematic = new ConcurrentHashMap<>();
    // volatile 保证读线程立即看到新的 Map 引用（原子替换，无 clear/putAll 窗口期）
    private volatile Map<String, List<StagingArea>> stagingAreasByWorld = new ConcurrentHashMap<>();
    // Phase 5: 全局仓库
    private final Map<Integer, Warehouse> warehousesById = new ConcurrentHashMap<>();
    private volatile Map<String, List<Warehouse>> warehousesByWorld = new ConcurrentHashMap<>();
    private final Map<BlockPos, ServerLevel> dirtyContainers = new ConcurrentHashMap<>();
    // 延迟重扫：容器被移除后，记录需要延迟一个 tick 处理的位置
    // key=位置, value=添加时的 tick（只延迟一个 tick，下个 tick 正常处理）
    private final Map<BlockPos, Integer> pendingDeferredRescans = new ConcurrentHashMap<>();
    private int currentServerTick = 0;
    // 防止 markRemoved 在多个 tick 重复触发导致重入
    private final Set<BlockPos> processedRemovals = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<ServerPlayer>> subscribers = new ConcurrentHashMap<>();
    // Phase 5: 初始化跟踪（服务器重启后，区域首次全部区块加载完成即标记为已初始化）
    // 初始化状态按 "S:区域id"/"W:仓库id" 记录：备货区和仓库的自增 id 各自独立，
    // 不加前缀会互相串（备货区 3 已初始化 ≠ 仓库 3 已初始化）
    private final Set<String> initializedAreas = ConcurrentHashMap.newKeySet();
    // 临时区块跟踪：初始化阶段记录已扫描的区块，全部扫描完成后清除
    private final Map<String, Set<Long>> initChunkTracking = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public StagingAreaManager(SchematicDatabase database) {
        this.database = database;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void subscribe(ServerPlayer player, String schematicId) {
        subscribers.computeIfAbsent(schematicId, k -> ConcurrentHashMap.newKeySet()).add(player);
        SyncMaterial.LOGGER.info("[StagingArea] 玩家 {} 订阅了原理图 {} 的备货区更新", player.getName().getString(), schematicId);
    }

    public void unsubscribe(ServerPlayer player, String schematicId) {
        Set<ServerPlayer> set = subscribers.get(schematicId);
        if (set != null) {
            set.remove(player);
            // 两参数 remove 做值比对：避免误删并发场景下刚被其他玩家重新填充的集合
            if (set.isEmpty()) {
                subscribers.remove(schematicId, set);
            }
        }
    }

    public void unsubscribeAll(ServerPlayer player) {
        for (var entry : subscribers.entrySet()) {
            entry.getValue().remove(player);
        }
        subscribers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public void broadcastUpdate(String schematicId) {
        Set<ServerPlayer> set = subscribers.get(schematicId);
        if (set == null || set.isEmpty()) {
            return;
        }

        String schematicName = getSchematicNameFromDb(schematicId);
        List<StagingArea> areas = getStagingAreas(schematicId);
        List<StagingAreaConfigResponseS2CPacket.AreaInfo> areaInfos = buildAreaInfos(areas);
        StagingAreaConfigResponseS2CPacket packet = new StagingAreaConfigResponseS2CPacket("LIST", schematicId, schematicName, true, "", areaInfos);

        ModNetworkHandler.sendToPlayers(set, packet);
        SyncMaterial.LOGGER.info("[StagingArea] 广播原理图 {} 的备货区更新给 {} 个玩家", schematicId, set.size());
    }

    public int addStagingArea(String schematicId, String world, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            // 检查同原理图下是否已有同名备货区，如有则加后缀
            String finalName = name;
            try (var rs = database.executeQuery(
                "SELECT COUNT(*) FROM staging_areas WHERE schematic_id = ? AND name = ?",
                schematicId, name
            )) {
                if (rs.next() && rs.getInt(1) > 0) {
                    int suffix = 2;
                    while (true) {
                        String candidate = name + " (" + suffix + ")";
                        try (var rs2 = database.executeQuery(
                            "SELECT COUNT(*) FROM staging_areas WHERE schematic_id = ? AND name = ?",
                            schematicId, candidate
                        )) {
                            if (rs2.next() && rs2.getInt(1) == 0) {
                                finalName = candidate;
                                break;
                            }
                        }
                        suffix++;
                    }
                }
            }

            database.executeUpdate(
                "INSERT INTO staging_areas (schematic_id, world, name, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                schematicId, world, finalName, x1, y1, z1, x2, y2, z2
            );

            try (var rs = database.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    int areaId = rs.getInt(1);
                    refreshCache(schematicId);
                    SyncMaterial.LOGGER.info("Added staging area {} for schematic {}", areaId, schematicId);
                    return areaId;
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to add staging area", e);
        }
        return -1;
    }

    public void renameStagingArea(int areaId, String schematicId, String newName) {
        try {
            database.executeUpdate("UPDATE staging_areas SET name = ? WHERE id = ?", newName, areaId);
            refreshCache(schematicId);
            SyncMaterial.LOGGER.info("Renamed staging area {} to {}", areaId, newName);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to rename staging area", e);
        }
    }

    public void updateStagingArea(int areaId, String schematicId, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        updateStagingArea(areaId, schematicId, name, null, x1, y1, z1, x2, y2, z2);
    }

    /**
     * 更新备货区，可同时迁移维度。
     * world 传 null 表示保持原维度；与 updateWarehouse 同理，
     * 只改坐标不改 world 会让数据自相矛盾（渲染与扫描都按 world 过滤）。
     */
    public void updateStagingArea(int areaId, String schematicId, String name, @javax.annotation.Nullable String world,
                                  int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            if (world != null) {
                database.executeUpdate(
                    "UPDATE staging_areas SET name = ?, world = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ? WHERE id = ?",
                    name, world, x1, y1, z1, x2, y2, z2, areaId
                );
            } else {
                database.executeUpdate(
                    "UPDATE staging_areas SET name = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ? WHERE id = ?",
                    name, x1, y1, z1, x2, y2, z2, areaId
                );
            }
            refreshCache(schematicId);
            // 范围变了：重置初始化状态，扩大的新领土重新按区块跟踪补扫
            resetInitState("staging_area", areaId);
            SyncMaterial.LOGGER.info("Updated staging area {} coordinates to [{},{},{}]~[{},{},{}]", areaId, x1, y1, z1, x2, y2, z2);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update staging area", e);
        }
    }

    public void removeStagingArea(int areaId, String schematicId) {
        try {
            database.executeUpdate("DELETE FROM staging_areas WHERE id = ?", areaId);
            refreshCache(schematicId);
            resetInitState("staging_area", areaId);
            SyncMaterial.LOGGER.info("Removed staging area {}", areaId);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to remove staging area", e);
        }
    }

    public List<StagingArea> getStagingAreas(String schematicId) {
        boolean needsRebuild = !stagingAreasBySchematic.containsKey(schematicId);
        List<StagingArea> areas = stagingAreasBySchematic.computeIfAbsent(schematicId, this::loadStagingAreasFromDb);
        // computeIfAbsent 首次加载时不经过 refreshCache，需要重建世界索引
        if (needsRebuild && !areas.isEmpty()) {
            rebuildWorldIndex();
        }
        return areas;
    }

    /**
     * 检查位置是否在任何备货区或仓库区域内（脏容器检测用）
     */
    public boolean isInAnyContainerArea(BlockPos pos, ServerLevel world) {
        String worldId = world.dimension().identifier().toString();
        // 检查备货区
        List<StagingArea> areas = stagingAreasByWorld.get(worldId);
        if (areas != null) {
            for (StagingArea area : areas) {
                if (isPosInArea(pos, area)) {
                    return true;
                }
            }
        }
        // 检查仓库
        List<Warehouse> warehouses = warehousesByWorld.get(worldId);
        if (warehouses != null) {
            for (Warehouse wh : warehouses) {
                if (isPosInWarehouse(pos, wh)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void scheduleContainerScan(BlockPos pos, ServerLevel world) {
        dirtyContainers.put(pos, world);
        // 新的容器变化事件清除延迟标记（如玩家放下了新箱子）
        pendingDeferredRescans.remove(pos);
        SyncMaterial.LOGGER.info("[StagingArea] scheduleContainerScan: pos={},{},{}", pos.getX(), pos.getY(), pos.getZ());
    }

    public void processDirtyContainers() {
        if (dirtyContainers.isEmpty()) {
            return;
        }

        // 原子快照：取走当前所有条目，后续新加入的条目不受影响
        Map<BlockPos, ServerLevel> snapshot = new java.util.HashMap<>(dirtyContainers);
        dirtyContainers.keySet().removeAll(snapshot.keySet());

        // 跟踪受影响的原理图 ID，用于后续广播材料状态
        Set<String> affectedSchematics = new HashSet<>();

        SyncMaterial.LOGGER.info("[StagingArea] processDirtyContainers: {} dirty containers", snapshot.size());

        // 按区域收集脏容器 pos（同一个区域的多个箱子分别处理）
        Map<String, List<Map.Entry<BlockPos, ServerLevel>>> dirtyByArea = new HashMap<>();

        for (Map.Entry<BlockPos, ServerLevel> entry : snapshot.entrySet()) {
            BlockPos pos = entry.getKey();
            ServerLevel world = entry.getValue();

            Integer stagingAreaId = findAreaId(pos, world);
            if (stagingAreaId != null) {
                dirtyByArea.computeIfAbsent("S:" + stagingAreaId, k -> new ArrayList<>()).add(entry);
            }
            Integer warehouseId = findWarehouseId(pos, world);
            if (warehouseId != null) {
                dirtyByArea.computeIfAbsent("W:" + warehouseId, k -> new ArrayList<>()).add(entry);
            }
        }

        if (dirtyByArea.isEmpty() || server == null) return;

        // 大箱子配对检测：记录已处理的配对位置，跳过扫描避免重复

        server.execute(() -> {
            // 延迟重扫：跳过当前 tick 标记的位置，同时清理旧 container_inventory
            // 只跳过在之前 tick 标记的位置（pendingDeferredRescans tick < currentServerTick）
            // 当前 tick 标记的不跳过（会在下次 processDirtyContainers 时处理）
            currentServerTick++;
            processedRemovals.clear();
            dirtyByArea.entrySet().removeIf(e -> {
                String areaKey = e.getKey();
                boolean isWh = areaKey.startsWith("W:");
                int areaId = Integer.parseInt(areaKey.substring(2));
                String areaType = isWh ? "warehouse" : "staging_area";
                boolean shouldRemove = false;
                for (var entry : e.getValue()) {
                    BlockPos pos = entry.getKey();
                    Integer deferredTick = pendingDeferredRescans.get(pos);
                    if (deferredTick != null && deferredTick < currentServerTick) {
                        // 已延迟一个 tick，清除标记，正常处理（不跳过）
                        pendingDeferredRescans.remove(pos);
                        SyncMaterial.LOGGER.info("[StagingArea] 延迟到期，正常处理: {}", pos);
                    } else if (deferredTick != null) {
                        // 当前 tick 刚标记的，跳过并清理旧记录
                        try {
                            database.executeUpdate(
                                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                                areaId, areaType, pos.getX(), pos.getY(), pos.getZ());
                        } catch (java.sql.SQLException ex) {
                            SyncMaterial.LOGGER.error("[StagingArea] 延迟清理 container_inventory 失败", ex);
                        }
                        SyncMaterial.LOGGER.info("[StagingArea] 延迟跳过并清理旧记录: {}", pos);
                        shouldRemove = true;
                    }
                }
                return shouldRemove;
            });
            if (dirtyByArea.isEmpty()) return;

            for (var areaEntry : dirtyByArea.entrySet()) {
                String areaKey = areaEntry.getKey();
                List<Map.Entry<BlockPos, ServerLevel>> positions = areaEntry.getValue();
                boolean isWarehouse = areaKey.startsWith("W:");
                int areaId = Integer.parseInt(areaKey.substring(2));

                for (var posEntry : positions) {
                    BlockPos pos = posEntry.getKey();
                    ServerLevel world = posEntry.getValue();
                    String areaType = isWarehouse ? "warehouse" : "staging_area";

                    // 1. 扫描该箱子获取新物品集合（含潜影盒）
                    Set<String> newItems = scanSingleContainerItems(world, pos);

                    // 全量替换：删除旧记录 → 插入新记录
                    // 每个位置独立扫描，大箱子左右半箱各自 27 格
                    try {
                        database.executeUpdate(
                            "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                            areaId, areaType, pos.getX(), pos.getY(), pos.getZ());
                    } catch (java.sql.SQLException e) {
                        SyncMaterial.LOGGER.error("[StagingArea] 全量替换删除失败", e);
                    }
                    for (String itemId : newItems) {
                        try {
                            database.executeUpdate(
                                "INSERT OR IGNORE INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                                areaId, areaType, pos.getX(), pos.getY(), pos.getZ(), itemId);
                        } catch (java.sql.SQLException e) {
                            SyncMaterial.LOGGER.error("[StagingArea] 全量替换插入失败", e);
                        }
                    }
                }

                // 4. 如果是备货区，重扫该原理图下所有备货区（因为材料总数是所有备货区的 SUM）
                if (!isWarehouse) {
                    // 找到该备货区所属的原理图，重扫所有备货区
                    for (var schematicEntry : stagingAreasBySchematic.entrySet()) {
                        boolean found = false;
                        for (StagingArea area : schematicEntry.getValue()) {
                            if (area.id == areaId) {
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            affectedSchematics.add(schematicEntry.getKey());
                            for (StagingArea area : schematicEntry.getValue()) {
                                Map<String, Integer> result = scanAreaContents(area.id);
                                if (result != null) updateStagingAreaContainer(area.id, result);
                            }
                            break;
                        }
                    }
                } else {
                    Map<String, Integer> result = scanWarehouseContents(areaId);
                    if (result != null) updateWarehouseContainer(areaId, result);
                    affectedSchematics.addAll(getSchematicsReferencingWarehouse(areaId));
                }
            }

            // processedRemovals 在下次 processDirtyContainers 开头清除

            // 推送更新给取货模式玩家
            pushDirtyUpdateWithCooldown();

            // 广播受影响原理图的最新材料状态（含仓库/备货区计数）
            if (!affectedSchematics.isEmpty() && server != null) {
                for (String schematicId : affectedSchematics) {
                    net.syncmaterial.syncmaterial.network.Phase4Handler.broadcastAllMaterialStatus(server, schematicId);
                }
            }
        });
    }


    /**
     * 扫描单个容器位置的物品集合（含潜影盒），返回 item_id 集合
     */
    private Set<String> scanSingleContainerItems(ServerLevel world, BlockPos pos) {
        Set<String> items = new HashSet<>();
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Container) {
            Container inventory = (Container) be;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                items.add(stack.getItem().toString());

                // 潜影盒内容物
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem &&
                    blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                    var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
                    if (container != null) {
                        for (var stored : container.nonEmptyItemCopyStream().toList()) {
                            if (!stored.isEmpty()) {
                                items.add(stored.getItem().toString());
                            }
                        }
                    }
                }
            }
        }
        return items;
    }

    /**
     * 推送更新给取货模式玩家
     */
    private void pushDirtyUpdateWithCooldown() {
        net.syncmaterial.syncmaterial.network.ModNetworkHandler.pushWarehouseContainerUpdate(this);
    }


    private Integer findAreaId(BlockPos pos, ServerLevel world) {
        String worldId = world.dimension().identifier().toString();
        List<StagingArea> areas = stagingAreasByWorld.get(worldId);
        if (areas != null) {
            for (StagingArea area : areas) {
                if (isPosInArea(pos, area)) {
                    return area.id;
                }
            }
        }
        return null;
    }

    /**
     * 查找位置所在的仓库 ID
     */
    private Integer findWarehouseId(BlockPos pos, ServerLevel world) {
        String worldId = world.dimension().identifier().toString();
        List<Warehouse> warehouses = warehousesByWorld.get(worldId);
        if (warehouses == null) return null;
        for (Warehouse wh : warehouses) {
            if (isPosInWarehouse(pos, wh)) {
                return wh.id();
            }
        }
        return null;
    }

    /**
     * 扫描备货区内容器内容（在主线程调用，需要访问世界数据）。
     * 返回 null 表示找不到区域或世界。
     */
    @Nullable
    private Map<String, Integer> scanAreaContents(int areaId) {
        StagingArea area = findStagingAreaById(areaId);
        if (area == null) return null;

        ServerLevel world = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(area.world)));
        if (world == null) return null;

        Map<String, Integer> totalItems = new HashMap<>();
        int skippedChunks = 0;

        int minX = Math.min(area.x1, area.x2);
        int maxX = Math.max(area.x1, area.x2);
        int minZ = Math.min(area.z1, area.z2);
        int maxZ = Math.max(area.z1, area.z2);
        int minY = Math.min(area.y1, area.y2);
        int maxY = Math.max(area.y1, area.y2);

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // 只扫描已加载的区块，绝不触发区块加载
                if (world.getChunkSource().getChunk(chunkX, chunkZ, false) == null) {
                    skippedChunks++;
                    continue;
                }

                int startX = Math.max(chunkX << 4, minX);
                int endX = Math.min((chunkX << 4) + 15, maxX);
                int startZ = Math.max(chunkZ << 4, minZ);
                int endZ = Math.min((chunkZ << 4) + 15, maxZ);

                for (int x = startX; x <= endX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = startZ; z <= endZ; z++) {
                            BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                            if (be instanceof Container) {
                                Container inventory = (Container) be;
                                countContainerItems(inventory, totalItems);
                            }
                        }
                    }
                }

                // 扫描成功，标记该区块并检查是否完成初始化
                markChunkAndCheckInit("staging_area", areaId, ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL),
                    area.x1, area.y1, area.z1, area.x2, area.y2, area.z2);
            }
        }

        if (skippedChunks > 0) {
            SyncMaterial.LOGGER.info("[StagingArea] areaId={} skipped {} unloaded chunks", areaId, skippedChunks);
        }
        SyncMaterial.LOGGER.info("[StagingArea] scanAreaContents: areaId={} found {} item types", areaId, totalItems.size());
        return totalItems;
    }

    public void rescanStagingArea(int areaId) {
        var totalItems = scanAreaContents(areaId);
        if (totalItems == null) {
            SyncMaterial.LOGGER.warn("[StagingArea] rescanStagingArea: area {} not found or world missing", areaId);
            return;
        }

        SyncMaterial.LOGGER.info("[StagingArea] rescanStagingArea: areaId={} found {} item types", areaId, totalItems.size());
        updateStagingAreaContainer(areaId, totalItems);
    }

    /** 统计容器内物品（含潜影盒） */
    private void countContainerItems(Container inventory, Map<String, Integer> totalItems) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            totalItems.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);

            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem &&
                blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
                if (container != null) {
                    for (var stored : container.nonEmptyItemCopyStream().toList()) {
                        if (!stored.isEmpty()) {
                            totalItems.merge(stored.getItem().toString(), stored.getCount(), Integer::sum);
                        }
                    }
                }
            }
        }
    }

    private StagingArea findStagingAreaById(int areaId) {
        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.id == areaId) {
                    return area;
                }
            }
        }
        return null;
    }

    private void updateStagingAreaContainer(int areaId, Map<String, Integer> itemCounts) {
        if (itemCounts == null || itemCounts.isEmpty()) {
            SyncMaterial.LOGGER.info("[StagingArea] updateStagingAreaContainer: areaId={} itemCounts is empty, clearing old records", areaId);
            try {
                database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);
            } catch (SQLException e) {
                SyncMaterial.LOGGER.error("Failed to clear staging area inventory", e);
            }
            return;
        }

        SyncMaterial.LOGGER.info("[StagingArea] updateStagingAreaContainer: areaId={} with {} items", areaId, itemCounts.size());

        // 事务包裹：清空 + 逐条插入是一次逻辑替换，中途失败会留下残缺库存；
        // 同时把 N 次自动提交合并为 1 次，物品种类多时差异显著
        try {
            database.beginTransaction();
            try {
                database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);

                for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                    database.executeUpdate(
                        "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) VALUES (?, ?, ?)",
                        areaId, entry.getKey(), entry.getValue()
                    );
                }
                database.commitTransaction();
            } catch (SQLException e) {
                database.rollbackTransaction();
                throw e;
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update staging area inventory", e);
        }
    }

    public int getStagingCountForMaterial(String schematicId, String itemId) {
        try (var rs = database.executeQuery(
            "SELECT COALESCE(SUM(sai.count), 0) FROM staging_area_inventory sai " +
            "JOIN staging_areas sa ON sai.staging_area_id = sa.id " +
            "WHERE sa.schematic_id = ? AND sai.item_id = ?",
            schematicId, itemId
        )) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to get staging count for material", e);
        }
        return 0;
    }

    /**
     * 统计原理图引用的仓库库存
     */
    public int getWarehouseCountForMaterial(String schematicId, String itemId) {
        try (var rs = database.executeQuery(
            "SELECT COALESCE(SUM(wi.count), 0) FROM warehouse_inventory wi " +
            "JOIN schematic_warehouses sw ON wi.warehouse_id = sw.warehouse_id " +
            "WHERE sw.schematic_id = ? AND wi.item_id = ?",
            schematicId, itemId
        )) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to get warehouse count for material", e);
        }
        return 0;
    }

    public void onContainerRemoved(BlockPos pos, ServerLevel world) {
        String worldId = world.dimension().identifier().toString();
        boolean found = false;
        Set<String> affectedSchematics = new HashSet<>();

        // 检查备货区
        for (var schematicEntry : stagingAreasBySchematic.entrySet()) {
            for (StagingArea area : schematicEntry.getValue()) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    // 防止 markRemoved 多次触发导致重入
                    if (processedRemovals.contains(pos)) break;
                    processedRemovals.add(pos);
                    // 延迟重扫：清理旧记录，标记延迟一个 tick
                    try {
                        database.executeUpdate(
                            "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                            area.id, "staging_area", pos.getX(), pos.getY(), pos.getZ());
                    } catch (java.sql.SQLException e) {
                        SyncMaterial.LOGGER.error("[StagingArea] 容器移除清理失败", e);
                    }
                    pendingDeferredRescans.put(pos, currentServerTick);
                    dirtyContainers.put(pos, world);
                    SyncMaterial.LOGGER.info("[StagingArea] 容器被移除(延迟): area={} pos={},{},{}", area.id, pos.getX(), pos.getY(), pos.getZ());
                    affectedSchematics.add(schematicEntry.getKey());
                    found = true;
                    break;
                }
            }
            if (found) break;
        }

        if (!found) {
            // 检查仓库
            List<Warehouse> warehouses = warehousesByWorld.get(worldId);
            if (warehouses != null) {
                for (Warehouse wh : warehouses) {
                    if (isPosInWarehouse(pos, wh)) {
                        // 防止 markRemoved 多次触发导致重入
                        if (processedRemovals.contains(pos)) break;
                        processedRemovals.add(pos);
                        // 延迟重扫：清理旧记录，标记延迟一个 tick
                        try {
                            database.executeUpdate(
                                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                                wh.id(), "warehouse", pos.getX(), pos.getY(), pos.getZ());
                        } catch (java.sql.SQLException e) {
                            SyncMaterial.LOGGER.error("[StagingArea] 容器移除清理失败", e);
                        }
                        pendingDeferredRescans.put(pos, currentServerTick);
                        dirtyContainers.put(pos, world);
                        SyncMaterial.LOGGER.info("[StagingArea] 容器被移除(延迟): area={} type=warehouse pos={},{},{}", wh.id(), pos.getX(), pos.getY(), pos.getZ());
                        found = true;
                        affectedSchematics.addAll(getSchematicsReferencingWarehouse(wh.id()));
                        break;
                    }
                }
            }
        }

        if (found) {
            pushDirtyUpdateWithCooldown();

            // 广播受影响原理图的最新材料状态
            if (!affectedSchematics.isEmpty() && server != null) {
                for (String schematicId : affectedSchematics) {
                    net.syncmaterial.syncmaterial.network.Phase4Handler.broadcastAllMaterialStatus(server, schematicId);
                }
            }
        }
    }

    /**
     * 容器被破坏后，清理 container_inventory 明细并全量重扫该区域
     */

    private boolean isPosInArea(BlockPos pos, StagingArea area) {
        int minX = Math.min(area.x1, area.x2);
        int maxX = Math.max(area.x1, area.x2);
        int minY = Math.min(area.y1, area.y2);
        int maxY = Math.max(area.y1, area.y2);
        int minZ = Math.min(area.z1, area.z2);
        int maxZ = Math.max(area.z1, area.z2);

        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private List<StagingArea> loadStagingAreasFromDb(String schematicId) {
        List<StagingArea> areas = new ArrayList<>();
        try (var rs = database.executeQuery(
            "SELECT id, world, name, x1, y1, z1, x2, y2, z2 FROM staging_areas WHERE schematic_id = ?",
            schematicId
        )) {
            while (rs.next()) {
                areas.add(new StagingArea(
                    rs.getInt("id"),
                    rs.getString("world"),
                    rs.getString("name"),
                    rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"),
                    rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2")
                ));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to load staging areas", e);
        }
        return areas;
    }

    private void refreshCache(String schematicId) {
        stagingAreasBySchematic.put(schematicId, loadStagingAreasFromDb(schematicId));
        rebuildWorldIndex();
    }

    private void rebuildWorldIndex() {
        Map<String, List<StagingArea>> byWorld = new ConcurrentHashMap<>();
        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                byWorld.computeIfAbsent(area.world, k -> new ArrayList<>()).add(area);
            }
        }
        // 原子替换引用，读线程看到的要么是旧 map 要么是新 map，不会看到空状态
        this.stagingAreasByWorld = byWorld;
    }

    private String getSchematicNameFromDb(String schematicId) {
        try (var rs = database.executeQuery("SELECT name FROM schematics WHERE id = ?", schematicId)) {
            if (rs.next()) return rs.getString("name");
        } catch (SQLException e) {
            SyncMaterial.LOGGER.warn("获取原理图名称失败: {}", schematicId);
        }
        return "";
    }

    // ========== Phase 5: 仓库扫描 + container_inventory 维护 ==========

    /**
     * 查询引用了指定仓库的原理图 ID 集合（仓库库存变动后需广播这些原理图的材料状态）
     */
    public Set<String> getSchematicsReferencingWarehouse(int warehouseId) {
        Set<String> result = new HashSet<>();
        try (var rs = database.executeQuery(
                "SELECT DISTINCT schematic_id FROM schematic_warehouses WHERE warehouse_id = ?", warehouseId)) {
            while (rs.next()) {
                result.add(rs.getString("schematic_id"));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 查询仓库关联原理图失败: warehouseId={}", warehouseId, e);
        }
        return result;
    }

    /**
     * 扫描仓库并标记区块为已扫描。
     * 启动时以及新建/修改仓库后调用：后者若不调用，仓库会一直停留在"未初始化"
     * 状态（前端显示"数据可能过时"且库存为 0），直到有容器变动才被动触发扫描。
     */
    public void rescanWarehouseAndMarkChunks(int warehouseId) {
        Warehouse wh = warehousesById.get(warehouseId);
        if (wh == null) return;

        var totalItems = scanWarehouseContents(warehouseId);
        if (totalItems == null) return;

        updateWarehouseContainer(warehouseId, totalItems);
        SyncMaterial.LOGGER.info("[StagingArea] rescanWarehouse: id={} found {} item types", warehouseId, totalItems.size());
    }

    /**
     * 扫描仓库内容物（复用 scanAreaContents 的逻辑，针对仓库坐标）。
     * 同时重建 container_inventory 明细，取货模式的箱子高亮依赖该表。
     */
    private Map<String, Integer> scanWarehouseContents(int warehouseId) {
        Warehouse wh = warehousesById.get(warehouseId);
        if (wh == null) return null;

        ServerLevel world = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(wh.world())));
        if (world == null) return null;

        // 全量重扫前清空旧明细，避免已移除的容器残留在取货模式高亮里
        try {
            database.executeUpdate(
                "DELETE FROM container_inventory WHERE area_id = ? AND area_type = 'warehouse'", warehouseId);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 清理仓库容器明细失败: id={}", warehouseId, e);
        }

        Map<String, Integer> totalItems = new HashMap<>();
        int minX = Math.min(wh.x1(), wh.x2());
        int maxX = Math.max(wh.x1(), wh.x2());
        int minY = Math.min(wh.y1(), wh.y2());
        int maxY = Math.max(wh.y1(), wh.y2());
        int minZ = Math.min(wh.z1(), wh.z2());
        int maxZ = Math.max(wh.z1(), wh.z2());

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.getChunkSource().getChunk(chunkX, chunkZ, false) == null) continue;

                int startX = Math.max(chunkX << 4, minX);
                int endX = Math.min((chunkX << 4) + 15, maxX);
                int startZ = Math.max(chunkZ << 4, minZ);
                int endZ = Math.min((chunkZ << 4) + 15, maxZ);

                for (int x = startX; x <= endX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = startZ; z <= endZ; z++) {
                            BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                            if (be instanceof Container) {
                                Container inventory = (Container) be;
                                countContainerItems(inventory, totalItems);
                                writeContainerContainer(warehouseId, "warehouse", x, y, z, inventory);
                            }
                        }
                    }
                }

                // 扫描成功，标记该区块并检查是否完成初始化
                markChunkAndCheckInit("warehouse", warehouseId, ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL),
                    wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2());
            }
        }
        return totalItems;
    }

    /**
     * 更新仓库库存总数表
     */
    private void updateWarehouseContainer(int warehouseId, Map<String, Integer> itemCounts) {
        try {
            database.beginTransaction();
            try {
                database.executeUpdate("DELETE FROM warehouse_inventory WHERE warehouse_id = ?", warehouseId);
                for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                    database.executeUpdate(
                        "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, ?, ?)",
                        warehouseId, entry.getKey(), entry.getValue());
                }
                database.commitTransaction();
            } catch (SQLException e) {
                database.rollbackTransaction();
                throw e;
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update warehouse inventory", e);
        }
    }

    /**
     * 写入单个箱子的容器明细（含潜影盒，不存数量）
     */
    private void writeContainerContainer(int areaId, String areaType, int x, int y, int z, Container inventory) {
        Set<String> itemIds = new HashSet<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            itemIds.add(stack.getItem().toString());

            // 潜影盒内容物
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem &&
                blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
                if (container != null) {
                    for (var stored : container.nonEmptyItemCopyStream().toList()) {
                        if (!stored.isEmpty()) {
                            itemIds.add(stored.getItem().toString());
                        }
                    }
                }
            }
        }

        for (String itemId : itemIds) {
            try {
                database.executeUpdate(
                    "INSERT OR IGNORE INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                    areaId, areaType, x, y, z, itemId);
            } catch (SQLException e) {
                SyncMaterial.LOGGER.error("Failed to write container inventory", e);
            }
        }
    }

    /**
     * 备货区是否已初始化（本次启动后所有区块至少扫描过一次）
     */
    public boolean isStagingAreaInitialized(int areaId) {
        return initializedAreas.contains(initKey("staging_area", areaId));
    }

    /**
     * 仓库是否已初始化（本次启动后所有区块至少扫描过一次）
     */
    public boolean isWarehouseInitialized(int warehouseId) {
        return initializedAreas.contains(initKey("warehouse", warehouseId));
    }

    private static String initKey(String areaType, int id) {
        return ("warehouse".equals(areaType) ? "W:" : "S:") + id;
    }

    /**
     * 重置区域的初始化状态（区域被修改或删除后调用）：
     * 修改后必须按新范围重新跟踪，否则扩大的新领土永远不会被补扫，也不会再出现过时警告
     */
    private void resetInitState(String areaType, int id) {
        String key = initKey(areaType, id);
        initializedAreas.remove(key);
        initChunkTracking.remove(key);
    }

    /**
     * 标记区块已扫描，如果区域所有区块都已扫描则标记为已初始化。
     *
     * @return 本次调用是否刚好完成初始化（用于触发全量校正）
     */
    private boolean markChunkAndCheckInit(String areaType, int areaId, long chunkPos,
            int x1, int y1, int z1, int x2, int y2, int z2) {
        String key = initKey(areaType, areaId);
        if (initializedAreas.contains(key)) return false;

        initChunkTracking.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(chunkPos);

        // 检查是否所有区块都已扫描
        int minChunkX = Math.min(x1, x2) >> 4;
        int maxChunkX = Math.max(x1, x2) >> 4;
        int minChunkZ = Math.min(z1, z2) >> 4;
        int maxChunkZ = Math.max(z1, z2) >> 4;

        Set<Long> scanned = initChunkTracking.get(key);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!scanned.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL))) {
                    return false; // 还有未扫描的区块
                }
            }
        }

        // 所有区块已扫描，标记为已初始化，清除临时跟踪
        initializedAreas.add(key);
        initChunkTracking.remove(key);
        SyncMaterial.LOGGER.info("[StagingArea] {} id={} 初始化完成（所有区块已扫描）", areaType, areaId);
        return true;
    }

    /**
     * 区块加载时扫描：检查该区块内是否有仓库/备货区的箱子，有则扫描写入数据库
     */
    public void scanChunkForContainerAreas(net.minecraft.world.level.chunk.LevelChunk chunk, ServerLevel world) {
        String worldId = world.dimension().identifier().toString();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        long chunkPos = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

        // 检查备货区（只处理未初始化的）
        List<StagingArea> areas = stagingAreasByWorld.get(worldId);
        if (areas != null) {
            for (StagingArea area : areas) {
                if (initializedAreas.contains(initKey("staging_area", area.id))) continue;
                if (chunkIntersectsArea(chunkX, chunkZ, area.x1, area.y1, area.z1, area.x2, area.y2, area.z2)) {
                    clearTotalsOnFirstChunk("staging_area", area.id);
                    scanChunkForArea(chunk, world, area.id, "staging_area",
                        area.x1, area.y1, area.z1, area.x2, area.y2, area.z2);
                    if (markChunkAndCheckInit("staging_area", area.id, chunkPos,
                        area.x1, area.y1, area.z1, area.x2, area.y2, area.z2)) {
                        correctTotalsIfFullyLoaded(world, "staging_area", area.id,
                            area.x1, area.z1, area.x2, area.z2);
                    }
                }
            }
        }

        // 检查仓库（只处理未初始化的）
        List<Warehouse> warehouses = warehousesByWorld.get(worldId);
        if (warehouses != null) {
            for (Warehouse wh : warehouses) {
                if (initializedAreas.contains(initKey("warehouse", wh.id()))) continue;
                if (chunkIntersectsArea(chunkX, chunkZ, wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2())) {
                    clearTotalsOnFirstChunk("warehouse", wh.id());
                    scanChunkForArea(chunk, world, wh.id(), "warehouse",
                        wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2());
                    if (markChunkAndCheckInit("warehouse", wh.id(), chunkPos,
                        wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2())) {
                        correctTotalsIfFullyLoaded(world, "warehouse", wh.id(),
                            wh.x1(), wh.z1(), wh.x2(), wh.z2());
                    }
                }
            }
        }
    }

    /**
     * 区域重新初始化的第一个区块：清掉上一会话遗留的总数统计，
     * 之后逐区块增量合并，避免重启后重复累计。
     */
    private void clearTotalsOnFirstChunk(String areaType, int areaId) {
        if (initChunkTracking.containsKey(initKey(areaType, areaId))) return;
        try {
            if ("warehouse".equals(areaType)) {
                database.executeUpdate("DELETE FROM warehouse_inventory WHERE warehouse_id = ?", areaId);
            } else {
                database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 清理区域旧统计失败: areaId={}", areaId, e);
        }
    }

    /**
     * 区域完成初始化后的全量校正：所有区块当前都在加载状态时，
     * 重扫一次以区块级合并无法纠正的场景（如箱子被清空但区块未再触发变化）。
     * 部分区块已卸载时跳过，保留合并结果（全量重扫会因跳过卸载区块而丢数据）。
     */
    private void correctTotalsIfFullyLoaded(ServerLevel world, String areaType, int areaId,
            int x1, int z1, int x2, int z2) {
        int minCX = Math.min(x1, x2) >> 4, maxCX = Math.max(x1, x2) >> 4;
        int minCZ = Math.min(z1, z2) >> 4, maxCZ = Math.max(z1, z2) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (world.getChunkSource().getChunk(cx, cz, false) == null) {
                    SyncMaterial.LOGGER.info("[StagingArea] areaId={} 部分区块已卸载，跳过全量校正，保留合并统计", areaId);
                    return;
                }
            }
        }
        if ("warehouse".equals(areaType)) {
            rescanWarehouseAndMarkChunks(areaId);
        } else {
            rescanStagingArea(areaId);
        }
    }

    private boolean chunkIntersectsArea(int chunkX, int chunkZ, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int chunkMinX = chunkX << 4, chunkMaxX = (chunkX << 4) + 15;
        int chunkMinZ = chunkZ << 4, chunkMaxZ = (chunkZ << 4) + 15;
        return minX <= chunkMaxX && maxX >= chunkMinX && minZ <= chunkMaxZ && maxZ >= chunkMinZ;
    }

    /**
     * 扫描区块内属于指定区域的箱子，写入 container_inventory
     */
    private void scanChunkForArea(net.minecraft.world.level.chunk.LevelChunk chunk, ServerLevel world, int areaId, String areaType,
            int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.max(chunk.getPos().x() << 4, Math.min(x1, x2));
        int maxX = Math.min((chunk.getPos().x() << 4) + 15, Math.max(x1, x2));
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.max(chunk.getPos().z() << 4, Math.min(z1, z2));
        int maxZ = Math.min((chunk.getPos().z() << 4) + 15, Math.max(z1, z2));

        Map<String, Integer> items = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                    if (be instanceof Container) {
                        Container inventory = (Container) be;
                        writeContainerContainer(areaId, areaType, x, y, z, inventory);
                        countContainerItems(inventory, items);
                    }
                }
            }
        }

        // 更新总数表：增量合并（区块加载顺序无关，跨区块区域不会被单区块结果覆盖；
        // 区域完成初始化且区块全在时会做一次全量校正）
        if (!items.isEmpty()) {
            if ("warehouse".equals(areaType)) {
                mergeWarehouseContainer(areaId, items);
            } else {
                mergeStagingAreaContainer(areaId, items);
            }
        }
    }

    /** 逐条合并区块扫描结果到备货区总数（无则插入，有则累加） */
    private void mergeStagingAreaContainer(int areaId, Map<String, Integer> items) {
        // 不用 upsert：staging_area_inventory 无 (staging_area_id, item_id) 唯一索引，
        // ON CONFLICT 无匹配约束会直接报错。事务化足以消除逐条自动提交的开销。
        try {
            database.beginTransaction();
            try {
                for (Map.Entry<String, Integer> e : items.entrySet()) {
                    try (var rs = database.executeQuery(
                            "SELECT count FROM staging_area_inventory WHERE staging_area_id = ? AND item_id = ?",
                            areaId, e.getKey())) {
                        if (rs.next()) {
                            database.executeUpdate(
                                "UPDATE staging_area_inventory SET count = ? WHERE staging_area_id = ? AND item_id = ?",
                                rs.getInt("count") + e.getValue(), areaId, e.getKey());
                        } else {
                            database.executeUpdate(
                                "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) VALUES (?, ?, ?)",
                                areaId, e.getKey(), e.getValue());
                        }
                    }
                }
                database.commitTransaction();
            } catch (SQLException ex) {
                database.rollbackTransaction();
                throw ex;
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 合并备货区统计失败: areaId={}", areaId, e);
        }
    }

    /** 逐条合并区块扫描结果到仓库总数（无则插入，有则累加） */
    private void mergeWarehouseContainer(int warehouseId, Map<String, Integer> items) {
        try {
            database.beginTransaction();
            try {
                for (Map.Entry<String, Integer> e : items.entrySet()) {
                    try (var rs = database.executeQuery(
                            "SELECT count FROM warehouse_inventory WHERE warehouse_id = ? AND item_id = ?",
                            warehouseId, e.getKey())) {
                        if (rs.next()) {
                            database.executeUpdate(
                                "UPDATE warehouse_inventory SET count = ? WHERE warehouse_id = ? AND item_id = ?",
                                rs.getInt("count") + e.getValue(), warehouseId, e.getKey());
                        } else {
                            database.executeUpdate(
                                "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, ?, ?)",
                                warehouseId, e.getKey(), e.getValue());
                        }
                    }
                }
                database.commitTransaction();
            } catch (SQLException ex) {
                database.rollbackTransaction();
                throw ex;
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 合并仓库统计失败: warehouseId={}", warehouseId, e);
        }
    }

    public record StagingArea(int id, String world, String name, int x1, int y1, int z1, int x2, int y2, int z2) {}

    // Phase 5: 全局仓库
    public record Warehouse(int id, String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {}

    private boolean isPosInWarehouse(BlockPos pos, Warehouse wh) {
        int minX = Math.min(wh.x1(), wh.x2());
        int maxX = Math.max(wh.x1(), wh.x2());
        int minY = Math.min(wh.y1(), wh.y2());
        int maxY = Math.max(wh.y1(), wh.y2());
        int minZ = Math.min(wh.z1(), wh.z2());
        int maxZ = Math.max(wh.z1(), wh.z2());
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    // ========== 全局仓库 CRUD ==========

    /**
     * 加载所有全局仓库到内存
     */
    public void loadWarehousesFromDb() {
        try (var rs = database.executeQuery("SELECT id, name, world, x1, y1, z1, x2, y2, z2 FROM warehouses")) {
            Map<Integer, Warehouse> byId = new HashMap<>();
            while (rs.next()) {
                Warehouse wh = new Warehouse(
                    rs.getInt("id"), rs.getString("name"), rs.getString("world"),
                    rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"),
                    rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2"));
                byId.put(wh.id(), wh);
            }
            warehousesById.clear();
            warehousesById.putAll(byId);
            rebuildWarehouseWorldIndex();
            SyncMaterial.LOGGER.info("[StagingArea] 加载了 {} 个全局仓库", byId.size());
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 加载仓库数据失败", e);
        }
    }

    /**
     * 新建全局仓库，返回自增 ID
     */
    public int addWarehouse(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            database.executeUpdate(
                "INSERT INTO warehouses (name, world, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                name, world, x1, y1, z1, x2, y2, z2);
            try (var rs = database.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                int id = rs.getInt(1);
                Warehouse wh = new Warehouse(id, name, world, x1, y1, z1, x2, y2, z2);
                warehousesById.put(id, wh);
                rebuildWarehouseWorldIndex();
                SyncMaterial.LOGGER.info("[StagingArea] 新建仓库: id={}, name={}", id, name);
                return id;
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 新建仓库失败", e);
            return -1;
        }
    }

    /**
     * 编辑全局仓库
     */
    public void updateWarehouse(int id, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        updateWarehouse(id, name, null, x1, y1, z1, x2, y2, z2);
    }

    /**
     * 编辑全局仓库，可同时迁移维度。
     * world 传 null 表示保持原维度；传新值则连同坐标一起迁移，
     * 否则坐标改到了新维度而 world 仍是旧值，会导致线框渲染不出来、扫描扫错维度。
     */
    public void updateWarehouse(int id, String name, @javax.annotation.Nullable String world,
                                int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            Warehouse old = warehousesById.get(id);
            String finalWorld = world != null ? world : (old != null ? old.world() : null);

            if (finalWorld != null) {
                database.executeUpdate(
                    "UPDATE warehouses SET name=?, world=?, x1=?, y1=?, z1=?, x2=?, y2=?, z2=? WHERE id=?",
                    name, finalWorld, x1, y1, z1, x2, y2, z2, id);
            } else {
                database.executeUpdate(
                    "UPDATE warehouses SET name=?, x1=?, y1=?, z1=?, x2=?, y2=?, z2=? WHERE id=?",
                    name, x1, y1, z1, x2, y2, z2, id);
            }

            if (old != null) {
                Warehouse wh = new Warehouse(id, name, finalWorld != null ? finalWorld : old.world(),
                        x1, y1, z1, x2, y2, z2);
                warehousesById.put(id, wh);
                rebuildWarehouseWorldIndex();
            }
            // 范围变了：重置初始化状态，扩大的新领土重新按区块跟踪补扫
            resetInitState("warehouse", id);
            SyncMaterial.LOGGER.info("[StagingArea] 更新仓库: id={}, name={}", id, name);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 更新仓库失败: id={}", id, e);
        }
    }

    /**
     * 删除全局仓库（级联清理 warehouse_inventory + container_inventory + schematic_warehouses）
     */
    public void deleteWarehouse(int id) {
        try {
            // warehouse_inventory 和 schematic_warehouses 通过 ON DELETE CASCADE 自动清理
            // container_inventory 需要手动清理（没有外键约束）
            database.executeUpdate("DELETE FROM container_inventory WHERE area_id = ? AND area_type = 'warehouse'", id);
            database.executeUpdate("DELETE FROM warehouses WHERE id = ?", id);
            warehousesById.remove(id);
            rebuildWarehouseWorldIndex();
            resetInitState("warehouse", id);
            SyncMaterial.LOGGER.info("[StagingArea] 删除仓库: id={}", id);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 删除仓库失败: id={}", id, e);
        }
    }

    /**
     * 获取所有全局仓库
     */
    public List<Warehouse> getAllWarehouses() {
        return new ArrayList<>(warehousesById.values());
    }

    // ========== 原理图-仓库引用管理 ==========

    /**
     * 原理图引用仓库
     */
    public void addWarehouseReference(String schematicId, int warehouseId) {
        try {
            database.executeUpdate(
                "INSERT OR IGNORE INTO schematic_warehouses (schematic_id, warehouse_id) VALUES (?, ?)",
                schematicId, warehouseId);
            SyncMaterial.LOGGER.info("[StagingArea] 原理图 {} 引用仓库 {}", schematicId, warehouseId);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 添加仓库引用失败", e);
        }
    }

    /**
     * 原理图取消引用仓库
     */
    public void removeWarehouseReference(String schematicId, int warehouseId) {
        try {
            database.executeUpdate(
                "DELETE FROM schematic_warehouses WHERE schematic_id = ? AND warehouse_id = ?",
                schematicId, warehouseId);
            SyncMaterial.LOGGER.info("[StagingArea] 原理图 {} 取消引用仓库 {}", schematicId, warehouseId);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 移除仓库引用失败", e);
        }
    }

    /**
     * 获取原理图引用的仓库列表
     */
    public List<Warehouse> getWarehousesForSchematic(String schematicId) {
        try (var rs = database.executeQuery(
                "SELECT w.id, w.name, w.world, w.x1, w.y1, w.z1, w.x2, w.y2, w.z2 FROM warehouses w " +
                "INNER JOIN schematic_warehouses sw ON w.id = sw.warehouse_id WHERE sw.schematic_id = ?",
                schematicId)) {
            List<Warehouse> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Warehouse(
                    rs.getInt("id"), rs.getString("name"), rs.getString("world"),
                    rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"),
                    rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2")));
            }
            return result;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 获取原理图仓库引用失败", e);
            return List.of();
        }
    }

    /**
     * 重建仓库按世界索引
     */
    private void rebuildWarehouseWorldIndex() {
        Map<String, List<Warehouse>> byWorld = new ConcurrentHashMap<>();
        for (Warehouse wh : warehousesById.values()) {
            byWorld.computeIfAbsent(wh.world(), k -> new ArrayList<>()).add(wh);
        }
        this.warehousesByWorld = byWorld;
    }

    public static List<StagingAreaConfigResponseS2CPacket.AreaInfo> buildAreaInfos(List<StagingArea> areas) {
        return areas.stream().map(a -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
            a.id(), a.name(), a.x1(), a.y1(), a.z1(), a.x2(), a.y2(), a.z2(), a.world())).toList();
    }

    /** 仓库列表转网络包结构（原先在 handler 里重复了四次） */
    public static List<StagingAreaConfigResponseS2CPacket.AreaInfo> buildWarehouseInfos(List<Warehouse> warehouses) {
        return warehouses.stream().map(w -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
            w.id(), w.name(), w.x1(), w.y1(), w.z1(), w.x2(), w.y2(), w.z2(), w.world())).toList();
    }

    // ========== Phase 5: 容器数据查询 ==========

    /**
     * 查询指定仓库集合的 container_inventory，返回容器坐标+物品种类列表
     */
    public List<net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry>
            getContainerEntriesForWarehouses(Set<Integer> warehouseIds) {
        if (warehouseIds.isEmpty()) return List.of();

        // 按 (pos_x, pos_y, pos_z) 聚合物品种类
        Map<String, List<String>> posToItems = new HashMap<>();

        for (int warehouseId : warehouseIds) {
            try (var rs = database.executeQuery(
                    "SELECT pos_x, pos_y, pos_z, item_id FROM container_inventory WHERE area_id = ? AND area_type = 'warehouse'",
                    warehouseId)) {
                while (rs.next()) {
                    int px = rs.getInt("pos_x");
                    int py = rs.getInt("pos_y");
                    int pz = rs.getInt("pos_z");
                    String itemId = rs.getString("item_id");
                    String posKey = px + "," + py + "," + pz;
                    posToItems.computeIfAbsent(posKey, k -> new ArrayList<>()).add(itemId);
                }
            } catch (java.sql.SQLException e) {
                SyncMaterial.LOGGER.error("[StagingArea] 查询容器数据失败: warehouseId={}", warehouseId, e);
            }
        }

        List<net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry> result = new ArrayList<>();
        for (var entry : posToItems.entrySet()) {
            String[] parts = entry.getKey().split(",");
            int px = Integer.parseInt(parts[0]);
            int py = Integer.parseInt(parts[1]);
            int pz = Integer.parseInt(parts[2]);
            result.add(new net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry(
                    px, py, pz, entry.getValue()));
        }
        return result;
    }
}
