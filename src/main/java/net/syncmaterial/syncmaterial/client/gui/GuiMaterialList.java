package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase.SortCriteria;
import net.syncmaterial.syncmaterial.client.gui.MaterialListUtils;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;

    public GuiMaterialList(String schematicName, List<MaterialEntry> entries) {
        super(10, 44);

        this.materialList = new SyncMaterialList(schematicName);
        this.materialList.setMaterialEntries(entries);
        this.title = this.materialList.getTitle();
        this.useTitleHierarchy = false;

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
        WidgetMaterialListEntry.setMaxNameLength(this.materialList.getMaterialsAll(), this.materialList.getMultiplier());
    }

    public SyncMaterialList getMaterialList() {
        return this.materialList;
    }

    @Override
    protected int getBrowserWidth() {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        return this.getScreenHeight() - 88;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    public void initGui() {
        super.initGui();

        int x = 10;
        int y = 26;

        x += this.createButtonSortBy(x, y, SortCriteria.COUNT_TOTAL);
        x += this.createButtonSortBy(x, y, SortCriteria.COUNT_MISSING);
        x += this.createButtonSortBy(x, y, SortCriteria.NAME);

        x = this.getScreenWidth() - 170;
        this.createButtonToggleHud(x, y);
        x += 60;
        this.createButtonClose(x, y);
    }

    private int createButtonSortBy(int x, int y, SortCriteria criteria) {
        String label = switch (criteria) {
            case COUNT_TOTAL -> "总计";
            case COUNT_MISSING -> "缺失";
            case NAME -> "名称";
            default -> "???";
        };

        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            if (this.materialList.getSortCriteria() == criteria) {
                this.materialList.setSortInReverse(!this.materialList.getSortInReverse());
            } else {
                this.materialList.setSortCriteria(criteria);
                this.materialList.setSortInReverse(false);
            }
            this.getListWidget().refreshEntries();
        });
        return button.getWidth() + 2;
    }

    private void createButtonToggleHud(int x, int y) {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, true, "HUD", this.materialList.getHudRenderer().getShouldRender());
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            button.updateDisplayString(this.materialList.getHudRenderer().getShouldRender());
        });
    }

    private void createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
