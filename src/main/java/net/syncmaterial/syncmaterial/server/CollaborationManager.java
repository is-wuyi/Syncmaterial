package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborationManager {
    private final SchematicDatabase database;
    
    private final Map<String, Map<Integer, Integer>> playerInventories = new ConcurrentHashMap<>();

    public CollaborationManager(SchematicDatabase database) {
        this.database = database;
    }

    public boolean joinCollaboration(String schematicId, int materialId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT id FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (!rs.next()) {
                    SyncMaterial.LOGGER.warn("材料 {} 不存在于原理图 {}", materialId, schematicId);
                    return false;
                }
            }

            database.executeUpdate(
                "INSERT OR IGNORE INTO claims (schematic_id, material_id, player_name, status) VALUES (?, ?, ?, 'active')",
                schematicId, materialId, playerName
            );
            
            SyncMaterial.LOGGER.info("玩家 {} 加入材料 {} 的协作组", playerName, materialId);
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

            SyncMaterial.LOGGER.info("玩家 {} 退出材料 {} 的协作组", playerName, materialId);
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("退出协作组失败", e);
            return false;
        }
    }

    public void updatePlayerInventory(String playerName, String schematicId, int materialId, int count) {
        playerInventories.computeIfAbsent(playerName, k -> new ConcurrentHashMap<>())
                         .put(materialId, count);
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

            int stagingCount = 0;
            try (var rs = database.executeQuery(
                "SELECT count FROM staging_area WHERE schematic_id = ? AND material_id = ?",
                schematicId, materialId
            )) {
                if (rs.next()) {
                    stagingCount = rs.getInt("count");
                }
            }

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
                }
            }

            return new CollaborationStatusS2CPacket(schematicId, materialId, totalCount, stagingCount, participants);

        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取协作状态失败", e);
            return null;
        }
    }

    public void onPlayerDisconnect(String playerName) {
    }
}
