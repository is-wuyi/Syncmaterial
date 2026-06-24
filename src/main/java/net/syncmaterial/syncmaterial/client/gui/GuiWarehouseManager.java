package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouses;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetWarehouseEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket.AreaInfo;

/**
 * 全局仓库管理界面（Phase 5）
 * 复用 MaLiLib GuiListBase 模式（与备货区编辑器一致）
 */
public class GuiWarehouseManager extends GuiListBase<WarehouseEntry, WidgetWarehouseEntry, WidgetListWarehouses>
        implements ISelectionListener<WarehouseEntry>, StagingAreaSelector.SelectionCallback
{
    private final List<WarehouseEntry> warehouses = new ArrayList<>();
    private String pendingWarehouseName = null; // 等待准星选区完成后的仓库名

    public GuiWarehouseManager()
    {
        super(8, 48);
        this.title = StringUtils.translate("syncmaterial.gui.title.warehouse_manager");
    }

    public List<WarehouseEntry> getWarehouses()
    {
        return this.warehouses;
    }

    @Override
    protected WidgetListWarehouses createListWidget(int listX, int listY)
    {
        return new WidgetListWarehouses(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
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
        requestWarehouseList();
    }

    public void requestRefresh()
    {
        requestWarehouseList();
    }

    private void requestWarehouseList()
    {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "LIST_WAREHOUSES", 0, Optional.empty()));
    }

    /**
     * 收到仓库列表响应
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

    @Override
    public void drawContents(net.minecraft.client.gui.DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);

        // 底部按钮
        int y = this.getScreenHeight() - 28;
        int x = 12;

        x += this.createButtonAddWarehouse(x, y) + 4;
    }

    private int createButtonAddWarehouse(int x, int y)
    {
        String label = StringUtils.translate("syncmaterial.gui.button.add_warehouse");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            // 先输入仓库名称，然后进入准星选区
            String title = StringUtils.translate("syncmaterial.gui.title.warehouse_manager");
            fi.dy.masa.malilib.gui.GuiTextInputFeedback gui = new fi.dy.masa.malilib.gui.GuiTextInputFeedback(
                128, title, "仓库",
                (net.minecraft.client.gui.screen.Screen) this,
                (name) -> {
                    if (name != null && !name.trim().isEmpty()) {
                        this.pendingWarehouseName = name.trim();
                        // 启动准星选区
                        StagingAreaSelector.getInstance().start(this, this, null, null, null);
                    }
                    return true;
                });
            net.minecraft.client.MinecraftClient.getInstance().setScreen(gui);
        });
        return button.getWidth();
    }

    @Override
    public void onSelectionChange(WarehouseEntry entry)
    {
        // 点击条目时的回调（可选）
    }

    @Override
    public void onSelectionConfirmed(@javax.annotation.Nullable String boxName,
                                      @javax.annotation.Nullable net.minecraft.util.math.BlockPos pos1,
                                      @javax.annotation.Nullable net.minecraft.util.math.BlockPos pos2)
    {
        // 准星选区完成，发送新建仓库请求
        if (pos1 != null && pos2 != null) {
            String name = pendingWarehouseName != null ? pendingWarehouseName : "仓库";
            String world = net.minecraft.client.MinecraftClient.getInstance().player != null
                ? net.minecraft.client.MinecraftClient.getInstance().player.getWorld().getRegistryKey().getValue().toString()
                : "minecraft:overworld";
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
                Optional.of(new AreaData(name, pos1.getX(), pos1.getY(), pos1.getZ(),
                    pos2.getX(), pos2.getY(), pos2.getZ(), Optional.of(world)))));
            pendingWarehouseName = null;
            // 刷新列表
            requestWarehouseList();
        }
    }
}
