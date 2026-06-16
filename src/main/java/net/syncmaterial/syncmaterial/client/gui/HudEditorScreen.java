package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import fi.dy.masa.malilib.util.GuiUtils;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;

/**
 * HUD 可视化编辑屏幕。
 * 进入编辑模式后以此屏幕捕获鼠标事件，在 HUD 周围绘制交互边框、拖拽手柄和设置按钮。
 */
public class HudEditorScreen extends Screen {
    private static final int HANDLE_SIZE = 6;
    private static final int BORDER_COLOR = 0xFF00AAFF;
    private static final int HANDLE_COLOR = 0xFF00CCFF;
    private static final int HANDLE_HOVER_COLOR = 0xFF44DDFF;
    private static final int SETTINGS_BTN_SIZE = 12;
    private static final int MAX_LINES_HANDLE_WIDTH = 24;
    private static final int MAX_LINES_HANDLE_HEIGHT = 6;
    private static final int MIN_HUD_PIXELS = 30;

    // 拖拽状态
    private enum DragMode {
        NONE, MOVE, RESIZE_TL, RESIZE_T, RESIZE_TR,
        RESIZE_L, RESIZE_R, RESIZE_BL, RESIZE_B, RESIZE_BR,
        MAX_LINES
    }

    private DragMode dragMode = DragMode.NONE;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private double dragStartScaleX;
    private double dragStartScaleY;
    private int dragStartXOffset;
    private int dragStartYOffset;
    private int dragStartMaxLines;
    private int dragStartHudX;
    private int dragStartHudY;
    private int dragStartHudW;
    private int dragStartHudH;

    // 设置浮层状态
    private boolean settingsPopupOpen = false;

    // 颜色编辑状态
    private boolean editingBgColor = false;
    private boolean editingTextColor = false;
    private String colorInput = "";
    private long colorInputLastTyped = 0;

    // 悬停状态
    private DragMode hoveredHandle = DragMode.NONE;
    private boolean hoveredSettingsBtn = false;

    private final MaterialListHudRenderer hudRenderer;

    public HudEditorScreen(MaterialListHudRenderer hudRenderer) {
        super(Text.translatable("syncmaterial.gui.title.hud_editor"));
        this.hudRenderer = hudRenderer;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        Configs.Generic.HUD_EDIT_MODE.setBooleanValue(false);
        Configs.saveToFile();
        super.close();
    }

    @Override
    public void removed() {
        // 确保编辑模式标记被重置（包括被其他屏幕替换的情况）
        Configs.Generic.HUD_EDIT_MODE.setBooleanValue(false);
        super.removed();
    }

    // ---- HUD 边界获取 ----

    private int getHudX() { return hudRenderer.getCachedScreenX(); }
    private int getHudY() { return hudRenderer.getCachedScreenY(); }
    private int getHudW() { return hudRenderer.getCachedScreenWidth(); }
    private int getHudH() { return hudRenderer.getCachedScreenHeight(); }

    // ---- 手柄位置计算 ----

    private int[] getHandleRect(DragMode handle) {
        int x = getHudX(), y = getHudY(), w = getHudW(), h = getHudH();
        int hs = HANDLE_SIZE;
        return switch (handle) {
            case RESIZE_TL -> new int[]{x - hs, y - hs, hs, hs};
            case RESIZE_T  -> new int[]{x + w / 2 - hs / 2, y - hs, hs, hs};
            case RESIZE_TR -> new int[]{x + w, y - hs, hs, hs};
            case RESIZE_L  -> new int[]{x - hs, y + h / 2 - hs / 2, hs, hs};
            case RESIZE_R  -> new int[]{x + w, y + h / 2 - hs / 2, hs, hs};
            case RESIZE_BL -> new int[]{x - hs, y + h, hs, hs};
            case RESIZE_B  -> new int[]{x + w / 2 - hs / 2, y + h, hs, hs};
            case RESIZE_BR -> new int[]{x + w, y + h, hs, hs};
            default -> new int[]{0, 0, 0, 0};
        };
    }

