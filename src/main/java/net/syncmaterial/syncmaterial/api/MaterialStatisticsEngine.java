package net.syncmaterial.syncmaterial.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 材料统计引擎的 API 接口。
 */
public interface MaterialStatisticsEngine {

    /**
     * 异步请求指定原理图文件的材料清单。
     * 
     * @param schematicPath 原理图文件的绝对路径。
     * @return 一个包含材料条目列表的 CompletableFuture。
     */
    CompletableFuture<List<MaterialEntry>> requestMaterialsAsync(String schematicPath);

    /**
     * 清除指定原理图的缓存。
     * 
     * @param schematicPath 原理图文件的绝对路径。
     */
    void invalidateCache(String schematicPath);

    /**
     * 清除所有统计结果缓存。
     */
    void clearAllCaches();
}
