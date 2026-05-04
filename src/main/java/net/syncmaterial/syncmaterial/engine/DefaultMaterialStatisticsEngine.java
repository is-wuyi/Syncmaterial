package net.syncmaterial.syncmaterial.engine;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.api.MaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.StatisticsProcessor;
import net.syncmaterial.syncmaterial.engine.internal.MaterialCache;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 材料统计引擎的实现类。
 * 串联了 Cache -> Parser -> Processor 的完整流水线。
 */
public class DefaultMaterialStatisticsEngine implements MaterialStatisticsEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("SyncMaterial-Engine");

    private final MaterialCache cache;
    private final ParsingThreadPool threadPool;
    private final DefaultLitematicaParser parser;
    private final StatisticsProcessor processor;

    public DefaultMaterialStatisticsEngine() {
        this.threadPool = new ParsingThreadPool();
        this.cache = new MaterialCache();
        this.parser = new DefaultLitematicaParser(threadPool);
        this.processor = new StatisticsProcessor();
    }

    @Override
    public CompletableFuture<List<MaterialEntry>> requestMaterialsAsync(String schematicPath) {
        // 使用 MaterialCache 的 getOrCompute 实现：请求合并 + 缓存校验 + 异步计算流水线
        return cache.getOrCompute(schematicPath, path -> {
            LOGGER.info("开始流水线解析任务: {}", path);

            // 1. 调用 Parser 进行完整的解析和统计 (异步)
            return parser.parseAsync(path).thenApply(materials -> {
                LOGGER.info("材料统计完成: {} 项材料", materials.size());
                return materials;
            });
        });
    }

    @Override
    public void invalidateCache(String schematicPath) {
        cache.invalidate(schematicPath);
        LOGGER.info("已清除原理图缓存: {}", schematicPath);
    }

    @Override
    public void clearAllCaches() {
        cache.clear();
        LOGGER.info("已清空所有材料统计缓存");
    }

    /**
     * 释放引擎占用的资源（如线程池）。
     */
    public void shutdown() {
        threadPool.shutdown();
        LOGGER.info("材料统计引擎已关闭");
    }
}
