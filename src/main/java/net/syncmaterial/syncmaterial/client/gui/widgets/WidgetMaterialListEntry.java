package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.List;
import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase.SortCriteria;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;

public class WidgetMaterialListEntry extends WidgetListEntrySortable<MaterialListEntry>
{
    private static final String[] HEADERS = new String[] {
            "litematica.gui.label.material_list.title.item",        // 物品
            "litematica.gui.label.material_list.title.total",       // 总数
            "litematica.gui.label.material_list.title.missing",     // 缺失/还需收集/取货
            "syncmaterial.gui.label.material_list.title.backpack",  // 我的背包
            "syncmaterial.gui.label.material_list.title.other",     // 其他背包
            "syncmaterial.gui.label.material_list.title.staging",   // 备货区
            "syncmaterial.gui.label.material_list.title.warehouse", // 仓库
            "syncmaterial.gui.label.material_list.title.claim" };   // 认领
    private static int maxNameLength;
    private static int maxCountLength1;  // 总数
    private static int maxCountLength2;  // 缺失/还需收集/取货
    private static int maxCountLength3;  // 我的背包
    private static int maxCountLength4;  // 其他背包
    private static int maxCountLength5;  // 备货区
    private static int maxCountLength6;  // 仓库
    private static int maxClaimLength;

    private final MaterialListBase materialList;
    private final WidgetListMaterialList listWidget;
    private final MaterialListEntry entry;
    private final String header1;
    private final String header2;
    private final String header3;
    private final String header4;
    private final String header5;
    private final String header6;
    private final String header7;
    private final String header8;
    private final String shulkerBoxAbbr;
    private final boolean isOdd;
    private final boolean isOwner;
    private final boolean claimDisabled;

