package net.syncmaterial.syncmaterial.engine.internal;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 专门用于原理图解析任务的有界线程池。
 * 使用 CPU 核心数 - 1 作为最大线程数，并使用有界队列防止内存溢出。
 */
public class ParsingThreadPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParsingThreadPool.class);

    private final ExecutorService executor;
    private final int maxThreads;
    private final int queueCapacity = 16;

    public ParsingThreadPool() {
        // 获取可用核心数，但限制最大线程数，避免在Minecraft启动时创建太多线程影响性能
        int cores = Runtime.getRuntime().availableProcessors();
        this.maxThreads = Math.max(1, Math.min(cores / 2, 4)); // 最多4个线程，避免影响游戏性能

        // 使用有界队列，防止大量解析请求堆积撑爆内存
        this.executor = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ParsingThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy() // 队列满时抛出异常，由调用方处理（如返回失败的 Future）
        );

        LOGGER.info("Initialized ParsingThreadPool with {} threads and queue capacity {} (cores: {})", maxThreads, queueCapacity, cores);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class ParsingThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "material-parser-thread-";

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            if (t.isDaemon()) t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