    private int[] getMaxLinesHandleRect() {
        int x = getHudX(), y = getHudY(), w = getHudW(), h = getHudH();
        return new int[]{x + w / 2 - MAX_LINES_HANDLE_WIDTH / 2, y + h, MAX_LINES_HANDLE_WIDTH, MAX_LINES_HANDLE_HEIGHT};
    }

    private int[] getSettingsBtnRect() {
        int x = getHudX(), y = getHudY(), w = getHudW();
        return new int[]{x + w + 2, y - SETTINGS_BTN_SIZE - 2, SETTINGS_BTN_SIZE, SETTINGS_BTN_SIZE};
    }

    // ---- 碰撞检测 ----

    private boolean isInRect(int mx, int my, int[] rect) {
        return mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3];
    }

    private boolean isInHudBody(int mx, int my) {
        return mx >= getHudX() && mx < getHudX() + getHudW()
                && my >= getHudY() && my < getHudY() + getHudH();
    }

    private DragMode getHandleAt(int mx, int my) {
        for (DragMode mode : new DragMode[]{
                DragMode.RESIZE_TL, DragMode.RESIZE_T, DragMode.RESIZE_TR,
                DragMode.RESIZE_L, DragMode.RESIZE_R,
                DragMode.RESIZE_BL, DragMode.RESIZE_B, DragMode.RESIZE_BR}) {
            if (isInRect(mx, my, getHandleRect(mode))) return mode;
        }
        if (isInRect(mx, my, getMaxLinesHandleRect())) return DragMode.MAX_LINES;
        return DragMode.NONE;
    }

    // ---- 鼠标事件 ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        int mx = (int) mouseX, my = (int) mouseY;

        // 设置浮层优先处理
        if (settingsPopupOpen) {
            return handleSettingsPopupClick(mx, my);
        }

        // 设置按钮
        if (isInRect(mx, my, getSettingsBtnRect())) {
            settingsPopupOpen = !settingsPopupOpen;
            return true;
        }

        // 缩放手柄
        DragMode handle = getHandleAt(mx, my);
        if (handle != DragMode.NONE) {
            startDrag(handle, mx, my);
            return true;
        }

        // HUD 主体拖拽
        if (isInHudBody(mx, my)) {
            startDrag(DragMode.MOVE, mx, my);
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragMode != DragMode.NONE) {
            dragMode = DragMode.NONE;
            Configs.saveToFile();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragMode == DragMode.NONE) return false;

        int mx = (int) mouseX, my = (int) mouseY;
        int deltaMX = mx - dragStartMouseX;
        int deltaMY = my - dragStartMouseY;

        switch (dragMode) {
            case MOVE -> handleMoveDrag(deltaMX, deltaMY);
            case MAX_LINES -> handleMaxLinesDrag(deltaMY);
            default -> handleResizeDrag(deltaMX, deltaMY);
        }

        return true;
    }

    private void startDrag(DragMode mode, int mx, int my) {
        dragMode = mode;
        dragStartMouseX = mx;
        dragStartMouseY = my;
        dragStartScaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        dragStartScaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        dragStartXOffset = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
        dragStartYOffset = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
        dragStartMaxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        dragStartHudX = getHudX();
        dragStartHudY = getHudY();
        dragStartHudW = getHudW();
        dragStartHudH = getHudH();
    }

    // ---- 拖拽处理 ----

    private void handleMoveDrag(int deltaMX, int deltaMY) {
        HudAlignmentOption alignment = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();

        int newXOffset, newYOffset;

        // 根据对齐方向转换屏幕像素偏移到配置偏移
        if (alignment.isRight()) {
            newXOffset = dragStartXOffset - (int)(deltaMX / scaleX);
        } else {
            newXOffset = dragStartXOffset + (int)(deltaMX / scaleX);
        }

        if (alignment.isBottom()) {
            newYOffset = dragStartYOffset - (int)(deltaMY / scaleY);
        } else {
            newYOffset = dragStartYOffset + (int)(deltaMY / scaleY);
        }

        // 边界限制：HUD 不可完全拖出屏幕
        newXOffset = Math.max(0, Math.min(500, newXOffset));
        newYOffset = Math.max(0, Math.min(500, newYOffset));

        Configs.Hud.HUD_X_OFFSET.setIntegerValue(newXOffset);
        Configs.Hud.HUD_Y_OFFSET.setIntegerValue(newYOffset);
    }

    private void handleResizeDrag(int deltaMX, int deltaMY) {
        int unscaledW = hudRenderer.getCachedUnscaledWidth();
        int unscaledH = hudRenderer.getCachedUnscaledHeight();
        if (unscaledW <= 0 || unscaledH <= 0) return;

        double newScaleX = dragStartScaleX;
        double newScaleY = dragStartScaleY;

        // 根据拖拽方向计算新缩放
        boolean adjustLeft = dragMode == DragMode.RESIZE_TL || dragMode == DragMode.RESIZE_L || dragMode == DragMode.RESIZE_BL;
        boolean adjustRight = dragMode == DragMode.RESIZE_TR || dragMode == DragMode.RESIZE_R || dragMode == DragMode.RESIZE_BR;
        boolean adjustTop = dragMode == DragMode.RESIZE_TL || dragMode == DragMode.RESIZE_T || dragMode == DragMode.RESIZE_TR;
        boolean adjustBottom = dragMode == DragMode.RESIZE_BL || dragMode == DragMode.RESIZE_B || dragMode == DragMode.RESIZE_BR;

        if (adjustRight) {
            int newPixelW = dragStartHudW + deltaMX;
            newScaleX = Math.max(0.3, Math.min(3.0, (double) newPixelW / (unscaledW + 4)));
        } else if (adjustLeft) {
            int newPixelW = dragStartHudW - deltaMX;
            newScaleX = Math.max(0.3, Math.min(3.0, (double) newPixelW / (unscaledW + 4)));
        }

        if (adjustBottom) {
            int newPixelH = dragStartHudH + deltaMY;
            newScaleY = Math.max(0.3, Math.min(3.0, (double) newPixelH / (unscaledH + 2)));
        } else if (adjustTop) {
            int newPixelH = dragStartHudH - deltaMY;
            newScaleY = Math.max(0.3, Math.min(3.0, (double) newPixelH / (unscaledH + 2)));
        }

        Configs.Hud.HUD_SCALE_X.setDoubleValue(newScaleX);
        Configs.Hud.HUD_SCALE_Y.setDoubleValue(newScaleY);

        // 调整偏移量，使锚定边（被拖拽边的对边）保持固定
        HudAlignmentOption alignment = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();
        int unscaledBgW = unscaledW + 4;
        int unscaledBgH = unscaledH + 2;

        if (adjustLeft || adjustRight) {
            // 计算应保持固定的屏幕X坐标
            double fixedScreenX;
            if (adjustRight) {
                // 拖拽右边，固定左边
                fixedScreenX = dragStartHudX;
            } else {
                // 拖拽左边，固定右边
                fixedScreenX = dragStartHudX + dragStartHudW;
            }

            int newXOffset;
            if (alignment.isLeft()) {
                if (adjustRight) {
                    newXOffset = (int)(fixedScreenX / newScaleX);
                } else {
                    // 固定右边：fixedScreenX = (xOffset + unscaledBgW) * newScaleX
                    newXOffset = (int)(fixedScreenX / newScaleX) - unscaledBgW;
                }
            } else if (alignment.isRight()) {
                if (adjustRight) {
                    // 固定左边：fixedScreenX = scaledWidth - (unscaledBgW + xOffset) * newScaleX
                    newXOffset = (int)((scaledWidth - fixedScreenX) / newScaleX) - unscaledBgW;
                } else {
                    // 固定右边：fixedScreenX = scaledWidth - xOffset * newScaleX
                    newXOffset = (int)((scaledWidth - fixedScreenX) / newScaleX);
                }
            } else {
                // 居中对齐
                if (adjustRight) {
                    newXOffset = (int)(fixedScreenX / newScaleX - (scaledWidth / newScaleX - unscaledBgW) / 2.0);
                } else {
                    newXOffset = (int)((fixedScreenX - (scaledWidth - unscaledBgW * newScaleX) / 2) / newScaleX);
                }
            }
            newXOffset = Math.max(0, Math.min(500, newXOffset));
            Configs.Hud.HUD_X_OFFSET.setIntegerValue(newXOffset);
        }

        if (adjustTop || adjustBottom) {
            double fixedScreenY;
            if (adjustBottom) {
                fixedScreenY = dragStartHudY;
            } else {
                fixedScreenY = dragStartHudY + dragStartHudH;
            }

            int newYOffset;
            if (alignment.isTop()) {
                if (adjustBottom) {
                    newYOffset = (int)(fixedScreenY / newScaleY);
                } else {
                    newYOffset = (int)(fixedScreenY / newScaleY) - unscaledBgH;
                }
            } else if (alignment.isBottom()) {
                if (adjustBottom) {
                    newYOffset = (int)((scaledHeight - fixedScreenY) / newScaleY) - unscaledBgH;
                } else {
                    newYOffset = (int)((scaledHeight - fixedScreenY) / newScaleY);
                }
            } else {
                if (adjustBottom) {
                    newYOffset = (int)(fixedScreenY / newScaleY - (scaledHeight / newScaleY - unscaledBgH) / 2.0);
                } else {
                    newYOffset = (int)((fixedScreenY - (scaledHeight - unscaledBgH * newScaleY) / 2) / newScaleY);
                }
            }
            newYOffset = Math.max(0, Math.min(500, newYOffset));
            Configs.Hud.HUD_Y_OFFSET.setIntegerValue(newYOffset);
        }
    }

    private void handleMaxLinesDrag(int deltaMY) {
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        int lineHeightPixels = (int)(16 * scaleY);
        if (lineHeightPixels <= 0) lineHeightPixels = 1;

        int deltaLines = -deltaMY / lineHeightPixels;
        int newMaxLines = dragStartMaxLines + deltaLines;

        // 限制行数范围
        int screenH = GuiUtils.getScaledWindowHeight();
        int maxPossibleLines = Math.max(1, (screenH - 20) / lineHeightPixels);
        newMaxLines = Math.max(1, Math.min(Math.min(50, maxPossibleLines), newMaxLines));

        Configs.Hud.HUD_MAX_LINES.setIntegerValue(newMaxLines);
    }

    // ---- 设置浮层 ----

    private int[] getSettingsPopupRect() {
        int x = getHudX() + getHudW() + 4;
        int y = getHudY();
        int w = 140;
        int h = 150;
        // 如果右边放不下，放左边
        if (x + w > GuiUtils.getScaledWindowWidth()) {
            x = getHudX() - w - 4;
        }
        return new int[]{x, y, w, h};
    }

    private boolean handleSettingsPopupClick(int mx, int my) {
        int[] popupRect = getSettingsPopupRect();
        int px = popupRect[0], py = popupRect[1], pw = popupRect[2];

        // 背景色按钮
        int bgColorY = py + 20;
        if (mx >= px + 4 && mx < px + pw - 4 && my >= bgColorY && my < bgColorY + 16) {
            editingBgColor = true;
            editingTextColor = false;
            colorInput = Configs.Hud.HUD_BG_COLOR.getStringValue();
            colorInputLastTyped = System.currentTimeMillis();
            return true;
        }

        // 文本色按钮
        int textColorY = py + 42;
        if (mx >= px + 4 && mx < px + pw - 4 && my >= textColorY && my < textColorY + 16) {
            editingTextColor = true;
            editingBgColor = false;
            colorInput = Configs.Hud.HUD_TEXT_COLOR.getStringValue();
            colorInputLastTyped = System.currentTimeMillis();
            return true;
        }

        // 对齐方式 3x3 网格
        int gridX = px + 4;
        int gridY = py + 68;
        int cellSize = 20;
        int gap = 2;
        HudAlignmentOption[] alignments = HudAlignmentOption.values();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int cx = gridX + col * (cellSize + gap);
                int cy = gridY + row * (cellSize + gap);
                if (mx >= cx && mx < cx + cellSize && my >= cy && my < cy + cellSize) {
                    Configs.Hud.HUD_ALIGNMENT.setOptionListValue(alignments[idx]);
                    Configs.saveToFile();
                    return true;
                }
            }
        }

        // 点击浮层外部关闭
        if (!isInRect(mx, my, popupRect)) {
            settingsPopupOpen = false;
            editingBgColor = false;
            editingTextColor = false;
        }
        return false;
    }

    // ---- 渲染 ----

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        super.render(drawContext, mouseX, mouseY, delta);

        // 更新悬停状态
        hoveredHandle = getHandleAt(mouseX, mouseY);
        hoveredSettingsBtn = isInRect(mouseX, mouseY, getSettingsBtnRect());

        // 绘制边框
        int x = getHudX(), y = getHudY(), w = getHudW(), h = getHudH();
        drawBorder(drawContext, x, y, w, h);

        // 绘制8个缩放手柄
        drawResizeHandles(drawContext, mouseX, mouseY);

        // 绘制最大行数手柄
        drawMaxLinesHandle(drawContext, mouseX, mouseY);

        // 绘制设置按钮
        drawSettingsButton(drawContext, mouseX, mouseY);

        // 绘制当前值提示
        drawValueHints(drawContext);

        // 绘制设置浮层
        if (settingsPopupOpen) {
            drawSettingsPopup(drawContext, mouseX, mouseY);
        }

        // 绘制颜色输入框
        if (editingBgColor || editingTextColor) {
            drawColorInput(drawContext);
        }
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h) {
        // 虚线边框效果（用实线代替，每2像素间隔）
        int color = BORDER_COLOR;
        // 上
        ctx.fill(x, y - 1, x + w, y, color);
        // 下
        ctx.fill(x, y + h, x + w, y + h + 1, color);
        // 左
        ctx.fill(x - 1, y, x, y + h, color);
        // 右
        ctx.fill(x + w, y, x + w + 1, y + h, color);
    }

    private void drawResizeHandles(DrawContext ctx, int mx, int my) {
        DragMode[] handles = {
                DragMode.RESIZE_TL, DragMode.RESIZE_T, DragMode.RESIZE_TR,
                DragMode.RESIZE_L, DragMode.RESIZE_R,
                DragMode.RESIZE_BL, DragMode.RESIZE_B, DragMode.RESIZE_BR
        };
        for (DragMode handle : handles) {
            int[] rect = getHandleRect(handle);
            boolean hovered = hoveredHandle == handle;
            ctx.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                    hovered ? HANDLE_HOVER_COLOR : HANDLE_COLOR);
        }
    }

    private void drawMaxLinesHandle(DrawContext ctx, int mx, int my) {
        int[] rect = getMaxLinesHandleRect();
        boolean hovered = hoveredHandle == DragMode.MAX_LINES;
        int color = hovered ? HANDLE_HOVER_COLOR : 0xFF00FF88;

        // 绘制为小横条
        ctx.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], color);

        // 中间小三角指示
        int cx = rect[0] + rect[2] / 2;
        int cy = rect[1] + rect[3] / 2;
        ctx.fill(cx - 4, cy - 1, cx + 4, cy, color);
        ctx.fill(cx - 3, cy + 1, cx + 3, cy + 2, color);
    }

    private void drawSettingsButton(DrawContext ctx, int mx, int my) {
        int[] rect = getSettingsBtnRect();
        int color = hoveredSettingsBtn ? 0xFF44DDFF : 0xFF0088AA;
        ctx.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], color);

        // 齿轮图标（简化为 ⚙ 字符）
        MinecraftClient mc = MinecraftClient.getInstance();
        String gear = "\u2699";
        int textW = mc.textRenderer.getWidth(gear);
        int textX = rect[0] + (rect[2] - textW) / 2;
        int textY = rect[1] + (rect[3] - 8) / 2;
        ctx.drawText(mc.textRenderer, gear, textX, textY, 0xFFFFFFFF, false);
    }

    private void drawValueHints(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int x = getHudX(), y = getHudY() - 12;
        if (y < 2) y = getHudY() + getHudH() + 2;

        double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        int maxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        String hint = String.format("X:%d Y:%d SX:%.2f SY:%.2f L:%d",
                Configs.Hud.HUD_X_OFFSET.getIntegerValue(),
                Configs.Hud.HUD_Y_OFFSET.getIntegerValue(),
                scaleX, scaleY, maxLines);
        int textW = mc.textRenderer.getWidth(hint);
        ctx.drawTextWithShadow(mc.textRenderer, hint, x, y, 0xFFAAAAAA);
    }

    private void drawSettingsPopup(DrawContext ctx, int mx, int my) {
        int[] rect = getSettingsPopupRect();
        int px = rect[0], py = rect[1], pw = rect[2], ph = rect[3];

        // 背景
        ctx.fill(px, py, px + pw, py + ph, 0xE0000000);
        ctx.fill(px, py, px + pw, py + 1, BORDER_COLOR);
        ctx.fill(px, py + ph - 1, px + pw, py + ph, BORDER_COLOR);
        ctx.fill(px, py, px + 1, py + ph, BORDER_COLOR);
        ctx.fill(px + pw - 1, py, px + pw, py + ph, BORDER_COLOR);

        MinecraftClient mc = MinecraftClient.getInstance();

        // 标题
        String title = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.label.hud_settings");
        ctx.drawTextWithShadow(mc.textRenderer, title, px + 4, py + 4, 0xFFFFFFFF);

        // 背景色
        String bgLabel = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudBgColor");
        ctx.drawTextWithShadow(mc.textRenderer, bgLabel, px + 4, py + 22, 0xFFCCCCCC);
        int bgColorY = py + 20;
        int bgColor = Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        ctx.fill(px + pw - 40, bgColorY, px + pw - 4, bgColorY + 16, bgColor);
        ctx.fill(px + pw - 40, bgColorY, px + pw - 39, bgColorY + 16, 0xFF888888);
        ctx.fill(px + pw - 5, bgColorY, px + pw - 4, bgColorY + 16, 0xFF888888);
        ctx.fill(px + pw - 40, bgColorY, px + pw - 4, bgColorY + 1, 0xFF888888);
        ctx.fill(px + pw - 40, bgColorY + 15, px + pw - 4, bgColorY + 16, 0xFF888888);

        // 文本色
        String textLabel = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudTextColor");
        ctx.drawTextWithShadow(mc.textRenderer, textLabel, px + 4, py + 44, 0xFFCCCCCC);
        int textColorY = py + 42;
        int textColor = Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();
        ctx.fill(px + pw - 40, textColorY, px + pw - 4, textColorY + 16, textColor);
        ctx.fill(px + pw - 40, textColorY, px + pw - 39, textColorY + 16, 0xFF888888);
        ctx.fill(px + pw - 5, textColorY, px + pw - 4, textColorY + 16, 0xFF888888);
        ctx.fill(px + pw - 40, textColorY, px + pw - 4, textColorY + 1, 0xFF888888);
        ctx.fill(px + pw - 40, textColorY + 15, px + pw - 4, textColorY + 16, 0xFF888888);

        // 对齐方式
        String alignLabel = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudAlignment");
        ctx.drawTextWithShadow(mc.textRenderer, alignLabel, px + 4, py + 64, 0xFFCCCCCC);

        // 3x3 网格
        int gridX = px + 4;
        int gridY = py + 78;
        int cellSize = 20;
        int gap = 2;
        HudAlignmentOption currentAlignment = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        HudAlignmentOption[] alignments = HudAlignmentOption.values();

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int cx = gridX + col * (cellSize + gap);
                int cy = gridY + row * (cellSize + gap);
                boolean isCurrent = alignments[idx] == currentAlignment;
                boolean isHovered = mx >= cx && mx < cx + cellSize && my >= cy && my < cy + cellSize;

                int cellColor = isCurrent ? 0xFF00AAFF : (isHovered ? 0xFF444444 : 0xFF222222);
                ctx.fill(cx, cy, cx + cellSize, cy + cellSize, cellColor);
                // 边框
                ctx.fill(cx, cy, cx + cellSize, cy + 1, 0xFF666666);
                ctx.fill(cx, cy + cellSize - 1, cx + cellSize, cy + cellSize, 0xFF666666);
                ctx.fill(cx, cy, cx + 1, cy + cellSize, 0xFF666666);
                ctx.fill(cx + cellSize - 1, cy, cx + cellSize, cy + cellSize, 0xFF666666);

                // 小锚点指示
                int dotX = cx + cellSize / 2 - 1;
                int dotY = cy + cellSize / 2 - 1;
                ctx.fill(dotX, dotY, dotX + 3, dotY + 3, isCurrent ? 0xFFFFFFFF : 0xFF888888);
            }
        }
    }

    private void drawColorInput(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String label = editingBgColor
                ? fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudBgColor")
                : fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.config.name.hudTextColor");

        int x = getHudX(), y = getHudY() + getHudH() + 10;
        int w = 120, h = 24;

        ctx.fill(x, y, x + w, y + h, 0xE0000000);
        ctx.fill(x, y, x + w, y + 1, BORDER_COLOR);
        ctx.fill(x, y + h - 1, x + w, y + h, BORDER_COLOR);
        ctx.fill(x, y, x + 1, y + h, BORDER_COLOR);
        ctx.fill(x + w - 1, y, x + w, y + h, BORDER_COLOR);

        ctx.drawTextWithShadow(mc.textRenderer, label + ":", x + 4, y + 4, 0xFFCCCCCC);

        // 光标闪烁
        long time = System.currentTimeMillis();
        String displayText = colorInput;
        if (time % 1000 < 500) {
            displayText += "_";
        }
        ctx.drawTextWithShadow(mc.textRenderer, displayText, x + 4, y + 14, 0xFFFFFFFF);

        // 颜色预览
        try {
            int color = parseColor(colorInput);
            ctx.fill(x + w - 20, y + 4, x + w - 4, y + h - 4, color);
        } catch (Exception ignored) {}
    }

    // ---- 键盘输入 ----

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (editingBgColor || editingTextColor) {
            colorInput += chr;
            colorInputLastTyped = System.currentTimeMillis();
            applyColorInput();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 编辑模式快捷键可切换退出
        if (Configs.Generic.HUD_EDIT_MODE.getKeybind().matchesKey(keyCode, scanCode)) {
            this.close();
            return true;
        }
        if (editingBgColor || editingTextColor) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                if (!colorInput.isEmpty()) {
                    colorInput = colorInput.substring(0, colorInput.length() - 1);
                    applyColorInput();
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                applyColorInput();
                editingBgColor = false;
                editingTextColor = false;
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                editingBgColor = false;
                editingTextColor = false;
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyColorInput() {
        try {
            // 先验证颜色格式
            parseColor(colorInput);
            if (editingBgColor) {
                Configs.Hud.HUD_BG_COLOR.setValueFromString(colorInput);
            } else if (editingTextColor) {
                Configs.Hud.HUD_TEXT_COLOR.setValueFromString(colorInput);
            }
            Configs.saveToFile();
        } catch (Exception ignored) {}
    }

    private int parseColor(String str) {
        String clean = str.replace("#", "");
        if (clean.length() == 8) {
            return (int) Long.parseLong(clean, 16);
        } else if (clean.length() == 6) {
            return 0xFF000000 | (int) Long.parseLong(clean, 16);
        }
        throw new IllegalArgumentException("Invalid color");
    }

    // ---- 鼠标光标样式 ----

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 不渲染默认背景，保持透明
    }
}
