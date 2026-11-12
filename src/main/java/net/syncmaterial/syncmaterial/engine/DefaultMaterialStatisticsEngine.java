package net.syncmaterial.syncmaterial.engine;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.api.MaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.MaterialCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 材料统计引擎的实现类。
 * 串联了 Cache -> Parser 的完整流水线。
 *
 * <p>资源归属：默认构造器创建并拥有自己的解析器，{@link #close()} 会关闭它；
 * 注入构造器只借用调用方的解析器，绝不替调用方关闭。服务器上下文应在自己的
 * 关闭序列里对借出的解析器恰好关闭一次。</p>
 */
public class DefaultMaterialStatisticsEngine implements MaterialStatisticsEngine, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("SyncMaterial-Engine");

    private final MaterialCache cache;
    private final LitematicaParser parser;
    private final boolean ownsParser;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<List<MaterialEntry>>> inFlight = ConcurrentHashMap.newKeySet();

    public DefaultMaterialStatisticsEngine() {
        this(new DefaultLitematicaParser(), true);
    }

    /**
     * 使用调用方提供的解析器。引擎不拥有该解析器，关闭引擎不会关闭它。
     */
    public DefaultMaterialStatisticsEngine(LitematicaParser parser) {
        this(parser, false);
    }

    DefaultMaterialStatisticsEngine(LitematicaParser parser, boolean ownsParser) {
        this.cache = new MaterialCache();
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ownsParser = ownsParser;
    }

    @Override
    public CompletableFuture<List<MaterialEntry>> requestMaterialsAsync(String schematicPath) {
        Objects.requireNonNull(schematicPath, "schematicPath");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Material statistics engine is closed"));
        }

        // 使用 MaterialCache 的 getOrCompute 实现：请求合并 + 缓存校验 + 异步计算流水线
        return cache.getOrCompute(schematicPath, path -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Material statistics engine is closed"));
            }
            LOGGER.info("开始流水线解析任务: {}", path);

            // 解析器已完成解析和统计；这里只保留流水线日志语义。
            // 转发 future 属于本引擎，关闭时需要取消它；借用的解析器任务继续由 owner 负责。
            CompletableFuture<List<MaterialEntry>> forwarding = parser.parseAsync(path).thenApply(materials -> {
                LOGGER.info("材料统计完成: {} 项材料", materials.size());
                return materials;
            });
            inFlight.add(forwarding);
            if (closed.get()) {
                // close() 可能在本任务登记前已经遍历过 inFlight，补一次取消。
                forwarding.cancel(true);
            }
            forwarding.whenComplete((materials, error) -> inFlight.remove(forwarding));
            return forwarding;
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
     * 封闭引擎、取消本引擎的转发 future 并封闭缓存；仅当解析器由本引擎创建时才关闭它。
     * 借用解析器的在途任务不会在这里被强行终止，但迟到的结果不会重新进入已关闭的缓存。
     * 幂等、非阻塞。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CompletableFuture<List<MaterialEntry>> future : List.copyOf(inFlight)) {
            future.cancel(true);
        }
        inFlight.clear();
        cache.close();
        if (ownsParser) {
            parser.close();
        }
        LOGGER.info("材料统计引擎已关闭");
    }

    /**
     * 兼容旧调用点的关闭入口，与 {@link #close()} 完全等价。
     */
    public void shutdown() {
        close();
    }
}
