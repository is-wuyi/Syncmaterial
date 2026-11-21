package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}

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

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
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
    /** 本次弹窗是否已读取列表；loading 期间 resize 不得重复发 LIST。 */
    private boolean warehouseListRequested;
    /** 已读取的全局视图代次；只有视图真正重开时才允许再读。 */
    private ProjectViewClient.ViewToken warehouseListView;
    /** 本项目 LIST_WAREHOUSES 快照携带的重叠提示；null 表示无提示。 */
    private String overlapWarning;
    /** 本弹窗自己的全局视图消费者；不依赖先访问仓库管理器。 */
    private ProjectViewClient.GlobalLease globalLease;
    private final EditorSaveTracker saveTracker = new EditorSaveTracker();

    public EditorSaveTracker.State getSaveState() {
        return saveTracker.state();
    }

    public GuiWarehouseRefPopup(String schematicId)
    {
        this.schematicId = schematicId;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.select_warehouse");

        this.setParent(net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(
                net.minecraft.client.Minecraft.getInstance()));
        this.setWidthAndHeight(320, 200);
        this.centerOnScreen();
    }

    public String getSchematicId()
    {
        return this.schematicId;
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

    public void applyProjectViewConfiguration(
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> definitions) {
        onWarehouseListResponse(definitions.stream().map(area -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
                area.id(), area.name(), area.bounds().x1(), area.bounds().y1(), area.bounds().z1(),
                area.bounds().x2(), area.bounds().y2(), area.bounds().z2(), area.world())).toList());
    }

    /** Ensures the global scope exists before the first LIST_WAREHOUSES request. */
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

    void releaseGlobalLease()
    {
        if (this.globalLease != null) {
            this.globalLease.close();
            this.globalLease = null;
        }
    }

    @Override
    public void initGui()
    {
        super.initGui();

        if (loading)
        {
            requestWarehouseList();
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

    /**
     * Ensures the global view is OPEN, then sends LIST_WAREHOUSES only once for this popup
     * and global view generation. Repeated loading initGui calls (including resize) reuse it.
     */
    void requestWarehouseList()
    {
        ensureGlobalLease();
        ProjectViewClient.ViewToken view = ProjectViewEditorSupport.captureView(
                net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse());
        if (warehouseListRequested && java.util.Objects.equals(warehouseListView, view)) {
            return;
        }
        if (ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)
                && globalLease == null) {
            return;
        }
        warehouseListRequested = true;
        warehouseListView = view;
        ProjectViewEditorSupport.read(net.syncmaterial.syncmaterial.core.ProjectViewScope.globalWarehouse(),
                "LIST_WAREHOUSES", new StagingAreaConfigC2SPacket(
                        this.schematicId, "LIST_WAREHOUSES", 0, Optional.empty()));
    }

    @Override
    //? if >=26.2 {
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    //?} else {
    /*public void drawContents(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks)
    *///?}
    {
        // 渲染父界面（半透明覆盖效果）
        // 26.2 的 Screen.render 改用 state 化渲染，弹窗直接跳过父界面重绘（半透明底由 drawOutlinedBox 提供）
        // MaLiLib 标准弹窗框
        RenderUtils.drawOutlinedBox(drawContext, this.dialogLeft, this.dialogTop,
                this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);

        // 标题
        this.drawStringWithShadow(drawContext, this.getTitleString(),
                this.dialogLeft + 10, this.dialogTop + 4, COLOR_WHITE);

        if (this.overlapWarning != null)
        {
            String warningText = "⚠ " + StringUtils.translate("syncmaterial.gui.label.area_overlap_warning");
            int warningWidth = this.mc.font.width(warningText);
            AreaOverlapWarningRenderer.render(drawContext,
                    this.dialogLeft + this.dialogWidth - warningWidth - 18, this.dialogTop + 4,
                    this.overlapWarning, mouseX, mouseY);
        }

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
    //? if >=26.2 {
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY)
    //?} else {
    /*protected void drawHoveredWidget(GuiGraphics drawContext, int mouseX, int mouseY)
    *///?}
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);
        if (this.listWidget != null)
        {
            this.listWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick)
    //?} else {
    /*public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        //?} else {
        /*boolean isDoubleClick = false;
        *///?}
        //? if >=26.2 {
        if (super.onMouseClicked(event, isDoubleClick))
        //?} else {
        /*if (super.onMouseClicked(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }
        //? if >=26.2 {
        if (this.listWidget != null && this.listWidget.onMouseClicked(event, isDoubleClick))
        //?} else {
        /*if (this.listWidget != null && this.listWidget.onMouseClicked(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }
        // 点击弹窗外关闭
        if (mouseX < dialogLeft || mouseX > dialogLeft + dialogWidth
                || mouseY < dialogTop || mouseY > dialogTop + dialogHeight)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseReleased(net.minecraft.client.input.MouseButtonEvent event)
    //?} else {
    /*public boolean onMouseReleased(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        super.onMouseReleased(event);
        if (this.listWidget != null) this.listWidget.onMouseReleased(event);
        //?} else {
        /*super.onMouseReleased(mouseX, mouseY, mouseButton);
        if (this.listWidget != null) this.listWidget.onMouseReleased(mouseX, mouseY, mouseButton);
        *///?}
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    //?} else {
    /*public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    *///?}
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
    //? if >=26.2 {
    public boolean onKeyTyped(net.minecraft.client.input.KeyEvent event)
    //?} else {
    /*public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    *///?}
    {
        //? if >=26.2 {
        int key = event.key();
        //?} else {
        /*int key = keyCode;
        *///?}
        if (key == KeyCodes.KEY_ESCAPE)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        //? if >=26.2 {
        return super.onKeyTyped(event);
        //?} else {
        /*return super.onKeyTyped(key, scanCode, modifiers);
        *///?}
    }

    @Override
    public void closeGui(boolean showParent)
    {
        releaseGlobalLease();
        super.closeGui(showParent);
    }

    @Override
    public void removed()
    {
        // Esc/点击弹窗外/选择完成都会直接 setScreen(parent)，不一定走 closeGui。
        releaseGlobalLease();
        super.removed();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    private void selectWarehouse(StagingAreaConfigResponseS2CPacket.AreaInfo warehouse)
    {
        ProjectViewEditorSupport.write(net.syncmaterial.syncmaterial.core.ProjectViewScope.project(schematicId),
                warehouse.areaId(), "ADD_WAREHOUSE_REF", Optional.empty(),
                new StagingAreaConfigC2SPacket(schematicId, "ADD_WAREHOUSE_REF", warehouse.areaId(), Optional.empty()),
                saveTracker);
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

        //? if >=26.2 {
        public void renderHoverEffects(GuiContext drawContext, int mouseX, int mouseY)
        //?} else {
        /*public void renderHoverEffects(GuiGraphics drawContext, int mouseX, int mouseY)
        *///?}
        {
            super.drawHoveredWidget(drawContext, mouseX, mouseY);
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
        //? if >=26.2 {
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
        //?} else {
        /*public void render(GuiGraphics drawContext, int mouseX, int mouseY, boolean selected)
        *///?}
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
