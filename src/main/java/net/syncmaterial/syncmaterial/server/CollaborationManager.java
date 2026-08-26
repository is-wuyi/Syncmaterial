//? if >=26 {
package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborationManager {
    private final SchematicDatabase database;
    private StagingAreaManager stagingAreaManager;
    
    private final Map<String, Map<Integer, Integer>> playerInventories = new ConcurrentHashMap<>();

    public CollaborationManager(SchematicDatabase database) {
        this.database = database;
    }

    public void setStagingAreaManager(StagingAreaManager stagingAreaManager) {
        this.stagingAreaManager = stagingAreaManager;
    }

    /**
     * 服务端启动时，从数据库加载所有已知原理图的玩家背包缓存。
     * 确保离线玩家的进度数据在重启后不会丢失。
     */
    public void loadAllInventories() {
        try {
            Map<String, Map<Integer, Integer>> allInventories = new java.util.HashMap<>();
            List<String> schematicIds = new java.util.ArrayList<>();
            try (var rs = database.executeQuery("SELECT DISTINCT id FROM schematics")) {
                while (rs.next()) {
                    schematicIds.add(rs.getString("id"));
                }
            }
            for (String sid : schematicIds) {
                Map<String, Map<Integer, Integer>> invs = database.loadPlayerInventories(sid);
                for (java.util.Map.Entry<String, Map<Integer, Integer>> entry : invs.entrySet()) {
                    allInventories.merge(entry.getKey(), entry.getValue(), (oldVal, newVal) -> {
                        oldVal.putAll(newVal);
                        return oldVal;
                    });
                }
            }
            playerInventories.putAll(allInventories);
            SyncMaterial.LOGGER.info("已从数据库加载 {} 位玩家的背包缓存", allInventories.size());
        } catch (java.sql.SQLException e) {
            SyncMaterial.LOGGER.error("加载背包缓存失败", e);
        }
    }

    public boolean joinCollaboration(String schematicId, int materialId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (!rs.next()) {
                    SyncMaterial.LOGGER.debug("材料 {} 不存在于原理图 {}", materialId, schematicId);
                    return false;
                }
            }

            database.executeUpdate(
                "INSERT OR IGNORE INTO claims (schematic_id, material_id, player_name, status) VALUES (?, ?, ?, 'active')",
                schematicId, materialId, playerName
            );
            
            SyncMaterial.LOGGER.debug("玩家 {} 加入材料 {} 的协作组", playerName, materialId);
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("加入协作组失败", e);
            return false;
        }
    }

    public boolean leaveCollaboration(String schematicId, int materialId, String playerName) {
        try {
            database.executeUpdate(
                "DELETE FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                schematicId, materialId, playerName
            );
            
            playerInventories.computeIfPresent(playerName, (name, inv) -> {
                inv.remove(materialId);
                return inv.isEmpty() ? null : inv;
            });

            SyncMaterial.LOGGER.debug("玩家 {} 退出材料 {} 的协作组", playerName, materialId);
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("退出协作组失败", e);
            return false;
        }
    }

    public void updateInventory(String playerName, String schematicId, int materialId, int count) {
        playerInventories.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>())
                         .put(materialId, count);
        try {
            database.upsertInventory(schematicId, playerName, materialId, count);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to persist player inventory", e);
        }
    }

    public CollaborationStatusS2CPacket getCollaborationStatus(String schematicId, int materialId) {
        try {
            int totalCount = 0;
            String itemId = "";
            try (var rs = database.executeQuery(
                "SELECT count, item_id FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (rs.next()) {
                    totalCount = rs.getInt("count");
                    itemId = rs.getString("item_id");
                }
            }

            int stagingCount = stagingAreaManager.getStagingCountForMaterial(schematicId, itemId);
            int warehouseCount = stagingAreaManager.getWarehouseCountForMaterial(schematicId, itemId);

            List<CollaborationStatusS2CPacket.ParticipantInfo> participants = new ArrayList<>();
            try (var rs = database.executeQuery(
                "SELECT player_name FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
                schematicId, materialId
            )) {
                while (rs.next()) {
                    String playerName = rs.getString("player_name");
                    int count = 0;
                    Map<Integer, Integer> inv = playerInventories.get(playerName);
                    if (inv != null) {
                        count = inv.getOrDefault(materialId, 0);
                    }
                    participants.add(new CollaborationStatusS2CPacket.ParticipantInfo(playerName, count));
                    SyncMaterial.LOGGER.debug("  participant: {} count={} (from playerInventories)", playerName, count);
                }
            }

            int playersSum = 0;
            for (var p : participants) {
                playersSum += p.count();
            }
            // 与 ModNetworkHandler/HUD 保持同一口径：已收集含备货区 + 仓库 + 参与者背包
            int collected = stagingCount + warehouseCount + playersSum;
            SyncMaterial.LOGGER.debug("getCollaborationStatus: material={}, total={}, staging={}, warehouse={}, participants={}, collected={}",
                materialId, totalCount, stagingCount, warehouseCount, participants.size(), collected);

            // 构建数据新鲜度列表
            List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessInfo = buildFreshnessInfo(schematicId);

            return new CollaborationStatusS2CPacket(schematicId, materialId, totalCount, stagingCount, warehouseCount, participants, freshnessInfo);

        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取协作状态失败", e);
            return null;
        }
    }

    public List<Integer> getAllMaterialIds(String schematicId) {
        List<Integer> materialIds = new ArrayList<>();
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM material_entries WHERE schematic_id = ?",
                schematicId
            )) {
                while (rs.next()) {
                    materialIds.add(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取原理图 {} 的所有材料ID失败", schematicId, e);
        }
        return materialIds;
    }

    public boolean isCollaborating(String schematicId, int materialId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                schematicId, materialId, playerName
            )) {
                return rs.next();
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("检查协作状态失败", e);
            return false;
        }
    }

    public List<String> getParticipants(String schematicId, int materialId) {
        List<String> participants = new ArrayList<>();
        try (var rs = database.executeQuery(
            "SELECT player_name FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
            schematicId, materialId
        )) {
            while (rs.next()) {
                participants.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取参与者列表失败", e);
        }
        return participants;
    }

    /**
     * 构建初始化状态列表
     * 检查该原理图引用的所有备货区和仓库是否已初始化（所有区块至少扫描过一次）
     */
    private List<CollaborationStatusS2CPacket.AreaFreshnessInfo> buildFreshnessInfo(String schematicId) {
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> result = new ArrayList<>();
        if (stagingAreaManager == null) return result;

        // 检查备货区
        var stagingAreas = stagingAreaManager.getStagingAreas(schematicId);
        for (var area : stagingAreas) {
            if (!stagingAreaManager.isStagingAreaInitialized(area.id())) {
                result.add(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                    "staging_area", area.id(), area.name(), "区域尚未初始化"));
            }
        }

        // 检查仓库
        var warehouses = stagingAreaManager.getWarehousesForSchematic(schematicId);
        for (var wh : warehouses) {
            if (!stagingAreaManager.isWarehouseInitialized(wh.id())) {
                result.add(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                    "warehouse", wh.id(), wh.name(), "区域尚未初始化"));
            }
        }

        return result;
    }
}
//?} else {
package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborationManager {
    private final SchematicDatabase database;
    private StagingAreaManager stagingAreaManager;
    
    private final Map<String, Map<Integer, Integer>> playerInventories = new ConcurrentHashMap<>();

    public CollaborationManager(SchematicDatabase database) {
        this.database = database;
    }

    public void setStagingAreaManager(StagingAreaManager stagingAreaManager) {
        this.stagingAreaManager = stagingAreaManager;
    }

    /**
     * 服务端启动时，从数据库加载所有已知原理图的玩家背包缓存。
     * 确保离线玩家的进度数据在重启后不会丢失。
     */
    public void loadAllInventories() {
        try {
            Map<String, Map<Integer, Integer>> allInventories = new java.util.HashMap<>();
            List<String> schematicIds = new java.util.ArrayList<>();
            try (var rs = database.executeQuery("SELECT DISTINCT id FROM schematics")) {
                while (rs.next()) {
                    schematicIds.add(rs.getString("id"));
                }
            }
            for (String sid : schematicIds) {
                Map<String, Map<Integer, Integer>> invs = database.loadPlayerInventories(sid);
                for (java.util.Map.Entry<String, Map<Integer, Integer>> entry : invs.entrySet()) {
                    allInventories.merge(entry.getKey(), entry.getValue(), (oldVal, newVal) -> {
                        oldVal.putAll(newVal);
                        return oldVal;
                    });
                }
            }
            playerInventories.putAll(allInventories);
            SyncMaterial.LOGGER.info("已从数据库加载 {} 位玩家的背包缓存", allInventories.size());
        } catch (java.sql.SQLException e) {
            SyncMaterial.LOGGER.error("加载背包缓存失败", e);
        }
    }

    public boolean joinCollaboration(String schematicId, int materialId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (!rs.next()) {
                    SyncMaterial.LOGGER.debug("材料 {} 不存在于原理图 {}", materialId, schematicId);
                    return false;
                }
            }

            database.executeUpdate(
                "INSERT OR IGNORE INTO claims (schematic_id, material_id, player_name, status) VALUES (?, ?, ?, 'active')",
                schematicId, materialId, playerName
            );
            
            SyncMaterial.LOGGER.debug("玩家 {} 加入材料 {} 的协作组", playerName, materialId);
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("加入协作组失败", e);
            return false;
        }
    }

    public boolean leaveCollaboration(String schematicId, int materialId, String playerName) {
        try {
            database.executeUpdate(
                "DELETE FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                schematicId, materialId, playerName
            );
            
            playerInventories.computeIfPresent(playerName, (name, inv) -> {
                inv.remove(materialId);
                return inv.isEmpty() ? null : inv;
            });

            SyncMaterial.LOGGER.debug("玩家 {} 退出材料 {} 的协作组", playerName, materialId);
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("退出协作组失败", e);
            return false;
        }
    }

    public void updatePlayerInventory(String playerName, String schematicId, int materialId, int count) {
        playerInventories.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>())
                         .put(materialId, count);
        try {
            database.upsertPlayerInventory(schematicId, playerName, materialId, count);
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("Failed to persist player inventory", e);
        }
    }

    public CollaborationStatusS2CPacket getCollaborationStatus(String schematicId, int materialId) {
        try {
            int totalCount = 0;
            String itemId = "";
            try (var rs = database.executeQuery(
                "SELECT count, item_id FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (rs.next()) {
                    totalCount = rs.getInt("count");
                    itemId = rs.getString("item_id");
                }
            }

            int stagingCount = stagingAreaManager.getStagingCountForMaterial(schematicId, itemId);
            int warehouseCount = stagingAreaManager.getWarehouseCountForMaterial(schematicId, itemId);

            List<CollaborationStatusS2CPacket.ParticipantInfo> participants = new ArrayList<>();
            try (var rs = database.executeQuery(
                "SELECT player_name FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
                schematicId, materialId
            )) {
                while (rs.next()) {
                    String playerName = rs.getString("player_name");
                    int count = 0;
                    Map<Integer, Integer> inv = playerInventories.get(playerName);
                    if (inv != null) {
                        count = inv.getOrDefault(materialId, 0);
                    }
                    participants.add(new CollaborationStatusS2CPacket.ParticipantInfo(playerName, count));
                    SyncMaterial.LOGGER.debug("  participant: {} count={} (from playerInventories)", playerName, count);
                }
            }

            int playersSum = 0;
            for (var p : participants) {
                playersSum += p.count();
            }
            // 与 ModNetworkHandler/HUD 保持同一口径：已收集含备货区 + 仓库 + 参与者背包
            int collected = stagingCount + warehouseCount + playersSum;
            SyncMaterial.LOGGER.debug("getCollaborationStatus: material={}, total={}, staging={}, warehouse={}, participants={}, collected={}",
                materialId, totalCount, stagingCount, warehouseCount, participants.size(), collected);

            // 构建数据新鲜度列表
            List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessInfo = buildFreshnessInfo(schematicId);

            return new CollaborationStatusS2CPacket(schematicId, materialId, totalCount, stagingCount, warehouseCount, participants, freshnessInfo);

        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取协作状态失败", e);
            return null;
        }
    }

    public List<Integer> getAllMaterialIds(String schematicId) {
        List<Integer> materialIds = new ArrayList<>();
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM material_entries WHERE schematic_id = ?",
                schematicId
            )) {
                while (rs.next()) {
                    materialIds.add(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取原理图 {} 的所有材料ID失败", schematicId, e);
        }
        return materialIds;
    }

    public boolean isCollaborating(String schematicId, int materialId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                schematicId, materialId, playerName
            )) {
                return rs.next();
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("检查协作状态失败", e);
            return false;
        }
    }

    public List<String> getParticipants(String schematicId, int materialId) {
        List<String> participants = new ArrayList<>();
        try (var rs = database.executeQuery(
            "SELECT player_name FROM claims WHERE schematic_id = ? AND material_id = ? AND status = 'active'",
            schematicId, materialId
        )) {
            while (rs.next()) {
                participants.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取参与者列表失败", e);
        }
        return participants;
    }

    /**
     * 构建初始化状态列表
     * 检查该原理图引用的所有备货区和仓库是否已初始化（所有区块至少扫描过一次）
     */
    private List<CollaborationStatusS2CPacket.AreaFreshnessInfo> buildFreshnessInfo(String schematicId) {
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> result = new ArrayList<>();
        if (stagingAreaManager == null) return result;

        // 检查备货区
        var stagingAreas = stagingAreaManager.getStagingAreas(schematicId);
        for (var area : stagingAreas) {
            if (!stagingAreaManager.isStagingAreaInitialized(area.id())) {
                result.add(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                    "staging_area", area.id(), area.name(), "区域尚未初始化"));
            }
        }

        // 检查仓库
        var warehouses = stagingAreaManager.getWarehousesForSchematic(schematicId);
        for (var wh : warehouses) {
            if (!stagingAreaManager.isWarehouseInitialized(wh.id())) {
                result.add(new CollaborationStatusS2CPacket.AreaFreshnessInfo(
                    "warehouse", wh.id(), wh.name(), "区域尚未初始化"));
            }
        }

        return result;
    }
}
//?}
