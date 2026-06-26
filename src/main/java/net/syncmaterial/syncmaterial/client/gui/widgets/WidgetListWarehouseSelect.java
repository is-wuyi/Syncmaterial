package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;

import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseSelect;
import net.syncmaterial.syncmaterial.client.gui.WarehouseEntry;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

public class WidgetListWarehouseSelect extends WidgetListBase<WarehouseEntry, WidgetWarehouseSelectEntry>
{
    private final GuiWarehouseSelect gui;

    public WidgetListWarehouseSelect(int x, int y, int width, int height, GuiWarehouseSelect gui)
    {
        super(x, y, width, height, gui);

        this.gui = gui;
        this.browserEntryHeight = 22;
    }

    public GuiWarehouseSelect getWarehouseSelectGui()
    {
        return this.gui;
    }

    @Override
    protected Collection<WarehouseEntry> getAllEntries()
    {
        return this.gui.getWarehouses();
    }

    @Override
    protected WidgetWarehouseSelectEntry createListEntryWidget(int x, int y, int listIndex,
            boolean isOdd, WarehouseEntry entry)
    {
        return new WidgetWarehouseSelectEntry(x, y, this.browserEntryWidth,
                this.browserEntryHeight, isOdd, entry, listIndex, this);
    }
}
