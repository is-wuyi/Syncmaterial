
package net.syncmaterial.syncmaterial.client.gui.widgets;

//? if >=26.2 {
import net.minecraft.client.input.MouseButtonEvent;
//?}


import java.util.List;
import org.joml.Matrix3x2fStack;

//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.world.item.ItemStack;

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
import net.syncmaterial.syncmaterial.client.gui.MaterialListLayout;
import net.syncmaterial.syncmaterial.client.gui.MaterialRowStatus;
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
    private final MaterialListLayout layout;

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
        this.claimDisabled = !isCollaborating && !allowSelfClaim && !this.isOwner
                || materialList instanceof SyncMaterialList pending && this.entry != null
                && pending.isClaimPending(this.entry.getDatabaseId());
        this.layout = this.listWidget.rowLayout(width);
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
            this.header3 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]) + GuiBase.TXT_RST;
            this.header4 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]) + GuiBase.TXT_RST;
            this.header5 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[4]) + GuiBase.TXT_RST;
            this.header6 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[5]) + GuiBase.TXT_RST;
            this.header7 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[6]) + GuiBase.TXT_RST;
            this.header8 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[7]) + GuiBase.TXT_RST;
        }

        if (this.entry != null) {
            this.createButtonGeneric(y + 1, ButtonListener.ButtonType.CLAIM);
        }
    }

    private void createButtonGeneric(int y, ButtonListener.ButtonType type)
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
        if (type == ButtonListener.ButtonType.CLAIM && this.entry != null
                && this.materialList instanceof SyncMaterialList pending
                && pending.isClaimPending(this.entry.getDatabaseId())) {
            label = StringUtils.translate("syncmaterial.gui.button.pending");
            disabled = true;
        }
        ButtonListener listener = new ButtonListener(type, this.materialList, this.entry, this.listWidget);
        ButtonGeneric btn = this.addButton(new ButtonGeneric(
                this.x + this.layout.action().x(), y, this.layout.action().width(), 20, label), listener);
        if (disabled) {
            btn.setEnabled(false);
        }
    }

    static MaterialListLayout measureLayout(int width, MaterialListBase list, boolean owner) {
        java.util.Map<MaterialListLayout.ColumnKey, String> samples =
                new java.util.EnumMap<>(MaterialListLayout.ColumnKey.class);
        for (var key : MaterialListLayout.ColumnKey.values()) {
            samples.put(key, GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[key.ordinal()])
                    + GuiBase.TXT_RST + " ▼");
        }
        if (list != null) {
            for (MaterialListEntry entry : list.getMaterialsAll()) {
                for (var key : MaterialListLayout.ColumnKey.values()) {
                    String candidate = sample(key, entry, list);
                    if (StringUtils.getStringWidth(candidate) > StringUtils.getStringWidth(samples.get(key))) {
                        samples.put(key, candidate);
                    }
                }
            }
        }
        String action = java.util.stream.Stream.of(
                        "syncmaterial.gui.button.join_collaboration",
                        "syncmaterial.gui.button.leave_collaboration",
                        "syncmaterial.gui.button.pending")
                .map(StringUtils::translate)
                .max(java.util.Comparator.comparingInt(StringUtils::getStringWidth)).orElse("");
        return MaterialListLayout.compute(width, StringUtils::getStringWidth, samples, action, owner ? 16 : 0);
    }

    private static String sample(MaterialListLayout.ColumnKey key, MaterialListEntry entry, MaterialListBase list) {
        return switch (key) {
            case ITEM -> entry.getStack().getHoverName().getString();
            case TOTAL -> list.displayedTotal(entry).toString();
            case MISSING -> list.displayedMissing(entry).toString();
            case MINE -> entry.observedCountText(entry.getCountAvailable());
            case OTHERS -> entry.observedCountText(entry.getOtherPlayersCount());
            case STAGING -> String.valueOf(entry.getStagingCount());
            case WAREHOUSE -> String.valueOf(entry.getWarehouseCount());
            case CLAIM -> entry.getParticipants().stream().map(MaterialListEntry.ParticipantData::playerName)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseGet(() -> StringUtils.translate("syncmaterial.gui.label.unclaimed"));
        };
    }

    @Override
    //? if >=26.2 {
    public boolean canSelectAt(MouseButtonEvent event)
    //?} else {
    /*public boolean canSelectAt(int mouseX, int mouseY, int mouseButton)
    *///?}
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
        int offset = this.listWidget.horizontalOffset();
        if (column == 8) return this.x + this.layout.dataViewportRight();
        var key = MaterialListLayout.ColumnKey.values()[column];
        return this.x + this.layout.column(key).x() - offset;
    }

    @Override
    protected int getMouseOverColumn(int mouseX, int mouseY) {
        int localX = mouseX - this.x;
        if (mouseY < this.y || mouseY >= this.y + this.height
                || localX < 0 || localX >= this.layout.dataViewportRight()) return -1;
        return this.layout.columnAt(localX + this.listWidget.horizontalOffset())
                .map(column -> column.key().ordinal()).orElse(-1);
    }

    private int textX(MaterialListLayout.ColumnKey key, String text) {
        return this.x + this.layout.column(key).textX(text, StringUtils::getStringWidth)
                - this.listWidget.horizontalOffset();
    }

    @Override
    //? if >=26.2 {
    protected boolean onMouseClickedImpl(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick)
    //?} else {
    /*protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        //?} else {
        /*boolean isDoubleClick = false;
        *///?}
        if (mouseX < this.x || mouseX >= this.x + this.width
                || mouseY < this.y || mouseY >= this.y + this.height) return false;
        if (this.entry != null && mouseX >= this.x + this.layout.dataViewportRight()) {
            //? if >=26.2 {
            return super.onMouseClickedImpl(event, isDoubleClick);
            //?} else {
            /*return super.onMouseClickedImpl(mouseX, mouseY, mouseButton);
            *///?}
        }
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
                this.listWidget.getGui().initGui();
                return true;
            }
        }

        //? if >=26.2 {
        if (super.onMouseClickedImpl(event, isDoubleClick))
        //?} else {
        /*if (super.onMouseClickedImpl(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }

        if (this.entry != null)
        {
            return false;
        }

        int column = this.getMouseOverColumn(mouseX, mouseY);

        SortCriteria criteria = MaterialListBase.criteriaForColumn(column);
        if (criteria == null)
        {
            return false;
        }
        this.materialList.setSortCriteria(criteria);

        // A new sorting intention starts at the top; data refreshes retain the material-ID anchor.
        this.listWidget.refreshFromBeginning();

        return true;
    }

    @Override
    //? if >=26.2 {
    public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
    //?} else {
    /*public void render(GuiGraphics drawContext, int mouseX, int mouseY, boolean selected)
    *///?}
    {
        drawContext.enableScissor(this.x, this.y, this.x + this.layout.dataViewportRight(), this.y + this.height);
        try {
            this.renderRow(drawContext, mouseX, mouseY, selected);
        } finally {
            drawContext.disableScissor();
        }
        if (this.entry != null) {
            RenderUtils.drawRect(drawContext, this.x + this.layout.dataViewportRight(), this.y,
                    this.width - this.layout.dataViewportRight(), this.height,
                    this.isOdd ? 0xFF181818 : 0xFF282828);
            drawContext.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
            try {
                super.render(drawContext, mouseX, mouseY, selected);
            } finally {
                drawContext.disableScissor();
            }
        }
    }

    //? if >=26.2 {
    private void renderRow(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
    //?} else {
    /*private void renderRow(GuiGraphics drawContext, int mouseX, int mouseY, boolean selected)
    *///?}
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
            var countTotal = this.materialList.displayedTotal(this.entry);
            var countMissing = this.materialList.displayedMissing(this.entry);
            long countAvailable = this.entry.getCountAvailable();
            long otherPlayersCount = this.entry.getOtherPlayersCount();
            long stagingCount = this.entry.getStagingCount();
            String green = GuiBase.TXT_GREEN;
            String gold = GuiBase.TXT_GOLD;
            String red = GuiBase.TXT_RED;
            String pre;
            String itemName = this.layout.column(MaterialListLayout.ColumnKey.ITEM)
                    .clip(this.entry.getStack().getHoverName().getString(), StringUtils::getStringWidth).text();
            this.drawString(drawContext, x1 + 20, y, color, itemName);

            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.TOTAL, String.valueOf(countTotal)),
                    y, color, String.valueOf(countTotal));

            // 缺失（新公式：总计 - 备货区 - 所有背包）
            if (countMissing.signum() == 0) {
                pre = this.materialList.isPreparationKnown() ? green : gold;
            } else {
                pre = java.math.BigInteger.valueOf(countAvailable).compareTo(countMissing) >= 0 ? gold : red;
            }
            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.MISSING, String.valueOf(countMissing)),
                    y, color, pre + String.valueOf(countMissing));

            String mineText = this.entry.observedCountText(countAvailable);
            String othersText = this.entry.observedCountText(otherPlayersCount);
            pre = countAvailable > 0 ? green : red;
            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.MINE, mineText),
                    y, color, pre + mineText);

            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.OTHERS, othersText),
                    y, otherPlayersCount > 0 ? 0xFF55FF55 : 0xFFAAAAAA, othersText);

            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.STAGING, String.valueOf(stagingCount)),
                    y, stagingCount > 0 ? 0xFFFFAA00 : 0xFFAAAAAA, String.valueOf(stagingCount));

            long warehouseCount = this.entry.getWarehouseCount();
            this.drawString(drawContext, textX(MaterialListLayout.ColumnKey.WAREHOUSE, String.valueOf(warehouseCount)),
                    y, warehouseCount > 0 ? 0xFF55AAFF : 0xFFAAAAAA, String.valueOf(warehouseCount));

            // 认领状态 — 统一显示参与者名称
            int x8 = this.getColumnPosX(7);
            var participants = this.entry.getParticipants();
            if (!participants.isEmpty()) {
                String names = participants.stream()
                    .map(p -> p.playerName())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
                this.drawString(drawContext, x8, y, 0xFF55FF55,
                        this.layout.column(MaterialListLayout.ColumnKey.CLAIM)
                                .clip(names, StringUtils::getStringWidth).text());
            } else {
                this.drawString(drawContext, x8, y, 0xFF888888, StringUtils.translate("syncmaterial.gui.label.unclaimed"));
            }

            y = this.y + 3;
            RenderUtils.drawRect(drawContext, x1, y, 16, 16, 0x20FFFFFF); // light background for the item
            drawContext.renderItem(this.entry.getStack(), x1, y);

            this.renderSourceBar(drawContext);
        }
    }

    //? if >=26.2 {
    private void renderSourceBar(GuiContext drawContext) {
    //?} else {
    /*private void renderSourceBar(GuiGraphics drawContext) {
    *///?}
        var view = this.entry.progressView(this.materialList);
        var status = MaterialRowStatus.of(view);
        var bar = this.layout.sourceBar();
        int textY = this.y + 18;
        String summary = MaterialListLayout.clipText(StringUtils.translate(status.translationKey()),
                bar.width(), StringUtils::getStringWidth).text();
        this.drawString(drawContext, this.x + bar.x() - this.listWidget.horizontalOffset(), textY,
                view.preparationKnown() ? 0xFFB0B0B0 : 0xFF888888, summary);

        java.util.List<Long> participants = this.entry.getParticipants().stream()
                .map(MaterialListEntry.ParticipantData::count).toList();
        var segments = this.layout.sourceSegments(view.required(), view.staging(), view.warehouse(),
                participants, view.teamMissing());
        int y = MaterialListLayout.sourceBarY(this.y);
        RenderUtils.drawRect(drawContext, this.x + bar.x() - this.listWidget.horizontalOffset(), y, bar.width(),
                MaterialListLayout.SOURCE_BAR_HEIGHT, 0xFF222222);
        int[] participantColors = {0xFF00AA00, 0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAA5500, 0xFF666666};
        for (var segment : segments) {
            int color = !view.preparationKnown()
                    ? (segment.kind() == MaterialListLayout.SourceKind.MISSING ? 0xFF333333 : 0xFF666666)
                    : switch (segment.kind()) {
                        case STAGING -> 0xFFFFAA00;
                        case WAREHOUSE -> 0xFF55AAFF;
                        case PARTICIPANT -> participantColors[segment.participantIndex() % participantColors.length];
                        case MISSING -> 0xFF444444;
                    };
            RenderUtils.drawRect(drawContext, this.x + segment.x() - this.listWidget.horizontalOffset(), y, segment.width(),
                    MaterialListLayout.SOURCE_BAR_HEIGHT, color);
        }
    }

    @Override
    //? if >=26.2 {
    public void postRenderHovered(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
    //?} else {
    /*public void postRenderHovered(GuiGraphics drawContext, int mouseX, int mouseY, boolean selected)
    *///?}
    {
        if (this.entry != null)
        {
            Matrix3x2fStack matrixStack = drawContext.pose();
            matrixStack.translate(0, 0);

            String header1 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]);
            String header2 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]);
            String missingKey = HEADERS[2];
            String header3 = GuiBase.TXT_BOLD + StringUtils.translate(missingKey);

            ItemStack stack = this.entry.getStack();
            String stackName = stack.getHoverName().getString();
            var total = this.materialList.displayedTotal(this.entry);
            var missing = this.materialList.displayedMissing(this.entry);
            String strCountTotal = MaterialListBase.getFormattedCountString(total, stack.getMaxStackSize(), this.shulkerBoxAbbr);
            String strCountMissing = MaterialListBase.getFormattedCountString(missing, stack.getMaxStackSize(), this.shulkerBoxAbbr);

            var participants = this.entry.getParticipants();
            long stagingCount = this.entry.getStagingCount();
            long warehouseCount = this.entry.getWarehouseCount();
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
            drawContext.renderItem(stack, x2, y1);

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
                    java.math.BigInteger otherCount = participants.subList(5, participants.size()).stream()
                            .map(participant -> java.math.BigInteger.valueOf(participant.count()))
                            .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add);
                    this.drawString(drawContext, x1, y, 0xFFAAAAAA, StringUtils.translate("syncmaterial.gui.label.progress_other_hover", otherCount));
                    y += 14;
                }
            }
        }
        // 禁用按钮悬停提示
        if (this.claimDisabled && this.layout.action().contains(mouseX - this.x)) {
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
