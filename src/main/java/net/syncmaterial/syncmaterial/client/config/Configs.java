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
    private static final String PREFIX = "syncmaterial.config.";

    // Tab 1: 通用
    public static class Generic {
        public static final ConfigBooleanHotkeyed HUD_ENABLED =
                new ConfigBooleanHotkeyed("hudEnabled", true, "", PREFIX + "hudEnabled");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_ENABLED
        );
    }

    // Tab 2: HUD 样式
    public static class Hud {
        public static final ConfigOptionList HUD_ALIGNMENT =
                new ConfigOptionList("hudAlignment", HudAlignmentOption.TOP_LEFT, PREFIX + "hudAlignment");
        public static final ConfigInteger HUD_X_OFFSET =
                new ConfigInteger("hudXOffset", 10, 0, 500).apply(PREFIX);
        public static final ConfigInteger HUD_Y_OFFSET =
                new ConfigInteger("hudYOffset", 44, 0, 500).apply(PREFIX);
        public static final ConfigDouble HUD_SCALE =
                new ConfigDouble("hudScale", 1.0, 0.5, 2.0).apply(PREFIX);
        public static final ConfigInteger HUD_MAX_LINES =
                new ConfigInteger("hudMaxLines", 20, 1, 50).apply(PREFIX);
        public static final ConfigColor HUD_BG_COLOR =
                new ConfigColor("hudBgColor", "#A0000000", PREFIX + "hudBgColor");
        public static final ConfigColor HUD_TEXT_COLOR =
                new ConfigColor("hudTextColor", "#FFFFFFFF", PREFIX + "hudTextColor");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_ALIGNMENT, HUD_X_OFFSET, HUD_Y_OFFSET, HUD_SCALE,
                HUD_MAX_LINES, HUD_BG_COLOR, HUD_TEXT_COLOR
        );
    }

    // Tab 3: 线框渲染
    public static class Render {
        public static final ConfigColor AREA_LINE_COLOR =
                new ConfigColor("areaLineColor", "#FF00FF00", PREFIX + "areaLineColor");
        public static final ConfigColor AREA_SIDE_COLOR =
                new ConfigColor("areaSideColor", "#2E00FF00", PREFIX + "areaSideColor");
        public static final ConfigColor AREA_HIGHLIGHT_LINE_COLOR =
                new ConfigColor("areaHighlightLineColor", "#FFFFAA00", PREFIX + "areaHighlightLineColor");
        public static final ConfigDouble AREA_LINE_WIDTH =
                new ConfigDouble("areaLineWidth", 2.0, 1.0, 5.0).apply(PREFIX);
        public static final ConfigBoolean LABEL_ENABLED =
                new ConfigBoolean("labelEnabled", true, PREFIX + "labelEnabled");
        public static final ConfigDouble LABEL_SCALE =
                new ConfigDouble("labelScale", 0.05, 0.02, 0.1).apply(PREFIX);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                AREA_LINE_COLOR, AREA_SIDE_COLOR, AREA_HIGHLIGHT_LINE_COLOR,
                AREA_LINE_WIDTH, LABEL_ENABLED, LABEL_SCALE
        );
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
            }
        } else {
            saveToFile();
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
