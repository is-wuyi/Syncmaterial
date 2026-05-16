package net.syncmaterial.syncmaterial.client.gui;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;

public class WidgetListMaterialList extends WidgetListBase<MaterialListEntry, WidgetMaterialListEntry> {
    private static int lastScrollbarPosition;

    private final GuiMaterialList gui;
    private final MaterialListSorter sorter;
    private boolean scrollbarRestored;

    public WidgetListMaterialList(int x, int y, int width, int height, GuiMaterialList parent) {
        super(x, y, width, height, null);

        this.browserEntryHeight = 22;
        this.gui = parent;
        this.sorter = new MaterialListSorter();
        this.shouldSortList = true;
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection) {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected WidgetMaterialListEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight) {
        int height = this.browserEntryHeight;
        if ((usedHeight + height) > usableHeight) {
            return null;
        }
        return this.createListEntryWidget(x, y, listIndexStart, true, null);
    }

    @Override
    protected Collection<MaterialListEntry> getAllEntries() {
        return this.gui.getMaterialList().getMaterialsFiltered(true);
    }

    @Override
    protected Comparator<MaterialListEntry> getComparator() {
        return this.sorter;
    }

    @Override
    protected WidgetMaterialListEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, MaterialListEntry entry) {
        return new WidgetMaterialListEntry(x, y, this.browserEntryWidth, this.browserEntryHeight, isOdd,
                this.gui.getMaterialList(), entry, listIndex, this);
    }

    public void refreshEntries() {
        this.refreshEntries();
    }
}
