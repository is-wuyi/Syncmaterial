package net.syncmaterial.syncmaterial.engine.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * 线程池真实排队/执行/取消/拒绝测试。
 * 全部使用 CountDownLatch 手动控制，不依赖 sleep，也不依赖机器速度。
 */
class ParsingThreadPoolTest {

    private static ParsingThreadPool newPool(int threads, int queueCapacity) {
        return new ParsingThreadPool(threads, queueCapacity);
    }

    private static void closeAndAwait(ParsingThreadPool pool) {
        pool.close();
        try {
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "关闭后线程池应在预算内终止");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待线程池终止时被中断", e);
        }
    }

    @Test
    void submit_executesTaskAndCompletesFuture() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        try {
            CountDownLatch executed = new CountDownLatch(1);
            CompletableFuture<String> future = pool.submit(() -> {
                executed.countDown();
                return "done";
            });

            assertTrue(executed.await(5, TimeUnit.SECONDS), "任务应真正执行");
            assertEquals("done", future.get(5, TimeUnit.SECONDS));
        } finally {
            closeAndAwait(pool);
        }
    }

    @Test
    void getExecutor_remainsUsable() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        try {
            CountDownLatch executed = new CountDownLatch(1);
            pool.getExecutor().execute(executed::countDown);
            assertTrue(executed.await(5, TimeUnit.SECONDS), "getExecutor() 仍应能提交普通任务");
        } finally {
            closeAndAwait(pool);
        }
    }

    @Test
    void queueFull_rejectsWithFailedFuture() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = pool.submit(() -> {
                running.countDown();
                release.await();
                return "first";
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "第一个任务应占住唯一工作线程");

            CompletableFuture<String> queued = pool.submit(() -> "queued");
            CompletableFuture<String> rejected = pool.submit(() -> "rejected");

            assertTrue(rejected.isCompletedExceptionally(), "队列满时应立即完成失败 future");
            ExecutionException error = assertThrows(ExecutionException.class,
                () -> rejected.get(1, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("queued", queued.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void close_cancelsQueuedFutureAndInterruptsRunningTask() throws Exception {
        ParsingThreadPool pool = newPool(1, 2);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> runningTask = pool.submit(() -> {
                running.countDown();
                try {
                    release.await();
                    return "running";
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "第一个任务应已开始");

            CompletableFuture<String> queuedTask = pool.submit(() -> "queued");
            pool.close();

            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "close 必须中断正在执行的任务");
            assertTrue(runningTask.isCancelled(), "运行中任务的 future 必须完成");
            assertTrue(queuedTask.isCancelled(), "排队任务的 future 不能永远挂起");
            assertThrows(CancellationException.class, () -> runningTask.get(1, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> queuedTask.get(1, TimeUnit.SECONDS));
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void workerCannotStartQueuedTasksWhileCloseCompletesCallbacks() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            CompletableFuture<String> first = pool.submit(() -> {
                running.countDown();
                try {
                    release.await();
                    return "first";
                } catch (InterruptedException error) {
                    interrupted.countDown();
                    throw error;
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            CompletableFuture<String> queued = pool.submit(() -> {
                queuedRan.set(true);
                return "queued";
            });
            first.whenComplete((value, error) -> {
                try {
                    assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interruptedWait) {
                    Thread.currentThread().interrupt();
                    fail(interruptedWait);
                }
            });
            pool.close();
            assertTrue(first.isCancelled() || first.isCompletedExceptionally());
            assertTrue(queued.isCancelled());
            assertFalse(queuedRan.get(), "close 封住准入后工作线程不得抢先执行排队任务");
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void futureCancel_interruptsRunningTask() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = pool.submit(() -> {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                    return "unreachable";
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "任务应已开始");

            assertTrue(future.cancel(true), "取消应成功");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "取消 future 必须真正中断任务");
            assertTrue(future.isCancelled());
            assertThrows(CancellationException.class, () -> future.get(1, TimeUnit.SECONDS));
        } finally {
            closeAndAwait(pool);
        }
    }

    @Test
    void cancelQueuedFuture_freesQueueCapacity() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            CompletableFuture<String> first = pool.submit(() -> {
                running.countDown();
                release.await();
                return "first";
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "第一个任务应占住唯一工作线程");

            CompletableFuture<String> queued = pool.submit(() -> {
                queuedRan.set(true);
                return "queued";
            });
            // 队列容量为 1，此时已满；取消必须把任务从执行器队列移除。
            assertTrue(queued.cancel(true), "排队任务应可取消");

            CompletableFuture<String> replacement = pool.submit(() -> "replacement");
            assertFalse(replacement.isCompletedExceptionally(), "取消排队任务必须释放有界队列容量");

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("replacement", replacement.get(5, TimeUnit.SECONDS));
            assertTrue(queued.isCancelled());
            assertFalse(queuedRan.get(), "已取消的排队任务不得执行");
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void cancellingCompletedTask_doesNotInterruptNextTask() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch nextRunning = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        CountDownLatch nextInterrupted = new CountDownLatch(1);
        try {
            CompletableFuture<String> completed = pool.submit(() -> "completed");
            assertEquals("completed", completed.get(5, TimeUnit.SECONDS));

            CompletableFuture<String> next = pool.submit(() -> {
                nextRunning.countDown();
                try {
                    releaseNext.await();
                    return "next";
                } catch (InterruptedException e) {
                    nextInterrupted.countDown();
                    throw e;
                }
            });
            assertTrue(nextRunning.await(5, TimeUnit.SECONDS), "后续任务应已在同一工作线程运行");

            assertFalse(completed.cancel(true), "已完成的任务不能被再次取消");
            releaseNext.countDown();
            assertEquals("next", next.get(5, TimeUnit.SECONDS));
            assertEquals(1, nextInterrupted.getCount(), "取消已完成任务不得中断后续任务");
        } finally {
            releaseNext.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void cancelBetweenAdmissionAndStart_neverRunsCallable() throws Exception {
        CountDownLatch atStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean taskRan = new AtomicBoolean();
        ParsingThreadPool pool = new ParsingThreadPool(1, 1, () -> {
            atStart.countDown();
            try {
                releaseStart.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            CompletableFuture<String> future = pool.submit(() -> {
                taskRan.set(true);
                return "ran";
            });
            assertTrue(atStart.await(5, TimeUnit.SECONDS), "任务应已准入并停在实际开始之前");

            assertTrue(future.cancel(true), "启动阻塞期间取消应成功");
            releaseStart.countDown();
            assertTrue(future.isCancelled(), "取消必须映射到配对 future");
            assertThrows(CancellationException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertFalse(taskRan.get(), "取消后 callable 绝不能执行");
        } finally {
            releaseStart.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void closeWhileStartBlocked_completesFutureWithoutRunningCallable() throws Exception {
        CountDownLatch atStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean taskRan = new AtomicBoolean();
        ParsingThreadPool pool = new ParsingThreadPool(1, 1, () -> {
            atStart.countDown();
            try {
                releaseStart.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            CompletableFuture<String> future = pool.submit(() -> {
                taskRan.set(true);
                return "ran";
            });
            assertTrue(atStart.await(5, TimeUnit.SECONDS), "任务应已准入并停在实际开始之前");

            pool.close();
            releaseStart.countDown();
            assertTrue(future.isDone(), "关闭竞态下的 future 必须终结");
            assertTrue(future.isCancelled() || future.isCompletedExceptionally(),
                "关闭时尚未启动的任务应以取消完成");
            assertFalse(taskRan.get(), "已被关闭取消的任务不能执行 callable");
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseStart.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void cancelQueuedFuture_preventsExecution() throws Exception {
        ParsingThreadPool pool = newPool(1, 2);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            CompletableFuture<String> first = pool.submit(() -> {
                running.countDown();
                release.await();
                return "first";
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "第一个任务应已开始");

            CompletableFuture<String> queued = pool.submit(() -> {
                queuedRan.set(true);
                return "queued";
            });
            CompletableFuture<String> sentinel = pool.submit(() -> "sentinel");

            assertTrue(queued.cancel(false), "排队中的 future 应可取消");
            release.countDown();

            // FIFO 队列保证 sentinel 完成时，排在前面的 queued 任务已经被处理过。
            assertEquals("sentinel", sentinel.get(5, TimeUnit.SECONDS));
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertTrue(queued.isCancelled());
            assertFalse(queuedRan.get(), "已取消的排队任务不得执行");
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }

    @Test
    void close_isIdempotentAndRejectsNewWork() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        pool.close();
        pool.close();

        CompletableFuture<String> rejected = pool.submit(() -> "must-not-run");
        assertTrue(rejected.isCompletedExceptionally(), "关闭后提交应立即失败");
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> rejected.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, error.getCause());

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        closeAndAwait(pool);
    }

    @Test
    void shutdown_isBoundedAndRejectsLaterWork() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch executed = new CountDownLatch(1);
        CompletableFuture<String> task = pool.submit(() -> {
            executed.countDown();
            return "done";
        });
        assertTrue(executed.await(5, TimeUnit.SECONDS));
        assertEquals("done", task.get(5, TimeUnit.SECONDS));

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "旧 shutdown 入口也必须有界结束");

        CompletableFuture<String> rejected = pool.submit(() -> "must-not-run");
        assertTrue(rejected.isCompletedExceptionally(), "shutdown 后提交应立即失败");
        closeAndAwait(pool);
    }

    @Test
    void awaitTermination_isBoundedByCallerBudget() throws Exception {
        ParsingThreadPool pool = newPool(1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> task = pool.submit(() -> {
                running.countDown();
                // 故意忽略中断，模拟不配合的第三方解析代码。
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // 继续等待手动释放
                    }
                }
                return "finished";
            });
            assertTrue(running.await(5, TimeUnit.SECONDS), "任务应已开始");

            pool.close();
            assertTrue(task.isCancelled(), "关闭时 future 必须先完成，不能被死循环拖住");
            assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS),
                "不响应中断的任务应让 awaitTermination 在调用方预算处返回 false");

            release.countDown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            closeAndAwait(pool);
        }
    }
}
