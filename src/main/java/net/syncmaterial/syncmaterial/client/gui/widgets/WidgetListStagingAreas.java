/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced SelectionManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import net.minecraft.util.math.BlockPos;

import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaEditorGui;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.util.AlphaNumComparator.AlphaNumStringComparator;

public class WidgetListStagingAreas extends WidgetListBase<StagingAreaEntry, WidgetStagingAreaEntry>
{
    private final StagingAreaEditorGui gui;
    private final AreaSelection selection;

    public WidgetListStagingAreas(int x, int y, int width, int height,
            AreaSelection selection, StagingAreaEditorGui gui)
    {
        super(x, y, width, height, gui);

        this.gui = gui;
        this.selection = selection;
        this.browserEntryHeight = 22;
        this.shouldSortList = true;
    }

    public StagingAreaEditorGui getEditorGui()
    {
        return this.gui;
    }

    public AreaSelection getSelection()
    {
        return this.selection;
    }

    public void renderHoverEffects(DrawContext drawContext, int mouseX, int mouseY)
    {
        this.drawHoveredWidget(drawContext, mouseX, mouseY);
        this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
    }

    @Override
    protected Collection<StagingAreaEntry> getAllEntries()
    {
        List<StagingAreaEntry> entries = new ArrayList<>();
        int id = 0;

        for (String name : this.selection.getAllSubRegionNames())
        {
            Box box = this.selection.getSubRegionBox(name);

            if (box != null)
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                entries.add(new StagingAreaEntry(id++, name,
                        pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ(),
                        this.gui.getAreaWorld(name)));
            }
        }

        return entries;
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
                isOdd, entry, listIndex, this.selection, this);
    }
}
