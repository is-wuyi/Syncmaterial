//? if >=26 {
package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import fi.dy.masa.malilib.render.GuiContext;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

public class WidgetListWarehouseRefs extends WidgetListBase<StagingAreaConfigResponseS2CPacket.AreaInfo, WidgetWarehouseRefEntry>
{
    private final String schematicId;

    public WidgetListWarehouseRefs(int x, int y, int width, int height, String schematicId)
    {
        super(x, y, width, height, null);
        this.schematicId = schematicId;
        this.browserEntryHeight = 22;
        this.shouldSortList = false;
    }

    public String getSchematicId()
    {
        return this.schematicId;
    }

    public int getEntryCount()
    {
        return this.entries.size();
    }

    public void renderHoverEffects(GuiContext drawContext, int mouseX, int mouseY)
    {
        this.drawHoveredWidget(drawContext, mouseX, mouseY);
        this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
    }

    private List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries = new java.util.ArrayList<>();

    public void setEntries(List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries)
    {
        this.entries = entries;
        this.refreshEntries();
    }

    @Override
    protected Collection<StagingAreaConfigResponseS2CPacket.AreaInfo> getAllEntries()
    {
        return this.entries;
    }

    @Override
    protected Comparator<StagingAreaConfigResponseS2CPacket.AreaInfo> getComparator()
    {
        return Comparator.comparing(StagingAreaConfigResponseS2CPacket.AreaInfo::name);
    }

    @Override
    protected List<String> getEntryStringsForFilter(StagingAreaConfigResponseS2CPacket.AreaInfo entry)
    {
        return List.of(entry.name().toLowerCase());
    }

    @Override
    protected WidgetWarehouseRefEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
            StagingAreaConfigResponseS2CPacket.AreaInfo entry)
    {
        return new WidgetWarehouseRefEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }
}
//?} else {
package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

public class WidgetListWarehouseRefs extends WidgetListBase<StagingAreaConfigResponseS2CPacket.AreaInfo, WidgetWarehouseRefEntry>
{
    private final String schematicId;

    public WidgetListWarehouseRefs(int x, int y, int width, int height, String schematicId)
    {
        super(x, y, width, height, null);
        this.schematicId = schematicId;
        this.browserEntryHeight = 22;
        this.shouldSortList = false;
    }

    public String getSchematicId()
    {
        return this.schematicId;
    }

    public int getEntryCount()
    {
        return this.entries.size();
    }

    public void renderHoverEffects(DrawContext drawContext, int mouseX, int mouseY)
    {
        this.drawHoveredWidget(drawContext, mouseX, mouseY);
        this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
    }

    private List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries = new java.util.ArrayList<>();

    public void setEntries(List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries)
    {
        this.entries = entries;
        this.refreshEntries();
    }

    @Override
    protected Collection<StagingAreaConfigResponseS2CPacket.AreaInfo> getAllEntries()
    {
        return this.entries;
    }

    @Override
    protected Comparator<StagingAreaConfigResponseS2CPacket.AreaInfo> getComparator()
    {
        return Comparator.comparing(StagingAreaConfigResponseS2CPacket.AreaInfo::name);
    }

    @Override
    protected List<String> getEntryStringsForFilter(StagingAreaConfigResponseS2CPacket.AreaInfo entry)
    {
        return List.of(entry.name().toLowerCase());
    }

    @Override
    protected WidgetWarehouseRefEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
            StagingAreaConfigResponseS2CPacket.AreaInfo entry)
    {
        return new WidgetWarehouseRefEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }
}
//?}
