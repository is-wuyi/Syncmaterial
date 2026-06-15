package net.syncmaterial.syncmaterial.client.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.infohud.IInfoHudRenderer;
import net.syncmaterial.syncmaterial.client.infohud.RenderPhase;

public class MaterialListHudRenderer implements IInfoHudRenderer {
    protected final MaterialListBase materialList;
    protected final MaterialListSorter sorter;
    protected boolean shouldRender;
    protected long lastUpdateTime;
    private List<MaterialListEntry> lastRenderedList = Collections.emptyList();

    // 缓存渲染后的HUD边界（屏幕坐标），供编辑器使用
    private int cachedScreenX;
    private int cachedScreenY;
    private int cachedScreenWidth;
    private int cachedScreenHeight;
    // 缓存未缩放的内容尺寸，供编辑器计算缩放
    private int cachedUnscaledWidth;
    private int cachedUnscaledHeight;

    public MaterialListHudRenderer(MaterialListBase materialList) {
        this.materialList = materialList;
        this.sorter = new MaterialListSorter(materialList);
    }

    @Override
    public boolean getShouldRenderText(RenderPhase phase) {
        return false;
    }

    @Override
    public boolean getShouldRenderCustom() {
        return this.shouldRender;
    }

    @Override
    public boolean shouldRenderInGuis() {
        return true;
    }

    public void toggleShouldRender() {
        this.shouldRender = !this.shouldRender;
    }

    public boolean getShouldRender() {
        return this.shouldRender;
    }

    public void setShouldRender(boolean value) {
        this.shouldRender = value;
    }

    @Override
    public List<String> getText(RenderPhase phase) {
        return Collections.emptyList();
    }

    // ---- 缓存边界访问器 ----

    public int getCachedScreenX() { return cachedScreenX; }
    public int getCachedScreenY() { return cachedScreenY; }
    public int getCachedScreenWidth() { return cachedScreenWidth; }
    public int getCachedScreenHeight() { return cachedScreenHeight; }
    public int getCachedUnscaledWidth() { return cachedUnscaledWidth; }
    public int getCachedUnscaledHeight() { return cachedUnscaledHeight; }

    /**
     * 根据对齐方式、偏移和缩放计算HUD在屏幕上的位置（未缩放坐标系）
     */
    public static int[] computePosition(HudAlignmentOption alignment, int xOffset, int yOffset,
                                         double scaleX, double scaleY,
                                         int unscaledWidth, int unscaledHeight) {
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();
        int posX, posY;

        if (alignment.isLeft()) {
            posX = xOffset;
        } else if (alignment.isCenterHorizontal()) {
            posX = (int)((scaledWidth / scaleX - unscaledWidth) / 2.0) + xOffset;
        } else {
            posX = (int)(scaledWidth / scaleX) - unscaledWidth - xOffset;
        }

        if (alignment.isTop()) {
            posY = yOffset;
        } else if (alignment.isCenterVertical()) {
            posY = (int)((scaledHeight / scaleY - unscaledHeight) / 2.0) + yOffset;
        } else {
            posY = (int)(scaledHeight / scaleY) - unscaledHeight - yOffset;
        }

        posY += RenderUtils.getHudOffsetForPotions(alignment.toMalilib(), Math.min(scaleX, scaleY), MinecraftClient.getInstance().player);

        return new int[]{posX, posY};
    }

