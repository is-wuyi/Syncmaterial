package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager;
import net.syncmaterial.syncmaterial.client.gui.WarehouseEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.util.AlphaNumComparator.AlphaNumStringComparator;

/**
 * 仓库列表 widget（类似 WidgetListStagingAreas）
 */
public class WidgetListWarehouses extends WidgetListBase<WarehouseEntry, WidgetWarehouseEntry>
{
    private final GuiWarehouseManager gui;

    public WidgetListWarehouses(int x, int y, int width, int height, GuiWarehouseManager gui)
    {
        super(x, y, width, height, gui);

        this.gui = gui;
        this.browserEntryHeight = 22;
        this.shouldSortList = true;
    }

    public GuiWarehouseManager getWarehouseGui()
    {
        return this.gui;
    }

    @Override
    protected Collection<WarehouseEntry> getAllEntries()
    {
        return new ArrayList<>(this.gui.getWarehouses());
    }

    @Override
    protected Comparator<WarehouseEntry> getComparator()
    {
        return Comparator.comparing(WarehouseEntry::name, new AlphaNumStringComparator());
    }

    @Override
    protected List<String> getEntryStringsForFilter(WarehouseEntry entry)
    {
        return List.of(entry.name().toLowerCase());
    }

    @Override
    protected WidgetWarehouseEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, WarehouseEntry entry)
    {
        return new WidgetWarehouseEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }
}
