package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.InventoryScanner;
import net.syncmaterial.syncmaterial.client.infohud.IInfoHudRenderer;
import net.syncmaterial.syncmaterial.client.infohud.RenderPhase;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;

public class MaterialListHudRenderer implements IInfoHudRenderer {
    protected final MaterialListBase materialList;
    protected final MaterialListSorter sorter;
    // 分闸：独立于 Configs.Generic.HUD_ENABLED 总闸，默认开启
    protected boolean shouldRender = true;

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

    /**
     * 26.2 的 malilib 移除了 GuiUtils/getHudOffsetForPotions，此处内联其实现
     * （照搬 malilib 0.25.x 的逻辑：TOP_RIGHT 对齐时为药水效果图标让位，其余对齐不偏移）。
     */
    private static int getHudOffsetForPotions(fi.dy.masa.malilib.config.HudAlignment alignment, double scale, net.minecraft.world.entity.player.Player player)
    {
        if (alignment == fi.dy.masa.malilib.config.HudAlignment.TOP_RIGHT)
        {
            if (scale == 0d)
            {
                return 0;
            }

            java.util.Collection<net.minecraft.world.effect.MobEffectInstance> effects = player.getActiveEffects();
            int y1 = 0;
            int y2 = 0;

            for (net.minecraft.world.effect.MobEffectInstance effectInstance : effects)
            {
                net.minecraft.world.effect.MobEffect effect = effectInstance.getEffect().value();
                if (effectInstance.isVisible() && effectInstance.showIcon())
                {
                    if (effect.getCategory() == net.minecraft.world.effect.MobEffectCategory.BENEFICIAL)
                    {
                        y1 = 26;
                    }
                    else
                    {
                        y2 = 52;
                        break;
                    }
                }
            }

            return (int) (Math.max(y1, y2) / scale);
        }

        return 0;
    }

    @Override
    public List<String> getText(RenderPhase phase) {
        return Collections.emptyList();
    }

    @Override
    //? if >=26.2 {
    public int render(GuiContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
    //?} else {
    /*public int render(GuiGraphics drawContext, int xOffset, int yOffset, HudAlignment alignment) {
    *///?}
        Minecraft mc = Minecraft.getInstance();
        boolean isPickupMode = GuiMaterialList.isPickupModeStatic();

        // 每次渲染都从当前材料列表重新投影：没有 2 秒缓存，也没有"上次渲染的列表"。
        // 模式切换、目录/进度推送和背包变化都必须在同一帧反映出来。
        List<MaterialListEntry> entries = this.materialList.getMaterialsAll();
        List<ProjectProgressMaterialView> views = new ArrayList<>(entries.size());
        for (MaterialListEntry entry : entries) {
            // progressView 已经应用列表级的新鲜度闸门；HUD 与 GUI 行状态使用同一份结果。
            views.add(entry.progressView(this.materialList));
        }

        // 取货模式使用本机含光标的即时样本；没有玩家可采样时才传 null 回退到视图观测。
        Map<String, Long> liveCounts = null;
        if (isPickupMode && mc.player != null) {
            liveCounts = InventoryScanner.liveCountsByItemId(mc.player);
        }

        MaterialHudProjection.Model model = MaterialHudProjection.compute(
                views, isPickupMode, liveCounts, this.materialList.isPreparationKnown());

        String warning = this.warningText(model);

        if (model.rows().isEmpty()) {
            this.renderEmpty(drawContext, xOffset, yOffset, alignment,
                    GuiBase.TXT_BOLD + StringUtils.translate(this.titleKey(isPickupMode)) + GuiBase.TXT_RST,
                    StringUtils.translate(model.emptyState() == MaterialHudProjection.EmptyState.NONE
                            ? "syncmaterial.gui.hint.no_materials"
                            : model.emptyState().translationKey()),
                    warning);
            return 34 + (warning.isEmpty() ? 0 : 11);
        }

        List<MaterialHudProjection.Row> rows = model.rows();
        if (rows.size() > 1) {
            List<MaterialHudProjection.Row> sorted = new ArrayList<>(rows);
            sorted.sort((left, right) -> this.sorter.compare(
                    entries.get(left.index()), entries.get(right.index())));
            rows = sorted;
        }

        Font font = mc.font;
        String shulkerBoxAbbr = StringUtils.translate("litematica.gui.label.material_list.abbr.shulker_box");
        int lineHeight = 16;
        int warehouseLineHeight = 12; // 仓库小字行高
        int warningLineHeight = 11;
        int warningHeight = warning.isEmpty() ? 0 : warningLineHeight;
        int maxLines = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_MAX_LINES.getIntegerValue();
        int size = Math.min(rows.size(), Math.max(0, maxLines));
        int contentHeight = (size * lineHeight) + 14 + warningHeight;
        int maxTextLength = 0;
        int maxCountLength = 0;
        int maxWarehouseTextLength = 0;
        int bgColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        int textColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();

        for (int i = 0; i < size; ++i) {
            MaterialHudProjection.Row row = rows.get(i);
            MaterialListEntry entry = entries.get(row.index());
            maxTextLength = Math.max(maxTextLength, font.width(entry.getStack().getHoverName().getString()));
            String strCount = GuiBase.TXT_RED
                    + MaterialListBase.getFormattedCountStringHud(row.count(), entry.getStack().getMaxStackSize(), shulkerBoxAbbr)
                    + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.width(strCount));
            if (isPickupMode && row.view().warehouse() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", row.view().warehouse());
                maxWarehouseTextLength = Math.max(maxWarehouseTextLength, font.width(whText));
                contentHeight += warehouseLineHeight;
            }
        }

