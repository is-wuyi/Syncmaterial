package net.syncmaterial.syncmaterial.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
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
        StagingAreaConfigResponseS2CPacket packet = new StagingAreaConfigResponseS2CPacket(schematicId, schematicName, true, "", areaInfos);

        ModNetworkHandler.sendToPlayers(set, packet);
        SyncMaterial.LOGGER.info("[StagingArea] 广播原理图 {} 的备货区更新给 {} 个玩家", schematicId, set.size());
    }

    public int addStagingArea(String schematicId, String world, String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            database.executeUpdate(
                "INSERT INTO staging_areas (schematic_id, world, name, x1, y1, z1, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                schematicId, world, name, x1, y1, z1, x2, y2, z2
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

        SyncMaterial.LOGGER.info("[StagingArea] processDirtyContainers: {} dirty containers", dirtyContainers.size());

        Set<Integer> dirtyAreaIds = new HashSet<>();

        for (Map.Entry<BlockPos, ServerWorld> entry : dirtyContainers.entrySet()) {
            BlockPos pos = entry.getKey();
            ServerWorld world = entry.getValue();

            Integer areaId = findAreaId(pos, world);
            SyncMaterial.LOGGER.info("[StagingArea] findAreaId: pos={},{},{} areaId={}", pos.getX(), pos.getY(), pos.getZ(), areaId);
            if (areaId != null) {
                dirtyAreaIds.add(areaId);
            }
        }

        dirtyContainers.clear();

        SyncMaterial.LOGGER.info("[StagingArea] dirtyAreaIds: {}", dirtyAreaIds);

        if (!dirtyAreaIds.isEmpty() && server != null) {
            server.execute(() -> {
                for (int areaId : dirtyAreaIds) {
                    Map<String, Integer> result = scanAreaContents(areaId);
                    if (result != null) {
                        updateStagingAreaInventory(areaId, result);
                        String schematicId = findSchematicIdByAreaId(areaId);
                        if (schematicId != null) {
                            net.syncmaterial.syncmaterial.network.Phase4Handler.broadcastAllMaterialStatus(server, schematicId);
                        }
                    }
                }
            });
        }
    }

    private Integer findAreaId(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();
        List<StagingArea> areas = stagingAreasByWorld.get(worldId);
        if (areas == null) return null;
        for (StagingArea area : areas) {
            if (isPosInArea(pos, area)) {
                return area.id;
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

    public void onContainerRemoved(BlockPos pos, ServerWorld world) {
        String worldId = world.getRegistryKey().getValue().toString();

        for (List<StagingArea> areas : stagingAreasBySchematic.values()) {
            for (StagingArea area : areas) {
                if (area.world.equals(worldId) && isPosInArea(pos, area)) {
                    try {
                        database.executeUpdate(
                            "DELETE FROM staging_area_inventory WHERE staging_area_id = ?",
                            area.id
                        );
                        SyncMaterial.LOGGER.info("Cleared staging area {} inventory after container removal", area.id);
                    } catch (SQLException e) {
                        SyncMaterial.LOGGER.error("Failed to clear staging area inventory on container removal", e);
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
}