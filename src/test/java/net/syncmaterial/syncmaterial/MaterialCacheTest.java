package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.internal.MaterialCache;

/**
 * 材料缓存测试：命中/失效（时间戳+大小）/请求合并/失败清理。
 */
public class MaterialCacheTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static List<MaterialEntry> materials(int marker) {
        return List.of(new MaterialEntry(0, new ItemStack(Items.STONE), marker));
    }

    private Path writeFile(String content) throws Exception {
        Path file = tempDir.resolve("cache.litematic");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void secondCall_hitsCache_withoutRecompute() throws Exception {
        Path file = writeFile("v1");
        MaterialCache cache = new MaterialCache();
        AtomicInteger calls = new AtomicInteger();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> CompletableFuture.completedFuture(materials(calls.incrementAndGet()));

        var first = cache.getOrCompute(file.toString(), computer).join();
        var second = cache.getOrCompute(file.toString(), computer).join();

        assertEquals(1, calls.get(), "第二次应命中缓存不再解析");
        assertSame(first, second, "命中缓存应返回同一实例");
    }

    @Test
    void fileModified_invalidatesCache() throws Exception {
        Path file = writeFile("v1");
        MaterialCache cache = new MaterialCache();
        AtomicInteger calls = new AtomicInteger();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> CompletableFuture.completedFuture(materials(calls.incrementAndGet()));

        cache.getOrCompute(file.toString(), computer).join();

        // 文件变化（内容变 → 大小/时间戳变）后应重新解析
        Files.writeString(file, "version-2-with-longer-content");
        var updated = cache.getOrCompute(file.toString(), computer).join();

        assertEquals(2, calls.get());
        assertEquals(2, updated.get(0).getCountTotal(), "应拿到重新解析的结果");
    }

    @Test
    void invalidate_forcesRecompute() throws Exception {
        Path file = writeFile("v1");
        MaterialCache cache = new MaterialCache();
        AtomicInteger calls = new AtomicInteger();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> CompletableFuture.completedFuture(materials(calls.incrementAndGet()));

        cache.getOrCompute(file.toString(), computer).join();
        cache.invalidate(file.toString());
        cache.getOrCompute(file.toString(), computer).join();

        assertEquals(2, calls.get(), "显式失效后应重新解析");
    }

    @Test
    void concurrentRequests_mergedIntoSingleComputation() throws Exception {
        Path file = writeFile("v1");
        MaterialCache cache = new MaterialCache();
        AtomicInteger calls = new AtomicInteger();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> CompletableFuture.supplyAsync(() -> {
                calls.incrementAndGet();
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                return materials(1);
            });

        var f1 = cache.getOrCompute(file.toString(), computer);
        var f2 = cache.getOrCompute(file.toString(), computer);

        assertEquals(1, calls.get(), "并发请求应合并为一次解析");
        assertSame(f1.join(), f2.join());
    }

    @Test
    void failedComputation_notCached() throws Exception {
        Path file = writeFile("v1");
        MaterialCache cache = new MaterialCache();
        AtomicInteger calls = new AtomicInteger();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new RuntimeException("解析失败"))
                : CompletableFuture.completedFuture(materials(2));

        assertThrows(java.util.concurrent.CompletionException.class,
            () -> cache.getOrCompute(file.toString(), computer).join(), "首次解析失败应向上传播");
        var result = cache.getOrCompute(file.toString(), computer).join();

        assertEquals(2, calls.get(), "失败结果不应进缓存，下次应重试");
        assertEquals(2, result.get(0).getCountTotal());
    }

    @Test
    void get_missingFile_returnsNull() {
        MaterialCache cache = new MaterialCache();
        assertNull(cache.get(tempDir.resolve("不存在.litematic").toString()));
    }
}
