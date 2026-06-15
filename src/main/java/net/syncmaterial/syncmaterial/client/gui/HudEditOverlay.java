package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;

/**
 * HUD 可视化编辑模式的交互覆盖层。
 * 负责渲染编辑边框、拖拽手柄、迷你设置面板，并处理鼠标交互。
 */
public class HudEditOverlay {
    private static final HudEditOverlay INSTANCE = new HudEditOverlay();

    // 编辑模式颜色
    private static final int CLR_BORDER = 0xFF00AAFF;
    private static final int CLR_BORDER_HOVER = 0xFF55CCFF;
    private static final int CLR_HANDLE = 0xFF0088CC;
    private static final int CLR_HANDLE_HOVER = 0xFF00AAFF;
    private static final int CLR_HANDLE_ACTIVE = 0xFF55FF55;
    private static final int CLR_ROW_HANDLE = 0xFFFFAA00;
    private static final int CLR_ROW_HANDLE_HOVER = 0xFFFFCC44;
    private static final int CLR_PANEL_BG = 0xE0000000;
    private static final int CLR_PANEL_BORDER = 0xFF999999;
    private static final int CLR_TEXT = 0xFFE0E0E0;
    private static final int CLR_TEXT_HIGHLIGHT = 0xFF55FF55;

    // 手柄尺寸
    private static final int HANDLE_SIZE = 8;
    private static final int ROW_HANDLE_HEIGHT = 10;
    private static final int ROW_HANDLE_WIDTH = 24;
    private static final int MINI_BTN_SIZE = 14;

    // 交互状态
    private boolean dragging = false;
    private boolean resizing = false;
    private boolean resizingRows = false;
    private boolean miniPanelOpen = false;
    private DragTarget dragTarget = DragTarget.NONE;
    private int dragStartMouseX, dragStartMouseY;
    private int dragStartOffsetX, dragStartOffsetY;
    private double dragStartScaleX, dragStartScaleY;
    private int dragStartMaxLines;
    private int dragStartHudX, dragStartHudY, dragStartHudW, dragStartHudH;

    private enum DragTarget {
        NONE, BODY,
        N, S, W, E,
        NW, NE, SW, SE,
        ROWS,
        MINI_BTN, MINI_PANEL
    }

    private HudEditOverlay() {}

