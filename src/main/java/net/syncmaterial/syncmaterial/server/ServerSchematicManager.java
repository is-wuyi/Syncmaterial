package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.SyncMaterial;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端原理图管理器
 * 负责索引和管理服务端存储的共享原理图文件
 */
public class ServerSchematicManager {
    private static final Logger LOGGER = SyncMaterial.LOGGER;

    // 原理图ID到文件路径的映射
    private final Map<String, Path> schematicIndex = new HashMap<>();

    /**
     * 初始化原理图索引
     * 扫描Syncmatica的原理图存储目录
     */
    public void initializeIndex() {
        try {
            // TODO: 获取Syncmatica的原理图存储目录
            // 暂时使用占位符逻辑
            LOGGER.info("初始化服务端原理图索引...");

            // 这里应该扫描Syncmatica的schematics目录
            // 并建立原理图ID到文件路径的映射

            LOGGER.info("原理图索引初始化完成，共索引 {} 个原理图", schematicIndex.size());
        } catch (Exception e) {
            LOGGER.error("初始化原理图索引失败", e);
        }
    }

    /**
     * 根据原理图ID获取文件路径
     */
    public Path getSchematicPath(String schematicId) {
        return schematicIndex.get(schematicId);
    }

    /**
     * 检查原理图是否存在
     */
    public boolean schematicExists(String schematicId) {
        Path path = schematicIndex.get(schematicId);
        return path != null && Files.exists(path);
    }

    /**
     * 获取所有可用的原理图ID
     */
    public java.util.Set<String> getAvailableSchematics() {
        return schematicIndex.keySet();
    }

    /**
     * 重新构建索引
     */
    public void rebuildIndex() {
        schematicIndex.clear();
        initializeIndex();
    }
}