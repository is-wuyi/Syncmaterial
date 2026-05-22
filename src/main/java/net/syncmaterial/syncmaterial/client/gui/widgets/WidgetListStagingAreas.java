package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditor;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

public class WidgetListStagingAreas extends WidgetListBase<StagingAreaEntry, WidgetStagingAreaEntry> {
    private final GuiStagingAreaEditor gui;

    public WidgetListStagingAreas(int x, int y, int width, int height, GuiStagingAreaEditor parent) {
        super(x, y, width, height, null);
        this.browserEntryHeight = 22;
        this.gui = parent;
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    protected List<StagingAreaEntry> getAllEntries() {
        return new ArrayList<>(this.gui.getAreas());
    }

    @Override
    protected WidgetStagingAreaEntry createListEntryWidget(int x, int y, int width, boolean selected, StagingAreaEntry entry) {
        return new WidgetStagingAreaEntry(x, y, width, 20, entry, this.gui);
    }
}