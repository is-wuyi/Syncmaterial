package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;

/**
 * HUD 可视化编辑模式处理器
 * 负责编辑模式的渲染与鼠标交互
 */
public class HudEditModeHandler {
    private static final HudEditModeHandler INSTANCE = new HudEditModeHandler();

    private boolean editMode = false;
    private boolean dragging = false;
    private boolean resizing = false;
    private boolean resizingLines = false;
    private boolean showSettingsPanel = false;
    private boolean leftDown = false;
    private int dragStartMouseX, dragStartMouseY;
    private int dragStartOffsetX, dragStartOffsetY;
    private int resizeStartW, resizeStartH;
    private int resizeStartMouseX, resizeStartMouseY;
    private double resizeStartScaleX, resizeStartScaleY;
    private int resizeStartMaxLines;
    private ResizeHandle activeHandle = ResizeHandle.NONE;
    private int cachedX, cachedY, cachedW, cachedH;
    private int cachedContentH;
    private int cachedLineH;
    private int cachedMaxLineLen;

    private static final int HANDLE_SIZE = 6;
    private static final int BORDER_COLOR = 0xFF00AAFF;
    private static final int HANDLE_COLOR = 0xFF00AAFF;
    private static final int HANDLE_HOVER_COLOR = 0xFF55CCFF;
    private static final int SETTINGS_BTN_SIZE = 12;
    private static final int LINES_HANDLE_H = 10;
    private static final int LINES_HANDLE_W = 24;

    private enum ResizeHandle {
        NONE, N, NE, E, SE, S, SW, W, NW
    }

    private HudEditModeHandler() {}

    public static HudEditModeHandler getInstance() {
        return INSTANCE;
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void toggleEditMode() {
        this.editMode = !this.editMode;
        this.showSettingsPanel = false;
        if (!this.editMode) {
            this.dragging = false;
            this.resizing = false;
            this.resizingLines = false;
        }
    }

    public void setEditMode(boolean value) {
        this.editMode = value;
        if (!value) {
            this.dragging = false;
            this.resizing = false;
            this.resizingLines = false;
            this.showSettingsPanel = false;
        }
    }

    /**
     * 计算 HUD 在当前配置下的屏幕矩形（未缩放坐标系，即缩放后的实际像素位置）
     */
    public void computeHudRect(DrawContext drawContext, MaterialListHudRenderer renderer) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<MaterialListEntry> list = renderer.getLastRenderedList();
        int maxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        int lineHeight = 16;
        int size = Math.min(list.size(), maxLines);
        if (size == 0) {
            // 空列表提示框尺寸
            String hint = StringUtils.translate("syncmaterial.gui.hint.claim_materials");
            int textWidth = mc.textRenderer.getWidth(hint);
            int boxWidth = textWidth + 10;
            int boxHeight = 18;
            cachedMaxLineLen = boxWidth;
            cachedContentH = boxHeight;
            cachedLineH = lineHeight;
        } else {
            int maxTextLength = 0;
            int maxCountLength = 0;
            for (int i = 0; i < size; ++i) {
                MaterialListEntry entry = list.get(i);
                maxTextLength = Math.max(maxTextLength, mc.textRenderer.getWidth(entry.getStack().getName().getString()));
                int count = entry.getCountMissing();
                if (count < 0) count = 0;
                String strCount = GuiBase.TXT_RED + renderer.getFormattedCountString(count, entry.getStack().getMaxCount()) + GuiBase.TXT_RST;
                maxCountLength = Math.max(maxCountLength, mc.textRenderer.getWidth(strCount));
            }
            cachedMaxLineLen = maxTextLength + maxCountLength + 30;
            cachedContentH = (size * lineHeight) + 14;
            cachedLineH = lineHeight;
        }

        double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();
        int xOffset = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
        int yOffset = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
        HudAlignmentOption align = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();

        int unscaledW = cachedMaxLineLen + 4;
        int unscaledH = cachedContentH + 4;

        // 逻辑坐标（在缩放空间内）
        int logicX, logicY;
        if (align.isLeft()) {
            logicX = xOffset;
        } else if (align.isRight()) {
            logicX = (int)(scaledWidth / scaleX) - unscaledW - xOffset;
        } else {
            logicX = (int)((scaledWidth / scaleX) - unscaledW) / 2 + xOffset;
        }

        if (align.isTop()) {
            logicY = yOffset;
        } else if (align.isBottom()) {
            logicY = (int)(scaledHeight / scaleY) - unscaledH - yOffset;
        } else {
            logicY = (int)((scaledHeight / scaleY) - unscaledH) / 2 + yOffset;
        }

        logicY += RenderUtils.getHudOffsetForPotions(align.toMalilib(), scaleY, mc.player);

        // 转为屏幕实际像素坐标
        cachedX = (int)(logicX * scaleX);
        cachedY = (int)(logicY * scaleY);
        cachedW = (int)(unscaledW * scaleX);
        cachedH = (int)(unscaledH * scaleY);
    }

