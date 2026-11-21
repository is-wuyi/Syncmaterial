package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase.SortCriteria;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;
    private boolean isOwner;
    private boolean isMainOwner;
    private String ownerName;
    private List<String> deputyOwners;
    private boolean allowSelfClaim;
    /** 最近一次权威团队状态；旧服务端没有该对象，身份只能回退为名字。 */
    private TeamState teamState;
    private boolean filterMyMaterials = false;
    private boolean legacyViewRequested;
    // 取货模式状态与需求量统一由 PickupModeState 持有（见该类注释）
    private List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessWarnings = java.util.Collections.emptyList();
    /** LIST 快照携带的区域重叠提示；null 表示无提示。 */
    private String overlapWarning;
    /** 每项选择带独立意图版本，迟到的分配回执不能清掉新勾选。 */
    private final MaterialSelection selectedMaterials = new MaterialSelection();
    /** 最近一次刷新响应（测试断言 RescanResponse 真实回流；null=从未收到） */
    private Boolean lastRescanResult;
    private final List<fi.dy.masa.malilib.gui.button.ButtonBase> toolbarControls = new ArrayList<>();
    private final List<fi.dy.masa.malilib.gui.button.ButtonBase> footerControls = new ArrayList<>();
    private static boolean stagingRenderEnabled = true;
    /** 右栏「已选 N 种材料」动态文本的 y 坐标（initGui 布局时写入，非 owner 为 -1 不画） */
    private int mgmtSelectedLabelY = -1;

    /**
     * 列表与右栏共用的顶边。顶部按钮行占 24~43 行，而 RenderUtils.drawOutlinedBox 的
     * 边线画在给定矩形**外侧** 1px（内部是 drawOutline(x-1, y-1, w+2, h+2)），
     * 因此顶边取 48 才能让边线落在 47 行、与按钮行之间留出 3px 可见间隙。
     */
    private static final int CONTENT_TOP = 48;
    /** 底部留白：边线画在 bottom 外 1px，留 6px 保证边线不被屏幕底边裁掉 */
    private static final int CONTENT_BOTTOM_MARGIN = 34;
    private static final int WARNING_LINE_HEIGHT = 18;

    private int warningHeight() {
        return (this.overlapWarning != null ? WARNING_LINE_HEIGHT : 0)
                + (this.freshnessWarnings.isEmpty() ? 0 : WARNING_LINE_HEIGHT);
    }

    private int contentTop() {
        return CONTENT_TOP + Math.max(0, this.toolbarRows() - 1) * 22 + this.warningHeight();
    }

    private boolean wideTeam() {
        return this.isOwner && MaterialListLayout.modeForScreenWidth(this.getScreenWidth())
                == MaterialListLayout.Mode.WIDE_SIDEBAR;
    }

    private List<Integer> toolbarWidths() {
        List<Integer> widths = new ArrayList<>(List.of(
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.close")) + 10,
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.refresh")) + 10,
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.staging_area_config")) + 10));
        if (!this.wideTeam()) widths.add(StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.title.management")) + 10);
        return widths;
    }

    private int toolbarRows() {
        return MaterialListLayout.packControls(this.getScreenWidth(), this.toolbarWidths(), true).rows();
    }

    private int footerRows() {
        List<Integer> widths = List.of(
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.hud_toggle",
                        StringUtils.translate(this.materialList.getHudRenderer().getShouldRender()
                                ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"))) + 10,
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                        StringUtils.translate(this.isPickupMode() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"))) + 10,
                StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.staging_wireframe",
                        StringUtils.translate(stagingRenderEnabled ? "syncmaterial.gui.label.show" : "syncmaterial.gui.label.hide"))) + 10,
                StringUtils.getStringWidth(StringUtils.translate(this.filterMyMaterials
                        ? "syncmaterial.gui.button.show_all" : "syncmaterial.gui.button.show_my_materials")) + 10);
        int rows = MaterialListLayout.packControls(this.getScreenWidth(), widths, false).rows();
        int total = widths.stream().mapToInt(Integer::intValue).sum() + 6;
        if (this.isOwner && !this.wideTeam() && !this.selectedMaterials.isEmpty()) {
            int batchWidth = StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.kick"))
                    + StringUtils.getStringWidth(StringUtils.translate("syncmaterial.gui.button.assign_to")) + 22;
            if (total + batchWidth + 4 > this.getScreenWidth() - 24) rows++;
        }
        return rows;
    }

    private void relayoutAfterWarningChange(int previousHeight) {
        if (previousHeight == this.warningHeight()) return;
        var previous = this.getListWidget().viewportState();
        this.setListPosition(10, this.contentTop());
        this.reCreateListWidget();
        this.getListWidget().restoreViewportState(previous);
        this.initGui();
    }

    public GuiMaterialList(String schematicId, String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        super(10, CONTENT_TOP);

        this.isOwner = isOwner;
        this.isMainOwner = isMainOwner;
        this.ownerName = ownerName != null ? ownerName : "";
        this.deputyOwners = deputyOwners != null ? new ArrayList<>(deputyOwners) : new ArrayList<>();
        this.allowSelfClaim = allowSelfClaim;
        this.materialList = new SyncMaterialList(schematicId, schematicName);
        this.materialList.setAllowSelfClaim(allowSelfClaim);
        this.materialList.setIsOwner(isOwner);
        this.materialList.setOnStatusUpdate(() -> this.getListWidget().refreshEntries());
        this.materialList.setMaterialEntries(entries);
        this.materialList.setOnMaterialCatalogChanged(this::retainMaterialSelection);
        this.title = this.materialList.getTitle();
        this.useTitleHierarchy = false;

        // 设置原理图名称，用于备货区线框标注
        net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
            .setSchematicName(schematicId, schematicName);

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
    }

    public SyncMaterialList getMaterialList() { return this.materialList; }
    public boolean isOwner() { return isOwner; }
    public boolean isPickupMode() { return net.syncmaterial.syncmaterial.client.PickupModeState.isActive(); }
    public static boolean isPickupModeStatic() { return net.syncmaterial.syncmaterial.client.PickupModeState.isActive(); }

    /**
     * 更新数据新鲜度警告（从协作状态包接收）
     */
    public void updateFreshnessWarnings(List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> info) {
        int previousHeight = this.warningHeight();
        this.freshnessWarnings = info != null ? List.copyOf(info) : java.util.Collections.emptyList();
        this.relayoutAfterWarningChange(previousHeight);
    }

    public List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> getFreshnessWarnings() {
        return freshnessWarnings;
    }

    /** 成功 LIST 响应的重叠提示；空串/null 清除。 */
    public void updateOverlapWarning(String message) {
        int previousHeight = this.warningHeight();
        this.overlapWarning = AreaOverlapWarningRenderer.normalize(message);
        this.relayoutAfterWarningChange(previousHeight);
    }

    public String getOverlapWarning() {
        return overlapWarning;
    }

    public boolean isMainOwner() { return isMainOwner; }
    public String getOwnerName() { return ownerName; }
    public List<String> getDeputyOwners() { return deputyOwners; }
    public boolean isAllowSelfClaim() { return allowSelfClaim; }
    public boolean isFilterMyMaterials() { return filterMyMaterials; }
    public List<Integer> getSelectedMaterialIds() { return selectedMaterials.ids(); }

    @Override
    protected int getBrowserWidth() {
        // owner 时列表右边缘停在管理栏左侧 6px 处（列表 listX=10，见构造的 super(10, CONTENT_TOP)）
        return isOwner && net.syncmaterial.syncmaterial.client.gui.MaterialListLayout
                .modeForScreenWidth(this.getScreenWidth()) == net.syncmaterial.syncmaterial.client.gui.MaterialListLayout.Mode.WIDE_SIDEBAR
                ? this.mgmtPanelLeft() - 10 - 6 : this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        // 为横向滚动提示保留底部文字行，不让长语言提示压住最后一行来源条。
        return this.getScreenHeight() - this.contentTop() - CONTENT_BOTTOM_MARGIN - 12
                - Math.max(0, this.footerRows() - 1) * 22;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    // ========== 初始化 ==========

    @Override
    public void initGui() {
        this.title = this.materialList.getTitle();
        var list = this.getListWidget();
        if (list.originY() != this.contentTop()) {
            var previous = list.viewportState();
            this.setListPosition(10, this.contentTop());
            this.reCreateListWidget();
            this.getListWidget().restoreViewportState(previous);
        }
        this.setListPosition(10, this.contentTop());
        super.initGui();
        int gap = 2;
        this.toolbarControls.clear();
        int x = this.getScreenWidth() - 20;
        x -= this.createButtonClose(x, 24) + gap;
        x -= this.createButtonRefresh(x, 24) + gap;
        x -= this.createButtonStagingArea(x, 24) + gap;
        boolean wideTeam = this.wideTeam();
        if (!wideTeam) this.createButtonTeam(x, 24);
        List<fi.dy.masa.malilib.gui.button.ButtonBase> topControls = this.toolbarControls;
        var topLayout = MaterialListLayout.packControls(this.getScreenWidth(),
                topControls.stream().map(fi.dy.masa.malilib.gui.button.ButtonBase::getWidth).toList(), true);
        for (int index = 0; index < topControls.size(); index++) {
            var position = topLayout.positions().get(index);
            topControls.get(index).setPosition(position.x(), 24 + position.y());
        }
        if (wideTeam) this.createManagementPanel();
        this.footerControls.clear();
        int bottomX = 12;
        int bottomY = this.getScreenHeight() - 24;
        bottomX += this.createButtonToggleHud(bottomX, bottomY) + gap;
        bottomX += this.createButtonTogglePickupMode(bottomX, bottomY) + gap;
        bottomX += this.createButtonToggleStagingRender(bottomX, bottomY) + gap;
        this.createButtonFilterMyMaterials(bottomX, bottomY);
        List<fi.dy.masa.malilib.gui.button.ButtonBase> bottomControls = this.footerControls;
        var bottomLayout = MaterialListLayout.packControls(this.getScreenWidth(),
                bottomControls.stream().map(fi.dy.masa.malilib.gui.button.ButtonBase::getWidth).toList(), false);
        int footerY = bottomY - Math.max(0, this.footerRows() - 1) * 22;
        for (int index = 0; index < bottomControls.size(); index++) {
            var position = bottomLayout.positions().get(index);
            bottomControls.get(index).setPosition(position.x(), footerY + position.y());
        }
        if (isOwner && !wideTeam && !this.selectedMaterials.isEmpty()) {
            int batchX = this.getScreenWidth() - 12;
            batchX -= this.createButtonKick(batchX, bottomY) + gap;
            this.createButtonAssign(batchX, bottomY);
        }
        if (teamPopup) createTeamPopup();
        else popupButtons.clear();
        if (!legacyViewRequested && !net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            legacyViewRequested = true;
            this.materialList.requestCollaborationStatus();
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null && !schematicId.isEmpty()) {
                ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket(
                        schematicId, "LIST", -1, java.util.Optional.empty()));
            }
        }
    }

    private int createButtonRefresh(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.refresh"));
        this.addButton(button, (btn, mouseButton) -> {
            if (refreshStagingAreas()) {
                btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.refreshing"));
                btn.setEnabled(false);
            }
        });
        toolbarControls.add(button);
        return button.getWidth();
    }

    /** 刷新按钮动作：发送备货区重扫请求（测试钩子与按钮走同一方法） */
    public boolean refreshStagingAreas() {
        String schematicId = this.materialList.getSchematicId();
        if (schematicId != null && !schematicId.isEmpty()) {
            if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
                return net.syncmaterial.syncmaterial.client.ProjectViewClient.current()
                        .map(session -> session.rescan(schematicId, 0) > 0).orElse(false);
            }
            ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(schematicId));
            return true;
        }
        return false;
    }

    private int createButtonStagingArea(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.staging_area_config"));
        this.addButton(button, (btn, mouseButton) -> {
            AreaSelection selection = new AreaSelection();
            GuiStagingAreaEditorNormal editor = new GuiStagingAreaEditorNormal(selection, null, this.materialList.getSchematicId());
            editor.setParent(this);
            net.syncmaterial.syncmaterial.client.ClientScreenAccess.showScreen(this.mc, editor);
        });
        toolbarControls.add(button);
        return button.getWidth();
    }

    private int createButtonToggleStagingRender(int x, int y) {
        String label = StringUtils.translate("syncmaterial.gui.button.staging_wireframe",
                StringUtils.translate(stagingRenderEnabled ? "syncmaterial.gui.label.show" : "syncmaterial.gui.label.hide"));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            stagingRenderEnabled = !stagingRenderEnabled;
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null) {
                net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance().setRenderEnabled(schematicId, stagingRenderEnabled);
            }
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.staging_wireframe",
                    StringUtils.translate(stagingRenderEnabled ? "syncmaterial.gui.label.show" : "syncmaterial.gui.label.hide")));
        });
        footerControls.add(button);
        return button.getWidth();
    }

    // Phase 5: 取货模式切换按钮
    private int createButtonTogglePickupMode(int x, int y) {
        String label = StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                StringUtils.translate(isPickupMode() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            boolean next = togglePickupMode();
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                    StringUtils.translate(next ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")));
        });
        footerControls.add(button);
        return button.getWidth();
    }

    /**
     * 取货模式切换按钮动作：切换本地状态 + 订阅/退订仓库容器数据，返回新状态
     * （测试钩子与按钮走同一方法）。
     */
    public boolean togglePickupMode() {
        boolean next = !isPickupMode();
        net.syncmaterial.syncmaterial.client.PickupModeState.setActive(next);
        // 协议 5 只使用关联视图兴趣，避免旧订阅与新快照同时覆写同一容器缓存。
        String schematicId = this.materialList.getSchematicId();
        if (schematicId != null && !schematicId.isEmpty()) {
            boolean scoped = net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS);
            var session = net.syncmaterial.syncmaterial.client.ProjectViewClient.current().orElse(null);
            if (scoped && session != null) {
                session.setPickup(schematicId, next);
            } else {
                ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket(schematicId, next));
            }
        }
        if (!next) {
            // 退出取货模式时清空容器缓存（需求量由 setActive 负责清空）
            net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance().clearWarehouseContainers();
        } else {
            // 立刻算一次，不必等下一个 tick 周期，避免刚开启时线框/高亮空一拍
            net.syncmaterial.syncmaterial.client.PickupModeState.recompute(
                    this.materialList.getMaterialsAll(),
                    net.syncmaterial.syncmaterial.client.InventoryScanner
                            .liveCountsByItemId(this.mc.player));
        }
        // 刷新列表显示
        this.getListWidget().refreshEntries();
        return next;
    }

    private int createButtonToggleHud(int x, int y) {
        String label = StringUtils.translate("syncmaterial.gui.button.hud_toggle",
                StringUtils.translate(this.materialList.getHudRenderer().getShouldRender() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.hud_toggle",
                    StringUtils.translate(this.materialList.getHudRenderer().getShouldRender() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")));
        });
        footerControls.add(button);
        return button.getWidth();
    }

    private int createButtonFilterMyMaterials(int x, int y) {
        String label = filterMyMaterials ? StringUtils.translate("syncmaterial.gui.button.show_all") : StringUtils.translate("syncmaterial.gui.button.show_my_materials");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, false, label);
        this.addButton(button, (btn, mouseButton) -> {
            filterMyMaterials = !filterMyMaterials;
            btn.setDisplayString(filterMyMaterials ? StringUtils.translate("syncmaterial.gui.button.show_all") : StringUtils.translate("syncmaterial.gui.button.show_my_materials"));
            this.getListWidget().refreshFromBeginning();
        });
        footerControls.add(button);
        return button.getWidth();
    }

    // ========== 右侧管理栏（Litematica 放置配置同款双栏布局）==========

    private static final int MGMT_PANEL_W = net.syncmaterial.syncmaterial.client.gui.MaterialListLayout.TEAM_PANEL_WIDTH;
    /** 面板内容与边框的间距 */
    private static final int MGMT_PAD = 6;
    /** 副负责人平铺上限：超过则折叠成「还有 N 位…」按钮，点击弹批量移除弹窗 */
    private static final int MGMT_DEPUTY_SHOWN = 4;

    /** 面板外框左边缘：右边缘与顶部按钮行、列表右侧统一留 10px 屏幕边距 */
    private int mgmtPanelLeft() {
        return this.getScreenWidth() - 10 - (MGMT_PANEL_W + MGMT_PAD * 2);
    }

    /** 面板内容左边缘（外框内缩一个 padding） */
    private int mgmtContentLeft() {
        return this.mgmtPanelLeft() + MGMT_PAD;
    }

    /** 面板底边 = 列表底边，右栏与列表同高（避免右下角出现大片空白） */
    private int mgmtPanelBottom() {
        return this.contentTop() + this.getBrowserHeight();
    }

    /** 区块分隔线的 y 坐标（initGui 布局时产生，drawScreenBackground 消费） */
    private final List<Integer> mgmtDividerYs = new ArrayList<>();

    /**
     * 右栏全部用 malilib 标准控件（addLabel / ButtonGeneric / ButtonOnOff）按绝对坐标
     * 竖排——与 Litematica GuiPlacementConfiguration 的右栏做法一致。
     *
     * 布局规矩，都是被中文标签与实机截图逼出来的：
     * 1. 文本各占一行，按钮各占一行，不并排（副负责人的 × 除外，它只有 14px）
     * 2. 所有主按钮统一整栏宽，左右边缘对齐
     * 3. 面板与列表同高同顶同底，右下角不留大片空白
     * 4. 「负责人」区从顶部往下排，「材料操作」区从底部往上排 —— 中间的伸缩
     *    空隙吸收副负责人行数变化，两端始终贴边，不会出现整片控件挤在上半部
     *
     * 唯一动态文本「已选 N 种材料」在 drawContents 每帧现画，勾选变化无需重建。
     */
    private void createManagementPanel() {
        int x = this.mgmtContentLeft();
        int w = MGMT_PANEL_W;
        int y = this.contentTop() + MGMT_PAD;
        this.mgmtDividerYs.clear();

        this.addLabel(x, y, w, 12, 0xFFFFFFFF,
                StringUtils.translate("syncmaterial.gui.title.management"));
        y += 16;
        this.mgmtDividerYs.add(y);
        y += 6;

        // ---- 负责人区（自顶向下）----
        this.addLabel(x, y, w, 10, 0xFF888888,
                StringUtils.translate("syncmaterial.gui.label.section_owner"));
        y += 13;

        this.addLabel(x, y, w, 12, 0xFF55FF55,
                StringUtils.translate("syncmaterial.gui.label.main_owner", ownerName));
        y += 14;

        if (isMainOwner) {
            ButtonGeneric transferBtn = new ButtonGeneric(x, y, w, 18,
                    StringUtils.translate("syncmaterial.gui.button.transfer"));
            this.addButton(transferBtn, (btn, mouseBtn) -> openTransfer());
            y += 21;
        }

        // 副负责人：前 N 个平铺（名字 + ×），超过折叠成批量移除按钮
        List<String> deputies = this.deputyOwners;
        int shown = Math.min(deputies.size(), MGMT_DEPUTY_SHOWN);
        if (deputies.isEmpty()) {
            this.addLabel(x, y, w, 12, 0xFFA0A0A0,
                    StringUtils.translate("syncmaterial.gui.label.deputy_owner_none"));
            y += 15;
        } else {
            for (int i = 0; i < shown; i++) {
                String deputy = deputies.get(i);
                this.addLabel(x, y, w - 18, 12, 0xFF55FF55,
                        StringUtils.translate("syncmaterial.gui.label.deputy_owner", deputy));
                if (isMainOwner) {
                    ButtonGeneric delBtn = new ButtonGeneric(x + w - 14, y - 2, 14, 14, GuiBase.TXT_RED + "×");
                    this.addButton(delBtn, (btn, mouseBtn) -> removeDeputy(deputy));
                }
                y += 15;
            }
            if (deputies.size() > MGMT_DEPUTY_SHOWN && isMainOwner) {
                ButtonGeneric moreBtn = new ButtonGeneric(x, y, w, 18,
                        StringUtils.translate("syncmaterial.gui.label.more_deputies", deputies.size() - shown));
                this.addButton(moreBtn, (btn, mouseBtn) -> openRemoveDeputies());
                y += 21;
            }
        }

        if (isMainOwner) {
            ButtonGeneric addBtn = new ButtonGeneric(x, y, w, 18,
                    StringUtils.translate("syncmaterial.gui.button.add_deputy"));
            this.addButton(addBtn, (btn, mouseBtn) -> openAddDeputy());
            y += 21;
        }

        // 自行认领开关（malilib 现成组件，自带 开/关 着色）
        ButtonOnOff claimBtn = new ButtonOnOff(x, y, w, false,
                "syncmaterial.gui.label.self_claim", allowSelfClaim);
        this.addButton(claimBtn, (btn, mouseBtn) -> toggleSelfClaim());

        // ---- 材料操作区（自底向上，贴面板底边）----
        int by = this.mgmtPanelBottom() - MGMT_PAD - 18;

        ButtonGeneric kickBtn = new ButtonGeneric(x, by, w, 18,
                StringUtils.translate("syncmaterial.gui.button.kick"));
        this.addButton(kickBtn, (btn, mouseBtn) -> {
            if (selectedMaterials.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            openKickDialog();
        });
        by -= 21;

        ButtonGeneric assignBtn = new ButtonGeneric(x, by, w, 18,
                StringUtils.translate("syncmaterial.gui.button.assign_to"));
        this.addButton(assignBtn, (btn, mouseBtn) -> {
            if (selectedMaterials.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            openAssignDialog();
        });
        by -= 15;

        // 动态文本「已选 N 种材料」：勾选随时变，记录 y 交给 drawContents 现画
        this.mgmtSelectedLabelY = by;
        by -= 13;

        this.addLabel(x, by, w, 10, 0xFF888888,
                StringUtils.translate("syncmaterial.gui.label.section_materials"));
        this.mgmtDividerYs.add(by - 6);
    }

    /**
     * 面板边框与分隔线。画在背景层：GuiBase.render 先调本方法，再画 widget 与按钮，
     * 因此框体一定在按钮下方，不会盖住它们。
     */
    @Override
    //? if >=26.2 {
    protected void drawScreenBackground(GuiContext drawContext, int mouseX, int mouseY) {
    //?} else {
    /*protected void drawScreenBackground(GuiGraphics drawContext, int mouseX, int mouseY) {
    *///?}
        drawContext.fill(0, 0, this.getScreenWidth(), this.getScreenHeight(), 0xFF181B20);

        if (!isOwner || net.syncmaterial.syncmaterial.client.gui.MaterialListLayout
                .modeForScreenWidth(this.getScreenWidth()) != net.syncmaterial.syncmaterial.client.gui.MaterialListLayout.Mode.WIDE_SIDEBAR) {
            return;
        }

        int x = this.mgmtPanelLeft();
        int w = MGMT_PANEL_W + MGMT_PAD * 2;
        int top = this.contentTop();
        int h = this.mgmtPanelBottom() - top;

        // 与 malilib 弹窗同款配色：半透明黑底 + 浅灰描边
        RenderUtils.drawOutlinedBox(drawContext, x, top, w, h, 0xC0000000, GuiBase.COLOR_HORIZONTAL_BAR);

        for (int dividerY : this.mgmtDividerYs) {
            RenderUtils.drawRect(drawContext, x + 4, dividerY, w - 8, 1, 0x60FFFFFF);
        }
    }

    /** 副负责人数量超过右栏平铺上限（折叠成「还有 N 位…」按钮的条件） */
    public boolean hasDeputyOverflow() {
        return this.deputyOwners.size() > MGMT_DEPUTY_SHOWN;
    }

    // ========== 管理动作（测试钩子与右栏按钮走同一方法）==========

    public void openTransfer() {
        GuiBase.openGui(new GuiPlayerSelectDialog(this, "TRANSFER", this));
    }

    public void openAddDeputy() {
        GuiBase.openGui(new GuiPlayerSelectDialog(this, "ADD_DEPUTY", this));
    }

    public void openRemoveDeputies() {
        GuiBase.openGui(GuiPlayerSelectDialog.forDeputyRemoval(this, this));
    }

    public void openAssignDialog() {
        GuiBase.openGui(new GuiPlayerSelectDialog(this, "ASSIGN", this));
    }

    public void openKickDialog() {
        GuiBase.openGui(new GuiPlayerSelectDialog(this, "KICK", this));
    }

    public void toggleSelfClaim() {
        String error = TeamManagementClient.toggleSelfClaim(this);
        if (error != null) {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, error);
        }
    }

    public void removeDeputy(String target) {
        String error = TeamManagementClient.removeDeputy(this, target);
        if (error != null) {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, error);
        }
    }

    private boolean teamPopup;
    private int teamX;
    private int teamY;
    private final List<fi.dy.masa.malilib.gui.button.ButtonBase> popupButtons = new ArrayList<>();
    private static final int TEAM_W = 250;
    private static final int TEAM_H = 168;

    private void createTeamPopup() {
        popupButtons.clear();
        teamX = (this.getScreenWidth() - TEAM_W) / 2;
        teamY = Math.max(62, (this.getScreenHeight() - TEAM_H) / 2);
        int x = teamX + 12;
        int y = teamY + 52;
        if (isMainOwner) {
            addPopupButton(new ButtonGeneric(x, y, TEAM_W - 24, 18,
                    StringUtils.translate("syncmaterial.gui.button.transfer")),
                    (btn, mouse) -> openTransfer());
            y += 22;
            addPopupButton(new ButtonGeneric(x, y, TEAM_W - 24, 18,
                    StringUtils.translate("syncmaterial.gui.button.add_deputy")),
                    (btn, mouse) -> openAddDeputy());
            y += 22;
            addPopupButton(new ButtonGeneric(x, y, TEAM_W - 24, 18,
                    StringUtils.translate("syncmaterial.gui.title.remove_deputy")),
                    (btn, mouse) -> openRemoveDeputies());
            y += 22;
        }
        if (isOwner) {
            ButtonOnOff selfClaim = new ButtonOnOff(x, y, TEAM_W - 24, false,
                    "syncmaterial.gui.label.self_claim", allowSelfClaim);
            addPopupButton(selfClaim, (btn, mouse) -> toggleSelfClaim());
        }
        addPopupButton(new ButtonGeneric(x, teamY + 142, TEAM_W - 24, 18,
                StringUtils.translate("syncmaterial.gui.button.close")),
                (btn, mouse) -> {
                    teamPopup = false;
                    initGui();
                });
    }

    private void addPopupButton(fi.dy.masa.malilib.gui.button.ButtonBase button,
                                fi.dy.masa.malilib.gui.button.IButtonActionListener listener) {
        button.setActionListener(listener);
        popupButtons.add(button);
    }

    private int createButtonAssign(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true,
                StringUtils.translate("syncmaterial.gui.button.assign_to"));
        this.addButton(button, (btn, mouse) -> openAssignDialog());
        return button.getWidth();
    }

    private int createButtonKick(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true,
                StringUtils.translate("syncmaterial.gui.button.kick"));
        this.addButton(button, (btn, mouse) -> openKickDialog());
        return button.getWidth();
    }

    private int createButtonTeam(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true,
                StringUtils.translate("syncmaterial.gui.title.management"));
        this.addButton(button, (btn, mouseButton) -> {
            teamPopup = true;
            initGui();
        });
        toolbarControls.add(button);
        return button.getWidth();
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.close"));
        this.addButton(button, (btn, mouseButton) -> this.closeGui(true));
        toolbarControls.add(button);
        return button.getWidth();
    }

    // ========== 网络回调 ==========

    /** 目录变化即使发生在子弹窗上方，也必须使失效选择立即退出批量意图。 */
    void retainMaterialSelection() {
        var ids = this.materialList.getMaterialsAll().stream().map(MaterialListEntry::getDatabaseId)
                .collect(java.util.stream.Collectors.toSet());
        this.selectedMaterials.retainIds(ids);
        if (this.getListWidget() != null) this.getListWidget().refreshEntries();
    }

    public void onMaterialCatalogUpdated() {
        this.title = this.materialList.getTitle();
        this.retainMaterialSelection();
        this.updateFreshnessWarnings(this.materialList.getAreaFreshness());
    }

    /** 当前搜索／参与筛选不可见、但仍会被批量命令携带的选择数量。 */
    public int getSelectedOutsideFilterCount() {
        var visible = new java.util.HashSet<>(this.getCurrentEntryIds());
        int count = 0;
        for (Integer id : this.selectedMaterials.ids()) {
            if (!visible.contains(id)) count++;
        }
        return count;
    }

    public void onRescanUnconfirmed() {
        this.lastRescanResult = null;
        this.initGui();
    }

    public void onRescanResponse(boolean success, String message) {
        this.lastRescanResult = success;
        this.initGui();
        if (success) {
            this.materialList.requestCollaborationStatus();
            if (!this.materialList.hasAuthoritativeInventory()) {
                MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
            }
            this.getListWidget().refreshEntries();
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
        } else {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, message);
        }
    }

    /**
     * 协议 4 权威团队状态落地：角色以 UUID 为准，名字只用于显示。
     * 只更新权限与右栏数据，绝不触碰材料目录、区域库存或当前界面栈。
     */
    public void applyTeamState(TeamState state, PlayerIdentity localPlayer) {
        if (state == null || !state.projectId().equals(this.materialList.getSchematicId())) {
            return;
        }
        this.teamState = state;
        String newOwnerName = state.owner().name();
        List<String> newDeputies = new ArrayList<>(state.deputyNames());
        boolean newAllowSelfClaim = state.allowSelfClaim();
        boolean oldOwner = this.isOwner;
        boolean oldMainOwner = this.isMainOwner;
        boolean newMainOwner = localPlayer != null ? state.isMainOwner(localPlayer) : oldMainOwner;
        boolean newOwner = localPlayer != null ? state.isOwner(localPlayer) : oldOwner;
        // 名字/副名单不变但本地身份升级（或 allowNameAuthority 策略变化）也必须重建右栏
        boolean roleChanged = oldOwner != newOwner || oldMainOwner != newMainOwner
                || !this.ownerName.equals(newOwnerName)
                || !this.deputyOwners.equals(newDeputies)
                || this.allowSelfClaim != newAllowSelfClaim;

        this.ownerName = newOwnerName;
        this.deputyOwners = newDeputies;
        this.allowSelfClaim = newAllowSelfClaim;
        if (localPlayer != null) {
            this.isMainOwner = newMainOwner;
            this.isOwner = newOwner;
        }
        this.materialList.setAllowSelfClaim(newAllowSelfClaim);
        this.materialList.setIsOwner(this.isOwner);

        if (roleChanged) {
            refreshManagementPanel();
        }
    }

    /**
     * 旧服务端只有名字，没有 UUID。成功响应才会走到这里；空负责人或缺失名单
     * 不覆盖已有状态，避免失败回包把界面清成"无主"的假状态。
     */
    public void applyLegacyOwnerState(String newOwnerName, List<String> newDeputyOwners,
                                      boolean newAllowSelfClaim) {
        boolean changed = false;
        String localName = localPlayerName();
        if (newOwnerName != null && !newOwnerName.isBlank()) {
            if (!newOwnerName.equals(this.ownerName)) {
                this.ownerName = newOwnerName;
                changed = true;
            }
            boolean main = newOwnerName.equals(localName);
            if (main != this.isMainOwner) {
                this.isMainOwner = main;
                changed = true;
            }
        }
        if (newDeputyOwners != null) {
            List<String> deputies = new ArrayList<>(newDeputyOwners);
            if (!deputies.equals(this.deputyOwners)) {
                this.deputyOwners = deputies;
                changed = true;
            }
        }
        boolean owner = this.isMainOwner || this.deputyOwners.stream()
                .anyMatch(deputy -> deputy.equalsIgnoreCase(localName));
        if (owner != this.isOwner) {
            this.isOwner = owner;
            changed = true;
        }
        if (this.allowSelfClaim != newAllowSelfClaim) {
            this.allowSelfClaim = newAllowSelfClaim;
            changed = true;
        }
        this.materialList.setAllowSelfClaim(newAllowSelfClaim);
        this.materialList.setIsOwner(this.isOwner);
        if (changed) {
            refreshManagementPanel();
        }
    }

    public void updateOwnerState(String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        applyLegacyOwnerState(newOwnerName, newDeputyOwners, newAllowSelfClaim);
    }

    private void refreshManagementPanel() {
        if (this.mc != null
                && net.syncmaterial.syncmaterial.client.ClientScreenAccess.currentScreen(this.mc) == this) {
            this.initGui(); // 副负责人行数/开关状态/右栏显隐可能变了，重建
        }
    }

    private String localPlayerName() {
        return this.mc != null && this.mc.player != null ? this.mc.player.getName().getString() : "";
    }

    /** 最近一次权威团队状态（协议 4）；旧服务端为 null。 */
    public TeamState getTeamState() {
        return this.teamState;
    }

    /** 移除副负责人时的身份来源：有权威状态用 UUID，没有则回退为未解析名字。 */
    public List<PlayerIdentity> getDeputyIdentities() {
        if (this.teamState != null) {
            return this.teamState.deputies();
        }
        List<PlayerIdentity> identities = new ArrayList<>(this.deputyOwners.size());
        for (String deputy : this.deputyOwners) {
            identities.add(new PlayerIdentity(null, deputy));
        }
        return identities;
    }

    public PlayerIdentity getOwnerIdentity() {
        if (this.teamState != null) {
            return this.teamState.owner();
        }
        return this.ownerName.isBlank() ? null : new PlayerIdentity(null, this.ownerName);
    }

    public void onTeamFeedback(boolean success, String message) {
        if (message != null && !message.isEmpty()) {
            InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        }
    }

    /** 提交时冻结选择意图，回包只清理仍属于这次提交的勾选。 */
    public MaterialSelection.Snapshot captureBatchSelection(List<Integer> materialIds) {
        return this.selectedMaterials.capture(materialIds);
    }

    /** 未关联到提交意图的旧匿名回包只能刷新，不能清空当前选择。 */
    public void applyBatchAssignResult(boolean success) {
        if (success) {
            this.materialList.requestCollaborationStatus();
        }
    }

    public void applyBatchAssignResult(boolean success, MaterialSelection.Snapshot captured) {
        if (success) {
            this.selectedMaterials.clearCaptured(captured);
            this.materialList.requestCollaborationStatus();
        }
    }

    /** 踢出结果落库：成功则重新拉取协作状态 */
    public void applyKickResult(boolean success) {
        if (success) {
            this.materialList.requestCollaborationStatus();
        }
    }

    public String getSchematicId() {
        return this.materialList.getSchematicId();
    }

    /** 测试钩子：最近一次刷新响应的结果，null 表示从未收到过响应 */
    public Boolean getLastRescanResult() {
        return this.lastRescanResult;
    }

    /**
     * 表头点击动作：column → 排序标准（同一映射）→ setSortCriteria → 刷新。
     * 与 WidgetMaterialListEntry 的点击处理操作同一 listWidget / materialList，
     * 测试用它模拟点击，不存在第二条被测路径。
     */
    public void handleHeaderClick(int column) {
        SortCriteria criteria = MaterialListBase.criteriaForColumn(column);
        if (criteria != null) {
            this.materialList.setSortCriteria(criteria);
            this.getListWidget().refreshFromBeginning();
        }
    }

    /** 测试钩子：当前列表（排序/过滤后）的条目 databaseId 顺序 */
    public List<Integer> getCurrentEntryIds() {
        return this.getListWidget().getCurrentEntries().stream()
                .filter(java.util.Objects::nonNull)
                .map(entry -> entry.getDatabaseId())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 无弹窗打开时的兜底（右栏 × 移除 / 自行认领开关的响应走这里，
     * 此时 currentScreen 就是本界面，initGui 已在 updateOwnerState 里重建右栏）。
     */
    public void onOwnerActionResponse(boolean success, String message, String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        updateOwnerState(newOwnerName, newDeputyOwners, newAllowSelfClaim);
        if (message != null && !message.isEmpty()) {
            InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        }
    }

    public void onBatchAssignResponse(boolean success, String message) {
        applyBatchAssignResult(success);
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
    }

    public void onKickResponse(boolean success, String message) {
        applyKickResult(success);
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
    }

    // ========== 渲染 ==========

    @Override
    //? if >=26.2 {
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    /*public void drawContents(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks) {
    *///?}
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);

        // 区域重叠提示与数据新鲜度警告：重叠在上，新鲜度在下，两者互不覆盖
        int warningY = CONTENT_TOP + Math.max(0, this.toolbarRows() - 1) * 22;
        if (overlapWarning != null) {
            AreaOverlapWarningRenderer.render(drawContext, 12, warningY, overlapWarning, mouseX, mouseY);
            warningY += 18;
        }
        if (!freshnessWarnings.isEmpty()) {
            renderFreshnessWarnings(drawContext, warningY);
        }

        // 右栏动态文本：已选材料数（勾选实时变，不能走 initGui 静态标签）
        if (isOwner && this.mgmtSelectedLabelY >= 0) {
            String countLabel = StringUtils.translate("syncmaterial.gui.label.materials_selected_count",
                    this.selectedMaterials.size());
            this.drawString(drawContext, countLabel,
                    this.mgmtContentLeft(), this.mgmtSelectedLabelY,
                    this.selectedMaterials.isEmpty() ? 0xFF888888 : 0xFFE0E0E0);
        }
        int outside = this.getSelectedOutsideFilterCount();
        if (outside > 0) {
            String hint = MaterialListLayout.clipText(
                    StringUtils.translate("syncmaterial.gui.hint.selected_outside_filter", outside),
                    Math.max(0, this.getScreenWidth() - 24), StringUtils::getStringWidth).text();
            int y = this.getScreenHeight() - 36 - Math.max(0, this.footerRows() - 1) * 22;
            this.drawString(drawContext, hint, 12, y, 0xFFFFB020);
        }
    }

    @Override
    //? if >=26.2 {
    protected void drawTextFields(GuiContext drawContext, int mouseX, int mouseY) {
    //?} else {
    /*protected void drawTextFields(GuiGraphics drawContext, int mouseX, int mouseY) {
    *///?}
        super.drawTextFields(drawContext, mouseX, mouseY);
        if (!teamPopup) return;
        drawContext.fill(0, 0, this.getScreenWidth(), this.getScreenHeight(), 0x78000000);
        RenderUtils.drawOutlinedBox(drawContext, teamX, teamY, TEAM_W, TEAM_H,
                0xFF101010, GuiBase.COLOR_HORIZONTAL_BAR);
        this.drawString(drawContext, this.title, teamX + 12, teamY + 6, 0xFFFFFFFF);
        this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.main_owner", ownerName),
                teamX + 12, teamY + 22, 0xFF55FF55);
        String deputies = this.deputyOwners.isEmpty()
                ? StringUtils.translate("syncmaterial.gui.label.deputy_owner_none")
                : String.join(", ", this.deputyOwners);
        this.drawString(drawContext, MaterialListLayout.clipText(deputies, TEAM_W - 24,
                StringUtils::getStringWidth).text(), teamX + 12, teamY + 36, 0xFFB0B0B0);
        for (var button : popupButtons) {
            button.render(drawContext, mouseX, mouseY, button.isMouseOver(mouseX, mouseY));
        }
    }

    @Override
    //? if >=26.2 {
    protected void drawButtonHoverTexts(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    /*protected void drawButtonHoverTexts(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks) {
    *///?}
        if (!teamPopup) super.drawButtonHoverTexts(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    //? if >=26.2 {
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY) {
    //?} else {
    /*protected void drawHoveredWidget(GuiGraphics drawContext, int mouseX, int mouseY) {
    *///?}
        if (!teamPopup) super.drawHoveredWidget(drawContext, mouseX, mouseY);
    }

    /**
     * 在列表上方显示数据新鲜度警告
     */
    //? if >=26.2 {
    private void renderFreshnessWarnings(GuiContext drawContext, int y) {
    //?} else {
    /*private void renderFreshnessWarnings(GuiGraphics drawContext, int y) {
    *///?}
        int x = 12;
        int bgColor = 0xA0FF8800; // 橙色半透明背景
        int textColor = 0xFFFFFF00; // 黄色文字

        String text = AreaWarningLabels.summary(this.freshnessWarnings);

        int textWidth = this.mc.font.width(text);
        int boxWidth = Math.max(0, Math.min(textWidth + 10, this.getScreenWidth() - x - 12));
        int boxHeight = 16;
        String displayed = MaterialListLayout.clipText("⚠ " + text, Math.max(0, boxWidth - 8),
                StringUtils::getStringWidth).text();
        drawContext.fill(x, y, x + boxWidth, y + boxHeight, bgColor);
        drawContext.drawString(this.mc.font, displayed, x + 4, y + 3, textColor, false);
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick)
    //?} else {
    /*public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        if (!teamPopup) {
            //? if >=26.2 {
            return super.onMouseClicked(event, isDoubleClick);
            //?} else {
            /*return super.onMouseClicked(mouseX, mouseY, mouseButton);
            *///?}
        }
        //? if >=26.2 {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        //?} else {
        /*int button = mouseButton;
        *///?}
        if (mouseX < teamX || mouseX > teamX + TEAM_W || mouseY < teamY || mouseY > teamY + TEAM_H) {
            teamPopup = false;
            initGui();
            return true;
        }
        for (var control : popupButtons) {
            if (control.isMouseOver(mouseX, mouseY)) {
                //? if >=26.2 {
                control.onMouseClicked(event, isDoubleClick);
                //?} else {
                /*control.onMouseClicked(mouseX, mouseY, button);
                *///?}
                return true;
            }
        }
        return true;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseReleased(net.minecraft.client.input.MouseButtonEvent event)
    //?} else {
    /*public boolean onMouseReleased(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        if (teamPopup) return true;
        //? if >=26.2 {
        return super.onMouseReleased(event);
        //?} else {
        /*return super.onMouseReleased(mouseX, mouseY, mouseButton);
        *///?}
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    //?} else {
    /*public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    *///?}
    {
        return teamPopup || super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    //? if >=26.2 {
    public boolean onKeyTyped(net.minecraft.client.input.KeyEvent event)
    //?} else {
    /*public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    *///?}
    {
        if (teamPopup) {
            //? if >=26.2 {
            int key = event.key();
            //?} else {
            /*int key = keyCode;
            *///?}
            if (key == 256) {
                teamPopup = false;
                initGui();
            }
            return true;
        }
        //? if >=26.2 {
        return super.onKeyTyped(event);
        //?} else {
        /*return super.onKeyTyped(keyCode, scanCode, modifiers);
        *///?}
    }

    @Override
    //? if >=26.2 {
    public boolean onCharTyped(net.minecraft.client.input.CharacterEvent event)
    //?} else {
    /*public boolean onCharTyped(char chr, int modifiers)
    *///?}
    {
        if (teamPopup) return true;
        //? if >=26.2 {
        return super.onCharTyped(event);
        //?} else {
        /*return super.onCharTyped(chr, modifiers);
        *///?}
    }

    public void closeGui(boolean showParent) {
        // 关闭界面不解除背包监听、不退订状态广播：HUD 的生命周期长于 GUI，
        // 数据源必须随 HUD 存续（与 1.21.7 实际行为一致——当时的 close() 重写
        // 是 malilib Esc 路径不会调用的死代码，clearContext 从未执行过）。
        // 真正的清理在原理图删除时由 SyncMaterialClient.clearActiveSchematic 统一做。
        TeamManagementClient.onMaterialListClosed(this);
        net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                session.closeGui(this.materialList.getSchematicId()));
        super.closeGui(showParent);
    }

    @Override
    public void removed() {
        // 标准 setScreen 路径不调用 closeGui；打开子弹窗时父列表同样会收到 removed，
        // 因此这里只失效直接绑定父界面的请求，弹窗自己的请求由弹窗 removed 处理。
        TeamManagementClient.onMaterialListRemoved(this);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
