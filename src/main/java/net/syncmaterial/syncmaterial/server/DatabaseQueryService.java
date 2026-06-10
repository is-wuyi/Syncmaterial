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

}