//? if >=26 {
package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;

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

        try (var qr = database.executeQuery(
                "SELECT id, item_id, count FROM material_entries WHERE schematic_id = ?",
                schematicId
        )) {
            while (qr.next()) {
                int dbId = qr.getInt("id");
                String itemId = qr.getString("item_id");
                int count = qr.getInt("count");

                if (itemId == null || itemId.isEmpty()) {
                    continue;
                }

                var itemRegistry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
                var identifier = net.minecraft.resources.Identifier.parse(itemId);
                // 26.2 的 Registry.get 返回 Optional<Holder.Reference>，与旧版直接返回 Item 不同
                var itemRef = itemRegistry.get(identifier);

                if (itemRef.isPresent() && itemRef.get().value() != net.minecraft.world.item.Items.AIR) {
                    var stack = new net.minecraft.world.item.ItemStack(itemRef.get(), count);
                    materials.add(new MaterialEntry(dbId, stack, count));
                } else {
                    SyncMaterial.LOGGER.warn("未找到物品: {}", itemId);
                }
            }
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询材料统计失败: {}", schematicId, e);
            return List.of();
        }

        return materials;
    }

    /**
     * 检查原理图是否存在于数据库中
     */
    public boolean schematicExists(String schematicId) {
        try (var qr = database.executeQuery(
                "SELECT COUNT(*) as count FROM schematics WHERE id = ?",
                schematicId
        )) {
            return qr.next() && qr.getInt("count") > 0;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("检查原理图存在性失败: {}", schematicId, e);
            return false;
        }
    }

}
//?} else {
package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;

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

        try (var qr = database.executeQuery(
                "SELECT id, item_id, count FROM material_entries WHERE schematic_id = ?",
                schematicId
        )) {
            while (qr.next()) {
                int dbId = qr.getInt("id");
                String itemId = qr.getString("item_id");
                int count = qr.getInt("count");

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
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("查询材料统计失败: {}", schematicId, e);
            return List.of();
        }

        return materials;
    }

    /**
     * 检查原理图是否存在于数据库中
     */
    public boolean schematicExists(String schematicId) {
        try (var qr = database.executeQuery(
                "SELECT COUNT(*) as count FROM schematics WHERE id = ?",
                schematicId
        )) {
            return qr.next() && qr.getInt("count") > 0;
        } catch (SQLException e) {
            SyncMaterial.LOGGER.error("检查原理图存在性失败: {}", schematicId, e);
            return false;
        }
    }

}
//?}
