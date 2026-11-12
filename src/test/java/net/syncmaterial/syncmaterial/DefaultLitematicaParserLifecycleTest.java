package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;

/**
 * DefaultLitematicaParser 的资源生命周期测试。
 * 使用真实线程池和闩锁手动控制，验证关闭/拒绝/排队 future 不会悬挂。
 */
class DefaultLitematicaParserLifecycleTest {

    @Test
    void close_rejectsNewParsesAndIsIdempotent() throws Exception {
        DefaultLitematicaParser parser = new DefaultLitematicaParser(new ParsingThreadPool(1, 1));
        parser.close();
        parser.close();

        CompletableFuture<List<MaterialEntry>> rejected = parser.parseAsync("missing.litematic");
        assertTrue(rejected.isCompletedExceptionally(), "关闭后 parseAsync 应返回失败 future");
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> rejected.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
        assertTrue(parser.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void close_completesQueuedParseFutureWithoutRunningIt() throws Exception {
        ParsingThreadPool pool = new ParsingThreadPool(1, 2);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        // 占住唯一工作线程，确保随后的解析只能排队，不会真正读取文件。
        Future<?> blocker = pool.getExecutor().submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        DefaultLitematicaParser parser = new DefaultLitematicaParser(pool);
        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS), "阻塞任务应已开始");

            CompletableFuture<List<MaterialEntry>> queued = parser.parseAsync("queued-does-not-exist.litematic");
            assertFalse(queued.isDone(), "解析任务应仍在队列中");

            parser.close();
            assertTrue(queued.isCancelled(), "关闭解析器必须完成排队中的 future");
            assertThrows(CancellationException.class, () -> queued.get(1, TimeUnit.SECONDS));
            assertTrue(parser.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseBlocker.countDown();
            parser.close();
            assertTrue(parser.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
