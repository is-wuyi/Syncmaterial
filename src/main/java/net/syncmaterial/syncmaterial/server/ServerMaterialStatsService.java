package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 服务端材料统计服务
 * 负责执行原理图的材料统计计算
 */
public class ServerMaterialStatsService {
    private static final Logger LOGGER = SyncMaterial.LOGGER;

    private final ServerSchematicManager schematicManager;

    public ServerMaterialStatsService(ServerSchematicManager schematicManager) {
        this.schematicManager = schematicManager;
    }

    /**
     * 异步计算指定原理图的材料统计
     */
    public CompletableFuture<List<MaterialEntry>> calculateStatsAsync(String schematicId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("开始计算原理图 {} 的材料统计", schematicId);

                // 1. 获取原理图文件路径
                Path schematicPath = schematicManager.getSchematicPath(schematicId);
                if (schematicPath == null) {
                    LOGGER.warn("原理图 {} 不存在", schematicId);
                    return List.of();
                }

                // 2. 执行材料统计
                // TODO: 这里需要实现实际的解析逻辑
                // 暂时返回空列表作为占位符
                List<MaterialEntry> materials = calculateStats(schematicPath);

                LOGGER.info("原理图 {} 统计完成，共 {} 项材料", schematicId, materials.size());
                return materials;

            } catch (Exception e) {
                LOGGER.error("计算原理图 {} 材料统计失败", schematicId, e);
                return List.of();
            }
        });
    }

    /**
     * 同步计算材料统计
     * 这里会调用我们之前实现的解析引擎
     */
    private List<MaterialEntry> calculateStats(Path schematicPath) {
        try {
            // TODO: 调用DefaultLitematicaParser来解析原理图
            // 暂时返回空列表

            // 示例调用（需要调整接口）：
            // return parser.parseAsync(schematicPath.toString())
            //     .join(); // 同步等待结果

            return List.of();
        } catch (Exception e) {
            LOGGER.error("解析原理图失败: {}", schematicPath, e);
            return List.of();
        }
    }
}