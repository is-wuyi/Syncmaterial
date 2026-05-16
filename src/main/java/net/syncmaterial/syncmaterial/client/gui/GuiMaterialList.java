package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;

    public GuiMaterialList(String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
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

        int gap = 2;

        int x = this.getScreenWidth() - 20;
        x -= this.createButtonClose(x, 24) + gap;
        x -= this.createButtonToggleHud(x, 24) + gap;
        x -= this.createButtonRefresh(x, 24);
    }

    private int createButtonRefresh(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "刷新列表");
        this.addButton(button, (btn, mouseButton) -> {
            MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
            this.getListWidget().refreshEntries();
        });
        return button.getWidth();
    }

    private int createButtonToggleHud(int x, int y) {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, true,
                "HUD信息显示",
                this.materialList.getHudRenderer().getShouldRender());
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            button.updateDisplayString(this.materialList.getHudRenderer().getShouldRender());
        });
        return button.getWidth();
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
        return button.getWidth();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
