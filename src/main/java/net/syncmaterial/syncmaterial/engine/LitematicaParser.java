//? if >=26 {
package net.syncmaterial.syncmaterial.engine;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

/**
 * Litematica 解析器的内部接口。
 */
public interface LitematicaParser {

    /**
     * 异步解析指定的 .litematic 文件。
     * 
     * @param schematicPath 原理图文件的绝对路径。
     * @return 一个包含材料条目列表的 CompletableFuture。
     */
    CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath);

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
//?} else {
package net.syncmaterial.syncmaterial.engine;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

/**
 * Litematica 解析器的内部接口。
 */
public interface LitematicaParser {

    /**
     * 异步解析指定的 .litematic 文件。
     * 
     * @param schematicPath 原理图文件的绝对路径。
     * @return 一个包含材料条目列表的 CompletableFuture。
     */
    CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath);

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
        Map<BlockPos, NbtCompound> getGlobalTileEntityMap();
    }
}
//?}
