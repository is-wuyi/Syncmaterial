package net.syncmaterial.syncmaterial.client;

import net.minecraft.util.math.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.lang.reflect.Method;
import java.util.*;

public class LitematicaSelectionReader {

    public record StagingAreaRegion(String name, BlockPos pos1, BlockPos pos2) {}

    public static List<StagingAreaRegion> read() {
        try {
            Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Method getSelectionManager = dataManagerClass.getMethod("getSelectionManager");
            Object selectionManager = getSelectionManager.invoke(null);

            if (selectionManager == null) {
                return Collections.emptyList();
            }

            Method getCurrentSelection = selectionManager.getClass().getMethod("getCurrentSelection");
            Object areaSelection = getCurrentSelection.invoke(selectionManager);

            if (areaSelection == null) {
                return Collections.emptyList();
            }

            Method getAllSubRegions = areaSelection.getClass().getMethod("getAllSubRegions");
            Object subRegionsObj = getAllSubRegions.invoke(areaSelection);

            if (!(subRegionsObj instanceof Map<?, ?> subRegions)) {
                return Collections.emptyList();
            }

            List<StagingAreaRegion> result = new ArrayList<>();
            for (Map.Entry<?, ?> entry : subRegions.entrySet()) {
                String name = entry.getKey().toString();
                Object box = entry.getValue();

                if (box == null) continue;

                BlockPos pos1 = extractPos(box, "getPos1");
                BlockPos pos2 = extractPos(box, "getPos2");

                if (pos1 != null && pos2 != null) {
                    result.add(new StagingAreaRegion(name, pos1, pos2));
                }
            }

            return result;

        } catch (ClassNotFoundException e) {
            SyncMaterial.LOGGER.debug("Litematica 未安装，选区读取返回空列表");
            return Collections.emptyList();
        } catch (NoSuchMethodException e) {
            SyncMaterial.LOGGER.warn("Litematica API 版本不兼容，选区读取返回空列表: {}", e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            SyncMaterial.LOGGER.warn("读取 Litematica 选区失败", e);
            return Collections.emptyList();
        }
    }

    public static boolean isAvailable() {
        try {
            Class.forName("fi.dy.masa.litematica.data.DataManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static BlockPos extractPos(Object box, String methodName) {
        try {
            Method method = box.getClass().getMethod(methodName);
            Object posObj = method.invoke(box);
            if (posObj == null) return null;

            Method getX = posObj.getClass().getMethod("getX");
            Method getY = posObj.getClass().getMethod("getY");
            Method getZ = posObj.getClass().getMethod("getZ");

            int x = ((Number) getX.invoke(posObj)).intValue();
            int y = ((Number) getY.invoke(posObj)).intValue();
            int z = ((Number) getZ.invoke(posObj)).intValue();

            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }
}