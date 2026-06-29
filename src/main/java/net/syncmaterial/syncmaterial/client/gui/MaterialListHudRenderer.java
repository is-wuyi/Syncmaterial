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
    private List<MaterialListEntry> lastRenderedList = Collections.emptyList();

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

    @Override
    public int render(DrawContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long currentTime = System.currentTimeMillis();
        boolean isPickupMode = GuiMaterialList.isPickupModeStatic();

        List<MaterialListEntry> list;

        if (currentTime - this.lastUpdateTime > 2000) {
            list = this.materialList.getMaterialsMissingOnly(true);
            // 取货模式：显示仓库+背包都不够的材料（还需取货 > 0）
            // 普通模式：显示已认领且还有缺失的材料
            if (isPickupMode) {
                list = list.stream()
                    .filter(e -> (e.getCountTotal() - e.getStagingCount() - e.getCountAvailable()) > 0 && e.isCurrentPlayerClaimed())
                    .collect(java.util.stream.Collectors.toList());
            } else {
                list = list.stream()
                    .filter(e -> e.getCountMissing() > 0 && e.isCurrentPlayerClaimed())
                    .collect(java.util.stream.Collectors.toList());
            }
            Collections.sort(list, this.sorter);
            this.lastRenderedList = list;
            this.lastUpdateTime = currentTime;
        } else {
            list = this.lastRenderedList;
        }

        if (list.size() == 0) {
            TextRenderer font = mc.textRenderer;
            String hint = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.claim_materials");
            int textWidth = font.getWidth(hint);
            int boxWidth = textWidth + 10;
            int boxHeight = 18;
            int x = xOffset + 2;
            int y = yOffset + 2;
            fi.dy.masa.malilib.render.RenderUtils.drawRect(x, y, boxWidth, boxHeight, net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_BG_COLOR.getIntegerValue());
            drawContext.drawText(font, hint, x + 5, y + 4, 0xFFAAAAAA, false);
            return boxHeight + 4;
        }

        TextRenderer font = mc.textRenderer;
        String shulkerBoxAbbr = StringUtils.translate("litematica.gui.label.material_list.abbr.shulker_box");
        int maxLines = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        int lineHeight = 16;
        int warehouseLineHeight = 12; // 仓库小字行高
        int contentHeight = (Math.min(list.size(), maxLines) * lineHeight) + 14;
        int maxTextLength = 0;
        int maxCountLength = 0;
        int maxWarehouseTextLength = 0;
        int bgColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        int textColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();

        final int size = Math.min(list.size(), maxLines);

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            maxTextLength = Math.max(maxTextLength, font.getWidth(entry.getStack().getName().getString()));
            // 取货模式：还需取货 = 总数 - 备货区 - 我的背包
            int count = isPickupMode ? Math.max(0, entry.getCountTotal() - entry.getStagingCount() - entry.getCountAvailable()) : entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = GuiBase.TXT_RED + MaterialListBase.getFormattedCountStringHud(count, entry.getStack().getMaxCount(), shulkerBoxAbbr) + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.getWidth(strCount));
            // 计算仓库小字宽度
            if (isPickupMode && entry.getWarehouseCount() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", entry.getWarehouseCount());
                maxWarehouseTextLength = Math.max(maxWarehouseTextLength, font.getWidth(whText));
                contentHeight += warehouseLineHeight;
            }
        }

        final int maxLineLength = Math.max(maxTextLength + maxCountLength + 30, maxWarehouseTextLength + 20);
        double scale = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_SCALE.getDoubleValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();
        boolean scaled = scale != 1.0;

        // 参照 MaLiLib renderText：缩放后用 scaledWidth/scale 和 scaledHeight/scale 定位
        int posX, posY;
        switch (alignment) {
            case TOP_LEFT:
                posX = xOffset;
                posY = yOffset;
                break;
            case TOP_RIGHT:
                posX = (int)(scaledWidth / scale) - maxLineLength - xOffset;
                posY = yOffset;
                break;
            case BOTTOM_LEFT:
                posX = xOffset;
                posY = (int)(scaledHeight / scale) - contentHeight - yOffset;
                break;
            case BOTTOM_RIGHT:
            default:
                posX = (int)(scaledWidth / scale) - maxLineLength - xOffset;
                posY = (int)(scaledHeight / scale) - contentHeight - yOffset;
                break;
        }
        posY += RenderUtils.getHudOffsetForPotions(alignment, scale, mc.player);

        if (scaled) {
            drawContext.getMatrices().pushMatrix();
            drawContext.getMatrices().scale((float) scale, (float) scale);
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
            // 取货模式下仓库小字占位
            if (isPickupMode && list.get(i).getWarehouseCount() > 0) {
                y += warehouseLineHeight;
            }
        }

        // 标题：取货模式时显示"材料清单 · 取货模式"
        String titleKey = isPickupMode ? "syncmaterial.gui.title.material_list_pickup" : "syncmaterial.gui.title.material_list";
        String title = GuiBase.TXT_BOLD + fi.dy.masa.malilib.util.StringUtils.translate(titleKey) + GuiBase.TXT_RST;
        drawContext.drawText(font, title, posX + 2, posY + 2, textColor, false);

        x = posX + 18;
        y = posY + 16;

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            String text = entry.getStack().getName().getString();
            // 取货模式：还需取货 = 总数 - 备货区 - 我的背包
            int count = isPickupMode ? Math.max(0, entry.getCountTotal() - entry.getStagingCount() - entry.getCountAvailable()) : entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = MaterialListBase.getFormattedCountStringHud(count, entry.getStack().getMaxCount(), shulkerBoxAbbr);
            int cntLen = font.getWidth(strCount);
            int cntPosX = posX + maxLineLength - cntLen - 2;

            drawContext.drawText(font, text, x, y, textColor, false);
            drawContext.drawText(font, strCount, cntPosX, y, textColor, false);
            y += lineHeight;

            // 取货模式下显示仓库小字
            if (isPickupMode && entry.getWarehouseCount() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", entry.getWarehouseCount());
                drawContext.drawText(font, whText, x + 4, y, 0xFFAAAAAA, false);
                y += warehouseLineHeight;
            }
        }

        if (scaled) {
            drawContext.getMatrices().popMatrix();
        }

        return contentHeight;
    }
}
