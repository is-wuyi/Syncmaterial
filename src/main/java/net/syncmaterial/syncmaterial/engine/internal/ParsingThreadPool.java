package net.syncmaterial.syncmaterial.engine.internal;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 专门用于原理图解析任务的有界线程池。
 * 使用 CPU 核心数 - 1 作为最大线程数，并使用有界队列防止内存溢出。
 *
 * <p>{@link #submit(Callable)} 使用 JDK {@link FutureTask} 的成熟状态机处理
 * “取消/开始”交接，再由配对的 {@link CompletableFuture} 暴露异步结果：
 * 取消 future 会取消真正的任务、中断正在执行的线程，并从有界队列移除尚未开始的任务；
 * 任务完成时才把终止状态映射到 future，避免 cancellation 竞态和误中断后续任务。</p>
 *
 * <p>{@link #close()} 只做封闭与取消，不等待；等待由调用方通过
 * {@link #awaitTermination(long, TimeUnit)} 使用自己的总预算完成。</p>
 */
public class ParsingThreadPool implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParsingThreadPool.class);

    /** 旧 shutdown() 的兼容等待上限，绝不再使用原来的 60 秒。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final ThreadPoolExecutor executor;
    private final Set<TrackedTask<?>> trackedTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable startProbe;

    public ParsingThreadPool() {
        this(defaultMaxThreads(), 16, null);
    }

    /**
     * 显式指定线程数与队列容量；生产默认值见无参构造器，测试可用小容量做确定性排队/拒绝验证。
     */
    public ParsingThreadPool(int maxThreads, int queueCapacity) {
        this(maxThreads, queueCapacity, null);
    }

    /**
     * 仅供同包测试注入：在 FutureTask 开始执行之前、工作线程内调用。
     * 用于确定性地覆盖“已准入、尚未开始”的取消/关闭竞态。
     */
    ParsingThreadPool(int maxThreads, int queueCapacity, Runnable startProbe) {
        if (maxThreads < 1) {
            throw new IllegalArgumentException("maxThreads must be >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1");
        }
        this.startProbe = startProbe;

        // 使用有界队列，防止大量解析请求堆积撑爆内存
        this.executor = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ParsingThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy() // 队列满时抛出异常，由调用方处理（如返回失败的 Future）
        );

        LOGGER.info("Initialized ParsingThreadPool with {} threads and queue capacity {}", maxThreads, queueCapacity);
    }

    private static int defaultMaxThreads() {
        // 获取可用核心数，但限制最大线程数，避免在 Minecraft 启动时创建太多线程影响性能
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(cores / 2, 4)); // 最多4个线程，避免影响游戏性能
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * 提交一个可取消、可等待的解析任务。
     *
     * <p>池已关闭或队列已满时返回已失败的 future，而不是让调用方永久等待。
     * 返回的 future 与真实任务绑定：{@code future.cancel(true)} 会中断执行线程，
     * 尚未开始的任务会从有界队列移除并释放容量。</p>
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Parsing thread pool is closed"));
        }

        TrackedTask<T> tracked = new TrackedTask<>(task);
        trackedTasks.add(tracked);

        // close() 可能在上面的检查之后、任务入队之前发生。第二次检查保证这类
        // 竞态不会留下一个永远无人完成的 future。
        if (closed.get()) {
            tracked.failAdmission(new RejectedExecutionException("Parsing thread pool is closed"));
            return tracked.future;
        }

        try {
            executor.execute(tracked);
        } catch (RejectedExecutionException e) {
            tracked.failAdmission(e);
        }
        return tracked.future;
    }

    /**
     * 封闭入口、取消全部排队/执行中的任务并立即 shutdownNow。非阻塞。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        List<TrackedTask<?>> tasks = List.copyOf(trackedTasks);
        for (TrackedTask<?> task : tasks) task.future.cancelFromTask();
        for (TrackedTask<?> task : tasks) task.cancelTask(true);
        LOGGER.info("ParsingThreadPool closed; queued and running tasks cancelled");
    }

    /**
     * 在调用方给定的预算内等待执行器终止。
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * 兼容旧调用点的有界关闭：先停止接收新任务，允许已提交任务自然结束；
     * 超过 {@value #SHUTDOWN_TIMEOUT_SECONDS} 秒仍未结束则取消并 shutdownNow。
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("ParsingThreadPool did not terminate within {} seconds; cancelling", SHUTDOWN_TIMEOUT_SECONDS);
                close();
            }
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * FutureTask 负责 start/cancel 的原子交接；配对的 CompletableFuture 负责调用方 API。
     * done() 只在任务进入终态后执行，因此映射结果不会阻塞或递归。
     */
    private final class TrackedTask<T> extends FutureTask<T> {
        private final TrackedFuture future = new TrackedFuture();

        private TrackedTask(Callable<T> callable) {
            super(callable);
        }

        @Override
        public void run() {
            if (closed.get()) {
                cancelTask(false);
                return;
            }
            Runnable probe = startProbe;
            if (probe != null) probe.run();
            if (closed.get()) cancelTask(false);
            else super.run();
        }

        @Override
        protected void done() {
            trackedTasks.remove(this);
            if (future.isDone()) {
                return;
            }
            if (isCancelled() || closed.get()) {
                future.cancelFromTask();
                return;
            }
            try {
                future.complete(get());
            } catch (CancellationException e) {
                future.cancelFromTask();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                future.completeExceptionally(cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        }

        /**
         * 取消真实任务；FutureTask.cancel 负责与 run() 竞争，绝不会放过已开始的取消。
         * 若任务仍在有界队列中，remove 立即释放槽位，避免排队取消继续挤占容量。
         */
        private boolean cancelTask(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                trackedTasks.remove(this);
                executor.remove(this);
            }
            return cancelled;
        }

        /**
         * 未真正进入执行器（关闭竞态或队列拒绝）时，直接以拒绝原因完成 future。
         */
        private void failAdmission(Throwable error) {
            trackedTasks.remove(this);
            future.completeExceptionally(error);
            super.cancel(false);
        }

        private final class TrackedFuture extends CompletableFuture<T> {
            /**
             * 绕过本类的 cancel 覆盖，供 FutureTask.done() 映射取消终态；不会递归。
             */
            private void cancelFromTask() {
                super.cancel(false);
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(false);
                if (cancelled) {
                    cancelTask(mayInterruptIfRunning);
                }
                return cancelled;
            }
        }
    }

    private static class ParsingThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "material-parser-thread-";

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            // 解析线程是后台工作线程：即使某个任务长时间不响应中断，也不能让
            // 测试/客户端 JVM 因线程池残留而无法退出。
            t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
