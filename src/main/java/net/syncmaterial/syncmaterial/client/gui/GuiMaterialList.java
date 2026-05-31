package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;

    public GuiMaterialList(String schematicId, String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
        super(10, 44);

        this.materialList = new SyncMaterialList(schematicId, schematicName);
        this.materialList.setOnStatusUpdate(() -> this.getListWidget().refreshEntries());
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
        x -= this.createButtonRefresh(x, 24) + gap;
        x -= this.createButtonStagingArea(x, 24);

        this.materialList.requestCollaborationStatus();
    }

    private int createButtonRefresh(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "刷新列表");
        this.addButton(button, (btn, mouseButton) -> {
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null && !schematicId.isEmpty()) {
                ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(schematicId));
                btn.setDisplayString("刷新中...");
                btn.setEnabled(false);
            }
        });
        return button.getWidth();
    }

    private int createButtonStagingArea(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "备货区配置");
        this.addButton(button, (btn, mouseButton) -> {
            AreaSelection selection = new AreaSelection();
            GuiStagingAreaEditorNormal editor = new GuiStagingAreaEditorNormal(selection, null, this.materialList.getSchematicId());
            editor.setParent(this);
            this.mc.setScreen(editor);
        });
        return button.getWidth();
    }

    private int createButtonToggleHud(int x, int y) {
        String label = "HUD信息显示：" + (this.materialList.getHudRenderer().getShouldRender() ? "开启" : "关闭");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            btn.setDisplayString("HUD信息显示：" + (this.materialList.getHudRenderer().getShouldRender() ? "开启" : "关闭"));
        });
        return button.getWidth();
    }

    public void onRescanResponse(boolean success, String message) {
        this.initGui();
        if (success) {
            this.materialList.requestCollaborationStatus();
            MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
            this.getListWidget().refreshEntries();
        }
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Rescan] 结果: success={}, message={}", success, message);
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
        return button.getWidth();
    }

    @Override
    public void close() {
        net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        if (this.materialList.getHudRenderer().getShouldRender()) {
            this.materialList.getHudRenderer().render(drawContext, 10, 44, HudAlignment.TOP_LEFT);
        }
    }
}
