package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouses;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetWarehouseEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

/**
 * 全局仓库管理界面（Phase 5）
 * 复用 MaLiLib GuiListBase 模式（与备货区编辑器一致）
 */
public class GuiWarehouseManager extends GuiListBase<WarehouseEntry, WidgetWarehouseEntry, WidgetListWarehouses>
        implements ISelectionListener<WarehouseEntry>, StagingAreaSelector.SelectionCallback
{
    private final List<WarehouseEntry> warehouses = new ArrayList<>();
    private String pendingWarehouseName = null; // 等待准星选区完成后的仓库名
    private int editingWarehouseId = -1; // 正在编辑的仓库ID（-1表示新建模式）

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
        addTopButtons();
    }

    /**
     * 左上角按钮（与备货区编辑器一致的布局）
     */
    private void addTopButtons()
    {
        int x = 10;
        int y = 24;

        // 添加仓库按钮 — 直接进入准星选区
        String label = StringUtils.translate("syncmaterial.gui.button.add_warehouse");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            this.pendingWarehouseName = "仓库";
            StagingAreaSelector.getInstance().start(this, this, null, null, null);
        });
    }

    public void requestRefresh()
    {
        requestWarehouseList();
    }

    /**
     * 开始编辑仓库（进入准星选区模式）
     */
    public void startEditWarehouse(WarehouseEntry entry)
    {
        this.editingWarehouseId = entry.warehouseId();
        this.pendingWarehouseName = entry.name();
        StagingAreaSelector.getInstance().start(this, this, entry.name(),
            new net.minecraft.util.math.BlockPos(entry.x1(), entry.y1(), entry.z1()),
            new net.minecraft.util.math.BlockPos(entry.x2(), entry.y2(), entry.z2()));
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
        SyncMaterial.LOGGER.info("[WarehouseManager] onSelectionConfirmed: pos1={}, pos2={}", pos1, pos2);
        if (pos1 != null && pos2 != null) {
            String name = pendingWarehouseName != null ? pendingWarehouseName : "仓库";
            String world = net.minecraft.client.MinecraftClient.getInstance().player != null
                ? net.minecraft.client.MinecraftClient.getInstance().player.getWorld().getRegistryKey().getValue().toString()
                : "minecraft:overworld";

            if (editingWarehouseId >= 0) {
                // 编辑模式：更新仓库
                SyncMaterial.LOGGER.info("[WarehouseManager] 发送 UPDATE_WAREHOUSE: id={}, name={}", editingWarehouseId, name);
                var areaData = new net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData(
                    name, pos1.getX(), pos1.getY(), pos1.getZ(),
                    pos2.getX(), pos2.getY(), pos2.getZ(), Optional.of(world));
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    "", "UPDATE_WAREHOUSE", editingWarehouseId, Optional.of(areaData)));
                editingWarehouseId = -1;
            } else {
                // 新建模式
                SyncMaterial.LOGGER.info("[WarehouseManager] 发送 ADD_WAREHOUSE: name={}, world={}", name, world);
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
                    Optional.of(new StagingAreaConfigC2SPacket.AreaData(name, pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ(), Optional.of(world)))));
            }
            pendingWarehouseName = null;
            requestWarehouseList();
        }
    }
}