    public static HudEditOverlay getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return Configs.Generic.HUD_EDIT_MODE.getBooleanValue();
    }

    public void setActive(boolean active) {
        Configs.Generic.HUD_EDIT_MODE.setBooleanValue(active);
        if (!active) {
            miniPanelOpen = false;
        }
    }

    // ========== 渲染入口 ==========

    public void render(DrawContext drawContext, int mouseX, int mouseY) {
        if (!isActive()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        HudAlignment alignment = ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();

        // 计算 HUD 在屏幕上的实际矩形（考虑缩放与对齐）
        HudRect rect = computeHudRect(alignment);

        // 绘制编辑边框
        drawEditBorder(drawContext, rect);

        // 绘制缩放手柄（8个）
        drawResizeHandles(drawContext, rect, mouseX, mouseY);

        // 绘制行数调整手柄（底部中间）
        drawRowHandle(drawContext, rect, mouseX, mouseY);

        // 绘制迷你设置按钮（右上角）
        drawMiniSettingsButton(drawContext, rect, mouseX, mouseY);

        // 绘制迷你设置面板
        if (miniPanelOpen) {
            drawMiniSettingsPanel(drawContext, rect, mouseX, mouseY);
        }

        // 绘制提示文字
        String hint = StringUtils.translate("syncmaterial.hud.edit.hint");
        int hintW = mc.textRenderer.getWidth(hint);
        drawContext.drawTextWithShadow(mc.textRenderer, hint, rect.x + rect.w - hintW, rect.y - 14, CLR_TEXT);

        // 绘制当前参数数值
        String info = String.format("X:%d Y:%d | SX:%.2f SY:%.2f | L:%d",
                Configs.Hud.HUD_X_OFFSET.getIntegerValue(),
                Configs.Hud.HUD_Y_OFFSET.getIntegerValue(),
                Configs.Hud.HUD_SCALE_X.getDoubleValue(),
                Configs.Hud.HUD_SCALE_Y.getDoubleValue(),
                Configs.Hud.HUD_MAX_LINES.getIntegerValue());
        int infoW = mc.textRenderer.getWidth(info);
        drawContext.drawTextWithShadow(mc.textRenderer, info, rect.x + rect.w - infoW, rect.y - 26, CLR_TEXT);
    }

    // ========== 鼠标事件 ==========

    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!isActive() || button != 0) return false;

        HudAlignment alignment = ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
        HudRect rect = computeHudRect(alignment);

        DragTarget target = hitTest((int) mouseX, (int) mouseY, rect);
        if (target == DragTarget.NONE) {
            if (miniPanelOpen) {
                miniPanelOpen = false;
                return true;
            }
            return false;
        }

        if (target == DragTarget.MINI_BTN) {
            miniPanelOpen = !miniPanelOpen;
            return true;
        }

        if (target == DragTarget.MINI_PANEL) {
            return handleMiniPanelClick((int) mouseX, (int) mouseY, rect);
        }

        dragTarget = target;
        dragStartMouseX = (int) mouseX;
        dragStartMouseY = (int) mouseY;
        dragStartOffsetX = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
        dragStartOffsetY = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
        dragStartScaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        dragStartScaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        dragStartMaxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        dragStartHudX = rect.x;
        dragStartHudY = rect.y;
        dragStartHudW = rect.w;
        dragStartHudH = rect.h;

        if (target == DragTarget.BODY) {
            dragging = true;
        } else if (target == DragTarget.ROWS) {
            resizingRows = true;
        } else {
            resizing = true;
        }
        return true;
    }

    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!isActive()) return false;
        if (button == 0 && (dragging || resizing || resizingRows)) {
            dragging = false;
            resizing = false;
            resizingRows = false;
            dragTarget = DragTarget.NONE;
            Configs.saveToFile();
            return true;
        }
        return false;
    }

    public boolean onMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!isActive() || button != 0) return false;

        if (dragging && dragTarget == DragTarget.BODY) {
            return handleBodyDrag((int) mouseX, (int) mouseY);
        }
        if (resizing) {
            return handleResizeDrag((int) mouseX, (int) mouseY);
        }
        if (resizingRows && dragTarget == DragTarget.ROWS) {
            return handleRowDrag((int) mouseY);
        }
        return false;
    }

    // ========== 交互实现 ==========

    private boolean handleBodyDrag(int mouseX, int mouseY) {
        HudAlignmentOption align = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();

        int dx = mouseX - dragStartMouseX;
        int dy = mouseY - dragStartMouseY;

        int newX = dragStartOffsetX;
        int newY = dragStartOffsetY;

        if (align.isLeft()) {
            newX = dragStartOffsetX + dx;
        } else if (align.isRight()) {
            newX = dragStartOffsetX - dx;
        } else {
            // 水平居中：偏移量表示距中心的距离，向右拖动增加偏移
            newX = dragStartOffsetX + dx;
        }

        if (align.isTop()) {
            newY = dragStartOffsetY + dy;
        } else if (align.isBottom()) {
            newY = dragStartOffsetY - dy;
        } else {
            // 垂直居中
            newY = dragStartOffsetY + dy;
        }

        // 边界限制：不可完全拖出屏幕，至少保留 20px 在屏幕内
        newX = MathHelper.clamp(newX, -dragStartHudW + 20, scaledWidth - 20);
        newY = MathHelper.clamp(newY, -dragStartHudH + 20, scaledHeight - 20);

        Configs.Hud.HUD_X_OFFSET.setIntegerValue(newX);
        Configs.Hud.HUD_Y_OFFSET.setIntegerValue(newY);
        return true;
    }

    private boolean handleResizeDrag(int mouseX, int mouseY) {
        int dx = mouseX - dragStartMouseX;
        int dy = mouseY - dragStartMouseY;
        double newScaleX = dragStartScaleX;
        double newScaleY = dragStartScaleY;

        switch (dragTarget) {
            case E -> newScaleX = resizeScaleX(dx);
            case W -> newScaleX = resizeScaleX(-dx);
            case S -> newScaleY = resizeScaleY(dy);
            case N -> newScaleY = resizeScaleY(-dy);
            case SE -> {
                newScaleX = resizeScaleX(dx);
                newScaleY = resizeScaleY(dy);
            }
            case SW -> {
                newScaleX = resizeScaleX(-dx);
                newScaleY = resizeScaleY(dy);
            }
            case NE -> {
                newScaleX = resizeScaleX(dx);
                newScaleY = resizeScaleY(-dy);
            }
            case NW -> {
                newScaleX = resizeScaleX(-dx);
                newScaleY = resizeScaleY(-dy);
            }
            default -> {}
        }

        Configs.Hud.HUD_SCALE_X.setDoubleValue(MathHelper.clamp(newScaleX, 0.3, 3.0));
        Configs.Hud.HUD_SCALE_Y.setDoubleValue(MathHelper.clamp(newScaleY, 0.3, 3.0));
        return true;
    }

    private double resizeScaleX(int pixelDelta) {
        if (pixelDelta == 0 || dragStartHudW <= 0) return dragStartScaleX;
        return dragStartScaleX * (1.0 + (double) pixelDelta / dragStartHudW);
    }

    private double resizeScaleY(int pixelDelta) {
        if (pixelDelta == 0 || dragStartHudH <= 0) return dragStartScaleY;
        return dragStartScaleY * (1.0 + (double) pixelDelta / dragStartHudH);
    }

    private boolean handleRowDrag(int mouseY) {
        int dy = mouseY - dragStartMouseY;
        int lineHeight = 16;
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        int lineDelta = (int) Math.round((double) dy / (lineHeight * scaleY));
        int newLines = MathHelper.clamp(dragStartMaxLines + lineDelta, 1, 100);
        Configs.Hud.HUD_MAX_LINES.setIntegerValue(newLines);
        return true;
    }

    // ========== 碰撞检测 ==========

    private DragTarget hitTest(int mx, int my, HudRect rect) {
        if (miniPanelOpen) {
            int[] mp = getMiniPanelRect(rect);
            if (mx >= mp[0] && mx < mp[0] + mp[2] && my >= mp[1] && my < mp[1] + mp[3]) {
                return DragTarget.MINI_PANEL;
            }
        }

        // 迷你设置按钮
        int btnX = rect.x + rect.w - MINI_BTN_SIZE - 2;
        int btnY = rect.y + 2;
        if (mx >= btnX && mx < btnX + MINI_BTN_SIZE && my >= btnY && my < btnY + MINI_BTN_SIZE) {
            return DragTarget.MINI_BTN;
        }

        // 行数手柄
        int rowX = rect.x + rect.w / 2 - ROW_HANDLE_WIDTH / 2;
        int rowY = rect.y + rect.h - ROW_HANDLE_HEIGHT / 2;
        if (mx >= rowX && mx < rowX + ROW_HANDLE_WIDTH && my >= rowY && my < rowY + ROW_HANDLE_HEIGHT) {
            return DragTarget.ROWS;
        }

        // 角点
        if (inHandle(mx, my, rect.x, rect.y)) return DragTarget.NW;
        if (inHandle(mx, my, rect.x + rect.w, rect.y)) return DragTarget.NE;
        if (inHandle(mx, my, rect.x, rect.y + rect.h)) return DragTarget.SW;
        if (inHandle(mx, my, rect.x + rect.w, rect.y + rect.h)) return DragTarget.SE;

        // 边中点
        if (inHandle(mx, my, rect.x + rect.w / 2, rect.y)) return DragTarget.N;
        if (inHandle(mx, my, rect.x + rect.w / 2, rect.y + rect.h)) return DragTarget.S;
        if (inHandle(mx, my, rect.x, rect.y + rect.h / 2)) return DragTarget.W;
        if (inHandle(mx, my, rect.x + rect.w, rect.y + rect.h / 2)) return DragTarget.E;

        // 主体
        if (mx >= rect.x && mx < rect.x + rect.w && my >= rect.y && my < rect.y + rect.h) {
            return DragTarget.BODY;
        }

        return DragTarget.NONE;
    }

    private boolean inHandle(int mx, int my, int hx, int hy) {
        int half = HANDLE_SIZE / 2;
        return mx >= hx - half && mx < hx + half && my >= hy - half && my < hy + half;
    }

    // ========== 迷你设置面板 ==========

    private boolean handleMiniPanelClick(int mx, int my, HudRect rect) {
        int[] pr = getMiniPanelRect(rect);
        int px = pr[0], py = pr[1], pw = pr[2], ph = pr[3];

        if (mx < px || mx >= px + pw || my < py || my >= py + ph) {
            miniPanelOpen = false;
            return true;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        int lineH = 12;
        int contentY = py + 4;

        // 对齐方式 3x3 网格
        int gridY = contentY;
        int cellW = pw / 3;
        int cellH = 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = px + col * cellW;
                int cy = gridY + row * cellH;
                if (mx >= cx && mx < cx + cellW && my >= cy && my < cy + cellH) {
                    int idx = row * 3 + col;
                    HudAlignmentOption[] vals = HudAlignmentOption.values();
                    if (idx < vals.length) {
                        Configs.Hud.HUD_ALIGNMENT.setOptionListValue(vals[idx]);
                    }
                    return true;
                }
            }
        }

        // 颜色行
        int colorY = gridY + 3 * cellH + 6;
        int colorBoxSize = 10;
        // 背景色
        if (my >= colorY && my < colorY + colorBoxSize) {
            if (mx >= px + 4 && mx < px + 4 + colorBoxSize) {
                // 点击背景色方块 -> 循环一些预设颜色
                cycleBgColor();
                return true;
            }
        }
        int colorY2 = colorY + 14;
        if (my >= colorY2 && my < colorY2 + colorBoxSize) {
            if (mx >= px + 4 && mx < px + 4 + colorBoxSize) {
                cycleTextColor();
                return true;
            }
        }

        return true;
    }

    private void cycleBgColor() {
        int[] presets = { 0xA0000000, 0xE0000000, 0x80000000, 0x40000000, 0xA0333333, 0xA0222244 };
        int current = Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == current) {
                Configs.Hud.HUD_BG_COLOR.setValue(String.format("#%08X", presets[(i + 1) % presets.length]));
                return;
            }
        }
        Configs.Hud.HUD_BG_COLOR.setValue("#A0000000");
    }

    private void cycleTextColor() {
        int[] presets = { 0xFFFFFFFF, 0xFF000000, 0xFF55FF55, 0xFFFF5555, 0xFF55FFFF, 0xFFFFFF55 };
        int current = Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == current) {
                Configs.Hud.HUD_TEXT_COLOR.setValue(String.format("#%08X", presets[(i + 1) % presets.length]));
                return;
            }
        }
        Configs.Hud.HUD_TEXT_COLOR.setValue("#FFFFFFFF");
    }

    private int[] getMiniPanelRect(HudRect rect) {
        int pw = 110;
        int ph = 90;
        int px = rect.x + rect.w - pw;
        int py = rect.y + MINI_BTN_SIZE + 4;
        // 防止超出屏幕右边界
        int sw = GuiUtils.getScaledWindowWidth();
        if (px + pw > sw) px = sw - pw - 2;
        if (py + ph > GuiUtils.getScaledWindowHeight()) py = rect.y - ph - 2;
        return new int[]{px, py, pw, ph};
    }

    private void drawMiniSettingsButton(DrawContext drawContext, HudRect rect, int mx, int my) {
        int x = rect.x + rect.w - MINI_BTN_SIZE - 2;
        int y = rect.y + 2;
        boolean hovered = mx >= x && mx < x + MINI_BTN_SIZE && my >= y && my < y + MINI_BTN_SIZE;
        int bg = hovered ? 0xFF666666 : 0xFF444444;
        drawContext.fill(x, y, x + MINI_BTN_SIZE, y + MINI_BTN_SIZE, bg);
        drawContext.drawBorder(x, y, MINI_BTN_SIZE, MINI_BTN_SIZE, hovered ? CLR_BORDER_HOVER : CLR_PANEL_BORDER);
        MinecraftClient mc = MinecraftClient.getInstance();
        String gear = "⚙";
        int tw = mc.textRenderer.getWidth(gear);
        drawContext.drawTextWithShadow(mc.textRenderer, gear, x + (MINI_BTN_SIZE - tw) / 2, y + 2, CLR_TEXT);
    }

    private void drawMiniSettingsPanel(DrawContext drawContext, HudRect rect, int mx, int my) {
        int[] pr = getMiniPanelRect(rect);
        int px = pr[0], py = pr[1], pw = pr[2], ph = pr[3];

        drawContext.fill(px, py, px + pw, py + ph, CLR_PANEL_BG);
        drawContext.drawBorder(px, py, pw, ph, CLR_PANEL_BORDER);

        MinecraftClient mc = MinecraftClient.getInstance();
        int cellW = pw / 3;
        int cellH = 14;
        HudAlignmentOption currentAlign = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();

        // 3x3 对齐网格
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = px + col * cellW;
                int cy = py + 4 + row * cellH;
                int idx = row * 3 + col;
                HudAlignmentOption[] vals = HudAlignmentOption.values();
                boolean isCurrent = idx < vals.length && vals[idx] == currentAlign;
                int bg = isCurrent ? 0xFF226622 : 0xFF333333;
                boolean hovered = mx >= cx && mx < cx + cellW && my >= cy && my < cy + cellH;
                if (hovered) bg = 0xFF448844;
                drawContext.fill(cx + 1, cy + 1, cx + cellW - 1, cy + cellH - 1, bg);
                String label = "";
                if (idx < vals.length) {
                    label = switch (vals[idx]) {
                        case TOP_LEFT -> "↖";
                        case TOP_CENTER -> "↑";
                        case TOP_RIGHT -> "↗";
                        case CENTER_LEFT -> "←";
                        case CENTER -> "·";
                        case CENTER_RIGHT -> "→";
                        case BOTTOM_LEFT -> "↙";
                        case BOTTOM_CENTER -> "↓";
                        case BOTTOM_RIGHT -> "↘";
                    };
                }
                int tw = mc.textRenderer.getWidth(label);
                drawContext.drawTextWithShadow(mc.textRenderer, label, cx + (cellW - tw) / 2, cy + 2, isCurrent ? CLR_TEXT_HIGHLIGHT : CLR_TEXT);
            }
        }

        // 颜色选择
        int colorY = py + 4 + 3 * cellH + 6;
        int colorBoxSize = 10;
        // 背景色
        drawContext.drawTextWithShadow(mc.textRenderer, "BG", px + 4, colorY, CLR_TEXT);
        drawContext.fill(px + 22, colorY, px + 22 + colorBoxSize, colorY + colorBoxSize, Configs.Hud.HUD_BG_COLOR.getIntegerValue());
        drawContext.drawBorder(px + 22, colorY, colorBoxSize, colorBoxSize, CLR_PANEL_BORDER);
        // 文本色
        int colorY2 = colorY + 14;
        drawContext.drawTextWithShadow(mc.textRenderer, "FG", px + 4, colorY2, CLR_TEXT);
        drawContext.fill(px + 22, colorY2, px + 22 + colorBoxSize, colorY2 + colorBoxSize, Configs.Hud.HUD_TEXT_COLOR.getIntegerValue());
        drawContext.drawBorder(px + 22, colorY2, colorBoxSize, colorBoxSize, CLR_PANEL_BORDER);
    }

    // ========== 绘制辅助 ==========

    private void drawEditBorder(DrawContext drawContext, HudRect rect) {
        drawContext.drawBorder(rect.x, rect.y, rect.w, rect.h, CLR_BORDER);
    }

    private void drawResizeHandles(DrawContext drawContext, HudRect rect, int mx, int my) {
        drawHandle(drawContext, rect.x, rect.y, mx, my, dragTarget == DragTarget.NW); // NW
        drawHandle(drawContext, rect.x + rect.w, rect.y, mx, my, dragTarget == DragTarget.NE); // NE
        drawHandle(drawContext, rect.x, rect.y + rect.h, mx, my, dragTarget == DragTarget.SW); // SW
        drawHandle(drawContext, rect.x + rect.w, rect.y + rect.h, mx, my, dragTarget == DragTarget.SE); // SE
        drawHandle(drawContext, rect.x + rect.w / 2, rect.y, mx, my, dragTarget == DragTarget.N); // N
        drawHandle(drawContext, rect.x + rect.w / 2, rect.y + rect.h, mx, my, dragTarget == DragTarget.S); // S
        drawHandle(drawContext, rect.x, rect.y + rect.h / 2, mx, my, dragTarget == DragTarget.W); // W
        drawHandle(drawContext, rect.x + rect.w, rect.y + rect.h / 2, mx, my, dragTarget == DragTarget.E); // E
    }

    private void drawHandle(DrawContext drawContext, int hx, int hy, int mx, int my, boolean active) {
        int half = HANDLE_SIZE / 2;
        boolean hovered = mx >= hx - half && mx < hx + half && my >= hy - half && my < hy + half;
        int color = active ? CLR_HANDLE_ACTIVE : (hovered ? CLR_HANDLE_HOVER : CLR_HANDLE);
        drawContext.fill(hx - half, hy - half, hx + half, hy + half, color);
        drawContext.drawBorder(hx - half, hy - half, HANDLE_SIZE, HANDLE_SIZE, 0xFFFFFFFF);
    }

    private void drawRowHandle(DrawContext drawContext, HudRect rect, int mx, int my) {
        int x = rect.x + rect.w / 2 - ROW_HANDLE_WIDTH / 2;
        int y = rect.y + rect.h - ROW_HANDLE_HEIGHT / 2;
        boolean hovered = mx >= x && mx < x + ROW_HANDLE_WIDTH && my >= y && my < y + ROW_HANDLE_HEIGHT;
        int color = resizingRows ? CLR_HANDLE_ACTIVE : (hovered ? CLR_ROW_HANDLE_HOVER : CLR_ROW_HANDLE);
        drawContext.fill(x, y, x + ROW_HANDLE_WIDTH, y + ROW_HANDLE_HEIGHT, color);
        drawContext.drawBorder(x, y, ROW_HANDLE_WIDTH, ROW_HANDLE_HEIGHT, 0xFFFFFFFF);
        MinecraftClient mc = MinecraftClient.getInstance();
        String label = "≡";
        int tw = mc.textRenderer.getWidth(label);
        drawContext.drawTextWithShadow(mc.textRenderer, label, x + (ROW_HANDLE_WIDTH - tw) / 2, y + 1, 0xFF000000);
    }

    // ========== HUD 矩形计算 ==========

    private HudRect computeHudRect(HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<MaterialListEntry> list = getCurrentHudEntries();
        int maxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        int lineHeight = 16;
        int contentHeight = (Math.min(list.size(), maxLines) * lineHeight) + 14;
        int maxTextLength = 0;
        int maxCountLength = 0;
        TextRenderer font = mc.textRenderer;

        int size = Math.min(list.size(), maxLines);
        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            maxTextLength = Math.max(maxTextLength, font.getWidth(entry.getStack().getName().getString()));
            int count = entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = GuiBase.TXT_RED + getFormattedCountString(count, entry.getStack().getMaxCount()) + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.getWidth(strCount));
        }

        int maxLineLength = maxTextLength + maxCountLength + 30;
        double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();

        HudAlignmentOption alignOpt = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        int xOffset = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
        int yOffset = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
        if (list.isEmpty()) {
            int defaultW = (int) (120 * scaleX);
            int defaultH = (int) (40 * scaleY);
            int px = 0, py = 0;
            if (alignOpt.isLeft()) px = xOffset;
            else if (alignOpt.isRight()) px = scaledWidth - defaultW - xOffset;
            else px = (scaledWidth - defaultW) / 2 + xOffset;
            if (alignOpt.isTop()) py = yOffset;
            else if (alignOpt.isBottom()) py = scaledHeight - defaultH - yOffset;
            else py = (scaledHeight - defaultH) / 2 + yOffset;
            return new HudRect(px, py, defaultW, defaultH);
        }

        int posX, posY;
        int unscaledW = maxLineLength + 4;
        int unscaledH = contentHeight + 2;

        if (alignOpt.isLeft()) {
            posX = xOffset - 2;
        } else if (alignOpt.isRight()) {
            posX = (int)(scaledWidth / scaleX) - unscaledW - xOffset + 2;
        } else {
            posX = (int)((scaledWidth / scaleX) / 2) - unscaledW / 2 + xOffset;
        }

        if (alignOpt.isTop()) {
            posY = yOffset - 2;
        } else if (alignOpt.isBottom()) {
            posY = (int)(scaledHeight / scaleY) - unscaledH - yOffset + 2;
        } else {
            posY = (int)((scaledHeight / scaleY) / 2) - unscaledH / 2 + yOffset;
        }

        posY += RenderUtils.getHudOffsetForPotions(alignment, scaleY, mc.player);

        // 将未缩放坐标转为屏幕坐标（基于当前 GUI 缩放系数）
        int screenX = (int)(posX * scaleX);
        int screenY = (int)(posY * scaleY);
        int screenW = (int)(unscaledW * scaleX);
        int screenH = (int)(unscaledH * scaleY);

        return new HudRect(screenX, screenY, screenW, screenH);
    }

    private List<MaterialListEntry> getCurrentHudEntries() {
        // 尝试从活跃列表获取；若未打开 GUI，则返回空列表（此时编辑模式仍显示边框，但尺寸基于默认/上次缓存）
        try {
            java.lang.reflect.Method m = net.syncmaterial.syncmaterial.client.SyncMaterialClient.class.getDeclaredMethod("getActiveMaterialList");
            m.setAccessible(true);
            Object list = m.invoke(null);
            if (list instanceof MaterialListBase base) {
                return base.getMaterialsMissingOnly(false);
            }
        } catch (Exception ignored) {}
        return java.util.Collections.emptyList();
    }

    private String getFormattedCountString(int count, int maxStackSize) {
        int stacks = count / maxStackSize;
        int remainder = count % maxStackSize;
        double boxCount = (double) count / (27D * maxStackSize);
        if (count > maxStackSize) {
            if (boxCount >= 1.0) {
                return String.format("%d (%.2f %s)", count, boxCount, StringUtils.translate("litematica.gui.label.material_list.abbr.shulker_box"));
            } else if (remainder > 0) {
                return String.format("%d (%d x %d + %d)", count, stacks, maxStackSize, remainder);
            } else {
                return String.format("%d (%d x %d)", count, stacks, maxStackSize);
            }
        } else {
            return String.format("%d", count);
        }
    }

    private record HudRect(int x, int y, int w, int h) {}
}