    public void renderEditOverlay(DrawContext drawContext, MaterialListHudRenderer renderer) {
        if (!editMode) return;

        computeHudRect(drawContext, renderer);

        int x = cachedX;
        int y = cachedY;
        int w = cachedW;
        int h = cachedH;

        // 边框
        drawContext.fill(x - 1, y - 1, x + w + 1, y, BORDER_COLOR);
        drawContext.fill(x - 1, y + h, x + w + 1, y + h + 1, BORDER_COLOR);
        drawContext.fill(x - 1, y, x, y + h, BORDER_COLOR);
        drawContext.fill(x + w, y, x + w + 1, y + h, BORDER_COLOR);

        // 8个缩放手柄
        drawHandle(drawContext, x - HANDLE_SIZE, y - HANDLE_SIZE, HANDLE_SIZE, HANDLE_SIZE); // NW
        drawHandle(drawContext, x + w / 2 - HANDLE_SIZE / 2, y - HANDLE_SIZE, HANDLE_SIZE, HANDLE_SIZE); // N
        drawHandle(drawContext, x + w, y - HANDLE_SIZE, HANDLE_SIZE, HANDLE_SIZE); // NE
        drawHandle(drawContext, x + w, y + h / 2 - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE); // E
        drawHandle(drawContext, x + w, y + h, HANDLE_SIZE, HANDLE_SIZE); // SE
        drawHandle(drawContext, x + w / 2 - HANDLE_SIZE / 2, y + h, HANDLE_SIZE, HANDLE_SIZE); // S
        drawHandle(drawContext, x - HANDLE_SIZE, y + h, HANDLE_SIZE, HANDLE_SIZE); // SW
        drawHandle(drawContext, x - HANDLE_SIZE, y + h / 2 - HANDLE_SIZE / 2, HANDLE_SIZE, HANDLE_SIZE); // W

        // 行数调整手柄（底部正中）
        int lhX = x + w / 2 - LINES_HANDLE_W / 2;
        int lhY = y + h + 2;
        drawContext.fill(lhX, lhY, lhX + LINES_HANDLE_W, lhY + LINES_HANDLE_H, 0xFFFFAA00);
        drawContext.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, "≡", lhX + LINES_HANDLE_W / 2, lhY + 1, 0xFF000000);

