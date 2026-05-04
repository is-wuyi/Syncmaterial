package net.syncmaterial.syncmaterial.server;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.SyncMaterial;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端统计结果缓存
 * 使用LRU策略管理内存使用
 */
public class ServerStatsCache {
    private static final Logger LOGGER = SyncMaterial.LOGGER;

    // 缓存结构：原理图ID -> 统计结果
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // 缓存配置
    private static final long DEFAULT_EXPIRY_TIME = 30 * 60 * 1000; // 30分钟
    private static final int MAX_CACHE_SIZE = 100; // 最大缓存100个结果

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final List<MaterialEntry> materials;
        final long timestamp;
        final long fileSize;
        final long fileModified;

        CacheEntry(List<MaterialEntry> materials, long fileSize, long fileModified) {
            this.materials = materials;
            this.timestamp = System.currentTimeMillis();
            this.fileSize = fileSize;
            this.fileModified = fileModified;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > DEFAULT_EXPIRY_TIME;
        }

        boolean isValid(long currentFileSize, long currentFileModified) {
            return fileSize == currentFileSize && fileModified == currentFileModified;
        }
    }

    /**
     * 获取缓存的统计结果
     */
    public List<MaterialEntry> get(String schematicId, long fileSize, long fileModified) {
        CacheEntry entry = cache.get(schematicId);
        if (entry != null && !entry.isExpired() && entry.isValid(fileSize, fileModified)) {
            LOGGER.debug("缓存命中: {}", schematicId);
            return entry.materials;
        }

        // 缓存失效或不存在
        if (entry != null) {
            cache.remove(schematicId);
            LOGGER.debug("缓存失效，移除: {}", schematicId);
        }

        return null;
    }

    /**
     * 存储统计结果到缓存
     */
    public void put(String schematicId, List<MaterialEntry> materials, long fileSize, long fileModified) {
        // 检查缓存大小，如果超过限制，清理最旧的条目
        if (cache.size() >= MAX_CACHE_SIZE) {
            cleanupExpiredEntries();
            if (cache.size() >= MAX_CACHE_SIZE) {
                // 如果清理后仍然超限，移除最旧的条目
                removeOldestEntry();
            }
        }

        cache.put(schematicId, new CacheEntry(materials, fileSize, fileModified));
        LOGGER.debug("缓存存储: {} ({} 项材料)", schematicId, materials.size());
    }

    /**
     * 清理过期的缓存条目
     */
    private void cleanupExpiredEntries() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 移除最旧的缓存条目
     */
    private void removeOldestEntry() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldestTime = entry.getValue().timestamp;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            cache.remove(oldestKey);
            LOGGER.debug("移除最旧缓存条目: {}", oldestKey);
        }
    }

    /**
     * 清除指定原理图的缓存
     */
    public void invalidate(String schematicId) {
        cache.remove(schematicId);
        LOGGER.debug("清除缓存: {}", schematicId);
    }

    /**
     * 清除所有缓存
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        LOGGER.info("清除所有缓存，共 {} 个条目", size);
    }

    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        return String.format("缓存条目: %d, 最大容量: %d", cache.size(), MAX_CACHE_SIZE);
    }
}