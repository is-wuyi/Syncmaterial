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
import net.syncmaterial.syncmaterial.client.infohud.IInfoHudRenderer;
import net.syncmaterial.syncmaterial.client.infohud.RenderPhase;

public class MaterialListHudRenderer implements IInfoHudRenderer {
    protected final MaterialListBase materialList;
    protected final MaterialListSorter sorter;
    protected boolean shouldRender;
    protected long lastUpdateTime;

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

    @Override
    public List<String> getText(RenderPhase phase) {
        return Collections.emptyList();
    }

    @Override
    public int render(DrawContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long currentTime = System.currentTimeMillis();

        List<MaterialListEntry> list;

        if (currentTime - this.lastUpdateTime > 2000) {
            MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), mc.player);
            list = this.materialList.getMaterialsMissingOnly(true);
            Collections.sort(list, this.sorter);
            this.lastUpdateTime = currentTime;
        } else {
            list = this.materialList.getMaterialsMissingOnly(false);
        }

        if (list.size() == 0) {
            return 0;
        }

        TextRenderer font = mc.textRenderer;
        int maxLines = 20;
        int lineHeight = 16;
        int contentHeight = (Math.min(list.size(), maxLines) * lineHeight) + 14;
        int maxTextLength = 0;
        int maxCountLength = 0;
        int posX = xOffset + 2;
        int posY = yOffset + 2;
        int bgColor = 0xA0000000;
        int textColor = 0xFFFFFFFF;

        final int size = Math.min(list.size(), maxLines);

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            maxTextLength = Math.max(maxTextLength, font.getWidth(entry.getStack().getName().getString()));
            int count = entry.getCountMissing() - entry.getCountAvailable();
            if (count < 0) count = 0;
            String strCount = GuiBase.TXT_RED + this.getFormattedCountString(count, entry.getStack().getMaxCount()) + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.getWidth(strCount));
        }

        final int maxLineLength = maxTextLength + maxCountLength + 30;

        switch (alignment) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                posX = (int) ((GuiUtils.getScaledWindowWidth()) - maxLineLength - xOffset - 2);
                break;
            case CENTER:
                posX = (int) ((GuiUtils.getScaledWindowWidth() / 2) - (maxLineLength / 2) - xOffset);
                break;
            default:
        }

        posY = RenderUtils.getHudPosY(posY, yOffset, contentHeight, 1.0, alignment);
        posY += RenderUtils.getHudOffsetForPotions(alignment, 1.0, mc.player);

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

        String title = GuiBase.TXT_BOLD + "材料清单" + GuiBase.TXT_RST;
        drawContext.drawText(font, title, posX + 2, posY + 2, textColor, false);

        x = posX + 18;
        y = posY + 16;

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            String text = entry.getStack().getName().getString();
            int count = entry.getCountMissing() - entry.getCountAvailable();
            if (count < 0) count = 0;
            String strCount = this.getFormattedCountString(count, entry.getStack().getMaxCount());
            int cntLen = font.getWidth(strCount);
            int cntPosX = posX + maxLineLength - cntLen - 2;

            drawContext.drawText(font, text, x, y, textColor, false);
            drawContext.drawText(font, strCount, cntPosX, y, textColor, false);
            y += lineHeight;
        }

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