    public WidgetMaterialListEntry(int x, int y, int width, int height, boolean isOdd,
            MaterialListBase materialList, MaterialListEntry entry, int listIndex, WidgetListMaterialList listWidget)
    {
        super(x, y, width, height, entry, listIndex);

        this.columnCount = 8;
        this.entry = entry;
        this.isOdd = isOdd;
        this.listWidget = listWidget;
        this.materialList = materialList;
        this.isOwner = listWidget.getGui().isOwner();
        boolean allowSelfClaim = materialList instanceof SyncMaterialList syncList && syncList.isAllowSelfClaim();
        boolean isCollaborating = materialList instanceof SyncMaterialList sl && this.entry != null && sl.isCollaborating(this.entry);
        this.claimDisabled = !isCollaborating && !allowSelfClaim && !this.isOwner;
        this.shulkerBoxAbbr = StringUtils.translate("litematica.gui.label.material_list.abbr.shulker_box");

        if (this.entry != null)
        {
            this.header1 = null;
            this.header2 = null;
            this.header3 = null;
            this.header4 = null;
            this.header5 = null;
            this.header6 = null;
            this.header7 = null;
            this.header8 = null;
        }
        else
        {
            this.header1 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]) + GuiBase.TXT_RST;
            this.header2 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]) + GuiBase.TXT_RST;
            // 取货模式切换列头：还需收集 / 还需取货
            String missingKey = listWidget.getGui().isPickupMode() ? "syncmaterial.gui.label.material_list.title.pickup_needed" : HEADERS[2];
            this.header3 = GuiBase.TXT_BOLD + StringUtils.translate(missingKey) + GuiBase.TXT_RST;
            this.header4 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]) + GuiBase.TXT_RST;
            this.header5 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[4]) + GuiBase.TXT_RST;
            this.header6 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[5]) + GuiBase.TXT_RST;
            this.header7 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[6]) + GuiBase.TXT_RST;
            this.header8 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[7]) + GuiBase.TXT_RST;
        }

        int posX = x + width;
        int posY = y + 1;

        posX = this.createButtonGeneric(posX, posY, ButtonListener.ButtonType.CLAIM);
    }

    private int createButtonGeneric(int xRight, int y, ButtonListener.ButtonType type)
    {
        String label = type.getDisplayName();
        boolean disabled = false;
        if (type == ButtonListener.ButtonType.CLAIM && this.entry != null && this.materialList instanceof SyncMaterialList syncList) {
            if (syncList.isCollaborating(this.entry)) {
                label = StringUtils.translate("syncmaterial.gui.button.leave_collaboration");
            } else if (!syncList.isAllowSelfClaim() && !syncList.isOwner()) {
                label = StringUtils.translate("syncmaterial.gui.button.join_collaboration");
                disabled = true;
            } else {
                label = StringUtils.translate("syncmaterial.gui.button.join_collaboration");
            }
        }
        ButtonListener listener = new ButtonListener(type, this.materialList, this.entry, this.listWidget);
        ButtonGeneric btn = this.addButton(new ButtonGeneric(xRight, y, -1, true, label), listener);
        if (disabled) {
            btn.setEnabled(false);
        }
        return btn.getX();
    }

    public static void setMaxNameLength(List<MaterialListEntry> materials, int multiplier)
    {
        maxNameLength   = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]) + GuiBase.TXT_RST);
        maxCountLength1 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]) + GuiBase.TXT_RST);
        maxCountLength2 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]) + GuiBase.TXT_RST);
        maxCountLength3 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]) + GuiBase.TXT_RST);
        maxCountLength4 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[4]) + GuiBase.TXT_RST);
        maxCountLength5 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[5]) + GuiBase.TXT_RST);
        maxCountLength6 = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[6]) + GuiBase.TXT_RST);
        maxClaimLength  = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[7]) + GuiBase.TXT_RST);

        for (MaterialListEntry entry : materials)
        {
            int countTotal = entry.getCountTotal() * multiplier;
            int countMissing = multiplier == 1 ? entry.getCountMissing() : countTotal;
            // 取货模式：还需取货 = 总数 - 备货区 - 我的背包（列宽计算）
            if (GuiMaterialList.isPickupModeStatic() && multiplier == 1) {
                countMissing = Math.max(0, entry.getCountTotal() - entry.getStagingCount() - entry.getCountAvailable());
            }

            maxNameLength   = Math.max(maxNameLength,   StringUtils.getStringWidth(entry.getStack().getName().getString()));
            maxCountLength1 = Math.max(maxCountLength1, StringUtils.getStringWidth(String.valueOf(countTotal)));
            maxCountLength2 = Math.max(maxCountLength2, StringUtils.getStringWidth(String.valueOf(countMissing)));
            maxCountLength3 = Math.max(maxCountLength3, StringUtils.getStringWidth(String.valueOf(entry.getCountAvailable())));
            maxCountLength4 = Math.max(maxCountLength4, StringUtils.getStringWidth(String.valueOf(entry.getOtherPlayersCount())));
            maxCountLength5 = Math.max(maxCountLength5, StringUtils.getStringWidth(String.valueOf(entry.getStagingCount())));
            maxCountLength6 = Math.max(maxCountLength6, StringUtils.getStringWidth(String.valueOf(entry.getWarehouseCount())));
            maxClaimLength  = Math.max(maxClaimLength, StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.label.unclaimed")));
        }
    }

    @Override
    public boolean canSelectAt(int mouseX, int mouseY, int mouseButton)
    {
        return false;
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.materialList.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.materialList.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        // Phase 4: 负责人视角复选框占 16px
        int checkboxOffset = this.isOwner ? 16 : 0;
        int x1 = this.x + 4 + checkboxOffset;
        int x2 = x1 + maxNameLength + 40; // item icon plus offset
        int x3 = x2 + maxCountLength1 + 20;
        int x4 = x3 + maxCountLength2 + 20;
        int x5 = x4 + maxCountLength3 + 20;
        int x6 = x5 + maxCountLength4 + 20;
        int x7 = x6 + maxCountLength5 + 20;
        int x8 = x7 + maxCountLength6 + 20;

        return switch (column)
        {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x4;
            case 4 -> x5;
            case 5 -> x6;
            case 6 -> x7;
            case 7 -> x8;
            case 8 -> x8 + maxClaimLength + 20;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton)
    {
        // Phase 4: 负责人复选框点击
        if (this.isOwner && this.entry != null) {
            int cbX = this.x + 2;
            int cbY = this.y + 7;
            if (mouseX >= cbX && mouseX <= cbX + 12 && mouseY >= cbY && mouseY <= cbY + 12) {
                var selected = this.listWidget.getGui().getSelectedMaterialIds();
                int id = this.entry.getDatabaseId();
                if (selected.contains(id)) {
                    selected.remove(Integer.valueOf(id));
                } else {
                    selected.add(id);
                }
                return true;
            }
        }

        if (super.onMouseClickedImpl(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        if (this.entry != null)
        {
            return false;
        }

        int column = this.getMouseOverColumn(mouseX, mouseY);

        switch (column)
        {
            case 0:
                this.materialList.setSortCriteria(SortCriteria.NAME);
                break;
            case 1:
                this.materialList.setSortCriteria(SortCriteria.COUNT_TOTAL);
                break;
            case 2:
                this.materialList.setSortCriteria(SortCriteria.COUNT_MISSING);
                break;
            case 3:
                this.materialList.setSortCriteria(SortCriteria.COUNT_AVAILABLE);
                break;
            case 4:
                this.materialList.setSortCriteria(SortCriteria.COUNT_OTHER);
                break;
            case 5:
                this.materialList.setSortCriteria(SortCriteria.COUNT_STAGING);
                break;
            case 6:
                this.materialList.setSortCriteria(SortCriteria.COUNT_WAREHOUSE);
                break;
            case 7:
                this.materialList.setSortCriteria(SortCriteria.COUNT_CLAIM);
                break;
            default:
                return false;
        }

        // Re-create the widgets
        this.listWidget.refreshEntries();

        return true;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        // Draw a lighter background for the hovered and the selected entry
        if (this.header1 == null && (selected || this.isMouseOver(mouseX, mouseY)))
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
        }
        else if (this.isOdd)
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
        }
        // Draw a slightly lighter background for even entries
        else
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int x4 = this.getColumnPosX(3);
        int x5 = this.getColumnPosX(4);
        int x6 = this.getColumnPosX(5);
        int y = this.y + 7;
        int color = 0xFFFFFFFF;

        // Phase 4: 负责人视角复选框
        if (this.isOwner && this.entry != null) {
            boolean checked = this.listWidget.getGui().getSelectedMaterialIds().contains(this.entry.getDatabaseId());
            int cbX = this.x + 2;
            int cbY = this.y + 7;
            drawContext.fill(cbX, cbY, cbX + 12, cbY + 12, 0xFF333333);
            drawContext.fill(cbX + 1, cbY + 1, cbX + 11, cbY + 11, checked ? 0xFF00AA00 : 0xFF222222);
            if (checked) {
                this.drawString(drawContext, cbX + 1, cbY, 0xFFFFFF, "✓");
            }
        }

        if (this.header1 != null)
        {
            if (this.listWidget.getSearchBarWidget() == null || this.listWidget.getSearchBarWidget().isSearchOpen() == false)
            {
                MaterialListBase.SortCriteria currentSort = this.materialList.getSortCriteria();
                boolean reverse = this.materialList.getSortInReverse();
                String indicator = reverse ? "▲" : "▼";
                int grayColor = 0xFFAAAAAA;

                this.drawString(drawContext, x1, y, color, this.header1);
                if (currentSort == MaterialListBase.SortCriteria.NAME) {
                    int indicatorX = x1 + this.getStringWidth(this.header1) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                this.drawString(drawContext, x2, y, color, this.header2);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_TOTAL) {
                    int indicatorX = x2 + this.getStringWidth(this.header2) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                this.drawString(drawContext, x3, y, color, this.header3);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_MISSING) {
                    int indicatorX = x3 + this.getStringWidth(this.header3) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                this.drawString(drawContext, x4, y, color, this.header4);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_AVAILABLE) {
                    int indicatorX = x4 + this.getStringWidth(this.header4) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                this.drawString(drawContext, x5, y, color, this.header5);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_OTHER) {
                    int indicatorX = x5 + this.getStringWidth(this.header5) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                this.drawString(drawContext, x6, y, color, this.header6);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_STAGING) {
                    int indicatorX = x6 + this.getStringWidth(this.header6) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                // 仓库列
                int x7 = this.getColumnPosX(6);
                this.drawString(drawContext, x7, y, color, this.header7);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_WAREHOUSE) {
                    int indicatorX = x7 + this.getStringWidth(this.header7) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }

                // 认领列
                int x8 = this.getColumnPosX(7);
                this.drawString(drawContext, x8, y, color, this.header8);
                if (currentSort == MaterialListBase.SortCriteria.COUNT_CLAIM) {
                    int indicatorX = x8 + this.getStringWidth(this.header8) + 2;
                    this.drawString(drawContext, indicatorX, y, grayColor, indicator);
                }
            }
        }
        else if (this.entry != null)
        {
            int multiplier = this.materialList.getMultiplier();
            int countTotal = this.entry.getCountTotal() * multiplier;
            int countMissing = multiplier == 1 ? this.entry.getCountMissing() : countTotal;
            // 取货模式：还需取货 = 总数 - 备货区 - 我的背包
            if (this.listWidget.getGui().isPickupMode() && multiplier == 1) {
                countMissing = Math.max(0, countTotal - this.entry.getStagingCount() - this.entry.getCountAvailable());
            }
            int countAvailable = this.entry.getCountAvailable();
            int otherPlayersCount = this.entry.getOtherPlayersCount();
            int stagingCount = this.entry.getStagingCount();
            String green = GuiBase.TXT_GREEN;
            String gold = GuiBase.TXT_GOLD;
            String red = GuiBase.TXT_RED;
            String pre;
            this.drawString(drawContext, x1 + 20, y, color, this.entry.getStack().getName().getString());

            // 总数
            this.drawString(drawContext, x2, y, color, String.valueOf(countTotal));

            // 缺失（新公式：总计 - 备货区 - 所有背包）
            pre = countMissing == 0 ? green : (countAvailable >= countMissing ? gold : red);
            this.drawString(drawContext, x3, y, color, pre + String.valueOf(countMissing));

            // 我的背包
            pre = countAvailable > 0 ? green : red;
            this.drawString(drawContext, x4, y, color, pre + String.valueOf(countAvailable));

            // 其他背包
            this.drawString(drawContext, x5, y, otherPlayersCount > 0 ? 0xFF55FF55 : 0xFFAAAAAA, String.valueOf(otherPlayersCount));

            // 备货区
            this.drawString(drawContext, x6, y, stagingCount > 0 ? 0xFFFFAA00 : 0xFFAAAAAA, String.valueOf(stagingCount));

            // 仓库
            int warehouseCount = this.entry.getWarehouseCount();
            int x7 = this.getColumnPosX(6);
            this.drawString(drawContext, x7, y, warehouseCount > 0 ? 0xFF55AAFF : 0xFFAAAAAA, String.valueOf(warehouseCount));

            // 认领状态 — 统一显示参与者名称
            int x8 = this.getColumnPosX(7);
            var participants = this.entry.getParticipants();
            if (!participants.isEmpty()) {
                String names = participants.stream()
                    .map(p -> p.playerName())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
                this.drawString(drawContext, x8, y, 0xFF55FF55, names);
            } else {
                this.drawString(drawContext, x8, y, 0xFF888888, StringUtils.translate("syncmaterial.gui.label.unclaimed"));
            }

            y = this.y + 3;
            RenderUtils.drawRect(drawContext, x1, y, 16, 16, 0x20FFFFFF); // light background for the item
            drawContext.drawItem(this.entry.getStack(), x1, y);

            // 进度条止于行高亮框右边缘（this.x + this.width），起点 x1 已含复选框偏移
            int progressWidth = this.x + this.width - x1;
            this.renderProgressBar(drawContext, x1, this.y + 22, progressWidth);

            super.render(drawContext, mouseX, mouseY, selected);
        }
    }

    private void renderProgressBar(DrawContext drawContext, int x, int y, int totalWidth) {
        int countTotal = this.entry.getCountTotal();
        int stagingCount = this.entry.getStagingCount();
        int warehouseCount = this.entry.getWarehouseCount();
        var participants = this.entry.getParticipants();

        if (countTotal <= 0) return;

        int collected = stagingCount + warehouseCount;
        for (var p : participants) collected += p.count();
        int remaining = Math.max(0, countTotal - collected);
        boolean isComplete = collected >= countTotal;
        boolean isUnclaimed = collected == 0 && participants.isEmpty();

        int barHeight = 4;
        int barY = y;

        if (isComplete) {
            RenderUtils.drawRect(drawContext, x, barY, totalWidth, barHeight, 0xFF006600);
            this.drawString(drawContext, x, barY + barHeight + 1, 0xFF00CC00, StringUtils.translate("syncmaterial.gui.label.progress_done"));
            return;
        }

        RenderUtils.drawRect(drawContext, x, barY, totalWidth, barHeight, 0xFF333333);

        if (isUnclaimed) {
            this.drawString(drawContext, x, barY + barHeight + 1, 0xFF888888, StringUtils.translate("syncmaterial.gui.label.unclaimed"));
            return;
        }

        int barX = x;
        if (stagingCount > 0) {
            int w = Math.max(1, (int)((double)stagingCount / countTotal * totalWidth));
            RenderUtils.drawRect(drawContext, barX, barY, w, barHeight, 0xFFFFAA00);
            barX += w;
        }
        if (warehouseCount > 0) {
            int w = Math.max(1, (int)((double)warehouseCount / countTotal * totalWidth));
            RenderUtils.drawRect(drawContext, barX, barY, w, barHeight, 0xFF55AAFF);
            barX += w;
        }

        int[] playerColors = {0xFF00AA00, 0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAA5500};
        int otherCount = 0;
        for (int i = 0; i < participants.size(); i++) {
            var p = participants.get(i);
            if (i < 5) {
                int w = Math.max(1, (int)((double)p.count() / countTotal * totalWidth));
                RenderUtils.drawRect(drawContext, barX, barY, w, barHeight, playerColors[i]);
                barX += w;
            } else {
                otherCount += p.count();
            }
        }
        if (otherCount > 0) {
            int w = Math.max(1, (int)((double)otherCount / countTotal * totalWidth));
            RenderUtils.drawRect(drawContext, barX, barY, w, barHeight, 0xFF666666);
            barX += w;
        }

        if (remaining > 0 && barX < x + totalWidth) {
            int w = x + totalWidth - barX;
            RenderUtils.drawRect(drawContext, barX, barY, w, barHeight, 0xFF444444);
        }

        // 文字描述 — 分段着色，裁剪到进度条宽度内
        int textX = x;
        int textY = barY + barHeight + 1;
        int textMaxX = x + totalWidth;

        if (stagingCount > 0) {
            String s = StringUtils.translate("syncmaterial.gui.label.progress_staging", stagingCount);
            if (textX + this.getStringWidth(s) < textMaxX) {
                this.drawString(drawContext, textX, textY, 0xFFFFAA00, s);
                textX += this.getStringWidth(s) + 6;
            }
        }
        if (warehouseCount > 0) {
            String s = StringUtils.translate("syncmaterial.gui.label.progress_warehouse", warehouseCount);
            if (textX + this.getStringWidth(s) < textMaxX) {
                this.drawString(drawContext, textX, textY, 0xFF55AAFF, s);
                textX += this.getStringWidth(s) + 6;
            }
        }
        for (int i = 0; i < Math.min(participants.size(), 5); i++) {
            var p = participants.get(i);
            int c = i < playerColors.length ? playerColors[i] : 0xFF666666;
            String s = p.playerName() + ":" + p.count();
            if (textX + this.getStringWidth(s) < textMaxX) {
                this.drawString(drawContext, textX, textY, c, s);
                textX += this.getStringWidth(s) + 6;
            }
        }
        if (participants.size() > 5) {
            String s = StringUtils.translate("syncmaterial.gui.label.progress_other", otherCount);
            if (textX + this.getStringWidth(s) < textMaxX) {
                this.drawString(drawContext, textX, textY, 0xFF666666, s);
                textX += this.getStringWidth(s) + 6;
            }
        }
        if (remaining > 0) {
            String s = StringUtils.translate("syncmaterial.gui.label.progress_remaining", remaining);
            if (textX + this.getStringWidth(s) < textMaxX) {
                this.drawString(drawContext, textX, textY, 0xFF444444, s);
            }
        }
    }

    @Override
    public void postRenderHovered(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        if (this.entry != null)
        {
            Matrix3x2fStack matrixStack = drawContext.getMatrices();
            matrixStack.translate(0, 0);

            String header1 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]);
            String header2 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]);
            String header3 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]);

            ItemStack stack = this.entry.getStack();
            String stackName = stack.getName().getString();
            int multiplier = this.materialList.getMultiplier();
            int total = this.entry.getCountTotal() * multiplier;
            int missing = multiplier == 1 ? this.entry.getCountMissing() : total;
            String strCountTotal = MaterialListBase.getFormattedCountString(total, stack.getMaxCount(), this.shulkerBoxAbbr);
            String strCountMissing = MaterialListBase.getFormattedCountString(missing, stack.getMaxCount(), this.shulkerBoxAbbr);

            var participants = this.entry.getParticipants();
            int stagingCount = this.entry.getStagingCount();
            int warehouseCount = this.entry.getWarehouseCount();
            int extraLines = 0;
            if (stagingCount > 0 || warehouseCount > 0 || !participants.isEmpty()) {
                extraLines = 1 + (warehouseCount > 0 ? 1 : 0) + Math.min(participants.size(), 6);
            }

            int w1 = Math.max(this.getStringWidth(header1), Math.max(this.getStringWidth(header2), this.getStringWidth(header3)));
            int w2 = Math.max(this.getStringWidth(stackName) + 20, Math.max(this.getStringWidth(strCountTotal), this.getStringWidth(strCountMissing)));
            int totalWidth = w1 + w2 + 60;
            int boxHeight = 60 + extraLines * 14;

            int x = mouseX + 10;
            int y = mouseY - 10;

            if (x + totalWidth - 20 >= this.width)
            {
                x -= totalWidth + 20;
            }

            int x1 = x + 10;
            int x2 = x1 + w1 + 20;

            fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(drawContext, x + 1, y + 1, totalWidth - 2, boxHeight - 2);
            RenderUtils.drawOutlinedBox(drawContext, x, y, totalWidth, boxHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
            y += 6;
            int y1 = y;
            y += 4;

            this.drawString(drawContext, x1, y, 0xFFFFFFFF, header1);
            this.drawString(drawContext, x2 + 20, y, 0xFFFFFFFF, stackName);
            y += 16;

            this.drawString(drawContext, x1, y, 0xFFFFFFFF, header2);
            this.drawString(drawContext, x2, y, 0xFFFFFFFF, strCountTotal);
            y += 16;

            this.drawString(drawContext, x1, y, 0xFFFFFFFF, header3);
            this.drawString(drawContext, x2, y, 0xFFFFFFFF, strCountMissing);

            RenderUtils.drawRect(drawContext, x2, y1, 16, 16, 0x20FFFFFF);
            drawContext.drawItem(stack, x2, y1);

            y += 18;
            if (stagingCount > 0 || warehouseCount > 0 || !participants.isEmpty()) {
                if (stagingCount > 0) {
                    this.drawString(drawContext, x1, y, 0xFFAAAAAA, GuiBase.TXT_BOLD + StringUtils.translate("syncmaterial.gui.label.progress_staging_hover", stagingCount));
                    y += 14;
                }
                if (warehouseCount > 0) {
                    this.drawString(drawContext, x1, y, 0xFF55AAFF, GuiBase.TXT_BOLD + StringUtils.translate("syncmaterial.gui.label.progress_warehouse_hover", warehouseCount));
                    y += 14;
                }
                for (int i = 0; i < Math.min(participants.size(), 5); i++) {
                    var p = participants.get(i);
                    this.drawString(drawContext, x1, y, 0xFFAAAAAA, p.playerName() + ": " + p.count());
                    y += 14;
                }
                if (participants.size() > 5) {
                    int otherCount = 0;
                    for (int i = 5; i < participants.size(); i++) otherCount += participants.get(i).count();
                    this.drawString(drawContext, x1, y, 0xFFAAAAAA, StringUtils.translate("syncmaterial.gui.label.progress_other_hover", otherCount));
                    y += 14;
                }
            }
        }
        // 禁用按钮悬停提示
        if (this.claimDisabled && mouseX >= this.x + this.width - 80) {
            java.util.List<String> lines = java.util.List.of(StringUtils.translate("syncmaterial.gui.tooltip.self_claim_disabled"));
            fi.dy.masa.malilib.render.RenderUtils.drawHoverText(drawContext, mouseX, mouseY, lines);
        }
    }

    static class ButtonListener implements IButtonActionListener
    {
        private final ButtonType type;
        private final MaterialListBase materialList;
        private final WidgetListMaterialList listWidget;
        private final MaterialListEntry entry;

        public ButtonListener(ButtonType type, MaterialListBase materialList, MaterialListEntry entry, WidgetListMaterialList listWidget)
        {
            this.type = type;
            this.materialList = materialList;
            this.listWidget = listWidget;
            this.entry = entry;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.entry == null) return;

            if (this.type == ButtonType.CLAIM)
            {
                this.materialList.claimEntry(this.entry);
            }
        }

        public enum ButtonType
        {
            CLAIM   ("syncmaterial.gui.button.material_list.claim");

            private final String translationKey;

            private ButtonType(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }
}
