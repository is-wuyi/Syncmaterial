/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced DataManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.core.BlockPos;

import fi.dy.masa.malilib.gui.*;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouseRefs;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetStagingAreaEntry;
import net.syncmaterial.syncmaterial.network.AreaOverlapStatus;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class GuiStagingAreaEditorNormal extends GuiBase
                                      implements StagingAreaEditorGui, StagingAreaSelector.SelectionCallback
{
    protected final AreaSelection selection;
    protected final String schematicId;
    protected WidgetCheckBox checkBoxCorner1;
    protected WidgetCheckBox checkBoxCorner2;
    @Nullable protected String selectionId;
    protected boolean needsServerLoad = false;
    protected boolean loadingFromServer = false;
    protected boolean needsWarehouseRefLoad = true;
    /**
     * 待创建备货区的名称。新建流程先弹输入框拿到名字，再进准星选区，
     * 选区确认时靠这个字段区分"新建"与"改已有区域坐标"（不能再用 boxName == null 判断）。
     */
    @Nullable protected String pendingAreaName;
    /**
     * 备货区名称 → 所属维度（编辑器列表展示用）。
     * Box 现在也带有 world（渲染按它做维度过滤），这里保留一份独立映射，
     * 是因为列表条目由 AreaSelection/Box 重建，界面显示不应依赖渲染状态。
     */
    protected final java.util.Map<String, String> areaWorlds = new java.util.HashMap<>();

    /**
     * 坐标编辑走去抖：文本框每敲一个字符就触发 onTextChange，而服务端每次
     * UPDATE 都会重扫备货区并广播状态。按钮类操作（微调、准星重选）走
     * flushNow 立即发出，它们是单次动作，等静默会让人以为没生效。
     */
    protected final UpdateDebouncer coordinateDebouncer = new UpdateDebouncer(COORD_QUIET_TICKS);
    private final EditorSaveTracker saveTracker = new EditorSaveTracker();
    /** 去抖载荷携带的目标备货区主键；载荷被丢弃/发出后清空。 */
    @Nullable private Integer pendingCoordinateServerId;

    public EditorSaveTracker.State getSaveState() {
        return saveTracker.state();
    }

    /** 10 tick ≈ 0.5 秒：停手半秒即生效，手感上仍是"改完就生效" */
    protected static final int COORD_QUIET_TICKS = 10;

    // 双列表
    @Nullable protected WidgetListStagingAreas stagingListWidget;
    @Nullable protected WidgetListWarehouseRefs warehouseListWidget;
    protected int stagingListY;
    protected int stagingListHeight;
    protected int warehouseListY;
    protected int warehouseListHeight;
    /** 本项目 LIST / LIST_WAREHOUSE_REFS 快照携带的重叠提示；null 表示无提示。 */
    @Nullable private String overlapWarning;
    /** 合并权威配置时有未落定意图，列表刷新被推迟；意图落定后由 tick 补一次重建。 */
    private boolean needsListRefresh;

    protected static StagingAreaConfigC2SPacket.AreaData toAreaData(String name, BlockPos pos1, BlockPos pos2) {
        return new StagingAreaConfigC2SPacket.AreaData(name,
            pos1.getX(), pos1.getY(), pos1.getZ(),
            pos2.getX(), pos2.getY(), pos2.getZ(), Optional.empty());
    }

    /** 带维度的重载：world 为 null 时退化为不改维度 */
    protected static StagingAreaConfigC2SPacket.AreaData toAreaData(String name, BlockPos pos1, BlockPos pos2,
                                                                    @Nullable String world) {
        return new StagingAreaConfigC2SPacket.AreaData(name,
            pos1.getX(), pos1.getY(), pos1.getZ(),
            pos2.getX(), pos2.getY(), pos2.getZ(), Optional.ofNullable(world));
    }

    /** 玩家当前所在维度 ID；玩家不存在时返回 null，表示不改维度 */
    @Nullable
    protected static String currentWorldId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                ? net.syncmaterial.syncmaterial.compat.McCompat.dimensionId(mc.player.level())
                : null;
    }

    /**
     * 兼容旧调用入口。编辑器实例由界面自身持有，不再放在进程级静态字段里；
     * 断线清理无需（也无法）从外部丢弃它，保留空实现避免调用方受影响。
     */
    public static void clearCurrentEditor()
    {
    }

    public void setNeedsServerLoad()
    {
        this.needsServerLoad = true;
    }

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId)
    {
        this(selection, selectionId, "");
    }

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId, String schematicId)
    {
        this.selection = selection;
        this.selectionId = selectionId;
        this.schematicId = schematicId;
        this.needsServerLoad = schematicId != null && !schematicId.isEmpty();
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.area_editor_normal");
    }

    public void setSelectionId(@Nullable String selectionId)
    {
        this.selectionId = selectionId;
    }

    @Override
    public String getSchematicId()
    {
        return this.schematicId;
    }

    @Override
    public void deleteArea(int areaId)
    {
        List<String> names = new ArrayList<>(this.selection.getAllSubRegionNames());
        if (areaId >= 0 && areaId < names.size())
        {
            String name = names.get(areaId);
            Integer serverId = this.selection.getServerId(name);

            // 被删目标的待发 UPDATE 必须先丢弃，不能与 DELETE 竞争；
            // 其他目标的待发输入先补发，避免静默期输入被静默丢掉
            if (this.pendingCoordinateServerId != null && !this.pendingCoordinateServerId.equals(serverId))
            {
                this.coordinateDebouncer.flushNow();
            }
            else
            {
                this.coordinateDebouncer.discard();
                this.pendingCoordinateServerId = null;
            }

            this.selection.removeSubRegionBox(name);
            this.selection.removeServerId(name);

            if (serverId != null && this.schematicId != null)
            {
                writeAreaConfig("DELETE", serverId, Optional.empty());
            }
        }
        this.publishWorkingCopyPreview();
        this.refreshStagingList();
    }

    @Override
    public void refreshAreas()
    {
        this.refreshStagingList();
    }

    // ========== 网络响应 ==========

    /** 成功 LIST 类响应的重叠提示；空串/null 清除。 */
    public void updateOverlapWarning(@Nullable String message)
    {
        this.overlapWarning = AreaOverlapWarningRenderer.normalize(message);
    }

    @Nullable
    public String getOverlapWarning()
    {
        return this.overlapWarning;
    }

    public void onServerResponse(StagingAreaConfigResponseS2CPacket packet)
    {
        if (!packet.success())
        {
            SyncMaterial.LOGGER.warn("[StagingArea] 服务端响应失败: {}", packet.message());
            return;
        }

        String action = packet.action();

        // 仓库引用响应
        if ("LIST_WAREHOUSE_REFS".equals(action) || "ADD_WAREHOUSE_REF".equals(action) || "REMOVE_WAREHOUSE_REF".equals(action))
        {
            if (this.warehouseListWidget != null)
            {
                this.warehouseListWidget.setEntries(packet.areas());
            }
            return;
        }

        // 设置原理图名称
        if (packet.schematicName() != null && !packet.schematicName().isEmpty()) {
            StagingAreaRenderer.getInstance().setSchematicName(this.schematicId, packet.schematicName());
        }

        if (this.loadingFromServer)
        {
            this.loadingFromServer = false;

            java.util.Set<String> serverNames = new java.util.HashSet<>();
            for (var area : packet.areas())
            {
                serverNames.add(area.name());
                this.areaWorlds.put(area.name(), area.world());
                Box existing = this.selection.getSubRegionBox(area.name());
                if (existing != null)
                {
                    existing.setPos1(new BlockPos(area.x1(), area.y1(), area.z1()));
                    existing.setPos2(new BlockPos(area.x2(), area.y2(), area.z2()));
                    existing.setWorld(area.world());
                }
                else
                {
                    BlockPos pos1 = new BlockPos(area.x1(), area.y1(), area.z1());
                    BlockPos pos2 = new BlockPos(area.x2(), area.y2(), area.z2());
                    Box box = new Box(pos1, pos2, area.name());
                    box.setWorld(area.world());
                    this.selection.addSubRegionBox(box, true);
                }
                this.selection.setServerId(area.name(), area.areaId());
            }

            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String name : this.selection.getAllSubRegionNames())
            {
                if (!serverNames.contains(name))
                {
                    toRemove.add(name);
                }
            }
            for (String name : toRemove)
            {
                this.selection.removeSubRegionBox(name);
                this.selection.removeServerId(name);
                this.areaWorlds.remove(name);
            }

            this.refreshStagingList();
            this.publishWorkingCopyPreview();
        }
        else
        {
            for (var area : packet.areas())
            {
                this.areaWorlds.put(area.name(), area.world());
                Box box = this.selection.getSubRegionBox(area.name());
                if (box != null)
                {
                    box.setWorld(area.world());
                }
                if (this.selection.getServerId(area.name()) == null)
                {
                    this.selection.setServerId(area.name(), area.areaId());
                }
            }
            this.publishWorkingCopyPreview();
        }
    }

    /**
     * 编辑器工作副本只作为本地预览登记，绝不写进权威渲染事实
     * （协议 5 权威线框来自 renderer 的不可变 ProjectAreasSnapshot）。
     */
    protected void publishWorkingCopyPreview()
    {
        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            StagingAreaRenderer.getInstance().setEditorPreview(this.schematicId, this.selection);
        }
    }

    /** 备货区所属维度；未知时返回空串 */
    public String getAreaWorld(String areaName)
    {
        return this.areaWorlds.getOrDefault(areaName, "");
    }

    /** 备货区在服务端的真实主键；尚未同步时返回 null */
    @Override
    public Integer getAreaServerId(String areaName)
    {
        return this.selection.getServerId(areaName);
    }

    // ========== GUI 生命周期 ==========

    @Override
    public void initGui()
    {
        super.initGui();
        if (this.schematicId != null && !this.schematicId.isEmpty()) {
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                    session.openEditor(this.schematicId));
            // 工作副本预览随界面重建重新登记（选区转场返回后同样生效）
            this.publishWorkingCopyPreview();
        }

        if (this.selection != null)
        {
            this.createTopButtons();
            this.createBottomButtons();
            this.createListWidgets();
            this.updateCheckBoxes();
        }
        else
        {
            this.addLabel(20, 30, 120, 12, 0xFFFFAA00, StringUtils.translate("syncmaterial.gui.error.no_selection"));
        }

        // 首次打开时从服务端加载备货区数据
        if (this.needsServerLoad && this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.needsServerLoad = false;
            this.loadingFromServer = true;
            readAreaConfig("LIST");
        }

        // 请求仓库引用列表（仅首次）
        if (this.needsWarehouseRefLoad && this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.needsWarehouseRefLoad = false;
            readAreaConfig("LIST_WAREHOUSE_REFS");
        }
        var coordinator = net.syncmaterial.syncmaterial.client.SyncMaterialClient.projectCoordinator();
        if (coordinator != null && this.schematicId != null && !this.schematicId.isEmpty()) {
            coordinator.subscribeConfigurations(this,
                    net.syncmaterial.syncmaterial.core.ProjectViewScope.project(this.schematicId),
                    this::applyProjectViewConfiguration);
        }
    }

    /** 驱动去抖计时。GuiBase 继承自 Screen，每客户端 tick 调用一次 */
    @Override
    public void tick()
    {
        super.tick();
        this.coordinateDebouncer.tick();
        // 推迟的列表刷新：等未落定意图结束（SAVED/IDLE）再重建，避免覆盖正在输入的文本框
        if (this.needsListRefresh
                && (saveTracker.state() == EditorSaveTracker.State.IDLE
                    || saveTracker.state() == EditorSaveTracker.State.SAVED)) {
            this.needsListRefresh = false;
            this.refreshStagingList();
        }
    }

    @Override
    public void closeGui(boolean showParent) {
        // 正常退出固定顺序：先补发未发出的编辑意图，再释放 editor 兴趣与工作副本预览。
        // 子区域页/准星选区等临时转场不经过 closeGui，工作副本与兴趣得以保留。
        this.coordinateDebouncer.flushNow();
        if (this.schematicId != null && !this.schematicId.isEmpty()) {
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                    session.setEditor(this.schematicId, false));
            StagingAreaRenderer.getInstance().setEditorPreview(this.schematicId, null);
        }
        super.closeGui(showParent);
    }

    @Override
    public void removed()
    {
        // 关界面前补发未发出的坐标改动，否则刚敲的值会被丢掉
        this.coordinateDebouncer.flushNow();
        super.removed();
        if (this.stagingListWidget != null) this.stagingListWidget.removed();
        if (this.warehouseListWidget != null) this.warehouseListWidget.removed();
        StagingAreaRenderer.getInstance().clearHighlightedBox();
    }

    // ========== 布局 ==========

    protected void createTopButtons()
    {
        int x = 10;
        int y = 26;

        // 添加备货区：先输入名称，确认后进入准星选区
        x += this.createButton(x, y, -1, ButtonListener.Type.CREATE_SUB_REGION) + 16;

        // 备货区数量
        String str = String.valueOf(this.selection.getAllSubRegionNames().size());
        this.addLabel(x, y + 4, -1, 16, 0xFFFFFFFF,
                GuiBase.TXT_BOLD + StringUtils.translate("syncmaterial.gui.label.staging_areas", str));
    }

    protected void createBottomButtons()
    {
        int yBottom = this.getScreenHeight() - 26;

        // 返回按钮
        String label = StringUtils.translate("gui.back");
        int buttonWidth = StringUtils.getStringWidth(label) + 10;
        int xClose = this.getScreenWidth() - buttonWidth - 10;
        this.addButton(new ButtonGeneric(xClose, yBottom, buttonWidth, 20, label),
                new ButtonListener(ButtonListener.Type.CLOSE, null, null, this));
    }

    protected void createListWidgets()
    {
        int listX = 8;
        int listWidth = this.getScreenWidth() - 16;
        int contentTop = 48;
        int contentBottom = this.getScreenHeight() - 36;
        int totalHeight = contentBottom - contentTop;

        // 上半：备货区列表（约 55%）
        this.stagingListY = contentTop;
        this.stagingListHeight = (int)(totalHeight * 0.55);
        int stagingBottom = stagingListY + stagingListHeight;

        // 分隔线位置
        int separatorY = stagingBottom + 2;

        // 下半：仓库引用列表
        this.warehouseListY = separatorY + 14;
        this.warehouseListHeight = contentBottom - this.warehouseListY;

        // 备货区列表
        this.stagingListWidget = new WidgetListStagingAreas(listX, this.stagingListY, listWidth, this.stagingListHeight, this.selection, this);
        this.stagingListWidget.initGui();

        // 仓库引用列表
        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.warehouseListWidget = new WidgetListWarehouseRefs(listX, this.warehouseListY, listWidth, this.warehouseListHeight, this.schematicId);
            this.warehouseListWidget.initGui();

            // 「添加仓库引用」按钮只在 initGui 里建一次。此前放在 drawContents 中，
            // 每帧都会 new + addButton，按钮列表无限增长（内存泄漏 + 命中测试变慢 + 同位重叠）。
            String warehouseLabel = StringUtils.translate("syncmaterial.gui.label.warehouse_refs_section",
                    String.valueOf(this.warehouseListWidget.getEntryCount()));
            String addLabel = StringUtils.translate("syncmaterial.gui.button.add_warehouse_ref");
            int addWidth = StringUtils.getStringWidth(addLabel) + 10;
            int addX = 14 + StringUtils.getStringWidth(GuiBase.TXT_BOLD + warehouseLabel) + 8;
            ButtonGeneric addBtn = new ButtonGeneric(addX, separatorY + 1, addWidth, 16, addLabel);
            this.addButton(addBtn, (btn, mouseBtn) -> {
                GuiBase.openGui(new GuiWarehouseRefPopup(this.schematicId));
            });
        }
    }

    private void refreshStagingList()
    {
        // 刷新整个界面以更新数量标签等 UI 元素
        this.needsWarehouseRefLoad = true;
        this.initGui();
    }

    // ========== 渲染 ==========

    @Override
    //? if >=26.2 {
    protected void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    //?} else {
    /*protected void drawContents(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks)
    *///?}
    {
        if (this.stagingListWidget != null)
        {
            this.stagingListWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);
        }

        // 分隔线 + 标签
        int separatorY = this.stagingListY + this.stagingListHeight + 2;
        RenderUtils.drawRect(drawContext, 10, separatorY, this.getScreenWidth() - 20, 1, 0xFF555555);
        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            String warehouseLabel = StringUtils.translate("syncmaterial.gui.label.warehouse_refs_section",
                    String.valueOf(this.warehouseListWidget != null ? this.warehouseListWidget.getEntryCount() : 0));
            this.drawString(drawContext, GuiBase.TXT_BOLD + warehouseLabel, 14, separatorY + 3, 0xFF55AAFF);
            // 「添加仓库引用」按钮已移到 createListWidgets 只建一次，这里不再每帧创建
        }

        if (this.warehouseListWidget != null)
        {
            this.warehouseListWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);
        }

        String saveStatus = ProjectViewEditorSupport.saveStatus(saveTracker.state());
        if (!saveStatus.isEmpty()) {
            this.drawString(drawContext, saveStatus, this.getScreenWidth() - 12
                    - StringUtils.getStringWidth(saveStatus), this.getScreenHeight() - 14, 0xFFB0B0B0);
        }
        if (this.overlapWarning != null)
        {
            String text = "⚠ " + StringUtils.translate(AreaOverlapStatus.MESSAGE_KEY);
            int width = this.mc.font.width(text);
            AreaOverlapWarningRenderer.render(drawContext, this.getScreenWidth() - width - 18, 8,
                    this.overlapWarning, mouseX, mouseY);
        }
    }

    @Override
    //? if >=26.2 {
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY)
    //?} else {
    /*protected void drawHoveredWidget(GuiGraphics drawContext, int mouseX, int mouseY)
    *///?}
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);

        if (!this.shouldRenderHoverStuff()) return;

        if (isMouseInRegion(mouseX, mouseY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            this.stagingListWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
        else if (isMouseInRegion(mouseX, mouseY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            this.warehouseListWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    // ========== 事件分发 ==========

    @Override
    //? if >=26.2 {
    public boolean onMouseClicked(MouseButtonEvent event, boolean isDoubleClick)
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

        if (isMouseInRegion(mouseX, mouseY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            //? if >=26.2 {
            return this.stagingListWidget.onMouseClicked(event, isDoubleClick);
            //?} else {
            /*return this.stagingListWidget.onMouseClicked(mouseX, mouseY, mouseButton);
            *///?}
        }
        if (isMouseInRegion(mouseX, mouseY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            //? if >=26.2 {
            return this.warehouseListWidget.onMouseClicked(event, isDoubleClick);
            //?} else {
            /*return this.warehouseListWidget.onMouseClicked(mouseX, mouseY, mouseButton);
            *///?}
        }
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseReleased(MouseButtonEvent event)
    //?} else {
    /*public boolean onMouseReleased(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        if (super.onMouseReleased(event))
        //?} else {
        /*if (super.onMouseReleased(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }
        //? if >=26.2 {
        if (this.stagingListWidget != null) this.stagingListWidget.onMouseReleased(event);
        if (this.warehouseListWidget != null) this.warehouseListWidget.onMouseReleased(event);
        //?} else {
        /*if (this.stagingListWidget != null) this.stagingListWidget.onMouseReleased(mouseX, mouseY, mouseButton);
        if (this.warehouseListWidget != null) this.warehouseListWidget.onMouseReleased(mouseX, mouseY, mouseButton);
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
        //? if >=26.2 {
        int regionX = (int) mouseX;
        int regionY = (int) mouseY;
        //?} else {
        /*int regionX = mouseX;
        int regionY = mouseY;
        *///?}
        if (super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        if (isMouseInRegion(regionX, regionY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            return this.stagingListWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        if (isMouseInRegion(regionX, regionY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            return this.warehouseListWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onKeyTyped(KeyEvent event)
    //?} else {
    /*public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    *///?}
    {
        //? if >=26.2 {
        int key = event.key();
        //?} else {
        /*int key = keyCode;
        *///?}

        //? if >=26.2 {
        if (key != KeyCodes.KEY_ESCAPE && super.onKeyTyped(event))
        //?} else {
        /*if (key != KeyCodes.KEY_ESCAPE && super.onKeyTyped(key, scanCode, modifiers))
        *///?}
        {
            return true;
        }
        // 键盘事件优先给备货区列表（它有搜索栏）
        //? if >=26.2 {
        if (this.stagingListWidget != null && this.stagingListWidget.onKeyTyped(event))
        //?} else {
        /*if (this.stagingListWidget != null && this.stagingListWidget.onKeyTyped(key, scanCode, modifiers))
        *///?}
        {
            return true;
        }
        //? if >=26.2 {
        if (key == KeyCodes.KEY_ESCAPE && super.onKeyTyped(event))
        //?} else {
        /*if (key == KeyCodes.KEY_ESCAPE && super.onKeyTyped(key, scanCode, modifiers))
        *///?}
        {
            return true;
        }
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onCharTyped(CharacterEvent event)
    //?} else {
    /*public boolean onCharTyped(char chr, int modifiers)
    *///?}
    {
        //? if >=26.2 {
        if (super.onCharTyped(event))
        {
            return true;
        }
        if (this.stagingListWidget != null && this.stagingListWidget.onCharTyped(event))
        {
            return true;
        }
        return super.onCharTyped(event);
        //?} else {
        /*if (super.onCharTyped(chr, modifiers))
        {
            return true;
        }
        if (this.stagingListWidget != null && this.stagingListWidget.onCharTyped(chr, modifiers))
        {
            return true;
        }
        return super.onCharTyped(chr, modifiers);
        *///?}
    }

    @Override
    //? if >=26.2 {
    public void resize(int width, int height)
    //?} else {
    /*public void resize(Minecraft mc, int width, int height)
    *///?}
    {
        //? if >=26.2 {
        super.resize(width, height);
        if (this.stagingListWidget != null) this.stagingListWidget.resize(width, height);
        if (this.warehouseListWidget != null) this.warehouseListWidget.resize(width, height);
        //?} else {
        /*super.resize(mc, width, height);
        if (this.stagingListWidget != null) this.stagingListWidget.resize(mc, width, height);
        if (this.warehouseListWidget != null) this.warehouseListWidget.resize(mc, width, height);
        *///?}
    }

    private boolean isMouseInRegion(int mouseX, int mouseY, int regionY, int regionHeight)
    {
        return mouseX >= 8 && mouseX <= this.getScreenWidth() - 8
            && mouseY >= regionY && mouseY <= regionY + regionHeight;
    }

    // ========== 选区回调 ==========

    @Override
    public void onSelectionChange(@Nullable StagingAreaEntry entry)
    {
        if (entry != null)
        {
            this.selection.setSelectedSubRegionBox(entry.name());
            StagingAreaRenderer.getInstance().setHighlightedBox(this.schematicId, entry.name());
        }
    }

    protected List<StagingAreaEntry> getStagingAreaEntries()
    {
        List<StagingAreaEntry> entries = new ArrayList<>();
        int id = 0;
        for (String name : this.selection.getAllSubRegionNames())
        {
            Box box = this.selection.getSubRegionBox(name);
            if (box != null)
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                entries.add(new StagingAreaEntry(id++, name,
                        pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ(), this.getAreaWorld(name)));
            }
        }
        return entries;
    }

    public void onSelectionConfirmed(@Nullable String boxName, BlockPos pos1, BlockPos pos2)
    {
        // 先补发已登记未发出的坐标输入（失效载荷会被去抖器丢弃），再应用本次选区结果
        this.coordinateDebouncer.flushNow();

        // 新建模式由 pendingAreaName 标记：名称已在弹窗中拿到，这里只补范围。
        // 取完立即清空，避免下次改坐标时被误判成新建。
        String newAreaName = this.pendingAreaName;
        this.pendingAreaName = null;

        if (newAreaName != null)
        {
            String world = currentWorldId();
            Box newBox = new Box(pos1, pos2, newAreaName);
            newBox.setWorld(world);
            this.selection.addSubRegionBox(newBox, true);
            this.selection.setSelectedSubRegionBox(newAreaName);
            if (world != null) this.areaWorlds.put(newAreaName, world);

            if (this.schematicId != null && !this.schematicId.isEmpty())
            {
                AreaData areaData = toAreaData(newAreaName, pos1, pos2, world);
                writeAreaConfig("ADD", 0, Optional.of(areaData));
            }
        }
        else if (boxName != null)
        {
            Box box = this.selection.getSubRegionBox(boxName);
            if (box != null)
            {
                box.setPos1(pos1);
                box.setPos2(pos2);

                // 带上当前维度：准星选区只能在玩家所在维度进行，
                // 若跨维度重选范围，world 必须一起迁移，否则坐标与维度不一致
                String world = currentWorldId();
                box.setWorld(world);
                if (world != null) this.areaWorlds.put(boxName, world);

                Integer serverId = this.selection.getServerId(boxName);
                if (serverId != null && this.schematicId != null && !this.schematicId.isEmpty())
                {
                    AreaData areaData = toAreaData(boxName, pos1, pos2, world);
                    writeAreaConfig("UPDATE", serverId, Optional.of(areaData));
                }
            }
            else
            {
                // 既有目标已删除（权威配置/引用列表里没有了）：
                // 不得降级成 ADD 写回一个同名新区域
                SyncMaterial.LOGGER.info(
                        "[StagingArea] 选区确认的目标 {} 已不存在，丢弃结果且不降级为新建", boxName);
            }
        }

        this.publishWorkingCopyPreview();
        this.refreshStagingList();
    }

    @Nullable
    public ISelectionListener<StagingAreaEntry> getSelectionListener()
    {
        return entry -> {
            if (entry != null)
            {
                this.selection.setSelectedSubRegionBox(entry.name());
                StagingAreaRenderer.getInstance().setHighlightedBox(this.schematicId, entry.name());
            }
        };
    }

    // ========== 坐标编辑（保持不变） ==========

    protected void renameSubRegion()
    {
    }

    protected int createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label = "";
        WidgetCheckBox widget = null;

        switch (corner)
        {
            case CORNER_1:
                label = StringUtils.translate("syncmaterial.gui.label.corner_1");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner1 = widget;
                break;
            case CORNER_2:
                label = StringUtils.translate("syncmaterial.gui.label.corner_2");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner2 = widget;
                break;
        }

        if (widget != null)
        {
            widget.setListener(new CheckBoxListener(corner, this));
            this.addWidget(widget);
        }
        y += 14;

        this.createCoordinateInput(x, y, width, CoordinateType.X, corner);
        y += 20;

        this.createCoordinateInput(x, y, width, CoordinateType.Y, corner);
        y += 20;

        this.createCoordinateInput(x, y, width, CoordinateType.Z, corner);
        y += 22;

        this.createButton(x + 10, y, -1, corner, ButtonListener.Type.MOVE_TO_PLAYER);
        y += 22;

        return y;
    }

    protected void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        String label = coordType.name() + ":";
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, label);
        int offset = 12;

        y += 2;
        BlockPos pos = corner == Corner.NONE ? this.selection.getEffectiveOrigin() : this.getBox().getPosition(corner);
        String text = "";
        ButtonListener.Type type = null;

        switch (coordType)
        {
            case X:
                text = String.valueOf(pos.getX());
                type = ButtonListener.Type.NUDGE_COORD_X;
                break;
            case Y:
                text = String.valueOf(pos.getY());
                type = ButtonListener.Type.NUDGE_COORD_Y;
                break;
            case Z:
                text = String.valueOf(pos.getZ());
                type = ButtonListener.Type.NUDGE_COORD_Z;
                break;
        }

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.mc.font);
        TextFieldListener listener = new TextFieldListener(coordType, corner, this);
        textField.setTextWrapper(text);
        this.addTextField(textField, listener);

        this.createCoordinateButton(x + offset + width + 4, y, corner, coordType, type);
    }

    protected int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        return this.createButton(x, y, width, null, type);
    }

    protected int createButton(int x, int y, int width, @Nullable Corner corner, ButtonListener.Type type)
    {
        String label = type.getDisplayName();
        if (width == -1)
        {
            width = StringUtils.getStringWidth(label) + 10;
        }
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        ButtonListener listener = new ButtonListener(type, corner, null, this);
        this.addButton(button, listener);
        return width;
    }

    protected void createCoordinateButton(int x, int y, Corner corner, CoordinateType coordType, ButtonListener.Type type)
    {
        String hover = StringUtils.translate("syncmaterial.gui.button.hover.plus_minus_tip");
        ButtonGeneric button = new ButtonGeneric(x, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        ButtonListener listener = new ButtonListener(type, corner, coordType, this);
        this.addButton(button, listener);
    }

    protected void updateCheckBoxes()
    {
        if (this.checkBoxCorner1 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_1;
            this.checkBoxCorner1.setChecked(checked, false);
        }
        if (this.checkBoxCorner2 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_2;
            this.checkBoxCorner2.setChecked(checked, false);
        }
    }

    @Nullable
    protected Box getBox()
    {
        return null;
    }

    /** 应用一次坐标输入；返回 false 表示输入仍不合法（未产生编辑意图）。 */
    protected boolean updatePosition(String numberString, Corner corner, CoordinateType type)
    {
        try
        {
            int value = Integer.parseInt(numberString);
            this.selection.setCoordinate(this.getBox(), corner, type, value);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    protected void moveCoordinate(int amount, Corner corner, CoordinateType type)
    {
        int oldValue = 0;
        if (corner == Corner.NONE)
        {
            oldValue = PositionUtils.getCoordinate(this.selection.getEffectiveOrigin(), type);
        }
        else if (this.getBox() != null)
        {
            oldValue = this.getBox().getCoordinate(corner, type);
        }
        this.selection.setCoordinate(this.getBox(), corner, type, oldValue + amount);
    }

    protected void sendCoordinateUpdate(Corner corner)
    {
        if (corner == Corner.NONE || this.schematicId == null)
        {
            return;
        }

        Box box = this.getBox();
        if (box == null)
        {
            box = this.selection.getSelectedSubRegionBox();
        }
        if (box == null)
        {
            return;
        }

        Integer serverId = this.selection.getServerId(box.getName());
        if (serverId == null)
        {
            return;
        }

        // 先补发已登记未发出的输入（上下文/版本失效的会被去抖器丢弃），再立即发送本次动作，
        // 避免逐字符输入的旧值晚于按钮动作到达服务端
        this.coordinateDebouncer.flushNow();
        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();
        AreaData areaData = toAreaData(box.getName(), pos1, pos2);
        this.pendingCoordinateServerId = serverId;
        writeAreaConfig("UPDATE", serverId, Optional.of(areaData));
    }

    private long writeAreaConfig(String action, int areaId, Optional<AreaData> data) {
        int legacyId = areaId == 0 ? -1 : areaId;
        return ProjectViewEditorSupport.write(net.syncmaterial.syncmaterial.core.ProjectViewScope.project(schematicId),
                areaId, action, data, new StagingAreaConfigC2SPacket(schematicId, action, legacyId, data), saveTracker);
    }

    private long readAreaConfig(String action) {
        int legacyId = action.equals("LIST_WAREHOUSE_REFS") ? 0 : -1;
        return ProjectViewEditorSupport.read(net.syncmaterial.syncmaterial.core.ProjectViewScope.project(schematicId),
                action, new StagingAreaConfigC2SPacket(schematicId, action, legacyId, Optional.empty()));
    }

    /**
     * Merges an accepted protocol-5 configuration into this editor's independent working
     * copy. The target of a pending/SAVING/FAILED/UNCONFIRMED intent keeps its local
     * value (authoritative refreshes must not erase uncommitted input, including while
     * this editor is hidden behind a child dialog); all other targets update immediately,
     * and targets the server no longer has are removed. On-screen widget rebuild is
     * deferred while an intent is outstanding so live text fields are not reset.
     */
    public void applyProjectViewConfiguration(String action,
            java.util.List<net.syncmaterial.syncmaterial.core.AreaDefinition> definitions) {
        if (!"LIST".equals(action)) {
            // LIST_WAREHOUSE_REFS 等列表响应维持既有整表替换路径
            var areas = definitions.stream().map(area -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
                    area.id(), area.name(), area.bounds().x1(), area.bounds().y1(), area.bounds().z1(),
                    area.bounds().x2(), area.bounds().y2(), area.bounds().z2(), area.world())).toList();
            onServerResponse(new StagingAreaConfigResponseS2CPacket(action, schematicId, "", true, "", areas));
            return;
        }

        // 协议 5 下首个权威 LIST 即完成初始装载（旧路径由 onServerResponse 复位该标记）
        this.loadingFromServer = false;
        boolean protecting = switch (saveTracker.state()) {
            case IDLE, SAVED -> false;
            default -> true;
        };
        boolean changed = false;
        java.util.Set<String> serverNames = new java.util.HashSet<>();
        for (var area : definitions) {
            serverNames.add(area.name());
            this.areaWorlds.put(area.name(), area.world());
            if (protecting && this.pendingCoordinateServerId != null
                    && this.pendingCoordinateServerId == area.id()) {
                // 正在编辑（或刚提交未确认/失败）的目标保留本地值，等待协调
                continue;
            }
            changed |= applyAuthoritativeTarget(area);
        }
        if (!protecting) {
            for (String name : java.util.List.copyOf(this.selection.getAllSubRegionNames())) {
                if (!serverNames.contains(name)) {
                    this.selection.removeSubRegionBox(name);
                    this.selection.removeServerId(name);
                    this.areaWorlds.remove(name);
                    changed = true;
                }
            }
        }
        this.publishWorkingCopyPreview();
        // 只有工作副本真的变化时才重建列表：权威 store 会在每次状态更新时重复推送
        // 相同 LIST，若无条件 refreshStagingList（initGui 重发 LIST_WAREHOUSE_REFS）
        // 会形成"响应→合并→刷新→新请求"的无限循环（真实 GameTest 抓住的回归）
        if (protecting) {
            if (changed) {
                // 有未落定意图时不重建控件，避免覆盖正在输入的文本框；列表稍后刷新
                this.needsListRefresh = true;
            }
        } else if (changed) {
            this.refreshStagingList();
        }
    }

    /** 把一条权威配置合并进工作副本；返回是否发生了可见变化（需要重建列表）。 */
    private boolean applyAuthoritativeTarget(net.syncmaterial.syncmaterial.core.AreaDefinition area) {
        BlockPos pos1 = new BlockPos(area.bounds().x1(), area.bounds().y1(), area.bounds().z1());
        BlockPos pos2 = new BlockPos(area.bounds().x2(), area.bounds().y2(), area.bounds().z2());
        Box box = this.selection.getSubRegionBox(area.name());
        Integer currentId = this.selection.getServerId(area.name());
        if (box != null) {
            boolean same = currentId != null && currentId == area.id()
                    && box.getPos1() != null && box.getPos1().equals(pos1)
                    && box.getPos2() != null && box.getPos2().equals(pos2)
                    && java.util.Objects.equals(box.getWorld(), area.world());
            if (same) {
                return false;
            }
            box.setPos1(pos1);
            box.setPos2(pos2);
            box.setWorld(area.world());
            if (currentId == null || currentId != area.id()) {
                this.selection.setServerId(area.name(), area.id());
            }
            return true;
        }
        Box created = new Box(pos1, pos2, area.name());
        created.setWorld(area.world());
        this.selection.addSubRegionBox(created, true);
        this.selection.setServerId(area.name(), area.id());
        return true;
    }

    /**
     * 登记一次延迟发出的坐标更新，供文本框逐字符输入使用。
     *
     * <p>合法输入在登记时就调用 {@link EditorSaveTracker#edit()} 产生版本；去抖载荷
     * 携带该版本以及登记时刻的不可变目标（scope/areaId）与完整坐标快照，发送时
     * 不再重新读取当前选中的 Box，也不重新制造版本。连续输入只会发出最后一次；
     * 上下文失效或版本被更新动作取代时，载荷被丢弃而不是发到旧目标。</p>
     */
    protected void scheduleCoordinateUpdate(Corner corner)
    {
        if (this.schematicId == null || this.schematicId.isEmpty())
        {
            return;
        }
        Box box = this.getBox();
        if (box == null)
        {
            box = this.selection.getSelectedSubRegionBox();
        }
        if (box == null || box.getPos1() == null || box.getPos2() == null)
        {
            return;
        }
        Integer serverId = this.selection.getServerId(box.getName());
        if (serverId == null)
        {
            return;
        }

        long version = this.saveTracker.edit();
        net.syncmaterial.syncmaterial.core.ProjectViewScope scope =
                net.syncmaterial.syncmaterial.core.ProjectViewScope.project(this.schematicId);
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(scope);
        // 登记时刻的不可变载荷：目标主键 + 完整坐标（world 保持既有语义：文本编辑不改维度）
        AreaData payload = toAreaData(box.getName(), box.getPos1(), box.getPos2());
        int targetId = serverId;
        String projectId = this.schematicId;
        this.pendingCoordinateServerId = targetId;
        this.coordinateDebouncer.schedule(() -> {
                    this.pendingCoordinateServerId = null;
                    ProjectViewEditorSupport.writePrepared(scope, targetId, "UPDATE",
                            Optional.of(payload),
                            new StagingAreaConfigC2SPacket(projectId, "UPDATE", targetId, Optional.of(payload)),
                            this.saveTracker, version);
                },
                ProjectViewEditorSupport.intentValidity(fence, version, this.saveTracker));
    }

    // ========== 内部类 ==========

    protected static class ButtonListener implements IButtonActionListener
    {
        private final GuiStagingAreaEditorNormal parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        public ButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType, GuiStagingAreaEditorNormal parent)
        {
            this.type = type;
            this.corner = corner;
            this.coordinateType = coordinateType;
            this.parent = parent;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            int amount = CoordinateNudge.amount(mouseButton);

            this.parent.setNextMessageType(MessageType.ERROR);

            switch (this.type)
            {
                case NUDGE_COORD_X:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case NUDGE_COORD_Y:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case NUDGE_COORD_Z:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case CREATE_SUB_REGION:
                {
                    GuiTextInput gui = new GuiTextInput(512, "litematica.gui.title.area_editor.sub_region_name", "", null, new SubRegionCreator(this.parent));
                    gui.setParent(this.parent);
                    GuiBase.openGui(gui);
                    break;
                }

                case SET_BOX_NAME:
                {
                    this.parent.renameSubRegion();
                    break;
                }

                case MOVE_TO_PLAYER:
                    if (this.parent.mc.player != null)
                    {
                        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.parent.mc.player);

                        if (this.corner == Corner.NONE)
                        {
                            this.parent.selection.setExplicitOrigin(pos);
                        }
                        else
                        {
                            this.parent.selection.setSelectedSubRegionCornerPos(pos, this.corner);
                            this.parent.sendCoordinateUpdate(this.corner);
                        }
                    }
                    break;

                case CLOSE:
                    this.parent.closeGui(true);
                    return;
            }

            this.parent.initGui();
        }

        public enum Type
        {
            SET_BOX_NAME            ("syncmaterial.gui.button.rename_staging_area"),
            CREATE_SUB_REGION       ("syncmaterial.gui.button.add_staging_area"),
            MOVE_TO_PLAYER          ("litematica.gui.button.move_to_player"),
            CLOSE                   ("gui.back"),
            NUDGE_COORD_X           (""),
            NUDGE_COORD_Y           (""),
            NUDGE_COORD_Z           ("");

            private final String translationKey;

            private Type(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getTranslationKey()
            {
                return this.translationKey;
            }

            public String getDisplayName(Object... args)
            {
                return StringUtils.translate(this.translationKey, args);
            }
        }
    }

    protected static class TextFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiStagingAreaEditorNormal parent;
        private final CoordinateType type;
        private final Corner corner;

        public TextFieldListener(CoordinateType type, Corner corner, GuiStagingAreaEditorNormal parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            // 只有合法坐标才登记编辑意图；非法中间态（空串、单负号）不产生版本
            if (this.parent.updatePosition(textField.getTextWrapper(), this.corner, this.type)) {
                this.parent.scheduleCoordinateUpdate(this.corner);
            }
            return false;
        }
    }

    public static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            return false;
        }
    }

    protected static class SubRegionCreator implements IStringConsumerFeedback
    {
        private final GuiStagingAreaEditorNormal gui;

        private SubRegionCreator(GuiStagingAreaEditorNormal gui)
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

            String name = string.trim();
            if (this.gui.selection.getSubRegionBox(name) != null)
            {
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR,
                        StringUtils.translate("syncmaterial.gui.error.duplicate_area_name", name));
                return false;
            }

            this.gui.pendingAreaName = name;

            // 直接启动即可：MaLiLib 弹窗基类在本方法返回 true 之后还会执行
            // openGui(parent) 把编辑器重新打开，但 StagingAreaSelector 会在
            // onTick 里持续关闭界面直到真正退出，不需要调用方安排时序。
            // 新建备货区同样捕获所属项目 scope：确认时才能校验项目视图仍然有效。
            StagingAreaSelector.getInstance().start(this.gui, this.gui, null, null, null,
                    StagingAreaSelector.TargetType.STAGING_AREA, this.gui.schematicId, -1);
            return true;
        }
    }

    protected static class CheckBoxListener implements ISelectionListener<WidgetCheckBox>
    {
        private final GuiStagingAreaEditorNormal gui;
        private final Corner corner;

        public CheckBoxListener(Corner corner, GuiStagingAreaEditorNormal gui)
        {
            this.gui = gui;
            this.corner = corner;
        }

        @Override
        public void onSelectionChange(WidgetCheckBox entry)
        {
            if (entry.isChecked())
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(true);
                    this.gui.selection.clearCurrentSelectedCorner();
                }
                else
                {
                    this.gui.selection.setOriginSelected(false);
                    this.gui.selection.setCurrentSelectedCorner(this.corner);
                }
            }
            else
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(false);
                }
                else
                {
                    this.gui.selection.clearCurrentSelectedCorner();
                }
            }

            this.gui.updateCheckBoxes();
        }
    }
}
