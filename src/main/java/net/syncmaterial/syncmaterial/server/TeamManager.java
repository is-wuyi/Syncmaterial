package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;

import java.sql.*;
import java.util.*;

public class TeamManager {
    private final SchematicDatabase database;

    public TeamManager(SchematicDatabase database) {
        this.database = database;
    }

    public int claimMaterial(String schematicId, int materialId, String playerName, int count) {
        try {
            int totalCount = 0;
            try (var rs = database.executeQuery(
                "SELECT count FROM material_entries WHERE id = ? AND schematic_id = ?",
                materialId, schematicId
            )) {
                if (rs.next()) {
                    totalCount = rs.getInt("count");
                }
            }

            if (totalCount == 0) {
                SyncMaterial.LOGGER.warn("材料 {} 不存在", materialId);
                return -1;
            }

            int existingClaimed = 0;
            try (var rs = database.executeQuery(
                "SELECT id, claimed_count FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                schematicId, materialId, playerName
            )) {
                if (rs.next()) {
                    existingClaimed = rs.getInt("claimed_count");
                }
            }

            if (existingClaimed + count > totalCount) {
                SyncMaterial.LOGGER.warn("玩家 {} 认领材料 {} 失败：已认领 {} + 新增 {} > 总量 {}",
                    playerName, materialId, existingClaimed, count, totalCount);
                return -2;
            }

            int newClaimed;
            if (existingClaimed > 0) {
                newClaimed = existingClaimed + count;
                try (var rs = database.executeQuery(
                    "SELECT id FROM claims WHERE schematic_id = ? AND material_id = ? AND player_name = ? AND status = 'active'",
                    schematicId, materialId, playerName
                )) {
                    if (rs.next()) {
                        int claimId = rs.getInt("id");
                        database.executeUpdate(
                            "UPDATE claims SET claimed_count = ? WHERE id = ?",
                            newClaimed, claimId
                        );
                    }
                }
                SyncMaterial.LOGGER.info("玩家 {} 累加认领材料 {} (原: {}, 新增: {}, 总计: {})", playerName, materialId, existingClaimed, count, newClaimed);
            } else {
                newClaimed = count;
                database.executeUpdate(
                    "INSERT INTO claims (schematic_id, material_id, player_name, claimed_count, status) VALUES (?, ?, ?, ?, 'active')",
                    schematicId, materialId, playerName, count
                );
                SyncMaterial.LOGGER.info("玩家 {} 认领了材料 {} (数量: {})", playerName, materialId, count);
            }

            return newClaimed;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("认领材料失败", e);
            return -1;
        }
    }

    public boolean abandonClaim(String playerName, int claimId) {
        try {
            database.executeUpdate(
                "UPDATE claims SET status = 'abandoned' WHERE id = ? AND player_name = ? AND status = 'active'",
                claimId, playerName
            );
            return true;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("放弃认领失败", e);
            return false;
        }
    }

    public Map<Integer, MaterialStatus> getMaterialStatus(String schematicId) {
        Map<Integer, MaterialStatus> statusMap = new HashMap<>();
        try {
            database.executeQueryAndProcess(
                "SELECT me.id, me.item_id, me.count FROM material_entries me WHERE me.schematic_id = ?",
                rs -> {
                    try {
                        int materialId = rs.getInt("id");
                        String itemId = rs.getString("item_id");
                        int totalCount = rs.getInt("count");

                        int claimedCount = 0;
                        String claimer = null;
                        try (var claimRs = database.executeQuery(
                            "SELECT player_name, claimed_count FROM claims WHERE material_id = ? AND status = 'active'",
                            materialId
                        )) {
                            if (claimRs.next()) {
                                claimer = claimRs.getString("player_name");
                                claimedCount = claimRs.getInt("claimed_count");
                            }
                        }

                        statusMap.put(materialId, new MaterialStatus(materialId, itemId, totalCount, claimedCount, claimer));
                    } catch (SQLException e) {
                        SyncMaterial.LOGGER.error("查询材料状态失败", e);
                    }
                },
                schematicId
            );
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询材料状态失败", e);
        }
        return statusMap;
    }

    public boolean isOwner(String schematicId, String playerName) {
        try {
            try (var rs = database.executeQuery(
                "SELECT uploaded_by FROM schematics WHERE id = ?",
                schematicId
            )) {
                if (rs.next()) {
                    return playerName.equals(rs.getString("uploaded_by"));
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询负责人失败", e);
        }
        return false;
    }

    public boolean canAssign(String schematicId, String playerName) {
        if (isOwner(schematicId, playerName)) {
            return true;
        }
        try {
            try (var rs = database.executeQuery(
                "SELECT 1 FROM assignment_permissions WHERE schematic_id = ? AND player_name = ?",
                schematicId, playerName
            )) {
                return rs.next();
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询权限失败", e);
            return false;
        }
    }

    public boolean isSelfClaimAllowed(String schematicId) {
        try {
            try (var rs = database.executeQuery(
                "SELECT allow_self_claim FROM schematics WHERE id = ?",
                schematicId
            )) {
                if (rs.next()) {
                    return rs.getInt("allow_self_claim") == 1;
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询认领开关失败", e);
        }
        return true;
    }

    public static class MaterialStatus {
        public final int materialId;
        public final String itemId;
        public final int totalCount;
        public final int claimedCount;
        public final String claimer;

        public MaterialStatus(int materialId, String itemId, int totalCount, int claimedCount, String claimer) {
            this.materialId = materialId;
            this.itemId = itemId;
            this.totalCount = totalCount;
            this.claimedCount = claimedCount;
            this.claimer = claimer;
        }

        public boolean isClaimed() {
            return claimer != null;
        }

        public int getRemainingCount() {
            return totalCount - claimedCount;
        }
    }
}
