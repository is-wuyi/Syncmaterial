package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import com.google.common.collect.ImmutableList;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListSorter;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

public class WidgetListMaterialList extends WidgetListBase<MaterialListEntry, WidgetMaterialListEntry>
{
    private static int lastScrollbarPosition;

    private final GuiMaterialList gui;
    private final MaterialListSorter sorter;
    private boolean scrollbarRestored;

    public WidgetListMaterialList(int x, int y, int width, int height, GuiMaterialList parent)
    {
        super(x, y, width, height, null);

        this.browserEntryHeight = 34;
        this.gui = parent;
        this.sorter = new MaterialListSorter(parent.getMaterialList());
        this.shouldSortList = true;

        // 搜索栏：无图标模式
        IGuiIcon dummyIcon = new IGuiIcon() {
            public int getWidth() { return 0; }
            public int getHeight() { return 0; }
            public int getU() { return 0; }
            public int getV() { return 0; }
            public void renderAt(DrawContext ctx, int ix, int iy, float z, boolean en, boolean sel) {}
            public Identifier getTexture() { return Identifier.of("minecraft", "textures/gui/widgets.png"); }
        };
        this.widgetSearchBar = new WidgetSearchBar(x + 2, y + 4, width - 14, 14, 0, dummyIcon, LeftRight.LEFT);
        this.browserEntriesOffsetY = this.widgetSearchBar.getHeight() + 3;
    }

    public GuiMaterialList getGui() {
        return this.gui;
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        lastScrollbarPosition = this.scrollBar.getValue();

        if (this.listWidgets.isEmpty()) {
            String hint = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.no_materials");
            MinecraftClient mc = MinecraftClient.getInstance();
            int textWidth = mc.textRenderer.getWidth(hint);
            int x = this.posX + (this.browserWidth - textWidth) / 2;
            int y = this.posY + this.browserHeight / 2 - 4;
            drawContext.drawText(mc.textRenderer, hint, x, y, 0xFFAAAAAA, false);
        }
    }

    @Override
    protected void offsetSelectionOrScrollbar(int amount, boolean changeSelection)
    {
        super.offsetSelectionOrScrollbar(amount, changeSelection);
        lastScrollbarPosition = this.scrollBar.getValue();
    }

    @Override
    protected WidgetMaterialListEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight)
    {
        int height = this.browserEntryHeight;

        if ((usedHeight + height) > usableHeight)
        {
            return null;
        }

        return this.createListEntryWidget(x, y, listIndexStart, true, null);
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
        Identifier rl = Registries.ITEM.getId(stack.getItem());

        if (rl != null)
        {
            return ImmutableList.of(stack.getName().getString().toLowerCase(), rl.toString().toLowerCase());
        }
        else
        {
            return ImmutableList.of(stack.getName().getString().toLowerCase());
        }
    }

    @Override
    protected void refreshBrowserEntries()
    {
        super.refreshBrowserEntries();

        if (this.scrollbarRestored == false && lastScrollbarPosition <= this.scrollBar.getMaxValue())
        {
            // This needs to happen after the setMaxValue() has been called in reCreateListEntryWidgets()
            this.scrollBar.setValue(lastScrollbarPosition);
            this.scrollbarRestored = true;
            this.reCreateListEntryWidgets();
        }
    }

    @Override
    protected WidgetMaterialListEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, MaterialListEntry entry)
    {
        return new WidgetMaterialListEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                isOdd, this.gui.getMaterialList(), entry, listIndex, this);
    }
}
