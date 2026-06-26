package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouseSelect;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetWarehouseSelectEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

/**
 * 仓库选择界面：点击「添加仓库」后打开，显示全局仓库列表供选择
 */
public class GuiWarehouseSelect extends GuiListBase<WarehouseEntry, WidgetWarehouseSelectEntry, WidgetListWarehouseSelect>
        implements ISelectionListener<WarehouseEntry>
{
    private final String schematicId;
    private final List<WarehouseEntry> warehouses = new ArrayList<>();

    public GuiWarehouseSelect(String schematicId)
    {
        super(8, 48);
        this.schematicId = schematicId;
        this.title = StringUtils.translate("syncmaterial.gui.title.select_warehouse");
    }

    public List<WarehouseEntry> getWarehouses()
    {
        return this.warehouses;
    }

    public String getSchematicId()
    {
        return this.schematicId;
    }

    @Override
    protected WidgetListWarehouseSelect createListWidget(int listX, int listY)
    {
        return new WidgetListWarehouseSelect(listX, listY,
                this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - 78;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        // 请求全局仓库列表
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                this.schematicId, "LIST_WAREHOUSES", 0, Optional.empty()));

        // 返回按钮
        int yBottom = this.getScreenHeight() - 26;
        String label = fi.dy.masa.malilib.gui.GuiBase.TXT_RED + StringUtils.translate("gui.back");
        int buttonWidth = this.getStringWidth(label) + 10;
        int xClose = this.getScreenWidth() - buttonWidth - 10;
        this.addButton(new ButtonGeneric(xClose, yBottom, buttonWidth, 20, label),
                (btn, mouseBtn) -> this.openParent());
    }

    /**
     * 收到服务端仓库列表响应
     */
    public void onWarehouseListResponse(List<StagingAreaConfigResponseS2CPacket.AreaInfo> areas)
    {
        this.warehouses.clear();
        for (var area : areas)
        {
            this.warehouses.add(new WarehouseEntry(
                area.areaId(), area.name(),
                area.x1(), area.y1(), area.z1(),
                area.x2(), area.y2(), area.z2(),
                area.world()));
        }

        if (this.getListWidget() != null)
        {
            this.getListWidget().refreshEntries();
        }
    }

    /**
     * 收到仓库引用添加响应，返回编辑器
     */
    public void onWarehouseRefAdded()
    {
        this.openParent();
    }

    private void openParent()
    {
        fi.dy.masa.malilib.gui.GuiBase.openGui(this.getParent());
    }

    @Override
    public void onSelectionChange(WarehouseEntry entry)
    {
        // 点击条目时不做任何事（用按钮操作）
    }
}