        long desiredLineLength = Math.max(
                (long) maxTextLength + maxCountLength + 30L,
                (long) maxWarehouseTextLength + 20L);
        String title = GuiBase.TXT_BOLD + StringUtils.translate(this.titleKey(isPickupMode)) + GuiBase.TXT_RST;
        desiredLineLength = Math.max(desiredLineLength, (long) font.width(title) + 10L);
        desiredLineLength = Math.max(desiredLineLength, (long) font.width(warning) + 10L);
        int maxLineLength = (int) Math.min(desiredLineLength, Integer.MAX_VALUE);
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
        posY += getHudOffsetForPotions(alignment, scale, mc.player);

        if (scaled) {
            drawContext.pose().pushMatrix();
            drawContext.pose().scale((float) scale, (float) scale);
        }

        int x1 = posX - 2;
        int y1 = posY - 2;
        int x2 = x1 + maxLineLength + 4;
        int y2 = y1 + contentHeight + 2;
        drawContext.fill(x1, y1, x2, y2, bgColor);

        int x = posX;
        int itemY = posY + 12 + warningHeight;

        for (int i = 0; i < size; ++i) {
            MaterialHudProjection.Row row = rows.get(i);
            drawContext.renderItem(entries.get(row.index()).getStack(), x, itemY);
            itemY += lineHeight;
            // 取货模式下仓库小字占位
            if (isPickupMode && row.view().warehouse() > 0) {
                itemY += warehouseLineHeight;
            }
        }

        // 标题：取货模式时显示"材料清单 · 取货模式"
        drawContext.drawString(font, title, posX + 2, posY + 2, textColor, false);

        // 未知/过期即使有正数行也要显示警告；这里不显示任何"已备齐"结论。
        if (!warning.isEmpty()) {
            drawContext.drawString(font, warning, posX + 2, posY + 13, 0xFFAAAAAA, false);
        }

        int textX = posX + 18;
        int textY = posY + 16 + warningHeight;

