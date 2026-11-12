package net.syncmaterial.syncmaterial.engine;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

/**
 * Litematica 解析器的内部接口。
 *
 * <p>实现若持有线程池等资源，应实现 {@link #close()} 与 {@link #awaitTermination(long, TimeUnit)}，
 * 以便服务器生命周期关闭时先封闭入口再在统一预算内回收。默认实现不持有资源，
 * 使测试 mock 和纯内存实现无需额外样板。</p>
 */
public interface LitematicaParser extends AutoCloseable {

    /**
     * 异步解析指定的 .litematic 文件。
     * 
     * @param schematicPath 原理图文件的绝对路径。
     * @return 一个包含材料条目列表的 CompletableFuture。
     */
    CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath);

    /**
     * 封闭解析入口并取消未完成任务。实现必须非阻塞，实际等待交给
     * {@link #awaitTermination(long, TimeUnit)}。
     */
    @Override
    default void close() {
    }

    /**
     * 在给定预算内等待已提交任务结束。
     *
     * @return {@code true} 表示执行器已终止；超时返回 {@code false}。
     */
    default boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return true;
    }

    /**
     * 解析过程中产生的中间数据结果，用于后续进行 Statistics Pass（如处理数量型状态、多方块去重等）。
     */
    interface ParsingResult {
        /**
         * 获取全局坐标下的方块状态映射。
         * 这里的 Map 已经处理了区域偏移 (xOffset, yOffset, zOffset) 和重叠覆盖逻辑 (Last-one-wins)。
         */
        Map<BlockPos, BlockState> getGlobalBlockMap();

        /**
         * 获取全局坐标下的 Tile Entity NBT 映射。
         */
        Map<BlockPos, CompoundTag> getGlobalTileEntityMap();
    }
}
