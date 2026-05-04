package net.syncmaterial.syncmaterial.engine.internal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

/**
 * 具有请求合并、双重校验（时间戳+大小）和失败清理机制的材料缓存。
 */
public class MaterialCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialCache.class);

    private record CacheEntry(
            java.util.List<MaterialEntry> materials,
            long lastModified,
            long fileSize
    ) {}

    private record PendingTask(CompletableFuture<java.util.List<MaterialEntry>> future) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 尝试从缓存中获取结果。如果不存在或失效，则返回 null。
     */
    public java.util.List<MaterialEntry> get(String path) {
        File file = new File(path);
        if (!file.exists()) return null;

        CacheEntry entry = cache.get(path);
        if (entry != null && isStillValid(file, entry)) {
            return entry.materials();
        } else {
            // 如果失效，从缓存移除
            cache.remove(path);
            return null;
        }
    }

    /**
     * 检查文件是否仍然有效（时间戳和大小）。
     */
    private boolean isStillValid(File file, CacheEntry entry) {
        return file.lastModified() == entry.lastModified() && 
               file.length() == entry.fileSize();
    }

    /**
     * 获取或创建一个解析任务。支持请求合并（Deduplication）。
     */
    public CompletableFuture<java.util.List<MaterialEntry>> getOrCompute(String path, java.util.function.Function<String, CompletableFuture<java.util.List<MaterialEntry>>> computer) {
        // 1. 先检查缓存
        java.util.List<MaterialEntry> cached = get(path);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        // 2. 使用锁来保证请求合并的原子性
        lock.lock();
        try {
            // 双重检查，防止在等待锁的过程中缓存已被填充
            cached = get(path);
            if (cached != null) return CompletableFuture.completedFuture(cached);

            // 3. 检查是否有正在进行的相同任务（请求合并）
            PendingTask pending = pendingTasks.get(path);
            if (pending != null) {
                return pending.future();
            }

            // 4. 创建新任务
            CompletableFuture<java.util.List<MaterialEntry>> future = computer.apply(path);
            PendingTask newTask = new PendingTask(future);
            pendingTasks.put(path, newTask);

            // 5. 任务完成后处理：存入缓存或清理失败记录
            return future.whenComplete((result, throwable) -> {
                lock.lock();
                try {
                    pendingTasks.remove(path);
                    if (throwable == null && result != null) {
                        // 解析成功，写入缓存
                        File file = new File(path);
                        cache.put(path, new CacheEntry(result, file.lastModified(), file.length()));
                    } else {
                        // 解析失败，确保缓存中没有脏数据（虽然 get() 里有校验，但这里显式清理更稳健）
                        cache.remove(path);
                    }
                } finally {
                    lock.unlock();
                }
            });
        } finally {
            lock.unlock();
        }
    }

    public void invalidate(String path) {
        cache.remove(path);
    }

    public void clear() {
        cache.clear();
        pendingTasks.clear();
    }
}
