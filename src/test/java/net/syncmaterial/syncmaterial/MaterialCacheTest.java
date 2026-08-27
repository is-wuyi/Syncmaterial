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

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
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
        // 手动控制完成时机的 future：在两次请求之间解析任务必须仍处于进行中，
        // 不用 sleep 避免时序不稳定
        CompletableFuture<List<MaterialEntry>> pending = new CompletableFuture<>();
        Function<String, CompletableFuture<List<MaterialEntry>>> computer =
            path -> {
                calls.incrementAndGet();
                return pending;
            };

        var f1 = cache.getOrCompute(file.toString(), computer);
        var f2 = cache.getOrCompute(file.toString(), computer);

        assertEquals(1, calls.get(), "进行中的任务应被复用，不触发第二次解析");
        pending.complete(materials(1));
        assertEquals(1, f1.join().get(0).getCountTotal());
        assertEquals(1, f2.join().get(0).getCountTotal(), "两个请求应拿到同一份结果");
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
