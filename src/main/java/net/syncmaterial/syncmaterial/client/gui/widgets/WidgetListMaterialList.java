package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import com.google.common.collect.ImmutableList;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListLayout;
import net.syncmaterial.syncmaterial.client.gui.MaterialListSorter;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;

public class WidgetListMaterialList extends WidgetListBase<MaterialListEntry, WidgetMaterialListEntry>
{
    private final GuiMaterialList gui;
    private final MaterialListSorter sorter;
    private Integer anchorMaterialId;
    private int anchorListIndex;
    private int anchorScroll;
    private boolean restoringAnchor;
    private boolean resetAnchorPending;
    private int horizontalOffset;
    private int maxHorizontalOverflow;
    private MaterialListLayout rowLayout;
    private int originY;

    public int originY() {
        return this.originY;
    }

    public WidgetListMaterialList(int x, int y, int width, int height, GuiMaterialList parent)
    {
        super(x, y, width, height, null);

        this.browserEntryHeight = net.syncmaterial.syncmaterial.client.gui.MaterialListLayout.ROW_HEIGHT;
        this.gui = parent;
        this.originY = y;
        this.widgetSearchBar = new ViewportSearchBar(x + 2, y + 8, width - 16, 14,
                0, MaLiLibIcons.SEARCH, LeftRight.RIGHT);
        this.widgetSearchBar.setZLevel(1);
        this.sorter = new MaterialListSorter(parent.getMaterialList());
        this.shouldSortList = true;
    }

    @Override
    public void initGui() {
        var search = ((ViewportSearchBar) this.widgetSearchBar).state();
        this.widgetSearchBar = new ViewportSearchBar(this.posX + 2, this.posY + 8, this.browserWidth - 16, 14,
                0, MaLiLibIcons.SEARCH, LeftRight.RIGHT);
        ((ViewportSearchBar) this.widgetSearchBar).restore(search.searchFilter(), search.searchOpen());
        this.widgetSearchBar.setZLevel(1);
        this.rowLayout = null;
        super.initGui();
    }

    public GuiMaterialList getGui() {
        return this.gui;
    }

    @Override
    //? if >=26.2 {
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    //?} else {
    /*public void drawContents(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks)
    *///?}
    {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        rememberAnchor();

        if (maxHorizontalOverflow > 0) {
            String hint = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.horizontal_scroll");
            int available = Math.max(0, this.browserWidth - 24);
            hint = MaterialListLayout.clipText(hint, available,
                    fi.dy.masa.malilib.util.StringUtils::getStringWidth).text();
            int width = Minecraft.getInstance().font.width(hint);
            drawContext.drawString(Minecraft.getInstance().font, hint,
                    this.posX + this.browserWidth - width - 20,
                    this.posY + this.browserHeight - 10, 0xFFAAAAAA, false);
        }
        if (this.listWidgets.isEmpty()) {
            String hint = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.no_materials");
            Minecraft mc = Minecraft.getInstance();
            int textWidth = mc.font.width(hint);
            int x = this.posX + (this.browserWidth - textWidth) / 2;
            int y = this.posY + this.browserHeight / 2 - 4;
            drawContext.drawString(mc.font, hint, x, y, 0xFFAAAAAA, false);
        }
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection)
    {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        rememberAnchor();
    }

