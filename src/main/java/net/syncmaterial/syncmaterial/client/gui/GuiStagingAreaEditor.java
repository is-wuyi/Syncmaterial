package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.syncmaterial.syncmaterial.client.LitematicaSelectionReader;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetStagingAreaEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class GuiStagingAreaEditor extends GuiListBase<StagingAreaEntry, WidgetStagingAreaEntry, WidgetListStagingAreas> {
    private final String schematicId;
    private final List<StagingAreaEntry> areas = new ArrayList<>();
    private String statusMessage = "";
    private boolean litematicaAvailable;

    public GuiStagingAreaEditor(String schematicId) {
        super(10, 44);
        this.schematicId = schematicId;
        this.title = "备货区配置 - " + schematicId;
        this.useTitleHierarchy = false;
        this.litematicaAvailable = LitematicaSelectionReader.isAvailable();
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
    protected WidgetListStagingAreas createListWidget(int listX, int listY) {
        return new WidgetListStagingAreas(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    public List<StagingAreaEntry> getAreas() {
        return this.areas;
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
    }

    @Override
    public void initGui() {
        super.initGui();

        int gap = 2;
        int x = this.getScreenWidth() - 20;
        x -= this.createButtonClose(x, 24) + gap;
        x -= this.createButtonSave(x, 24) + gap;
        x -= this.createButtonAddFromSelection(x, 24) + gap;
        x -= this.createButtonRefresh(x, 24);

        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "LIST", 0, null));
    }

    private int createButtonRefresh(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "刷新列表");
        this.addButton(button, (btn, mouseButton) -> {
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "LIST", 0, null));
        });
        return button.getWidth();
    }

    private int createButtonAddFromSelection(int x, int y) {
        String label = this.litematicaAvailable ? "从选区添加" : "请安装Litematica";
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            if (!this.litematicaAvailable) {
                this.statusMessage = "请安装 Litematica 以使用选区功能";
                return;
            }

            List<LitematicaSelectionReader.StagingAreaRegion> regions = LitematicaSelectionReader.read();
            if (regions.isEmpty()) {
                this.statusMessage = "当前无选区，请用小木棍框选";
                return;
            }

            for (LitematicaSelectionReader.StagingAreaRegion region : regions) {
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    this.schematicId, "ADD", 0,
                    new AreaData(region.name(),
                        region.pos1().getX(), region.pos1().getY(), region.pos1().getZ(),
                        region.pos2().getX(), region.pos2().getY(), region.pos2().getZ())
                ));
            }
            this.statusMessage = "已添加 " + regions.size() + " 个备货区";
        });
        return button.getWidth();
    }

    private int createButtonSave(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "保存到服务器");
        this.addButton(button, (btn, mouseButton) -> {
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "LIST", 0, null));
            this.statusMessage = "配置已同步到服务器";
        });
        return button.getWidth();
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
        return button.getWidth();
    }

    public void onServerResponse(StagingAreaConfigResponseS2CPacket response) {
        this.areas.clear();
        for (StagingAreaConfigResponseS2CPacket.AreaInfo info : response.areas()) {
            this.areas.add(new StagingAreaEntry(info.areaId(), info.name(), info.x1(), info.y1(), info.z1(), info.x2(), info.y2(), info.z2()));
        }
        if (!response.success()) {
            this.statusMessage = response.message();
        }
        this.getListWidget().refreshEntries();
    }

    public void deleteArea(int areaId) {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "DELETE", areaId, null));
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        if (!this.statusMessage.isEmpty()) {
            this.drawStringWithShadow(drawContext, this.statusMessage, 10, this.getScreenHeight() - 14, 0xFFFFFF);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}