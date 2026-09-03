package net.syncmaterial.syncmaterial.client.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.*;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.nio.file.Files;
import java.nio.file.Path;

public class Configs implements IConfigHandler {
    private static final String CONFIG_FILE_NAME = SyncMaterial.MOD_ID + ".json";
    private static final String PREFIX = "syncmaterial.config";
    private static final String HIDDEN_WAREHOUSES_KEY = "HiddenWarehouses";

    // Tab 1: 通用
    public static class Generic {
        public static final ConfigBooleanHotkeyed HUD_ENABLED =
                new ConfigBooleanHotkeyed("hudEnabled", true, "").apply(PREFIX);
        public static final ConfigBooleanHotkeyed WAREHOUSE_RENDER_ENABLED =
                new ConfigBooleanHotkeyed("warehouseRenderEnabled", true, "").apply(PREFIX);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_ENABLED, WAREHOUSE_RENDER_ENABLED
        );
    }

    // Tab 2: HUD 样式
    public static class Hud {
        public static final ConfigOptionList HUD_ALIGNMENT =
                new ConfigOptionList("hudAlignment", HudAlignmentOption.BOTTOM_RIGHT).apply(PREFIX);
        public static final ConfigInteger HUD_X_OFFSET =
                new ConfigInteger("hudXOffset", 1, 0, 500).apply(PREFIX);
        public static final ConfigInteger HUD_Y_OFFSET =
                new ConfigInteger("hudYOffset", 1, 0, 500).apply(PREFIX);
        public static final ConfigDouble HUD_SCALE =
                new ConfigDouble("hudScale", 1.0, 0.5, 2.0).apply(PREFIX);
        public static final ConfigInteger HUD_MAX_LINES =
                new ConfigInteger("hudMaxLines", 20, 1, 50).apply(PREFIX);
        public static final ConfigColor HUD_BG_COLOR =
                new ConfigColor("hudBgColor", "#A0000000").apply(PREFIX);
        public static final ConfigColor HUD_TEXT_COLOR =
                new ConfigColor("hudTextColor", "#FFFFFFFF").apply(PREFIX);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_ALIGNMENT, HUD_X_OFFSET, HUD_Y_OFFSET, HUD_SCALE,
                HUD_MAX_LINES, HUD_BG_COLOR, HUD_TEXT_COLOR
        );
    }

    // Tab 3: 线框渲染
    public static class Render {
        public static final ConfigColor AREA_LINE_COLOR =
                new ConfigColor("areaLineColor", "#FF00FF00").apply(PREFIX);
        public static final ConfigColor AREA_SIDE_COLOR =
                new ConfigColor("areaSideColor", "#2E00FF00").apply(PREFIX);
        public static final ConfigColor AREA_HIGHLIGHT_LINE_COLOR =
                new ConfigColor("areaHighlightLineColor", "#2EFFAA00").apply(PREFIX);
        public static final ConfigBoolean LABEL_ENABLED =
                new ConfigBoolean("labelEnabled", true).apply(PREFIX);
        public static final ConfigDouble LABEL_SCALE =
                new ConfigDouble("labelScale", 0.05, 0.02, 0.1).apply(PREFIX);
        // 仓库用蓝色系，与备货区的绿色区分；引用高亮色沿用取货模式箱子高亮的青蓝调
        public static final ConfigColor WAREHOUSE_LINE_COLOR =
                new ConfigColor("warehouseLineColor", "#FF3399FF").apply(PREFIX);
        public static final ConfigColor WAREHOUSE_SIDE_COLOR =
                new ConfigColor("warehouseSideColor", "#2E3399FF").apply(PREFIX);
        public static final ConfigColor WAREHOUSE_REFERENCED_LINE_COLOR =
                new ConfigColor("warehouseReferencedLineColor", "#FF00E5FF").apply(PREFIX);
        public static final ConfigColor CONTAINER_HIGHLIGHT_COLOR =
                new ConfigColor("containerHighlightColor", "#FFFFA000").apply(PREFIX);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AREA_LINE_COLOR, AREA_SIDE_COLOR, AREA_HIGHLIGHT_LINE_COLOR,
                LABEL_ENABLED, LABEL_SCALE,
                WAREHOUSE_LINE_COLOR, WAREHOUSE_SIDE_COLOR, WAREHOUSE_REFERENCED_LINE_COLOR,
                CONTAINER_HIGHLIGHT_COLOR
        );
    }

    /**
     * 被单独隐藏的仓库线框（持久化到配置文件的 HiddenWarehouses 段）。
     *
     * key 形如 "serverAddress#warehouseId"：仓库 ID 是服务端数据库自增值，
     * 不同服务器会出现相同 ID，不带服务器前缀会导致跨服误隐藏。
     * 采用"记录隐藏项"而非"记录显示项"，这样新建的仓库默认可见。
     */
    private static final java.util.Set<String> hiddenWarehouses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String warehouseKey(String serverKey, int warehouseId) {
        return serverKey + "#" + warehouseId;
    }

    public static boolean isWarehouseHidden(String serverKey, int warehouseId) {
        return hiddenWarehouses.contains(warehouseKey(serverKey, warehouseId));
    }

    public static void setWarehouseHidden(String serverKey, int warehouseId, boolean hidden) {
        String key = warehouseKey(serverKey, warehouseId);
        boolean changed = hidden ? hiddenWarehouses.add(key) : hiddenWarehouses.remove(key);
        if (changed) {
            // 内存状态已生效；落盘失败只影响下次启动的记忆，不能让按钮操作整体抛出
            try {
                saveToFile();
            } catch (Exception e) {
                SyncMaterial.LOGGER.warn("保存仓库线框显示状态失败，本次修改仅在当前会话有效", e);
            }
        }
    }

    /** 仅供测试重置隐藏状态，避免用例间互相污染 */
    public static void clearHiddenWarehouses() {
        hiddenWarehouses.clear();
    }

    /**
     * 获取指定 Tab 的配置列表
     */
    public static ImmutableList<IConfigBase> getTabOptions(ConfigTab tab) {
        return switch (tab) {
            case GENERIC -> Generic.OPTIONS;
            case HUD -> Hud.OPTIONS;
            case RENDER -> Render.OPTIONS;
        };
    }

    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);
        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            var element = JsonUtils.parseJsonFileAsPath(configFile);
            if (element != null && element.isJsonObject()) {
                var root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hud", Hud.OPTIONS);
                ConfigUtils.readConfigBase(root, "Render", Render.OPTIONS);
                readHiddenWarehouses(root);
            }
        } else {
            saveToFile();
        }
    }

    private static void readHiddenWarehouses(com.google.gson.JsonObject root) {
        hiddenWarehouses.clear();
        if (!root.has(HIDDEN_WAREHOUSES_KEY) || !root.get(HIDDEN_WAREHOUSES_KEY).isJsonArray()) {
            return;
        }
        for (var element : root.getAsJsonArray(HIDDEN_WAREHOUSES_KEY)) {
            if (element.isJsonPrimitive()) {
                String key = element.getAsString();
                if (!key.isBlank()) {
                    hiddenWarehouses.add(key);
                }
            }
        }
    }

    public static void saveToFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);
        var root = new com.google.gson.JsonObject();
        if (Files.exists(configFile)) {
            var element = JsonUtils.parseJsonFileAsPath(configFile);
            if (element != null && element.isJsonObject()) {
                root = element.getAsJsonObject();
            }
        }

        ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Hud", Hud.OPTIONS);
        ConfigUtils.writeConfigBase(root, "Render", Render.OPTIONS);

        var hidden = new com.google.gson.JsonArray();
        for (String key : hiddenWarehouses) {
            hidden.add(key);
        }
        root.add(HIDDEN_WAREHOUSES_KEY, hidden);

        JsonUtils.writeJsonToFileAsPath(root, configFile);
    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    @Override
    public void onConfigsChanged() {
        saveToFile();
    }

    public enum ConfigTab {
        GENERIC,
        HUD,
        RENDER
    }
}