    @Override
    protected WidgetMaterialListEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight)
    {
        int height = net.syncmaterial.syncmaterial.client.gui.MaterialListLayout.HEADER_HEIGHT;

        if ((usedHeight + height) > usableHeight)
        {
            return null;
        }

        return new WidgetMaterialListEntry(x, y, this.browserEntryWidth, height,
                true, this.gui.getMaterialList(), null, listIndexStart, this);
    }

    @Override
    protected Collection<MaterialListEntry> getAllEntries()
    {
        var entries = this.gui.getMaterialList().getMaterialsFiltered(true);

        // Phase 4: "仅显示我加入的" 过滤
        if (this.gui.isFilterMyMaterials()) {
            var syncList = this.gui.getMaterialList();
            entries = entries.stream()
                .filter(syncList::isCollaborating)
                .collect(java.util.stream.Collectors.toList());
        }

        return entries;
    }

    @Override
    protected Comparator<MaterialListEntry> getComparator()
    {
        return this.sorter;
    }

    @Override
    protected List<String> getEntryStringsForFilter(MaterialListEntry entry)
    {
        ItemStack stack = entry.getStack();
        //? if >=26.2 {
        Identifier rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        //?} else {
        /*ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        *///?}

        return ImmutableList.of(stack.getHoverName().getString().toLowerCase(), rl.toString().toLowerCase());
    }

    @Override
    protected void refreshBrowserEntries()
    {
        if (restoringAnchor) return;
        if (!resetAnchorPending) rememberAnchor();
        resetAnchorPending = false;
        restoringAnchor = true;
        rowLayout = null;
        try {
            super.refreshBrowserEntries();
            if (anchorMaterialId != null) {
                int targetIndex = -1;
                for (int index = 0; index < this.listContents.size(); index++) {
                    if (this.listContents.get(index).getDatabaseId() == anchorMaterialId) {
                        targetIndex = index;
                        break;
                    }
                }
                int target = targetIndex >= 0 ? targetIndex : anchorScroll;
                int restored = Math.max(0, Math.min(target, this.scrollBar.getMaxValue()));
                if (restored != this.scrollBar.getValue()) {
                    this.scrollBar.setValue(restored);
                    this.reCreateListEntryWidgets();
                }
            }
            rememberAnchorAfterRefresh();
        } finally {
            restoringAnchor = false;
        }
    }

    private void rememberAnchorAfterRefresh() {
        if (this.listWidgets.isEmpty()) return;
        for (var widget : this.listWidgets) {
            if (widget.getEntry() != null) {
                anchorMaterialId = widget.getEntry().getDatabaseId();
                anchorListIndex = widget.getListIndex();
                anchorScroll = this.scrollBar.getValue();
                return;
            }
        }
    }

    private void rememberAnchor() {
        if (!restoringAnchor) rememberAnchorAfterRefresh();
    }

    MaterialListLayout rowLayout(int width) {
        if (rowLayout == null || rowLayout.rowWidth() != width) {
            rowLayout = WidgetMaterialListEntry.measureLayout(width, this.gui.getMaterialList(), this.gui.isOwner());
            maxHorizontalOverflow = (int) Math.min(rowLayout.horizontalOverflowWidth(), Integer.MAX_VALUE);
            horizontalOffset = Math.min(horizontalOffset, maxHorizontalOverflow);
        }
        return rowLayout;
    }

    public int horizontalOffset() {
        return horizontalOffset;
    }

    public int maxHorizontalOverflow() {
        return maxHorizontalOverflow;
    }

    public void refreshFromBeginning() {
        this.resetAnchorPending = true;
        this.anchorMaterialId = null;
        this.anchorListIndex = 0;
        this.anchorScroll = 0;
        this.scrollBar.setValue(0);
        this.refreshEntries();
    }

    @Override
    public void resetScrollbarPosition() {
        this.anchorMaterialId = null;
        this.anchorListIndex = 0;
        this.anchorScroll = 0;
        super.resetScrollbarPosition();
        this.reCreateListEntryWidgets();
    }

    /** Captures user-facing state before the screen recreates this fixed-position viewport. */
    public ViewportState viewportState() {
        var search = ((ViewportSearchBar) this.widgetSearchBar).state();
        return new ViewportState(search.searchFilter(), search.searchOpen(),
                this.anchorMaterialId, this.anchorListIndex, this.anchorScroll, this.horizontalOffset);
    }

    /** Restores state without replaying any network operation. */
    public void restoreViewportState(ViewportState state) {
        this.anchorMaterialId = state.anchorMaterialId();
        this.anchorListIndex = state.anchorListIndex();
        this.anchorScroll = state.anchorScroll();
        this.horizontalOffset = state.horizontalOffset();
        ((ViewportSearchBar) this.widgetSearchBar).restore(state.searchFilter(), state.searchOpen());
    }

    public record ViewportState(String searchFilter, boolean searchOpen, Integer anchorMaterialId,
                                int anchorListIndex, int anchorScroll, int horizontalOffset) {}

    @Override
    //? if >=26.2 {
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    //?} else {
    /*public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    *///?}
    {
        if (fi.dy.masa.malilib.gui.GuiBase.isShiftDown() && maxHorizontalOverflow > 0) {
            int delta = (int) (-verticalAmount * 20 - horizontalAmount * 20);
            int next = Math.max(0, Math.min(maxHorizontalOverflow, horizontalOffset + delta));
            if (next != horizontalOffset) {
                horizontalOffset = next;
                this.reCreateListEntryWidgets();
            }
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected WidgetMaterialListEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, MaterialListEntry entry)
    {
        return new WidgetMaterialListEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                isOdd, this.gui.getMaterialList(), entry, listIndex, this);
    }

    private static final class ViewportSearchBar extends WidgetSearchBar {
        private ViewportSearchBar(int x, int y, int width, int height, int offset,
                                  MaLiLibIcons icon, LeftRight alignment) {
            super(x, y, width, height, offset, icon, alignment);
        }

        private record SearchState(String searchFilter, boolean searchOpen) {}

        private SearchState state() {
            //? if >=26.2 {
            return new SearchState(this.searchBox.getValueWrapper(), this.isSearchOpen());
            //?} else {
            /*return new SearchState(this.searchBox.getTextWrapper(), this.isSearchOpen());
            *///?}
        }

        private void restore(String filter, boolean open) {
            //? if >=26.2 {
            this.searchBox.setValueWrapper(filter);
            //?} else {
            /*this.searchBox.setTextWrapper(filter);
            *///?}
            this.setSearchOpen(open);
            this.searchBox.setFocused(open);
        }
    }
}