        for (int i = 0; i < size; ++i) {
            MaterialHudProjection.Row row = rows.get(i);
            MaterialListEntry entry = entries.get(row.index());
            String text = entry.getStack().getHoverName().getString();
            String strCount = MaterialListBase.getFormattedCountStringHud(
                    row.count(), entry.getStack().getMaxStackSize(), shulkerBoxAbbr);
            int cntLen = font.width(strCount);
            int cntPosX = posX + maxLineLength - cntLen - 2;

            drawContext.drawString(font, text, textX, textY, textColor, false);
            drawContext.drawString(font, strCount, cntPosX, textY, textColor, false);
            textY += lineHeight;

            // 取货模式下显示仓库小字
            if (isPickupMode && row.view().warehouse() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", row.view().warehouse());
                drawContext.drawString(font, whText, textX + 4, textY, 0xFFAAAAAA, false);
                textY += warehouseLineHeight;
            }
        }

        if (scaled) {
            drawContext.pose().popMatrix();
        }

        return contentHeight;
    }

    private String titleKey(boolean pickupMode) {
        return pickupMode ? "syncmaterial.gui.title.material_list_pickup" : "syncmaterial.gui.title.material_list";
    }

    /**
     * 未知/过期时的短警告。优先使用材料列表已本地化的区域警告（项目视图与旧后端共用
     * {@code AreaWarningLabels}）；列表没有可用的区域信息时退回通用文案。
     */
    private String warningText(MaterialHudProjection.Model model) {
        if (!model.warningVisible()) {
            return "";
        }
        if (this.materialList instanceof SyncMaterialList syncList) {
            String text = syncList.preparationWarning();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return StringUtils.translate("syncmaterial.gui.label.freshness_warning");
    }

    /**
     * 空态：标题 +（可选）新鲜度警告 + 明确的状态说明。
     * 无参与、零需求、本人当前无需再取、仓库无货、团队已凑齐未送达、
     * 备货已齐、待确认分别使用独立文案，不再把所有空列表都当成 pickup_complete。
     */
    //? if >=26.2 {
    private void renderEmpty(GuiContext drawContext, int xOffset, int yOffset, HudAlignment alignment,
                             String title, String hint, String warning) {
    //?} else {
    /*private void renderEmpty(GuiGraphics drawContext, int xOffset, int yOffset, HudAlignment alignment,
                             String title, String hint, String warning) {
    *///?}
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int warningLineHeight = 11;
        int warningHeight = warning.isEmpty() ? 0 : warningLineHeight;
        int titleWidth = font.width(title);
        int hintWidth = font.width(hint);
        int warningWidth = warning.isEmpty() ? 0 : font.width(warning);
        int maxLineLength = Math.max(Math.max(titleWidth, hintWidth), warningWidth) + 10;
        int contentHeight = 30 + warningHeight; // 标题行 + 提示行 + 上下内边距（有警告时再加一行）
        int bgColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_BG_COLOR.getIntegerValue();
        int textColor = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_TEXT_COLOR.getIntegerValue();
        double scale = net.syncmaterial.syncmaterial.client.config.Configs.Hud.HUD_SCALE.getDoubleValue();
        int scaledWidth = GuiUtils.getScaledWindowWidth();
        int scaledHeight = GuiUtils.getScaledWindowHeight();
        boolean scaled = scale != 1.0;

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
        posY += getHudOffsetForPotions(alignment, scale, mc.player);

        if (scaled) {
            drawContext.pose().pushMatrix();
            drawContext.pose().scale((float) scale, (float) scale);
        }

        int x1 = posX - 2;
        int y1 = posY - 2;
        int x2 = x1 + maxLineLength + 4;
        int y2 = y1 + contentHeight + 2;
        drawContext.fill(x1, y1, x2, y2, bgColor);
        drawContext.drawString(font, title, posX + 2, posY + 2, textColor, false);
        if (!warning.isEmpty()) {
            drawContext.drawString(font, warning, posX + 2, posY + 13, 0xFFAAAAAA, false);
        }
        drawContext.drawString(font, hint, posX + 5, posY + 16 + warningHeight, 0xFFAAAAAA, false);

        if (scaled) {
            drawContext.pose().popMatrix();
        }
    }
}
