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
     * 转为 MaLiLib 的 HudAlignment 渲染用（仅4角对齐有对应值，其余返回 BOTTOM_RIGHT）
     */
    public fi.dy.masa.malilib.config.HudAlignment toMalilib() {
        return switch (this) {
            case TOP_LEFT -> fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT;
            case TOP_RIGHT -> fi.dy.masa.malilib.config.HudAlignment.TOP_RIGHT;
            case BOTTOM_LEFT -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_LEFT;
            default -> fi.dy.masa.malilib.config.HudAlignment.BOTTOM_RIGHT;
        };
    }

    /**
     * 水平方向是否靠左
     */
    public boolean isLeft() {
        return this == TOP_LEFT || this == CENTER_LEFT || this == BOTTOM_LEFT;
    }

    /**
     * 水平方向是否居中
     */
    public boolean isCenterHorizontal() {
        return this == TOP_CENTER || this == CENTER || this == BOTTOM_CENTER;
    }

    /**
     * 水平方向是否靠右
     */
    public boolean isRight() {
        return this == TOP_RIGHT || this == CENTER_RIGHT || this == BOTTOM_RIGHT;
    }

    /**
     * 垂直方向是否靠上
     */
    public boolean isTop() {
        return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT;
    }

    /**
     * 垂直方向是否居中
     */
    public boolean isCenterVertical() {
        return this == CENTER_LEFT || this == CENTER || this == CENTER_RIGHT;
    }

    /**
     * 垂直方向是否靠下
     */
    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_CENTER || this == BOTTOM_RIGHT;
    }
}
