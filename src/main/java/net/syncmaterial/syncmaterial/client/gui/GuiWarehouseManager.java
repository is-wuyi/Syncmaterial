package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouses;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetWarehouseEntry;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
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
    /** 全局 LIST_WAREHOUSES 快照携带的重叠提示；null 表示无提示。 */
    private String overlapWarning;
    /** 本界面对全局视图的消费者令牌；子界面另有自己的令牌。 */
    private ProjectViewClient.GlobalLease globalLease;
    /** 本次界面是否已读取列表；resize 重入 initGui 不得重复发 LIST。 */
    private boolean warehouseListRequested;
    /** 已读取的全局视图代次；只有视图真正重开时才允许再读。 */
    private ProjectViewClient.ViewToken warehouseListView;
    /** 本界面发起的准星选区转场（新建仓库）：removed() 时保留 global lease。 */
    private boolean selectorTransition;
    private final EditorSaveTracker saveTracker = new EditorSaveTracker();

    public EditorSaveTracker.State getSaveState() {
        return saveTracker.state();
    }

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
        this.selectorTransition = false;
        ensureWarehouseListRead(false);
        addTopButtons();
        // 被子界面遮挡期间错过的后台配置更新，在恢复时从最新权威 store 补齐，
        // 不依赖"收到配置时 currentScreen 恰好是本界面"
        applyLatestGlobalStoreConfiguration();
        var coordinator = net.syncmaterial.syncmaterial.client.SyncMaterialClient.projectCoordinator();
        if (coordinator != null) {
            coordinator.subscribeConfigurations(this,
                    net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse(),
                    (action, areas) -> {
                        if ("LIST_WAREHOUSES".equals(action)) this.applyProjectViewConfiguration(areas);
                    });
        }
    }

    /**
     * Replays the latest accepted global configuration from the session store into the
     * list. Safe to call repeatedly (idempotent data replace); legacy servers without a
     * global store keep the legacy packet routing unchanged.
     */
    void applyLatestGlobalStoreConfiguration()
    {
        ProjectViewClient.current()
                .flatMap(ProjectViewClient::globalStore)
                .filter(store -> store.valid())
                .flatMap(store -> store.configuration("LIST_WAREHOUSES"))
                .ifPresent(this::applyProjectViewConfiguration);
    }

    /**
     * Reads the global warehouse list only for the first open, an explicit refresh, or a
     * genuinely new global view. Resize/role re-layout calls initGui again and must not
     * send another LIST; the global lease and its OPEN ordering are unchanged.
     */
    void ensureWarehouseListRead(boolean force)
    {
        ensureGlobalLease();
        ProjectViewClient.ViewToken view = ProjectViewEditorSupport.captureView(
                net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse());
        if (!force && warehouseListRequested && java.util.Objects.equals(warehouseListView, view)) {
            return;
        }
        if (ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)
                && globalLease == null) {
            return;
        }
        warehouseListRequested = true;
        warehouseListView = view;
        ProjectViewEditorSupport.read(net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse(),
                "LIST_WAREHOUSES", new StagingAreaConfigC2SPacket("", "LIST_WAREHOUSES", 0, Optional.empty()));
    }

    /** Acquires this screen's global-view token, or retries an OPEN that previously failed. */
    void ensureGlobalLease()
    {
        if (this.globalLease == null) {
            this.globalLease = ProjectViewClient.current()
                    .map(session -> session.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit()))
                    .orElse(null);
        } else {
            this.globalLease.retry();
        }
    }

    /** Releases only this screen's token; a warehouse editor child holds its own. */
    void releaseGlobalLease()
    {
        if (this.globalLease != null) {
            this.globalLease.close();
            this.globalLease = null;
        }
    }

    @Override
    public void closeGui(boolean showParent)
    {
        releaseGlobalLease();
        super.closeGui(showParent);
    }

    /**
     * 直接 setScreen 退出的兜底释放；本界面发起的准星选区转场（新建仓库）
     * 保留 lease，等确认/取消回到本界面时复用。
     */
    @Override
    public void removed()
    {
        super.removed();
        if (!this.selectorTransition) {
            releaseGlobalLease();
        }
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
        ensureWarehouseListRead(true);
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
            // 本界面发起的选区转场保留 global lease，removed() 不误释放。
            this.gui.selectorTransition = true;
            StagingAreaSelector.getInstance().start(this.gui, this.gui, null, null, null,
                    StagingAreaSelector.TargetType.WAREHOUSE, null, -1);
            return true;
        }
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

    public void applyProjectViewConfiguration(
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> definitions) {
        onWarehouseListResponse(definitions.stream().map(area -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
                area.id(), area.name(), area.bounds().x1(), area.bounds().y1(), area.bounds().z1(),
                area.bounds().x2(), area.bounds().y2(), area.bounds().z2(), area.world())).toList());
    }

    /** 成功 LIST_WAREHOUSES 的重叠提示；空串/null 清除。 */
    public void updateOverlapWarning(String message)
    {
        this.overlapWarning = AreaOverlapWarningRenderer.normalize(message);
    }

    public String getOverlapWarning()
    {
        return this.overlapWarning;
    }

    @Override
    //? if >=26.2 {
    public void drawContents(fi.dy.masa.malilib.render.GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    //?} else {
    /*public void drawContents(net.minecraft.client.gui.GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks)
    *///?}
    {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        if (this.overlapWarning != null)
        {
            String text = "⚠ " + StringUtils.translate("syncmaterial.gui.label.area_overlap_warning");
            int width = this.mc.font.width(text);
            AreaOverlapWarningRenderer.render(drawContext, this.getScreenWidth() - width - 18, 8,
                    this.overlapWarning, mouseX, mouseY);
        }
    }

    @Override
    public void onSelectionChange(WarehouseEntry entry)
    {
        // 点击条目时的回调（可选）
    }

    @Override
    public void onSelectionConfirmed(@javax.annotation.Nullable String boxName,
                                      @javax.annotation.Nullable net.minecraft.core.BlockPos pos1,
                                      @javax.annotation.Nullable net.minecraft.core.BlockPos pos2)
    {
        // 编辑已改由 GuiWarehouseEditor 处理，本回调只服务"新建"流程
        String name = this.pendingWarehouseName;
        this.pendingWarehouseName = null;

        if (pos1 == null || pos2 == null || name == null)
        {
            return;
        }

        // 取一次局部引用，避免两次 getInstance().player 之间状态变化
        var clientPlayer = Minecraft.getInstance().player;
        String world = clientPlayer != null
            ? net.syncmaterial.syncmaterial.compat.McCompat.dimensionId(clientPlayer.level())
            : "minecraft:overworld";

        var data = new StagingAreaConfigC2SPacket.AreaData(name,
                pos1.getX(), pos1.getY(), pos1.getZ(),
                pos2.getX(), pos2.getY(), pos2.getZ(), Optional.of(world));
        ProjectViewEditorSupport.write(net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse(),
                0, "ADD_WAREHOUSE", Optional.of(data),
                new StagingAreaConfigC2SPacket("", "ADD_WAREHOUSE", 0, Optional.of(data)), saveTracker);
        requestRefresh();
    }
}
