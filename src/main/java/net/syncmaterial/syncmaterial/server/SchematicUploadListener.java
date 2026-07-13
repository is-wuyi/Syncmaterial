package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 原理图上传监听器
 * 监听Syncmatica原理图上传事件，自动解析并存储到数据库
 * 使用泛型避免直接依赖Syncmatica类
 */
public class SchematicUploadListener implements Consumer<Object> {
    private final SchematicDatabase database;
    private final DatabaseQueryService queryService;
    private final LitematicaParser parser;

    public SchematicUploadListener(SchematicDatabase database,
                                   DatabaseQueryService queryService,
                                   LitematicaParser parser,
                                   Object ignored) {
        this.database = database;
        this.queryService = queryService;
        this.parser = parser;
    }

    /**
     * 处理原理图上传事件
     * 这个方法会被Syncmatica的上传事件触发
     */
    public void onSchematicUploaded(Object placement) {
        try {
            // 使用反射获取ServerPlacement的方法
            Class<?> placementClass = placement.getClass();
            Object id = placementClass.getMethod("getId").invoke(placement);
            Path schematicFile = (Path) placementClass.getMethod("getFile").invoke(placement);
            String schematicId = id.toString();
            String schematicName = (String) placementClass.getMethod("getName").invoke(placement);

            // 获取所有者信息
            Object owner = placementClass.getMethod("getOwner").invoke(placement);
            String uploader = "unknown";
            if (owner != null) {
                uploader = (String) owner.getClass().getMethod("getName").invoke(owner);
            }

            SyncMaterial.LOGGER.info("检测到原理图上传: {} (文件: {}, 上传者: {})",
                                   schematicId, schematicFile, uploader);

            // 异步处理上传的原理图
            CompletableFuture.runAsync(() -> {
                try {
                    processSchematicUpload(placement);
                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理原理图上传失败: {}", schematicId, e);
                }
            });
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理ServerPlacement对象失败", e);
        }
    }

    /**
     * Consumer接口实现
     * 通过 SyncmaticManager.getPlacement() 判断 placement 是新增还是删除：
     * - 新增：placement 仍在管理器中 → 处理上传
     * - 删除：placement 已从管理器移除 → 清理渲染和数据库
     */
    @Override
    public void accept(Object placement) {
        // 仅处理新增/更新事件。原理图删除由服务器 SchematicFolderWatcher 通过
        // SCHEMATIC_DELETED 网络包通知客户端，不依赖此 consumer。
        onSchematicUploaded(placement);
    }



    /**
     * 实际的原理图处理逻辑
     */
    private void processSchematicUpload(Object placement) {
        try {
            // 使用反射获取必要信息
            Class<?> placementClass = placement.getClass();
            Object id = placementClass.getMethod("getId").invoke(placement);
            Path schematicFile = (Path) placementClass.getMethod("getFile").invoke(placement);
            String schematicId = id.toString();
            String schematicName = (String) placementClass.getMethod("getName").invoke(placement);

            // 1. 检查文件是否存在
            if (!Files.exists(schematicFile)) {
                SyncMaterial.LOGGER.error("原理图文件不存在: {}", schematicFile);
                return;
            }

            // 2. 检查是否已经处理过
            if (queryService.schematicExists(schematicId)) {
                SyncMaterial.LOGGER.info("原理图已存在于数据库中: {}", schematicId);
                return;
            }

            // 3. 解析原理图文件
            SyncMaterial.LOGGER.info("开始解析原理图: {}", schematicId);
            List<MaterialEntry> materials = parser.parseAsync(schematicFile.toString())
                .join(); // 等待解析完成

            // 4. 存储到数据库
            storeSchematicToDatabase(placement, schematicId, schematicName, schematicFile, materials);

            SyncMaterial.LOGGER.info("原理图处理完成: {} ({} 项材料)",
                                   schematicId, materials.size());

        } catch (Exception e) {
            SyncMaterial.LOGGER.error("原理图上传处理失败", e);
        }
    }

    /**
     * 将解析结果存储到数据库
     */
    private void storeSchematicToDatabase(Object placement, String schematicId, String schematicName, Path schematicFile, List<MaterialEntry> materials) throws Exception {
        try {
            SyncMaterial.LOGGER.info("开始存储原理图到数据库: {} ({})", schematicId, schematicName);

            // 使用反射获取所有者信息
            Class<?> placementClass = placement.getClass();
            Object owner = placementClass.getMethod("getOwner").invoke(placement);

            String uploader = "unknown";
            if (owner != null) {
                uploader = (String) owner.getClass().getMethod("getName").invoke(owner);
            }

            // 1. 插入原理图基本信息
            database.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                schematicId,
                schematicName,
                schematicFile.toString(),
                uploader
            );
            SyncMaterial.LOGGER.info("插入原理图基本信息成功: {}", schematicId);

            // 2. 批量插入材料条目
            insertMaterialEntries(schematicId, materials);

            SyncMaterial.LOGGER.info("数据库存储完成: 原理图={}, 材料条目={}",
                                      schematicId, materials.size());
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("存储到数据库失败", e);
            throw e;
        }
    }

    /**
     * 批量插入材料条目
     */
    private void insertMaterialEntries(String schematicId, List<MaterialEntry> materials) throws Exception {
        // 使用事务确保数据一致性
        database.beginTransaction();

        try {
            for (MaterialEntry entry : materials) {
                // 从ItemStack获取物品ID
                String itemId = net.minecraft.registry.Registries.ITEM.getId(entry.getStack().getItem()).toString();
                long countLong = entry.getCountTotal();
                int count = (int) Math.min(countLong, Integer.MAX_VALUE); // 防止溢出

                database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, itemId, count
                );
            }

            database.commitTransaction();
        } catch (Exception e) {
            database.rollbackTransaction();
            throw e;
        }
    }

}
