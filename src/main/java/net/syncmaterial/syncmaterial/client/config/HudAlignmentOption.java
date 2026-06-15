package net.syncmaterial.syncmaterial.client.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

public enum HudAlignmentOption implements IConfigOptionListEntry {
    TOP_LEFT("top_left"),
    TOP_CENTER("top_center"),
    TOP_RIGHT("top_right"),
    CENTER_LEFT("center_left"),
    CENTER("center"),
    CENTER_RIGHT("center_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_CENTER("bottom_center"),
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
        return fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudAlignment." + this.name);
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
     * 转为 MaLiLib 的 HudAlignment 渲染用（MaLiLib 只有4种，超出时映射到最近的）
     */
    public fi.dy.masa.malilib.config.HudAlignment toMalilib() {
        return switch (this) {
            case TOP_LEFT -> fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT;
            case TOP_CENTER, CENTER_LEFT, CENTER -> fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT;
            case TOP_RIGHT, CENTER_RIGHT -> fi.dy.masa.malilib.config.HudAlignment.TOP_RIGHT;
            case BOTTOM_LEFT, BOTTOM_CENTER -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_LEFT;
            case BOTTOM_RIGHT -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_RIGHT;
        };
    }

    public boolean isLeft() {
        return this == TOP_LEFT || this == CENTER_LEFT || this == BOTTOM_LEFT;
    }

    public boolean isRight() {
        return this == TOP_RIGHT || this == CENTER_RIGHT || this == BOTTOM_RIGHT;
    }

    public boolean isTop() {
        return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_CENTER || this == BOTTOM_RIGHT;
    }

    public boolean isCenterHorizontal() {
        return this == TOP_CENTER || this == CENTER || this == BOTTOM_CENTER;
    }

    public boolean isCenterVertical() {
        return this == CENTER_LEFT || this == CENTER || this == CENTER_RIGHT;
    }
}
