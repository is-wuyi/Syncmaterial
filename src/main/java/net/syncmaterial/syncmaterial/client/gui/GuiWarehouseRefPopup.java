package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import fi.dy.masa.malilib.render.GuiContext;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

/**
 * 弹窗：仓库选择列表，继承 GuiDialogBase 遵循 MaLiLib 弹窗规范
 */
public class GuiWarehouseRefPopup extends GuiDialogBase
{
    private final String schematicId;
    private List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouses = new ArrayList<>();
    private boolean loading = true;
    private WarehouseListWidget listWidget;

    public GuiWarehouseRefPopup(String schematicId)
    {
        this.schematicId = schematicId;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.select_warehouse");

        this.setParent(net.minecraft.client.Minecraft.getInstance().gui.screen());
        this.setWidthAndHeight(320, 200);
        this.centerOnScreen();
    }

    public void onWarehouseListResponse(List<StagingAreaConfigResponseS2CPacket.AreaInfo> areas)
    {
        this.warehouses = areas;
        this.loading = false;

        // 根据条目数调整弹窗高度
        int visibleCount = Math.min(areas.size(), 8);
        if (areas.isEmpty()) visibleCount = 1;
        int newHeight = 36 + visibleCount * 22 + 30;
        this.setWidthAndHeight(320, newHeight);
        this.centerOnScreen();

        this.initGui();
    }

    @Override
    public void initGui()
    {
        super.initGui();

        if (loading)
        {
            // 首次打开时发请求
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    "", "LIST_WAREHOUSES", 0, Optional.empty()));
            return;
        }

        int listX = this.dialogLeft + 6;
        int listY = this.dialogTop + 22;
        int listWidth = this.dialogWidth - 12;
        int listHeight = this.dialogHeight - 56;

        this.listWidget = new WarehouseListWidget(listX, listY, listWidth, listHeight);
        this.listWidget.setEntries(this.warehouses);
        this.listWidget.initGui();
    }

    @Override
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        // 渲染父界面（半透明覆盖效果）
        // 26.2 的 Screen.render 改用 state 化渲染，弹窗直接跳过父界面重绘（半透明底由 drawOutlinedBox 提供）
        // MaLiLib 标准弹窗框
        RenderUtils.drawOutlinedBox(drawContext, this.dialogLeft, this.dialogTop,
                this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);

        // 标题
        this.drawStringWithShadow(drawContext, this.getTitleString(),
                this.dialogLeft + 10, this.dialogTop + 4, COLOR_WHITE);

        // 内容
        if (loading)
        {
            this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.loading"),
                    this.dialogLeft + 10, this.dialogTop + 30, 0xFFAAAAAA);
        }
        else if (warehouses.isEmpty())
        {
            this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.no_warehouses"),
                    this.dialogLeft + 10, this.dialogTop + 30, 0xFF888888);
        }
        else if (this.listWidget != null)
        {
            this.listWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);
        }

        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY)
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);
        if (this.listWidget != null)
        {
            this.listWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    @Override
    public boolean onMouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick)
    {
        if (super.onMouseClicked(event, isDoubleClick))
        {
            return true;
        }
        if (this.listWidget != null && this.listWidget.onMouseClicked(event, isDoubleClick))
        {
            return true;
        }
        // 点击弹窗外关闭
        if (event.x() < dialogLeft || event.x() > dialogLeft + dialogWidth
                || event.y() < dialogTop || event.y() > dialogTop + dialogHeight)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(net.minecraft.client.input.MouseButtonEvent event)
    {
        super.onMouseReleased(event);
        if (this.listWidget != null) this.listWidget.onMouseReleased(event);
        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        if (this.listWidget != null && this.listWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyTyped(net.minecraft.client.input.KeyEvent event)
    {
        if (event.key() == KeyCodes.KEY_ESCAPE)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return super.onKeyTyped(event);
    }

    
    public boolean shouldPause()
    {
        return false;
    }

    private void selectWarehouse(StagingAreaConfigResponseS2CPacket.AreaInfo warehouse)
    {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                this.schematicId, "ADD_WAREHOUSE_REF", warehouse.areaId(), Optional.empty()));
        GuiBase.openGui(this.getParent());
    }

    // ========== 仓库列表 Widget ==========

    private class WarehouseListWidget extends WidgetListBase<StagingAreaConfigResponseS2CPacket.AreaInfo, WarehouseEntryWidget>
    {
        public WarehouseListWidget(int x, int y, int width, int height)
        {
            super(x, y, width, height, null);
            this.browserEntryHeight = 22;
            this.shouldSortList = false;
        }

        private List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries = new ArrayList<>();

        public void setEntries(List<StagingAreaConfigResponseS2CPacket.AreaInfo> entries)
        {
            this.entries = entries;
            this.refreshEntries();
        }

        public void renderHoverEffects(GuiContext drawContext, int mouseX, int mouseY)
        {
            this.drawHoveredWidget(drawContext, mouseX, mouseY);
            this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
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
        protected WarehouseEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
                StagingAreaConfigResponseS2CPacket.AreaInfo entry)
        {
            return new WarehouseEntryWidget(x, y, this.browserEntryWidth, this.browserEntryHeight,
                    isOdd, entry, listIndex);
        }
    }

    // ========== 条目 Widget ==========

    private class WarehouseEntryWidget extends WidgetListEntryBase<StagingAreaConfigResponseS2CPacket.AreaInfo>
    {
        private final StagingAreaConfigResponseS2CPacket.AreaInfo entry;
        private final boolean isOdd;

        public WarehouseEntryWidget(int x, int y, int width, int height, boolean isOdd,
                StagingAreaConfigResponseS2CPacket.AreaInfo entry, int listIndex)
        {
            super(x, y, width, height, entry, listIndex);
            this.entry = entry;
            this.isOdd = isOdd;

            int btnX = x + width - 44;
            ButtonGeneric selectBtn = new ButtonGeneric(btnX, y + 2, 40, 16,
                    StringUtils.translate("syncmaterial.gui.button.select"));
            this.addButton(selectBtn, (btn, mouseBtn) -> selectWarehouse(this.entry));
        }

        @Override
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
        {
            if (selected || this.isMouseOver(mouseX, mouseY))
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
            }
            else if (this.isOdd)
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
            }
            else
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
            }

            String display = StringUtils.translate("syncmaterial.gui.label.area_entry_display",
                    this.entry.name(),
                    this.entry.x1(), this.entry.y1(), this.entry.z1(),
                    this.entry.x2(), this.entry.y2(), this.entry.z2());
            this.drawString(drawContext, this.x + 4, this.y + 7, 0xFFCCCCCC, display);

            super.render(drawContext, mouseX, mouseY, selected);
        }
    }
}