        // 设置按钮（右上角）
        int sbX = x + w - SETTINGS_BTN_SIZE - 2;
        int sbY = y - SETTINGS_BTN_SIZE - 2;
        drawContext.fill(sbX, sbY, sbX + SETTINGS_BTN_SIZE, sbY + SETTINGS_BTN_SIZE, 0xFF444444);
        drawContext.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, "⚙", sbX + SETTINGS_BTN_SIZE / 2, sbY + 2, 0xFFFFFFFF);

        // 提示文字
        String hint = StringUtils.translate("syncmaterial.gui.hud.edit_mode_hint");
        int hintW = MinecraftClient.getInstance().textRenderer.getWidth(hint);
        drawContext.fill(x, y - 14, x + hintW + 4, y - 2, 0xCC000000);
        drawContext.drawText(MinecraftClient.getInstance().textRenderer, hint, x + 2, y - 12, 0xFF00AAFF, false);

        if (showSettingsPanel) {
            renderSettingsPanel(drawContext);
        }
    }

    private void drawHandle(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, HANDLE_COLOR);
    }

    private void renderSettingsPanel(DrawContext drawContext) {
        int panelW = 140;
        int panelH = 110;
        int x = cachedX + cachedW + 4;
        int y = cachedY;
        int sw = GuiUtils.getScaledWindowWidth();
        int sh = GuiUtils.getScaledWindowHeight();
        if (x + panelW > sw) x = cachedX - panelW - 4;
        if (y + panelH > sh) y = sh - panelH - 4;
        if (y < 0) y = 0;

        drawContext.fill(x, y, x + panelW, y + panelH, 0xDD000000);
        drawContext.fill(x, y, x + panelW, y + 1, BORDER_COLOR);
        drawContext.fill(x, y + panelH - 1, x + panelW, y + panelH, BORDER_COLOR);
        drawContext.fill(x, y, x + 1, y + panelH, BORDER_COLOR);
        drawContext.fill(x + panelW - 1, y, x + panelW, y + panelH, BORDER_COLOR);

        MinecraftClient mc = MinecraftClient.getInstance();
        int tx = x + 6;
        int ty = y + 6;

        // 对齐方式 3x3
        drawContext.drawText(mc.textRenderer, StringUtils.translate("syncmaterial.gui.label.alignment"), tx, ty, 0xFFFFFFFF, false);
        ty += 12;
        HudAlignmentOption current = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        String[] alignLabels = {"↖", "↑", "↗", "←", "·", "→", "↙", "↓", "↘"};
        HudAlignmentOption[] alignValues = {
            HudAlignmentOption.TOP_LEFT, HudAlignmentOption.TOP_CENTER, HudAlignmentOption.TOP_RIGHT,
            HudAlignmentOption.CENTER_LEFT, HudAlignmentOption.CENTER, HudAlignmentOption.CENTER_RIGHT,
            HudAlignmentOption.BOTTOM_LEFT, HudAlignmentOption.BOTTOM_CENTER, HudAlignmentOption.BOTTOM_RIGHT
        };
        int cellW = 28;
        int cellH = 18;
        for (int i = 0; i < 9; i++) {
            int cx = tx + (i % 3) * (cellW + 2);
            int cy = ty + (i / 3) * (cellH + 2);
            boolean selected = current == alignValues[i];
            int bg = selected ? 0xFF00AAFF : 0xFF333333;
            drawContext.fill(cx, cy, cx + cellW, cy + cellH, bg);
            drawContext.drawCenteredTextWithShadow(mc.textRenderer, alignLabels[i], cx + cellW / 2, cy + 4, 0xFFFFFFFF);
        }
        ty += 3 * (cellH + 2) + 6;

        // 背景色
        drawContext.drawText(mc.textRenderer, StringUtils.translate("syncmaterial.gui.label.bg_color"), tx, ty, 0xFFFFFFFF, false);
        int colorBg = Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        drawContext.fill(tx + 70, ty - 2, tx + 102, ty + 12, 0xFFFFFFFF);
        drawContext.fill(tx + 71, ty - 1, tx + 101, ty + 11, colorBg);
        ty += 16;

        // 文本色
        drawContext.drawText(mc.textRenderer, StringUtils.translate("syncmaterial.gui.label.text_color"), tx, ty, 0xFFFFFFFF, false);
        int colorText = Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();
        drawContext.fill(tx + 70, ty - 2, tx + 102, ty + 12, 0xFFFFFFFF);
        drawContext.fill(tx + 71, ty - 1, tx + 101, ty + 11, colorText);
    }

    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!editMode) return false;
        if (button != 0) return false;

        if (showSettingsPanel) {
            if (handleSettingsClick((int) mouseX, (int) mouseY)) return true;
            showSettingsPanel = false;
            return true;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // 设置按钮
        int sbX = cachedX + cachedW - SETTINGS_BTN_SIZE - 2;
        int sbY = cachedY - SETTINGS_BTN_SIZE - 2;
        if (mx >= sbX && mx < sbX + SETTINGS_BTN_SIZE && my >= sbY && my < sbY + SETTINGS_BTN_SIZE) {
            showSettingsPanel = true;
            return true;
        }

        // 行数手柄
        int lhX = cachedX + cachedW / 2 - LINES_HANDLE_W / 2;
        int lhY = cachedY + cachedH + 2;
        if (mx >= lhX && mx < lhX + LINES_HANDLE_W && my >= lhY && my < lhY + LINES_HANDLE_H) {
            resizingLines = true;
            dragStartMouseY = my;
            resizeStartMaxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
            return true;
        }

        // 缩放手柄
        ResizeHandle handle = getHandleAt(mx, my);
        if (handle != ResizeHandle.NONE) {
            resizing = true;
            activeHandle = handle;
            resizeStartMouseX = mx;
            resizeStartMouseY = my;
            resizeStartScaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
            resizeStartScaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
            resizeStartW = cachedW;
            resizeStartH = cachedH;
            return true;
        }

        // 主体拖拽
        if (mx >= cachedX && mx < cachedX + cachedW && my >= cachedY && my < cachedY + cachedH) {
            dragging = true;
            dragStartMouseX = mx;
            dragStartMouseY = my;
            dragStartOffsetX = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
            dragStartOffsetY = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
            return true;
        }

        return false;
    }

    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!editMode) return false;
        if (button != 0) return false;
        boolean wasActive = dragging || resizing || resizingLines;
        dragging = false;
        resizing = false;
        resizingLines = false;
        activeHandle = ResizeHandle.NONE;
        return wasActive;
    }

    public boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!editMode) return false;
        if (button != 0) return false;

        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (dragging) {
            int dx = mx - dragStartMouseX;
            int dy = my - dragStartMouseY;
            HudAlignmentOption align = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
            double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
            double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();

            int newOffsetX = dragStartOffsetX;
            int newOffsetY = dragStartOffsetY;

            if (align.isLeft()) {
                newOffsetX = dragStartOffsetX + (int)(dx / scaleX);
            } else if (align.isRight()) {
                newOffsetX = dragStartOffsetX - (int)(dx / scaleX);
            } else {
                newOffsetX = dragStartOffsetX + (int)(dx / scaleX);
            }

            if (align.isTop()) {
                newOffsetY = dragStartOffsetY + (int)(dy / scaleY);
            } else if (align.isBottom()) {
                newOffsetY = dragStartOffsetY - (int)(dy / scaleY);
            } else {
                newOffsetY = dragStartOffsetY + (int)(dy / scaleY);
            }

            newOffsetX = Math.max(0, Math.min(500, newOffsetX));
            newOffsetY = Math.max(0, Math.min(500, newOffsetY));
            Configs.Hud.HUD_X_OFFSET.setIntegerValue(newOffsetX);
            Configs.Hud.HUD_Y_OFFSET.setIntegerValue(newOffsetY);
            return true;
        }

        if (resizing) {
            int dx = mx - resizeStartMouseX;
            int dy = my - resizeStartMouseY;
            double newScaleX = resizeStartScaleX;
            double newScaleY = resizeStartScaleY;

            switch (activeHandle) {
                case E, NE, SE -> newScaleX = resizeStartScaleX * (1.0 + (double) dx / Math.max(1, resizeStartW));
                case W, NW, SW -> newScaleX = resizeStartScaleX * (1.0 - (double) dx / Math.max(1, resizeStartW));
            }
            switch (activeHandle) {
                case S, SE, SW -> newScaleY = resizeStartScaleY * (1.0 + (double) dy / Math.max(1, resizeStartH));
                case N, NE, NW -> newScaleY = resizeStartScaleY * (1.0 - (double) dy / Math.max(1, resizeStartH));
            }

            newScaleX = Math.max(0.3, Math.min(3.0, newScaleX));
            newScaleY = Math.max(0.3, Math.min(3.0, newScaleY));
            Configs.Hud.HUD_SCALE_X.setDoubleValue(newScaleX);
            Configs.Hud.HUD_SCALE_Y.setDoubleValue(newScaleY);
            // 同步旧缩放值
            Configs.Hud.HUD_SCALE.setDoubleValue((newScaleX + newScaleY) / 2.0);
            return true;
        }

        if (resizingLines) {
            int dy = my - dragStartMouseY;
            int linePixelH = (int)(cachedLineH * Configs.Hud.HUD_SCALE_Y.getDoubleValue());
            if (linePixelH <= 0) linePixelH = 8;
            int deltaLines = dy / linePixelH;
            int newLines = resizeStartMaxLines + deltaLines;
            int scaledHeight = GuiUtils.getScaledWindowHeight();
            int maxPossible = Math.max(1, scaledHeight / Math.max(1, cachedLineH));
            newLines = Math.max(1, Math.min(maxPossible, newLines));
            Configs.Hud.HUD_MAX_LINES.setIntegerValue(newLines);
            return true;
        }

        return false;
    }

    private ResizeHandle getHandleAt(int mx, int my) {
        int x = cachedX;
        int y = cachedY;
        int w = cachedW;
        int h = cachedH;
        int s = HANDLE_SIZE;

        if (inRect(mx, my, x + w / 2 - s / 2, y - s, s, s)) return ResizeHandle.N;
        if (inRect(mx, my, x + w, y - s, s, s)) return ResizeHandle.NE;
        if (inRect(mx, my, x + w, y + h / 2 - s / 2, s, s)) return ResizeHandle.E;
        if (inRect(mx, my, x + w, y + h, s, s)) return ResizeHandle.SE;
        if (inRect(mx, my, x + w / 2 - s / 2, y + h, s, s)) return ResizeHandle.S;
        if (inRect(mx, my, x - s, y + h, s, s)) return ResizeHandle.SW;
        if (inRect(mx, my, x - s, y + h / 2 - s / 2, s, s)) return ResizeHandle.W;
        if (inRect(mx, my, x - s, y - s, s, s)) return ResizeHandle.NW;
        return ResizeHandle.NONE;
    }

    private boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean handleSettingsClick(int mx, int my) {
        // 面板位置与 renderSettingsPanel 一致
        int panelW = 140;
        int panelH = 110;
        int x = cachedX + cachedW + 4;
        int y = cachedY;
        int sw = GuiUtils.getScaledWindowWidth();
        int sh = GuiUtils.getScaledWindowHeight();
        if (x + panelW > sw) x = cachedX - panelW - 4;
        if (y + panelH > sh) y = sh - panelH - 4;
        if (y < 0) y = 0;

        int tx = x + 6;
        int ty = y + 6 + 12;
        int cellW = 28;
        int cellH = 18;
        HudAlignmentOption[] alignValues = {
            HudAlignmentOption.TOP_LEFT, HudAlignmentOption.TOP_CENTER, HudAlignmentOption.TOP_RIGHT,
            HudAlignmentOption.CENTER_LEFT, HudAlignmentOption.CENTER, HudAlignmentOption.CENTER_RIGHT,
            HudAlignmentOption.BOTTOM_LEFT, HudAlignmentOption.BOTTOM_CENTER, HudAlignmentOption.BOTTOM_RIGHT
        };
        for (int i = 0; i < 9; i++) {
            int cx = tx + (i % 3) * (cellW + 2);
            int cy = ty + (i / 3) * (cellH + 2);
            if (inRect(mx, my, cx, cy, cellW, cellH)) {
                Configs.Hud.HUD_ALIGNMENT.setOptionListValue(alignValues[i]);
                return true;
            }
        }

        // 颜色点击（简单循环切换几个预设，或打开颜色输入）
        ty += 3 * (cellH + 2) + 6;
        if (inRect(mx, my, tx + 70, ty - 2, 32, 14)) {
            // 背景色：简单轮询几个预设透明度
            int[] presets = {0xA0000000, 0xFF000000, 0x80000000, 0x40000000, 0xE0444444, 0xE0664433};
            int current = Configs.Hud.HUD_BG_COLOR.getIntegerValue();
            int idx = 0;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == current) { idx = (i + 1) % presets.length; break; }
            }
            Configs.Hud.HUD_BG_COLOR.setValueFromString(String.format("#%08X", presets[idx]));
            return true;
        }
        ty += 16;
        if (inRect(mx, my, tx + 70, ty - 2, 32, 14)) {
            int[] presets = {0xFFFFFFFF, 0xFFAAAAAA, 0xFF55FF55, 0xFF55FFFF, 0xFFFFFF55, 0xFFFF5555};
            int current = Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();
            int idx = 0;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == current) { idx = (i + 1) % presets.length; break; }
            }
            Configs.Hud.HUD_TEXT_COLOR.setValueFromString(String.format("#%08X", presets[idx]));
            return true;
        }

        return false;
    }

    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!editMode) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setEditMode(false);
            return true;
        }
        return false;
    }

    public boolean wasLeftDown() {
        return leftDown;
    }

    public void setLeftDown(boolean value) {
        this.leftDown = value;
    }
}
