//? if >=26 {
package net.syncmaterial.syncmaterial.engine.impl;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DefaultLitematicaParser implements LitematicaParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLitematicaParser.class);

    private final ParsingThreadPool threadPool;

    public DefaultLitematicaParser(ParsingThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    @Override
    public CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performParse(schematicPath);
            } catch (Exception e) {
                LOGGER.error("Failed to parse Litematica schematic: {}", schematicPath, e);
                throw new RuntimeException("Schematic parsing failed", e);
            }
        }, threadPool.getExecutor());
    }

    private List<MaterialEntry> performParse(String schematicPath) throws Exception {
        File file = new File(schematicPath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Schematic file not found: " + schematicPath);
        }

        CompoundTag rootNbt = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());

        LitematicaParser.ParsingResult result = parseNbtToResult(rootNbt);

        StatisticsProcessor processor = new StatisticsProcessor();
        List<MaterialEntry> materials = processor.process(result, true);

        LOGGER.info("原理图解析完成: {} 个方块, {} 个TileEntity, 统计出 {} 项材料",
                   result.getGlobalBlockMap().size(), result.getGlobalTileEntityMap().size(), materials.size());

        return materials;
    }

    private LitematicaParser.ParsingResult parseNbtToResult(CompoundTag rootNbt) {
        Map<BlockPos, BlockState> globalBlockMap = new HashMap<>();
        Map<BlockPos, CompoundTag> globalTileEntityMap = new HashMap<>();

        LOGGER.info("Root NBT keys: {}", rootNbt.getKeys());

        if (!rootNbt.contains("Regions")) {
            LOGGER.warn("NBT 不包含 Regions");
            return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
        }

        NbtElement regionsElement = rootNbt.get("Regions");
        if (!(regionsElement instanceof CompoundTag)) {
            LOGGER.warn("Regions 不是 Compound, type: {}", regionsElement.getType());
            return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
        }
        CompoundTag regionsNbt = (CompoundTag) regionsElement;

        LOGGER.info("Regions keys: {}", regionsNbt.getKeys());

        for (String regionName : regionsNbt.getKeys()) {
            NbtElement regionElement = regionsNbt.get(regionName);
            if (!(regionElement instanceof CompoundTag)) continue;
            CompoundTag regionNbt = (CompoundTag) regionElement;

            LOGGER.info("Region '{}' keys: {}", regionName, regionNbt.getKeys());

            int xOffset = regionNbt.getInt("xOffset").orElse(0);
            int yOffset = regionNbt.getInt("yOffset").orElse(0);
            int zOffset = regionNbt.getInt("zOffset").orElse(0);

            processRegionBlocks(regionNbt, xOffset, yOffset, zOffset, globalBlockMap);

            if (regionNbt.contains("TileEntities")) {
                processRegionTileEntities(regionNbt, xOffset, yOffset, zOffset, globalTileEntityMap);
            }
        }

        return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
    }

    private void processRegionBlocks(CompoundTag regionNbt, int xOff, int yOff, int zOff, Map<BlockPos, BlockState> globalMap) {
        // Size 是 Vec3i，包含 x=width, y=height, z=length
        // 注意：Size 可以是负数（表示方向），需要结合 Position 计算
        NbtElement sizeElement = regionNbt.get("Size");
        int width = 0, height = 0, length = 0;
        if (sizeElement instanceof CompoundTag sizeNbt) {
            width = sizeNbt.getInt("x").orElse(0);
            height = sizeNbt.getInt("y").orElse(0);
            length = sizeNbt.getInt("z").orElse(0);
        }
        
        // Position 是实际原点
        NbtElement posElement = regionNbt.get("Position");
        int posX = xOff, posY = yOff, posZ = zOff;
        if (posElement instanceof CompoundTag posNbt) {
            posX = posNbt.getInt("x").orElse(xOff);
            posY = posNbt.getInt("y").orElse(yOff);
            posZ = posNbt.getInt("z").orElse(zOff);
        }
        
        // 确保尺寸为正
        if (width < 0) { width = -width; posX += width; }
        if (height < 0) { height = -height; posY += height; }
        if (length < 0) { length = -length; posZ += length; }

        LOGGER.info("处理区域: width={}, height={}, length={}, pos=({},{},{})", width, height, length, posX, posY, posZ);
        LOGGER.info("预期方块数: {}", width * height * length);

        if (width == 0 || height == 0 || length == 0) {
            LOGGER.warn("区域尺寸无效: {}x{}x{}", width, height, length);
            return;
        }

        List<BlockState> palette = new ArrayList<>();
        
        // 检查是否有单独的 bits 字段
        int bitsPerBlock = 6; // 默认值
        if (regionNbt.contains("BitsPerEntry")) {
            bitsPerBlock = regionNbt.getInt("BitsPerEntry").orElse(6);
            LOGGER.info("从 NBT 读取 bitsPerBlock: {}", bitsPerBlock);
        }
        
        NbtElement paletteElement = regionNbt.get("BlockStatePalette");
        if (paletteElement == null) {
            paletteElement = regionNbt.get("Palette");
        }
        
        if (paletteElement instanceof ListTag) {
            ListTag paletteList = (ListTag) paletteElement;
            LOGGER.info("Palette 大小: {}", paletteList.size());
            
            // 如果没有单独的 bits，使用计算值
            if (!regionNbt.contains("BitsPerEntry")) {
                bitsPerBlock = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteList.size() - 1));
            }
            
            for (int i = 0; i < paletteList.size(); i++) {
                NbtElement itemElement = paletteList.get(i);
                if (itemElement instanceof CompoundTag stateNbt) {
                    BlockState state = parseBlockStateFromNbt(stateNbt);
                    palette.add(state);
                }
            }
            
            for (int i = 0; i < Math.min(palette.size(), 5); i++) {
                LOGGER.info("Palette[{}] = {}", i, palette.get(i).getBlock());
            }
        } else {
            LOGGER.warn("Palette 不是 ListTag, type: {}", paletteElement != null ? paletteElement.getType() : "null");
            return;
        }

        LOGGER.info("解析后 palette 大小: {}", palette.size());
        LOGGER.info("bitsPerBlock: {}", bitsPerBlock);
        if (palette.isEmpty()) return;

        int paletteSize = palette.size();
        long maxEntryValue = (1L << bitsPerBlock) - 1;

        if (bitsPerBlock == 0) {
            BlockState state = palette.get(0);
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        globalMap.put(new BlockPos(x + xOff, y + yOff, z + zOff), state);
                    }
                }
            }
            LOGGER.info("填充了 {} 个相同方块", width * height * length);
        } else {
            // Litematica 使用 BlockStates
            if (!regionNbt.contains("BlockStates")) {
                LOGGER.warn("BlockStates 不存在");
                return;
            }
            long[] blockStates = regionNbt.getLongArray("BlockStates").orElse(new long[0]);
            LOGGER.info("BlockStates 数组长度: {}", blockStates.length);

            if (blockStates.length == 0) return;

            // 从 palette 大小计算正确的 bitsPerBlock（Litematica 标准编码）
            int correctBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            if (bitsPerBlock != correctBits) {
                LOGGER.info("修正 bitsPerBlock: {} -> {} (palette 大小: {})", bitsPerBlock, correctBits, paletteSize);
                bitsPerBlock = correctBits;
                maxEntryValue = (1L << bitsPerBlock) - 1;
            }

            // 使用 Litematica 风格的位数组解码
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        long startOffset = index * bitsPerBlock;
                        int startArrIndex = (int) (startOffset >> 6);
                        int endArrIndex = (int) (((index + 1L) * bitsPerBlock - 1L) >> 6);
                        int startBitOffset = (int) (startOffset & 0x3F);

                        // 边界检查
                        if (startArrIndex >= blockStates.length || endArrIndex >= blockStates.length) {
                            index++;
                            continue;
                        }

                        int stateIndex;
                        if (startArrIndex == endArrIndex) {
                            stateIndex = (int) (blockStates[startArrIndex] >>> startBitOffset & maxEntryValue);
                        } else {
                            int endOffset = 64 - startBitOffset;
                            stateIndex = (int) ((blockStates[startArrIndex] >>> startBitOffset | 
                                                blockStates[endArrIndex] << endOffset) & maxEntryValue);
                        }

                        if (stateIndex >= 0 && stateIndex < paletteSize) {
                            BlockState state = palette.get(stateIndex);
                            globalMap.put(new BlockPos(x + posX, y + posY, z + posZ), state);
                        }

                        index++;
                    }
                }
            }

            LOGGER.info("解码完成，共 {} 个方块", index);
        }
    }

    private void processRegionTileEntities(CompoundTag regionNbt, int xOff, int yOff, int zOff, Map<BlockPos, CompoundTag> globalMap) {
        if (!regionNbt.contains("TileEntities")) return;
        NbtElement tileEntityElement = regionNbt.get("TileEntities");
        if (!(tileEntityElement instanceof ListTag tileEntityList)) return;

        for (int i = 0; i < tileEntityList.size(); i++) {
            NbtElement teElement = tileEntityList.get(i);
            if (!(teElement instanceof CompoundTag teNbt)) continue;

            int x = teNbt.getInt("x").orElse(0);
            int y = teNbt.getInt("y").orElse(0);
            int z = teNbt.getInt("z").orElse(0);

            globalMap.put(new BlockPos(x + xOff, y + yOff, z + zOff), teNbt);
        }
        LOGGER.debug("处理了 {} 个 TileEntity", globalMap.size());
    }

    // 安全：注册表在游戏启动后冻结为只读，可在任意线程读取
    private BlockState parseBlockStateFromNbt(CompoundTag nbt) {
        try {
            return net.minecraft.nbt.NbtHelper.toBlockState(
                net.minecraft.registry.BuiltInRegistries.BLOCK,
                nbt
            );
        } catch (Exception e) {
            LOGGER.error("解析 BlockState 失败: {}", e.getMessage());
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
    }
    
private record SimpleParsingResult(
            Map<BlockPos, BlockState> globalBlockMap,
            Map<BlockPos, CompoundTag> globalTileEntityMap
    ) implements LitematicaParser.ParsingResult {
        @Override
        public Map<BlockPos, BlockState> getGlobalBlockMap() { return globalBlockMap; }

        @Override
        public Map<BlockPos, CompoundTag> getGlobalTileEntityMap() { return globalTileEntityMap; }
    }
}
//?} else {
package net.syncmaterial.syncmaterial.engine.impl;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DefaultLitematicaParser implements LitematicaParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLitematicaParser.class);

    private final ParsingThreadPool threadPool;

    public DefaultLitematicaParser(ParsingThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    @Override
    public CompletableFuture<List<MaterialEntry>> parseAsync(String schematicPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performParse(schematicPath);
            } catch (Exception e) {
                LOGGER.error("Failed to parse Litematica schematic: {}", schematicPath, e);
                throw new RuntimeException("Schematic parsing failed", e);
            }
        }, threadPool.getExecutor());
    }

    private List<MaterialEntry> performParse(String schematicPath) throws Exception {
        File file = new File(schematicPath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Schematic file not found: " + schematicPath);
        }

        NbtCompound rootNbt = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());

        LitematicaParser.ParsingResult result = parseNbtToResult(rootNbt);

        StatisticsProcessor processor = new StatisticsProcessor();
        List<MaterialEntry> materials = processor.process(result, true);

        LOGGER.info("原理图解析完成: {} 个方块, {} 个TileEntity, 统计出 {} 项材料",
                   result.getGlobalBlockMap().size(), result.getGlobalTileEntityMap().size(), materials.size());

        return materials;
    }

    private LitematicaParser.ParsingResult parseNbtToResult(NbtCompound rootNbt) {
        Map<BlockPos, BlockState> globalBlockMap = new HashMap<>();
        Map<BlockPos, NbtCompound> globalTileEntityMap = new HashMap<>();

        LOGGER.info("Root NBT keys: {}", rootNbt.getKeys());

        if (!rootNbt.contains("Regions")) {
            LOGGER.warn("NBT 不包含 Regions");
            return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
        }

        NbtElement regionsElement = rootNbt.get("Regions");
        if (!(regionsElement instanceof NbtCompound)) {
            LOGGER.warn("Regions 不是 Compound, type: {}", regionsElement.getType());
            return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
        }
        NbtCompound regionsNbt = (NbtCompound) regionsElement;

        LOGGER.info("Regions keys: {}", regionsNbt.getKeys());

        for (String regionName : regionsNbt.getKeys()) {
            NbtElement regionElement = regionsNbt.get(regionName);
            if (!(regionElement instanceof NbtCompound)) continue;
            NbtCompound regionNbt = (NbtCompound) regionElement;

            LOGGER.info("Region '{}' keys: {}", regionName, regionNbt.getKeys());

            int xOffset = regionNbt.getInt("xOffset").orElse(0);
            int yOffset = regionNbt.getInt("yOffset").orElse(0);
            int zOffset = regionNbt.getInt("zOffset").orElse(0);

            processRegionBlocks(regionNbt, xOffset, yOffset, zOffset, globalBlockMap);

            if (regionNbt.contains("TileEntities")) {
                processRegionTileEntities(regionNbt, xOffset, yOffset, zOffset, globalTileEntityMap);
            }
        }

        return new SimpleParsingResult(globalBlockMap, globalTileEntityMap);
    }

    private void processRegionBlocks(NbtCompound regionNbt, int xOff, int yOff, int zOff, Map<BlockPos, BlockState> globalMap) {
        // Size 是 Vec3i，包含 x=width, y=height, z=length
        // 注意：Size 可以是负数（表示方向），需要结合 Position 计算
        NbtElement sizeElement = regionNbt.get("Size");
        int width = 0, height = 0, length = 0;
        if (sizeElement instanceof NbtCompound sizeNbt) {
            width = sizeNbt.getInt("x").orElse(0);
            height = sizeNbt.getInt("y").orElse(0);
            length = sizeNbt.getInt("z").orElse(0);
        }
        
        // Position 是实际原点
        NbtElement posElement = regionNbt.get("Position");
        int posX = xOff, posY = yOff, posZ = zOff;
        if (posElement instanceof NbtCompound posNbt) {
            posX = posNbt.getInt("x").orElse(xOff);
            posY = posNbt.getInt("y").orElse(yOff);
            posZ = posNbt.getInt("z").orElse(zOff);
        }
        
        // 确保尺寸为正
        if (width < 0) { width = -width; posX += width; }
        if (height < 0) { height = -height; posY += height; }
        if (length < 0) { length = -length; posZ += length; }

        LOGGER.info("处理区域: width={}, height={}, length={}, pos=({},{},{})", width, height, length, posX, posY, posZ);
        LOGGER.info("预期方块数: {}", width * height * length);

        if (width == 0 || height == 0 || length == 0) {
            LOGGER.warn("区域尺寸无效: {}x{}x{}", width, height, length);
            return;
        }

        List<BlockState> palette = new ArrayList<>();
        
        // 检查是否有单独的 bits 字段
        int bitsPerBlock = 6; // 默认值
        if (regionNbt.contains("BitsPerEntry")) {
            bitsPerBlock = regionNbt.getInt("BitsPerEntry").orElse(6);
            LOGGER.info("从 NBT 读取 bitsPerBlock: {}", bitsPerBlock);
        }
        
        NbtElement paletteElement = regionNbt.get("BlockStatePalette");
        if (paletteElement == null) {
            paletteElement = regionNbt.get("Palette");
        }
        
        if (paletteElement instanceof NbtList) {
            NbtList paletteList = (NbtList) paletteElement;
            LOGGER.info("Palette 大小: {}", paletteList.size());
            
            // 如果没有单独的 bits，使用计算值
            if (!regionNbt.contains("BitsPerEntry")) {
                bitsPerBlock = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteList.size() - 1));
            }
            
            for (int i = 0; i < paletteList.size(); i++) {
                NbtElement itemElement = paletteList.get(i);
                if (itemElement instanceof NbtCompound stateNbt) {
                    BlockState state = parseBlockStateFromNbt(stateNbt);
                    palette.add(state);
                }
            }
            
            for (int i = 0; i < Math.min(palette.size(), 5); i++) {
                LOGGER.info("Palette[{}] = {}", i, palette.get(i).getBlock());
            }
        } else {
            LOGGER.warn("Palette 不是 NbtList, type: {}", paletteElement != null ? paletteElement.getType() : "null");
            return;
        }

        LOGGER.info("解析后 palette 大小: {}", palette.size());
        LOGGER.info("bitsPerBlock: {}", bitsPerBlock);
        if (palette.isEmpty()) return;

        int paletteSize = palette.size();
        long maxEntryValue = (1L << bitsPerBlock) - 1;

        if (bitsPerBlock == 0) {
            BlockState state = palette.get(0);
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        globalMap.put(new BlockPos(x + xOff, y + yOff, z + zOff), state);
                    }
                }
            }
            LOGGER.info("填充了 {} 个相同方块", width * height * length);
        } else {
            // Litematica 使用 BlockStates
            if (!regionNbt.contains("BlockStates")) {
                LOGGER.warn("BlockStates 不存在");
                return;
            }
            long[] blockStates = regionNbt.getLongArray("BlockStates").orElse(new long[0]);
            LOGGER.info("BlockStates 数组长度: {}", blockStates.length);

            if (blockStates.length == 0) return;

            // 从 palette 大小计算正确的 bitsPerBlock（Litematica 标准编码）
            int correctBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            if (bitsPerBlock != correctBits) {
                LOGGER.info("修正 bitsPerBlock: {} -> {} (palette 大小: {})", bitsPerBlock, correctBits, paletteSize);
                bitsPerBlock = correctBits;
                maxEntryValue = (1L << bitsPerBlock) - 1;
            }

            // 使用 Litematica 风格的位数组解码
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        long startOffset = index * bitsPerBlock;
                        int startArrIndex = (int) (startOffset >> 6);
                        int endArrIndex = (int) (((index + 1L) * bitsPerBlock - 1L) >> 6);
                        int startBitOffset = (int) (startOffset & 0x3F);

                        // 边界检查
                        if (startArrIndex >= blockStates.length || endArrIndex >= blockStates.length) {
                            index++;
                            continue;
                        }

                        int stateIndex;
                        if (startArrIndex == endArrIndex) {
                            stateIndex = (int) (blockStates[startArrIndex] >>> startBitOffset & maxEntryValue);
                        } else {
                            int endOffset = 64 - startBitOffset;
                            stateIndex = (int) ((blockStates[startArrIndex] >>> startBitOffset | 
                                                blockStates[endArrIndex] << endOffset) & maxEntryValue);
                        }

                        if (stateIndex >= 0 && stateIndex < paletteSize) {
                            BlockState state = palette.get(stateIndex);
                            globalMap.put(new BlockPos(x + posX, y + posY, z + posZ), state);
                        }

                        index++;
                    }
                }
            }

            LOGGER.info("解码完成，共 {} 个方块", index);
        }
    }

    private void processRegionTileEntities(NbtCompound regionNbt, int xOff, int yOff, int zOff, Map<BlockPos, NbtCompound> globalMap) {
        if (!regionNbt.contains("TileEntities")) return;
        NbtElement tileEntityElement = regionNbt.get("TileEntities");
        if (!(tileEntityElement instanceof NbtList tileEntityList)) return;

        for (int i = 0; i < tileEntityList.size(); i++) {
            NbtElement teElement = tileEntityList.get(i);
            if (!(teElement instanceof NbtCompound teNbt)) continue;

            int x = teNbt.getInt("x").orElse(0);
            int y = teNbt.getInt("y").orElse(0);
            int z = teNbt.getInt("z").orElse(0);

            globalMap.put(new BlockPos(x + xOff, y + yOff, z + zOff), teNbt);
        }
        LOGGER.debug("处理了 {} 个 TileEntity", globalMap.size());
    }

    // 安全：注册表在游戏启动后冻结为只读，可在任意线程读取
    private BlockState parseBlockStateFromNbt(NbtCompound nbt) {
        try {
            return net.minecraft.nbt.NbtHelper.toBlockState(
                net.minecraft.registry.Registries.BLOCK,
                nbt
            );
        } catch (Exception e) {
            LOGGER.error("解析 BlockState 失败: {}", e.getMessage());
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
    }
    
private record SimpleParsingResult(
            Map<BlockPos, BlockState> globalBlockMap,
            Map<BlockPos, NbtCompound> globalTileEntityMap
    ) implements LitematicaParser.ParsingResult {
        @Override
        public Map<BlockPos, BlockState> getGlobalBlockMap() { return globalBlockMap; }

        @Override
        public Map<BlockPos, NbtCompound> getGlobalTileEntityMap() { return globalTileEntityMap; }
    }
}
//?}
