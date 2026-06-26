package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

/**
 * 弹窗：仓库选择列表，覆盖在当前界面上方
 */
public class GuiWarehouseRefPopup extends GuiBase
{
    private final String schematicId;
    private List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses = new ArrayList<>();
    private boolean loading = true;

    // 弹窗尺寸
    private int popupX;
    private int popupY;
    private int popupWidth = 320;
    private int popupHeight;
    private static final int ENTRY_HEIGHT = 22;
    private static final int MAX_VISIBLE_ENTRIES = 8;
    private int scrollOffset = 0;

    public GuiWarehouseRefPopup(String schematicId)
    {
        this.schematicId = schematicId;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.select_warehouse");
    }

    /**
     * 服务端返回仓库列表时调用
     */
    public void onWarehouseListResponse(List<StagingAreaConfigResponseS2CPacket.AreaInfo> areas)
    {
        this.warehouses = areas;
        this.loading = false;
        this.recalculateHeight();
        this.initGui();
    }

    private void recalculateHeight()
    {
        int visibleCount = Math.min(warehouses.size(), MAX_VISIBLE_ENTRIES);
        if (loading) visibleCount = 1;
        if (!loading && warehouses.isEmpty()) visibleCount = 1;
        this.popupHeight = 40 + visibleCount * ENTRY_HEIGHT + 10;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        this.popupX = (this.getScreenWidth() - this.popupWidth) / 2;
        this.popupY = (this.getScreenHeight() - this.popupHeight) / 2;

        if (loading)
        {
            // 请求仓库列表
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    "", "LIST_WAREHOUSES", 0, Optional.empty()));
            return;
        }

        this.clearButtons();
        this.recalculateHeight();
        this.popupX = (this.getScreenWidth() - this.popupWidth) / 2;
        this.popupY = (this.getScreenHeight() - this.popupHeight) / 2;

        int y = this.popupY + 28;
        int visibleCount = Math.min(warehouses.size() - scrollOffset, MAX_VISIBLE_ENTRIES);
        for (int i = 0; i < visibleCount; i++)
        {
            var wh = warehouses.get(scrollOffset + i);
            int btnX = this.popupX + this.popupWidth - 50;
            ButtonGeneric selectBtn = new ButtonGeneric(btnX, y + 2, 40, 16,
                    StringUtils.translate("syncmaterial.gui.button.select"));
            this.addButton(selectBtn, new WarehouseSelectListener(this, wh));
            y += ENTRY_HEIGHT;
        }
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    {
        if (!loading && warehouses.size() > MAX_VISIBLE_ENTRIES)
        {
            int maxScroll = warehouses.size() - MAX_VISIBLE_ENTRIES;
            this.scrollOffset -= (int) Math.signum(verticalAmount);
            this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
            this.initGui();
        }
        return true;
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        // 点击弹窗外关闭
        if (mouseX < popupX || mouseX > popupX + popupWidth || mouseY < popupY || mouseY > popupY + popupHeight)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return super.onMouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        // 先渲染父界面（半透明背景效果）
        if (this.getParent() != null)
        {
            this.getParent().render(drawContext, -1, -1, partialTicks);
        }

        // 弹窗背景
        RenderUtils.drawRect(drawContext, popupX, popupY, popupWidth, popupHeight, 0xFF202020);
        RenderUtils.drawOutline(drawContext, popupX, popupY, popupWidth, popupHeight, 0xFF555555);

        // 标题
        this.drawString(drawContext, this.title, popupX + 8, popupY + 8, 0xFFFFFFFF);

        // 内容
        if (loading)
        {
            this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.loading"),
                    popupX + 8, popupY + 32, 0xFFAAAAAA);
        }
        else if (warehouses.isEmpty())
        {
            this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.no_warehouses"),
                    popupX + 8, popupY + 32, 0xFF888888);
        }
        else
        {
            int y = popupY + 28;
            int visibleCount = Math.min(warehouses.size() - scrollOffset, MAX_VISIBLE_ENTRIES);
            for (int i = 0; i < visibleCount; i++)
            {
                var wh = warehouses.get(scrollOffset + i);
                boolean hovered = mouseY >= y && mouseY < y + ENTRY_HEIGHT;

                if (hovered)
                {
                    RenderUtils.drawRect(drawContext, popupX + 2, y, popupWidth - 4, ENTRY_HEIGHT, 0x40FFFFFF);
                }

                // 仓库名 + 坐标
                String display = StringUtils.translate("syncmaterial.gui.label.area_entry_display",
                        wh.name(),
                        wh.x1(), wh.y1(), wh.z1(),
                        wh.x2(), wh.y2(), wh.z2());
                this.drawString(drawContext, display, popupX + 6, y + 6, 0xFFCCCCCC);

                y += ENTRY_HEIGHT;
            }
        }

        super.render(drawContext, mouseX, mouseY, partialTicks);
    }

    private void selectWarehouse(StagingAreaConfigResponseS2CPacket.AreaInfo warehouse)
    {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                this.schematicId, "ADD_WAREHOUSE_REF", warehouse.areaId(), Optional.empty()));
        GuiBase.openGui(this.getParent());
    }

    private static class WarehouseSelectListener implements IButtonActionListener
    {
        private final GuiWarehouseRefPopup popup;
        private final StagingAreaConfigResponseS2CPacket.AreaInfo warehouse;

        public WarehouseSelectListener(GuiWarehouseRefPopup popup, StagingAreaConfigResponseS2CPacket.AreaInfo warehouse)
        {
            this.popup = popup;
            this.warehouse = warehouse;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.popup.selectWarehouse(this.warehouse);
        }
    }
}
