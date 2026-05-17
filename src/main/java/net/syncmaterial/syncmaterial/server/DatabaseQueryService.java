package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库查询服务
 * 提供高效的材料统计数据查询功能
 */
public class DatabaseQueryService {
    private final SchematicDatabase database;

    public DatabaseQueryService(SchematicDatabase database) {
        this.database = database;
    }

    /**
     * 查询指定原理图的材料统计
     */
    public List<MaterialEntry> getMaterials(String schematicId) {
        List<MaterialEntry> materials = new ArrayList<>();

        try {
            ResultSet rs = database.executeQuery(
                "SELECT id, item_id, count FROM material_entries WHERE schematic_id = ?",
                schematicId
            );

            try {
                while (rs.next()) {
                    int dbId = rs.getInt("id");
                    String itemId = rs.getString("item_id");
                    int count = rs.getInt("count");

                    if (itemId == null || itemId.isEmpty()) {
                        continue;
                    }

                    var itemRegistry = net.minecraft.registry.Registries.ITEM;
                    var identifier = net.minecraft.util.Identifier.of(itemId);
                    var item = itemRegistry.get(identifier);
                    
                    if (item != null && item != net.minecraft.item.Items.AIR) {
                        var stack = new net.minecraft.item.ItemStack(item, count);
                        materials.add(new MaterialEntry(dbId, stack, count));
                    } else {
                        SyncMaterial.LOGGER.warn("未找到物品: {}", itemId);
                    }
                }
            } finally {
                rs.close(); // 关闭 ResultSet，Statement 会随连接关闭
            }

        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询材料统计失败: {}", schematicId, e);
            return List.of(); // 返回空列表表示查询失败
        }

        return materials;
    }

    /**
     * 检查原理图是否存在于数据库中
     */
    public boolean schematicExists(String schematicId) {
        try (ResultSet rs = database.executeQuery(
                "SELECT COUNT(*) as count FROM schematics WHERE id = ?",
                schematicId
        )) {
            return rs.next() && rs.getInt("count") > 0;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("检查原理图存在性失败: {}", schematicId, e);
            return false;
        }
    }

    /**
     * 获取原理图的基本信息
     */
    public SchematicInfo getSchematicInfo(String schematicId) {
        try (ResultSet rs = database.executeQuery(
                "SELECT name, file_path, uploaded_by, created_at FROM schematics WHERE id = ?",
                schematicId
        )) {
            if (rs.next()) {
                String name = rs.getString("name");
                String filePath = rs.getString("file_path");
                String uploadedBy = rs.getString("uploaded_by");
                long createdAt = rs.getLong("created_at");
                return new SchematicInfo(schematicId, name, filePath, uploadedBy, createdAt);
            }
            return null;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取原理图信息失败: {}", schematicId, e);
            return null;
        }
    }

    /**
     * 获取数据库中的所有原理图ID
     */
    public List<String> getAllSchematicIds() {
        List<String> ids = new ArrayList<>();

        try (ResultSet rs = database.executeQuery("SELECT id FROM schematics ORDER BY created_at DESC")) {
            while (rs.next()) {
                ids.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("获取所有原理图ID失败", e);
            return List.of();
        }

        return ids;
    }

    /**
     * 原理图信息数据类
     */
    public static class SchematicInfo {
        public final String id;
        public final String name;
        public final String filePath;
        public final String uploadedBy;
        public final long createdAt;

        public SchematicInfo(String id, String name, String filePath, String uploadedBy, long createdAt) {
            this.id = id;
            this.name = name;
            this.filePath = filePath;
            this.uploadedBy = uploadedBy;
            this.createdAt = createdAt;
        }
    }
}