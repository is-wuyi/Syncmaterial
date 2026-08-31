package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.MinecraftClient;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.util.StringUtils;

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
    /** 待创建仓库的名称：先由名称弹窗填入，准星选区确认时取用 */
    private String pendingWarehouseName = null;

    public GuiWarehouseManager()
    {
        super(8, 48);
        this.title = StringUtils.translate("syncmaterial.gui.title.warehouse_manager");
    }

    public List<WarehouseEntry> getWarehouses()
    {
        return this.warehouses;
    }

    /** 测试入口：设置待创建仓库名。真实流程由文本弹窗的 setString 写入 */
    public void setPendingWarehouseNameForTest(String name)
    {
        this.pendingWarehouseName = name;
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

        // 添加仓库：先输入名称，确认后进入准星选区（与备货区一致）
        String label = StringUtils.translate("syncmaterial.gui.button.add_warehouse");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            GuiTextInput gui = new GuiTextInput(128,
                    "syncmaterial.gui.title.warehouse_name", "", null, new WarehouseCreator(this));
            gui.setParent(this);
            GuiBase.openGui(gui);
        });
    }

    public void requestRefresh()
    {
        requestWarehouseList();
    }

    /**
     * 打开仓库编辑界面（坐标编辑 + 准星选区），与备货区的"配置"按钮对应
     */
    public void startEditWarehouse(WarehouseEntry entry)
    {
        GuiWarehouseEditor editor = new GuiWarehouseEditor(entry, this);
        editor.setParent(this);
        GuiBase.openGui(editor);
    }

    /**
     * 名称输入完成后进入准星选区。
     * MaLiLib 弹窗在 setString 返回 true 后会 setScreen(parent)，
     * 若此处直接启动选区（内部 setScreen(null)）会被随后的 setScreen 盖掉，
     * 所以推迟到下一帧执行。
     */
    private static class WarehouseCreator implements IStringConsumerFeedback
    {
        private final GuiWarehouseManager gui;

        private WarehouseCreator(GuiWarehouseManager gui)
        {
            this.gui = gui;
        }

        @Override
        public boolean setString(String string)
        {
            if (string == null || string.trim().isEmpty())
            {
                return false;
            }

            this.gui.pendingWarehouseName = string.trim();

            // 新建：还没有仓库 ID，但仍用仓库配色，避免确认后框的颜色突变。
            // 界面关闭时序由 StagingAreaSelector.onTick 兜底，此处直接启动。
            StagingAreaSelector.getInstance().start(this.gui, this.gui, null, null, null,
                    StagingAreaSelector.TargetType.WAREHOUSE, null, -1);
            return true;
        }
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
        // 编辑已改由 GuiWarehouseEditor 处理，本回调只服务"新建"流程
        String name = this.pendingWarehouseName;
        this.pendingWarehouseName = null;

        if (pos1 == null || pos2 == null || name == null)
        {
            return;
        }

        // 取一次局部引用，避免两次 getInstance().player 之间状态变化
        var clientPlayer = MinecraftClient.getInstance().player;
        String world = clientPlayer != null
            ? clientPlayer.getWorld().getRegistryKey().getValue().toString()
            : "minecraft:overworld";

        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0,
            Optional.of(new StagingAreaConfigC2SPacket.AreaData(name,
                pos1.getX(), pos1.getY(), pos1.getZ(),
                pos2.getX(), pos2.getY(), pos2.getZ(), Optional.of(world)))));
        requestWarehouseList();
    }
}
