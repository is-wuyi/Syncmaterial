package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaEditorGui;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.util.AlphaNumComparator.AlphaNumStringComparator;

public class WidgetListStagingAreas extends WidgetListBase<StagingAreaEntry, WidgetStagingAreaEntry>
{
    private final StagingAreaEditorGui gui;
    private final List<StagingAreaEntry> areas;

    public WidgetListStagingAreas(int x, int y, int width, int height,
            List<StagingAreaEntry> areas, StagingAreaEditorGui gui)
    {
        super(x, y, width, height, gui);

        this.gui = gui;
        this.areas = areas;
        this.browserEntryHeight = 22;
        this.shouldSortList = true;
    }

    public StagingAreaEditorGui getEditorGui()
    {
        return this.gui;
    }

    @Override
    protected Collection<StagingAreaEntry> getAllEntries()
    {
        return this.areas;
    }

    @Override
    protected Comparator<StagingAreaEntry> getComparator()
    {
        return Comparator.comparing(StagingAreaEntry::name, new AlphaNumStringComparator());
    }

    @Override
    protected List<String> getEntryStringsForFilter(StagingAreaEntry entry)
    {
        return List.of(entry.name().toLowerCase());
    }

    @Override
    protected WidgetStagingAreaEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, StagingAreaEntry entry)
    {
        return new WidgetStagingAreaEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this.areas, this);
    }
}
