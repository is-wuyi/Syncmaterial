package net.syncmaterial.syncmaterial.client.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

public enum HudAlignmentOption implements IConfigOptionListEntry {
    TOP_LEFT("top_left"),
    TOP_RIGHT("top_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_RIGHT("bottom_right");

    private final String name;

    HudAlignmentOption(String name) {
        this.name = name;
    }

    @Override
    public String getStringValue() {
        return this.name;
    }

    @Override
    public String getDisplayName() {
        return this.name;
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int index = this.ordinal();
        HudAlignmentOption[] values = values();
        return values[forward ? (index + 1) % values.length : (index - 1 + values.length) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (HudAlignmentOption opt : values()) {
            if (opt.name.equals(value)) return opt;
        }
        return this;
    }

    /**
     * 转为 MaLiLib 的 HudAlignment 渲染用
     */
    public fi.dy.masa.malilib.config.HudAlignment toMalilib() {
        return switch (this) {
            case TOP_LEFT -> fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT;
            case TOP_RIGHT -> fi.dy.masa.malilib.config.HudAlignment.TOP_RIGHT;
            case BOTTOM_LEFT -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_LEFT;
            case BOTTOM_RIGHT -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_RIGHT;
        };
    }
}
