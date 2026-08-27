package net.syncmaterial.syncmaterial.client.gui;

import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import fi.dy.masa.malilib.render.GuiContext;

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
    // 分闸：独立于 Configs.Generic.HUD_ENABLED 总闸，默认开启
    protected boolean shouldRender = true;
    protected long lastUpdateTime;
    private List<MaterialListEntry> lastRenderedList = Collections.emptyList();

    // 取货指示器高亮缓存：物品ID → 还需取货数量，仅在HUD刷新时更新
    private final Map<String, Integer> pickupHighlightNeeds = new HashMap<>();
    private static final Map<String, Integer> pickupHighlightNeedsStatic = new HashMap<>();

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

    /** 取货指示器高亮缓存（静态访问，供 AbstractContainerScreenMixin 使用）*/
    public static Map<String, Integer> getPickupHighlightNeeds() {
        return Collections.unmodifiableMap(pickupHighlightNeedsStatic);
    }

    private void updatePickupHighlightNeeds(boolean isPickupMode) {
        pickupHighlightNeeds.clear();
        if (!isPickupMode) {
            pickupHighlightNeedsStatic.clear();
            return;
        }
        for (MaterialListEntry entry : this.materialList.getMaterialsAll()) {
            if (!entry.isCurrentPlayerClaimed()) continue;
            int pickupMissing = (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.pickupMissing(
                entry.getCountTotal(), entry.getStagingCount(), entry.getCountAvailable(), entry.getWarehouseCount());
            if (pickupMissing > 0) {
                String itemId = entry.getStack().getItem().toString();
                pickupHighlightNeeds.merge(itemId, pickupMissing, Integer::sum);
            }
        }
        pickupHighlightNeedsStatic.clear();
        pickupHighlightNeedsStatic.putAll(pickupHighlightNeeds);
    }

    @Override
    public List<String> getText(RenderPhase phase) {
        return Collections.emptyList();
    }

    @Override
    public int render(GuiContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        Minecraft mc = Minecraft.getInstance();
        long currentTime = System.currentTimeMillis();
        boolean isPickupMode = GuiMaterialList.isPickupModeStatic();

        List<MaterialListEntry> list;

        if (currentTime - this.lastUpdateTime > 2000) {
            // 取货模式：使用全部材料列表，按取货公式过滤
            // 普通模式：使用缺失材料列表，按收集模式过滤
            if (isPickupMode) {
                list = this.materialList.getMaterialsAll().stream()
                    .filter(e -> Math.min(e.getCountTotal() - e.getStagingCount() - e.getCountAvailable(), e.getWarehouseCount()) > 0 && e.isCurrentPlayerClaimed())
                    .collect(java.util.stream.Collectors.toList());
            } else {
                list = this.materialList.getMaterialsMissingOnly(true).stream()
                    .filter(e -> e.getCountMissing() > 0 && e.isCurrentPlayerClaimed())
                    .collect(java.util.stream.Collectors.toList());
            }
            Collections.sort(list, this.sorter);
            this.lastRenderedList = list;
            // 更新取货指示器高亮缓存
            updatePickupHighlightNeeds(isPickupMode);
            this.lastUpdateTime = currentTime;
        } else {
            list = this.lastRenderedList;
        }

        if (list.size() == 0) {
            Font font = mc.font;
            boolean hasAnyClaimed = this.materialList.getMaterialsAll().stream()
                .anyMatch(MaterialListEntry::isCurrentPlayerClaimed);

            String hint;
            if (!hasAnyClaimed) {
                hint = StringUtils.translate("syncmaterial.gui.hint.claim_materials");
            } else if (isPickupMode) {
                hint = StringUtils.translate("syncmaterial.gui.hint.pickup_complete");
            } else {
                hint = StringUtils.translate("syncmaterial.gui.hint.collect_complete");
            }

            String titleKey = isPickupMode ? "syncmaterial.gui.title.material_list_pickup" : "syncmaterial.gui.title.material_list";
            String title = GuiBase.TXT_BOLD + StringUtils.translate(titleKey) + GuiBase.TXT_RST;
            int titleWidth = font.width(title);
            int hintWidth = font.width(hint);
            int maxLineLength = Math.max(titleWidth, hintWidth) + 10;
            int contentHeight = 30; // 标题行 + 提示行 + 上下内边距
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
            drawContext.text(font, title, posX + 2, posY + 2, textColor, false);
            drawContext.text(font, hint, posX + 5, posY + 16, 0xFFAAAAAA, false);

            if (scaled) {
                drawContext.pose().popMatrix();
            }

            return contentHeight + 4;
        }

        Font font = mc.font;
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
            maxTextLength = Math.max(maxTextLength, font.width(entry.getStack().getHoverName().getString()));
            // 取货模式：还需取货 = min(总数 - 备货区 - 我的背包, 仓库数量)
            int count = isPickupMode
                ? (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.pickupMissing(
                    entry.getCountTotal(), entry.getStagingCount(), entry.getCountAvailable(), entry.getWarehouseCount())
                : entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = GuiBase.TXT_RED + MaterialListBase.getFormattedCountStringHud(count, entry.getStack().getMaxStackSize(), shulkerBoxAbbr) + GuiBase.TXT_RST;
            maxCountLength = Math.max(maxCountLength, font.width(strCount));
            // 计算仓库小字宽度
            if (isPickupMode && entry.getWarehouseCount() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", entry.getWarehouseCount());
                maxWarehouseTextLength = Math.max(maxWarehouseTextLength, font.width(whText));
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
        int y = posY + 12;

        for (int i = 0; i < size; ++i) {
            drawContext.item(list.get(i).getStack(), x, y);
            y += lineHeight;
            // 取货模式下仓库小字占位
            if (isPickupMode && list.get(i).getWarehouseCount() > 0) {
                y += warehouseLineHeight;
            }
        }

        // 标题：取货模式时显示"材料清单 · 取货模式"
        String titleKey = isPickupMode ? "syncmaterial.gui.title.material_list_pickup" : "syncmaterial.gui.title.material_list";
        String title = GuiBase.TXT_BOLD + fi.dy.masa.malilib.util.StringUtils.translate(titleKey) + GuiBase.TXT_RST;
        drawContext.text(font, title, posX + 2, posY + 2, textColor, false);

        x = posX + 18;
        y = posY + 16;

        for (int i = 0; i < size; ++i) {
            MaterialListEntry entry = list.get(i);
            String text = entry.getStack().getHoverName().getString();
            // 取货模式：还需取货 = min(总数 - 备货区 - 我的背包, 仓库数量)
            int count = isPickupMode
                ? (int) net.syncmaterial.syncmaterial.api.ProgressFormulas.pickupMissing(
                    entry.getCountTotal(), entry.getStagingCount(), entry.getCountAvailable(), entry.getWarehouseCount())
                : entry.getCountMissing();
            if (count < 0) count = 0;
            String strCount = MaterialListBase.getFormattedCountStringHud(count, entry.getStack().getMaxStackSize(), shulkerBoxAbbr);
            int cntLen = font.width(strCount);
            int cntPosX = posX + maxLineLength - cntLen - 2;

            drawContext.text(font, text, x, y, textColor, false);
            drawContext.text(font, strCount, cntPosX, y, textColor, false);
            y += lineHeight;

            // 取货模式下显示仓库小字
            if (isPickupMode && entry.getWarehouseCount() > 0) {
                String whText = StringUtils.translate("syncmaterial.gui.label.warehouse_has", entry.getWarehouseCount());
                drawContext.text(font, whText, x + 4, y, 0xFFAAAAAA, false);
                y += warehouseLineHeight;
            }
        }

        if (scaled) {
            drawContext.pose().popMatrix();
        }

        return contentHeight;
    }
}
