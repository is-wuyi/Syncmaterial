package net.syncmaterial.syncmaterial.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.MaterialCache;

/**
 * 引擎的资源归属测试：默认构造器拥有解析器，注入构造器只借用。
 */
class DefaultMaterialStatisticsEngineLifecycleTest {

    @TempDir
    Path tempDir;

    private static final class CountingParser implements LitematicaParser {
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger parseCount = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath) {
            parseCount.incrementAndGet();
            if (closed) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("parser closed"));
            }
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public void close() {
            closed = true;
            closeCount.incrementAndGet();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        int closeCount() {
            return closeCount.get();
        }

        boolean isClosed() {
            return closed;
        }
    }

    @Test
    void borrowedParser_isNeverClosedByEngine() {
        CountingParser parser = new CountingParser();
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine(parser);

        assertEquals(List.of(), engine.requestMaterialsAsync("borrowed").join());
        engine.close();
        engine.close();
        engine.shutdown();

        assertEquals(0, parser.closeCount(), "借用的解析器不能被引擎关闭");
        assertFalse(parser.isClosed(), "借用的解析器应保持可用");
        assertNotNull(parser.parseAsync("still-usable").join(), "借用方仍可继续使用解析器");
    }

    @Test
    void ownedParser_isClosedExactlyOnceAcrossCloseAndShutdown() {
        CountingParser parser = new CountingParser();
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine(parser, true);

        assertEquals(List.of(), engine.requestMaterialsAsync("owned").join());
        engine.close();
        engine.shutdown();
        engine.close();

        assertEquals(1, parser.closeCount(), "拥有者关闭必须恰好一次且幂等");
        assertTrue(parser.isClosed());
    }

    @Test
    void close_rejectsNewRequestsAndClearsPendingCacheEntry() {
        CountingParser parser = new CountingParser();
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine(parser);

        assertEquals(List.of(), engine.requestMaterialsAsync("path").join());
        engine.close();

        CompletableFuture<List<MaterialEntry>> rejected = engine.requestMaterialsAsync("path");
        assertTrue(rejected.isCompletedExceptionally(), "关闭后请求应快速失败");
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> rejected.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
    }

    @Test
    void close_cancelsInFlightForwardingWithoutClosingBorrowedParser() throws Exception {
        Path schematic = Files.writeString(tempDir.resolve("pending.litematic"), "not really parsed");
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        AtomicInteger closeCount = new AtomicInteger();
        LitematicaParser parser = new LitematicaParser() {
            @Override
            public CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath) {
                return pending;
            }

            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        };
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine(parser);

        CompletableFuture<List<MaterialEntry>> request = engine.requestMaterialsAsync(schematic.toString());
        assertFalse(request.isDone(), "解析未完成时请求应仍在进行");

        engine.close();
        assertTrue(request.isCancelled() || request.isCompletedExceptionally(),
            "引擎关闭必须终结自己的在途转发 future");
        assertEquals(0, closeCount.get(), "借用的解析器不能被引擎关闭");

        // 迟到的解析完成不能复活已关闭引擎的请求，也不能重新填充缓存。
        pending.complete(List.of());
        assertTrue(request.isCancelled() || request.isCompletedExceptionally(),
            "迟到结果不得重新完成已取消的引擎请求");
        assertEquals(0, closeCount.get());

        Field cacheField = DefaultMaterialStatisticsEngine.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        MaterialCache cache = (MaterialCache) cacheField.get(engine);
        assertNull(cache.get(schematic.toString()), "关闭后的迟到结果不得重新进入缓存");
    }

    @Test
    void defaultEngine_closeIsIdempotentAndRejectsNewRequests() {
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine();
        engine.close();
        engine.shutdown();

        CompletableFuture<List<MaterialEntry>> rejected = engine.requestMaterialsAsync("path");
        assertTrue(rejected.isCompletedExceptionally(), "关闭后请求应快速失败");
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> rejected.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
    }

    @Test
    void defaultEngine_ownsRealParserAndClosesItsThreadPool() throws Exception {
        DefaultMaterialStatisticsEngine engine = new DefaultMaterialStatisticsEngine();

        var parserField = DefaultMaterialStatisticsEngine.class.getDeclaredField("parser");
        parserField.setAccessible(true);
        LitematicaParser ownedParser = (LitematicaParser) parserField.get(engine);
        assertInstanceOf(DefaultLitematicaParser.class, ownedParser);

        engine.close();
        assertTrue(ownedParser.awaitTermination(5, TimeUnit.SECONDS), "默认引擎必须关闭自己的线程池");
        assertTrue(ownedParser.parseAsync("after-close").isCompletedExceptionally());
    }
}
