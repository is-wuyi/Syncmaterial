package net.syncmaterial.syncmaterial.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import net.minecraft.server.network.ServerPlayerEntity;
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
    private final Map<BlockPos, ServerWorld> dirtyContainers = new ConcurrentHashMap<>();
    private final Map<String, Set<ServerPlayerEntity>> subscribers = new ConcurrentHashMap<>();
    // Phase 5: 初始化跟踪（服务器重启后，区域首次全部区块加载完成即标记为已初始化）
    private final Set<Integer> initializedAreas = ConcurrentHashMap.newKeySet();
    // 临时区块跟踪：初始化阶段记录已扫描的区块，全部扫描完成后清除
    private final Map<String, Set<Long>> initChunkTracking = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public StagingAreaManager(SchematicDatabase database) {
        this.database = database;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void subscribe(ServerPlayerEntity player, String schematicId) {
        subscribers.computeIfAbsent(schematicId, k -> ConcurrentHashMap.newKeySet()).add(player);
        SyncMaterial.LOGGER.info("[StagingArea] 玩家 {} 订阅了原理图 {} 的备货区更新", player.getName().getString(), schematicId);
    }

    public void unsubscribe(ServerPlayerEntity player, String schematicId) {
        Set<ServerPlayerEntity> set = subscribers.get(schematicId);
        if (set != null) {
            set.remove(player);
            if (set.isEmpty()) {
                subscribers.remove(schematicId);
            }
        }
    }

    public void unsubscribeAll(ServerPlayerEntity player) {
        for (var entry : subscribers.entrySet()) {
            entry.getValue().remove(player);
        }
        subscribers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public void broadcastUpdate(String schematicId) {
        Set<ServerPlayerEntity> set = subscribers.get(schematicId);
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
        try {
            database.executeUpdate(
                "UPDATE staging_areas SET name = ?, x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ? WHERE id = ?",
                name, x1, y1, z1, x2, y2, z2, areaId
            );
            refreshCache(schematicId);
            SyncMaterial.LOGGER.info("Updated staging area {} coordinates to [{},{},{}]~[{},{},{}]", areaId, x1, y1, z1, x2, y2, z2);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update staging area", e);
        }
    }

    public void removeStagingArea(int areaId, String schematicId) {
        try {
            database.executeUpdate("DELETE FROM staging_areas WHERE id = ?", areaId);
            refreshCache(schematicId);
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
    public boolean isInAnyInventoryArea(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
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

    public void scheduleContainerScan(BlockPos pos, ServerWorld world) {
        dirtyContainers.put(pos, world);
        SyncMaterial.LOGGER.info("[StagingArea] scheduleContainerScan: pos={},{},{}", pos.getX(), pos.getY(), pos.getZ());
    }

    public void processDirtyContainers() {
        if (dirtyContainers.isEmpty()) {
            return;
        }

        // 原子快照：取走当前所有条目，后续新加入的条目不受影响
        Map<BlockPos, ServerWorld> snapshot = new java.util.HashMap<>(dirtyContainers);
        dirtyContainers.keySet().removeAll(snapshot.keySet());

        SyncMaterial.LOGGER.info("[StagingArea] processDirtyContainers: {} dirty containers", snapshot.size());

        // 按区域收集脏容器 pos（同一个区域的多个箱子分别处理）
        Map<String, List<Map.Entry<BlockPos, ServerWorld>>> dirtyByArea = new HashMap<>();

        for (Map.Entry<BlockPos, ServerWorld> entry : snapshot.entrySet()) {
            BlockPos pos = entry.getKey();
            ServerWorld world = entry.getValue();

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
        Set<BlockPos> pairedPositions = new HashSet<>();

        server.execute(() -> {
            for (var areaEntry : dirtyByArea.entrySet()) {
                String areaKey = areaEntry.getKey();
                List<Map.Entry<BlockPos, ServerWorld>> positions = areaEntry.getValue();
                boolean isWarehouse = areaKey.startsWith("W:");
                int areaId = Integer.parseInt(areaKey.substring(2));

                for (var posEntry : positions) {
                    BlockPos pos = posEntry.getKey();
                    ServerWorld world = posEntry.getValue();
                    String areaType = isWarehouse ? "warehouse" : "staging_area";

                    // 跳过已作为大箱子配对处理过的位置
                    if (pairedPositions.contains(pos)) {
                        continue;
                    }

                    // 大箱子检测：通过 CHEST_TYPE 属性判断，记录配对位置
                    BlockPos pairedPos = findPairedChestPos(world, pos);
                    if (pairedPos != null) {
                        pairedPositions.add(pairedPos);
                        dirtyContainers.remove(pairedPos);
                        // 清理配对位置的旧记录（大箱子两个位置共享同一 DoubleInventory，只记录在一个位置）
                        try {
                            database.executeUpdate(
                                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                                areaId, areaType, pairedPos.getX(), pairedPos.getY(), pairedPos.getZ());
                        } catch (java.sql.SQLException e) {
                            SyncMaterial.LOGGER.error("[StagingArea] 清理大箱子配对位置记录失败", e);
                        }
                    }

                    // 1. 扫描该箱子获取新物品集合（含潜影盒）
                    Set<String> newItems = scanSingleContainerItems(world, pos);

                    // 大箱子：全量替换（DoubleInventory 的物品可能在两个半箱间移动，增量更新无法检测）
                    // 普通容器：增量更新（更高效）
                    if (pairedPos != null) {
                        // 全量替换：删除旧记录 → 插入新记录
                        try {
                            database.executeUpdate(
                                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                                areaId, areaType, pos.getX(), pos.getY(), pos.getZ());
                        } catch (java.sql.SQLException e) {
                            SyncMaterial.LOGGER.error("[StagingArea] 大箱子全量替换删除失败", e);
                        }
                        for (String itemId : newItems) {
                            try {
                                database.executeUpdate(
                                    "INSERT OR IGNORE INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                                    areaId, areaType, pos.getX(), pos.getY(), pos.getZ(), itemId);
                            } catch (java.sql.SQLException e) {
                                SyncMaterial.LOGGER.error("[StagingArea] 大箱子全量替换插入失败", e);
                            }
                        }
                    } else {
                        // 普通容器：增量更新
                        Set<String> oldItems = getContainerItemsAt(areaId, areaType, pos);
                        Set<String> toDelete = new HashSet<>(oldItems);
                        toDelete.removeAll(newItems);
                        Set<String> toInsert = new HashSet<>(newItems);
                        toInsert.removeAll(oldItems);

                        for (String itemId : toDelete) {
                            try {
                                database.executeUpdate(
                                    "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=? AND item_id=?",
                                    areaId, areaType, pos.getX(), pos.getY(), pos.getZ(), itemId);
                            } catch (java.sql.SQLException e) {
                                SyncMaterial.LOGGER.error("[StagingArea] 增量扫描删除失败", e);
                            }
                        }
                        for (String itemId : toInsert) {
                            try {
                                database.executeUpdate(
                                    "INSERT OR IGNORE INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                                    areaId, areaType, pos.getX(), pos.getY(), pos.getZ(), itemId);
                            } catch (java.sql.SQLException e) {
                                SyncMaterial.LOGGER.error("[StagingArea] 增量扫描插入失败", e);
                            }
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
                            for (StagingArea area : schematicEntry.getValue()) {
                                Map<String, Integer> result = scanAreaContents(area.id);
                                if (result != null) updateStagingAreaInventory(area.id, result);
                            }
                            break;
                        }
                    }
                } else {
                    Map<String, Integer> result = scanWarehouseContents(areaId);
                    if (result != null) updateWarehouseInventory(areaId, result);
                }
            }

            // 推送更新给取货模式玩家
            pushDirtyUpdateWithCooldown();
        });
    }

    /**
     * 找到大箱子（DoubleInventory）中与 pos 配对的另一半坐标
     * 参考 Litematica：通过方块状态 CHEST_TYPE + getFacing 判断
     */
    private BlockPos findPairedChestPos(ServerWorld world, BlockPos pos) {
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.block.ChestBlock) {
            var chestTypeProp = net.minecraft.block.ChestBlock.CHEST_TYPE;
            if (state.contains(chestTypeProp)) {
                var chestType = state.get(chestTypeProp);
                if (chestType != net.minecraft.block.enums.ChestType.SINGLE) {
                    net.minecraft.util.math.Direction facing = net.minecraft.block.ChestBlock.getFacing(state);
                    return pos.offset(facing);
                }
            }
        }
        return null;
    }

    /**
     * 扫描单个容器位置的物品集合（含潜影盒），返回 item_id 集合
     */
    private Set<String> scanSingleContainerItems(ServerWorld world, BlockPos pos) {
        Set<String> items = new HashSet<>();
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof Inventory inventory) {
            for (int i = 0; i < inventory.size(); i++) {
                var stack = inventory.getStack(i);
                if (stack.isEmpty()) continue;
                items.add(stack.getItem().toString());

                // 潜影盒内容物
                if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem &&
                    blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                    var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
                    if (container != null) {
                        for (var stored : container.streamNonEmpty().toList()) {
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
     * 查询指定位置在 container_inventory 中的现有记录
     */
    private Set<String> getContainerItemsAt(int areaId, String areaType, BlockPos pos) {
        Set<String> items = new HashSet<>();
        try (var rs = database.executeQuery(
                "SELECT item_id FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                areaId, areaType, pos.getX(), pos.getY(), pos.getZ())) {
            while (rs.next()) {
                items.add(rs.getString("item_id"));
            }
        } catch (java.sql.SQLException e) {
            SyncMaterial.LOGGER.error("[StagingArea] 查询容器记录失败", e);
        }
        return items;
    }

    /**
     * 推送更新给取货模式玩家
     */
    private void pushDirtyUpdateWithCooldown() {
        net.syncmaterial.syncmaterial.network.ModNetworkHandler.pushWarehouseContainerUpdate(this);
    }


    private Integer findAreaId(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
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
    private Integer findWarehouseId(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
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

        ServerWorld world = server.getWorld(net.minecraft.registry.RegistryKey.of(
                net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(area.world)));
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
                if (world.getChunkManager().getWorldChunk(chunkX, chunkZ) == null) {
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
                            if (be instanceof Inventory inventory) {
                                countInventoryItems(inventory, totalItems);
                            }
                        }
                    }
                }

                // 扫描成功，标记该区块并检查是否完成初始化
                markChunkAndCheckInit(areaId, ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL),
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
        updateStagingAreaInventory(areaId, totalItems);
    }

    /** 统计容器内物品（含潜影盒） */
    private void countInventoryItems(Inventory inventory, Map<String, Integer> totalItems) {
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            totalItems.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);

            if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem &&
                blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
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

    private String findSchematicIdByAreaId(int areaId) {
        for (var entry : stagingAreasBySchematic.entrySet()) {
            for (StagingArea area : entry.getValue()) {
                if (area.id == areaId) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private void updateStagingAreaInventory(int areaId, Map<String, Integer> itemCounts) {
        if (itemCounts == null || itemCounts.isEmpty()) {
            SyncMaterial.LOGGER.info("[StagingArea] updateStagingAreaInventory: areaId={} itemCounts is empty, clearing old records", areaId);
            try {
                database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);
            } catch (SQLException e) {
                SyncMaterial.LOGGER.error("Failed to clear staging area inventory", e);
            }
            return;
        }

        SyncMaterial.LOGGER.info("[StagingArea] updateStagingAreaInventory: areaId={} with {} items", areaId, itemCounts.size());

        try {
            database.executeUpdate("DELETE FROM staging_area_inventory WHERE staging_area_id = ?", areaId);

            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                database.executeUpdate(
                    "INSERT INTO staging_area_inventory (staging_area_id, item_id, count) VALUES (?, ?, ?)",
                    areaId, entry.getKey(), entry.getValue()
                );
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
     * 仅统计备货区库存（不含仓库）
     */
    public int getStagingOnlyCountForMaterial(String schematicId, String itemId) {
        return getStagingCountForMaterial(schematicId, itemId);
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

    public void onContainerRemoved(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
        boolean found = false;
        int foundAreaId = -1;
        String foundAreaType = "";

        // 检查备货区
        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    onContainerRemovedFromArea(area.id, "staging_area", pos);
                    foundAreaId = area.id;
                    foundAreaType = "staging_area";
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
                        onContainerRemovedFromArea(wh.id(), "warehouse", pos);
                        foundAreaId = wh.id();
                        foundAreaType = "warehouse";
                        found = true;
                        break;
                    }
                }
            }
        }

        // 大箱子被破坏一半时，重新扫描另一半（从54格变回27格）
        // 被破坏的方块已经是 AIR，所以检查四个方向的相邻方块
        if (found) {
            for (var dir : new net.minecraft.util.math.Direction[]{
                    net.minecraft.util.math.Direction.NORTH,
                    net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST,
                    net.minecraft.util.math.Direction.WEST}) {
                BlockPos neighbor = pos.offset(dir);
                BlockEntity neighborBE = world.getBlockEntity(neighbor);
                if (neighborBE instanceof Inventory) {
                    // 相邻位置是容器且属于同一区域，重新扫描
                    Integer neighborAreaId = findAreaId(neighbor, world);
                    if (neighborAreaId == null) neighborAreaId = findWarehouseId(neighbor, world);
                    if (neighborAreaId != null && neighborAreaId == foundAreaId) {
                        Set<String> newItems = scanSingleContainerItems(world, neighbor);
                        // 全量替换该位置的记录
                        try {
                            database.executeUpdate(
                                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                                foundAreaId, foundAreaType, neighbor.getX(), neighbor.getY(), neighbor.getZ());
                        } catch (SQLException e) {
                            SyncMaterial.LOGGER.error("Failed to clean paired position inventory", e);
                        }
                        for (String itemId : newItems) {
                            try {
                                database.executeUpdate(
                                    "INSERT OR IGNORE INTO container_inventory (area_id, area_type, pos_x, pos_y, pos_z, item_id) VALUES (?, ?, ?, ?, ?, ?)",
                                    foundAreaId, foundAreaType, neighbor.getX(), neighbor.getY(), neighbor.getZ(), itemId);
                            } catch (SQLException e) {
                                SyncMaterial.LOGGER.error("Failed to insert paired position inventory", e);
                            }
                        }
                    }
                    break;
                }
            }
            pushDirtyUpdateWithCooldown();
        }
    }

    /**
     * 容器被破坏后，清理 container_inventory 明细并全量重扫该区域
     */
    private void onContainerRemovedFromArea(int areaId, String areaType, BlockPos pos) {
        try {
            // 1. 删除该位置在 container_inventory 中的记录
            database.executeUpdate(
                "DELETE FROM container_inventory WHERE area_id=? AND area_type=? AND pos_x=? AND pos_y=? AND pos_z=?",
                areaId, areaType, pos.getX(), pos.getY(), pos.getZ());
            SyncMaterial.LOGGER.info("[StagingArea] 容器被移除: area={} type={} pos={},{},{}", areaId, areaType, pos.getX(), pos.getY(), pos.getZ());
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to clean container_inventory on removal", e);
        }

        // 2. 全量重扫该区域及同原理图所有备货区
        if ("warehouse".equals(areaType)) {
            Map<String, Integer> result = scanWarehouseContents(areaId);
            if (result != null) updateWarehouseInventory(areaId, result);
        } else {
            rescanAllStagingAreasForSibling(areaId);
        }
    }

    /**
     * 重扫指定备货区所在原理图的所有备货区（材料总数是 SUM，必须全部重扫）
     */
    private void rescanAllStagingAreasForSibling(int areaId) {
        for (var schematicEntry : stagingAreasBySchematic.entrySet()) {
            for (StagingArea area : schematicEntry.getValue()) {
                if (area.id == areaId) {
                    for (StagingArea sibling : schematicEntry.getValue()) {
                        Map<String, Integer> result = scanAreaContents(sibling.id);
                        if (result != null) updateStagingAreaInventory(sibling.id, result);
                    }
                    return;
                }
            }
        }
    }

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
     * 启动时扫描仓库并标记区块为已扫描
     */
    public void rescanWarehouseAndMarkChunks(int warehouseId) {
        Warehouse wh = warehousesById.get(warehouseId);
        if (wh == null) return;

        var totalItems = scanWarehouseContents(warehouseId);
        if (totalItems == null) return;

        updateWarehouseInventory(warehouseId, totalItems);
        SyncMaterial.LOGGER.info("[StagingArea] rescanWarehouse: id={} found {} item types", warehouseId, totalItems.size());
    }

    /**
     * 扫描仓库内容物（复用 scanAreaContents 的逻辑，针对仓库坐标）
     */
    private Map<String, Integer> scanWarehouseContents(int warehouseId) {
        Warehouse wh = warehousesById.get(warehouseId);
        if (wh == null) return null;

        ServerWorld world = server.getWorld(net.minecraft.registry.RegistryKey.of(
                net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(wh.world())));
        if (world == null) return null;

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
                if (world.getChunkManager().getWorldChunk(chunkX, chunkZ) == null) continue;

                int startX = Math.max(chunkX << 4, minX);
                int endX = Math.min((chunkX << 4) + 15, maxX);
                int startZ = Math.max(chunkZ << 4, minZ);
                int endZ = Math.min((chunkZ << 4) + 15, maxZ);

                for (int x = startX; x <= endX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = startZ; z <= endZ; z++) {
                            BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                            if (be instanceof Inventory inventory) {
                                countInventoryItems(inventory, totalItems);
                            }
                        }
                    }
                }

                // 扫描成功，标记该区块并检查是否完成初始化
                markChunkAndCheckInit(warehouseId, ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL),
                    wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2());
            }
        }
        return totalItems;
    }

    /**
     * 更新仓库库存总数表
     */
    private void updateWarehouseInventory(int warehouseId, Map<String, Integer> itemCounts) {
        try {
            database.executeUpdate("DELETE FROM warehouse_inventory WHERE warehouse_id = ?", warehouseId);
            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                database.executeUpdate(
                    "INSERT INTO warehouse_inventory (warehouse_id, item_id, count) VALUES (?, ?, ?)",
                    warehouseId, entry.getKey(), entry.getValue());
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update warehouse inventory", e);
        }
    }

    /**
     * 更新容器明细表（全量重写指定区域的 container_inventory）
     */
    private void updateContainerInventoryForArea(int areaId, String areaType) {
        try {
            // 清空旧数据
            database.executeUpdate("DELETE FROM container_inventory WHERE area_id = ? AND area_type = ?", areaId, areaType);

            // 获取区域坐标
            int x1, y1, z1, x2, y2, z2;
            String worldId;
            if ("warehouse".equals(areaType)) {
                Warehouse wh = warehousesById.get(areaId);
                if (wh == null) return;
                x1 = wh.x1(); y1 = wh.y1(); z1 = wh.z1();
                x2 = wh.x2(); y2 = wh.y2(); z2 = wh.z2();
                worldId = wh.world();
            } else {
                StagingArea area = findStagingAreaById(areaId);
                if (area == null) return;
                x1 = area.x1; y1 = area.y1; z1 = area.z1;
                x2 = area.x2; y2 = area.y2; z2 = area.z2;
                worldId = area.world;
            }

            ServerWorld world = server.getWorld(net.minecraft.registry.RegistryKey.of(
                    net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(worldId)));
            if (world == null) return;

            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

            int minChunkX = minX >> 4, maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4, maxChunkZ = maxZ >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (world.getChunkManager().getWorldChunk(chunkX, chunkZ) == null) continue;

                    int startX = Math.max(chunkX << 4, minX);
                    int endX = Math.min((chunkX << 4) + 15, maxX);
                    int startZ = Math.max(chunkZ << 4, minZ);
                    int endZ = Math.min((chunkZ << 4) + 15, maxZ);

                    for (int x = startX; x <= endX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = startZ; z <= endZ; z++) {
                                BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                                if (be instanceof Inventory inventory) {
                                    writeContainerInventory(areaId, areaType, x, y, z, inventory);
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to update container inventory for area {} type {}", areaId, areaType, e);
        }
    }

    /**
     * 写入单个箱子的容器明细（含潜影盒，不存数量）
     */
    private void writeContainerInventory(int areaId, String areaType, int x, int y, int z, Inventory inventory) {
        Set<String> itemIds = new HashSet<>();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            itemIds.add(stack.getItem().toString());

            // 潜影盒内容物
            if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem &&
                blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
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
     * 区域是否已初始化（服务器重启后所有区块至少扫描过一次）
     */
    public boolean isAreaInitialized(int areaId) {
        return initializedAreas.contains(areaId);
    }

    /**
     * 标记区块已扫描，如果区域所有区块都已扫描则标记为已初始化
     */
    private void markChunkAndCheckInit(int areaId, long chunkPos,
            int x1, int y1, int z1, int x2, int y2, int z2) {
        if (initializedAreas.contains(areaId)) return;

        initChunkTracking.computeIfAbsent(String.valueOf(areaId), k -> ConcurrentHashMap.newKeySet()).add(chunkPos);

        // 检查是否所有区块都已扫描
        int minChunkX = Math.min(x1, x2) >> 4;
        int maxChunkX = Math.max(x1, x2) >> 4;
        int minChunkZ = Math.min(z1, z2) >> 4;
        int maxChunkZ = Math.max(z1, z2) >> 4;

        Set<Long> scanned = initChunkTracking.get(String.valueOf(areaId));
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!scanned.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL))) {
                    return; // 还有未扫描的区块
                }
            }
        }

        // 所有区块已扫描，标记为已初始化，清除临时跟踪
        initializedAreas.add(areaId);
        initChunkTracking.remove(String.valueOf(areaId));
        SyncMaterial.LOGGER.info("[StagingArea] areaId={} 初始化完成（所有区块已扫描）", areaId);
    }

    /**
     * 区块加载时扫描：检查该区块内是否有仓库/备货区的箱子，有则扫描写入数据库
     */
    public void scanChunkForInventoryAreas(net.minecraft.world.chunk.WorldChunk chunk, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        long chunkPos = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

        // 检查备货区（只处理未初始化的）
        List<StagingArea> areas = stagingAreasByWorld.get(worldId);
        if (areas != null) {
            for (StagingArea area : areas) {
                if (initializedAreas.contains(area.id)) continue;
                if (chunkIntersectsArea(chunkX, chunkZ, area.x1, area.y1, area.z1, area.x2, area.y2, area.z2)) {
                    scanChunkForArea(chunk, world, area.id, "staging_area",
                        area.x1, area.y1, area.z1, area.x2, area.y2, area.z2);
                    markChunkAndCheckInit(area.id, chunkPos,
                        area.x1, area.y1, area.z1, area.x2, area.y2, area.z2);
                }
            }
        }

        // 检查仓库（只处理未初始化的）
        List<Warehouse> warehouses = warehousesByWorld.get(worldId);
        if (warehouses != null) {
            for (Warehouse wh : warehouses) {
                if (initializedAreas.contains(wh.id())) continue;
                if (chunkIntersectsArea(chunkX, chunkZ, wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2())) {
                    scanChunkForArea(chunk, world, wh.id(), "warehouse",
                        wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2);
                    markChunkAndCheckInit(wh.id(), chunkPos,
                        wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2);
                }
            }
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
    private void scanChunkForArea(WorldChunk chunk, ServerWorld world, int areaId, String areaType,
            int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.max(chunk.getPos().x << 4, Math.min(x1, x2));
        int maxX = Math.min((chunk.getPos().x << 4) + 15, Math.max(x1, x2));
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.max(chunk.getPos().z << 4, Math.min(z1, z2));
        int maxZ = Math.min((chunk.getPos().z << 4) + 15, Math.max(z1, z2));

        Map<String, Integer> items = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
                    if (be instanceof Inventory inventory) {
                        writeContainerInventory(areaId, areaType, x, y, z, inventory);
                        countInventoryItems(inventory, items);
                    }
                }
            }
        }

        // 更新总数表
        if (!items.isEmpty()) {
            if ("warehouse".equals(areaType)) {
                updateWarehouseInventory(areaId, items);
            } else {
                updateStagingAreaInventory(areaId, items);
            }
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
        try {
            database.executeUpdate(
                "UPDATE warehouses SET name=?, x1=?, y1=?, z1=?, x2=?, y2=?, z2=? WHERE id=?",
                name, x1, y1, z1, x2, y2, z2, id);
            Warehouse old = warehousesById.get(id);
            if (old != null) {
                Warehouse wh = new Warehouse(id, name, old.world(), x1, y1, z1, x2, y2, z2);
                warehousesById.put(id, wh);
                rebuildWarehouseWorldIndex();
            }
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