    @Override
    public int render(DrawContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long currentTime = System.currentTimeMillis();

        List<MaterialListEntry> list;

        if (currentTime - this.lastUpdateTime > 2000) {
            list = this.materialList.getMaterialsMissingOnly(true);
            list = list.stream()
                .filter(e -> e.getCountMissing() > 0 && e.isCurrentPlayerClaimed())
                .collect(java.util.stream.Collectors.toList());
            Collections.sort(list, this.sorter);
            this.lastRenderedList = list;
            this.lastUpdateTime = currentTime;
        } else {
            list = this.lastRenderedList;
        }

        HudAlignmentOption alignmentOption = (HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue();
        double scaleX = Configs.Hud.HUD_SCALE_X.getDoubleValue();
        double scaleY = Configs.Hud.HUD_SCALE_Y.getDoubleValue();

        if (list.size() == 0) {
            TextRenderer font = mc.textRenderer;
            String hint = StringUtils.translate("syncmaterial.gui.hint.claim_materials");
            int textWidth = font.getWidth(hint);
            int boxWidth = textWidth + 10;
            int boxHeight = 18;
            int[] pos = computePosition(alignmentOption, xOffset, yOffset, scaleX, scaleY, boxWidth, boxHeight);
            int x = pos[0] + 2;
            int y = pos[1] + 2;

            boolean scaled = scaleX != 1.0 || scaleY != 1.0;
            if (scaled) {
                drawContext.getMatrices().pushMatrix();
                drawContext.getMatrices().scale((float) scaleX, (float) scaleY);
            }

            fi.dy.masa.malilib.render.RenderUtils.drawRect(x, y, boxWidth, boxHeight, Configs.Hud.HUD_BG_COLOR.getIntegerValue());
            drawContext.drawText(font, hint, x + 5, y + 4, 0xFFAAAAAA, false);

            if (scaled) {
                drawContext.getMatrices().popMatrix();
            }

            // 缓存边界
            cachedUnscaledWidth = boxWidth;
            cachedUnscaledHeight = boxHeight;
            cachedScreenX = (int)(x * scaleX);
            cachedScreenY = (int)(y * scaleY);
            cachedScreenWidth = (int)(boxWidth * scaleX);
            cachedScreenHeight = (int)(boxHeight * scaleY);
            return boxHeight + 4;
        }

        TextRenderer font = mc.textRenderer;
        int maxLines = Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        int lineHeight = 16;
        int contentHeight = (Math.min(list.size(), maxLines) * lineHeight) + 14;
        int maxTextLength = 0;
        int maxCountLength = 0;
        int bgColor = Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        int textColor = Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();

        final int size = Math.min(list.size(), maxLines);

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            maxTextLength = Math.max(maxTextLength, font.getWidth(entry.getStack().getName().getString()));
            int count = entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = GuiBase.TXT_RED + this.getFormattedCountString(count, entry.getStack().getMaxCount()) + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.getWidth(strCount));
        }

        final int maxLineLength = maxTextLength + maxCountLength + 30;
        boolean scaled = scaleX != 1.0 || scaleY != 1.0;

        int[] pos = computePosition(alignmentOption, xOffset, yOffset, scaleX, scaleY, maxLineLength, contentHeight);
        int posX = pos[0];
        int posY = pos[1];

        if (scaled) {
            drawContext.getMatrices().pushMatrix();
            drawContext.getMatrices().scale((float) scaleX, (float) scaleY);
        }

        int x1 = posX - 2;
        int y1 = posY - 2;
        int x2 = x1 + maxLineLength + 4;
        int y2 = y1 + contentHeight + 2;
        drawContext.fill(x1, y1, x2, y2, bgColor);

        int x = posX;
        int y = posY + 12;

        for (int i = 0; i < size; ++i) {
            drawContext.drawItem(list.get(i).getStack(), x, y);
            y += lineHeight;
        }

        String title = GuiBase.TXT_BOLD + StringUtils.translate("syncmaterial.gui.title.material_list") + GuiBase.TXT_RST;
        drawContext.drawText(font, title, posX + 2, posY + 2, textColor, false);

        x = posX + 18;
        y = posY + 16;

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            String text = entry.getStack().getName().getString();
            int count = entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = this.getFormattedCountString(count, entry.getStack().getMaxCount());
            int cntLen = font.getWidth(strCount);
            int cntPosX = posX + maxLineLength - cntLen - 2;

            drawContext.drawText(font, text, x, y, textColor, false);
            drawContext.drawText(font, strCount, cntPosX, y, textColor, false);
            y += lineHeight;
        }

        if (scaled) {
            drawContext.getMatrices().popMatrix();
        }

        // 缓存边界（屏幕坐标）
        cachedUnscaledWidth = maxLineLength;
        cachedUnscaledHeight = contentHeight;
        // 背景框尺寸（与 fill 绘制区域一致）
        int bgX = posX - 2;
        int bgY = posY - 2;
        int bgW = maxLineLength + 4;
        int bgH = contentHeight + 2;
        cachedScreenX = (int)(bgX * scaleX);
        cachedScreenY = (int)(bgY * scaleY);
        cachedScreenWidth = (int)(bgW * scaleX);
        cachedScreenHeight = (int)(bgH * scaleY);

        return contentHeight;
    }

    protected String getFormattedCountString(int count, int maxStackSize) {
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
}
