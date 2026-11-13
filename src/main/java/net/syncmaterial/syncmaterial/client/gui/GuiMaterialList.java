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
    // 取货模式状态与需求量统一由 PickupModeState 持有（见该类注释）
    private List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessWarnings = java.util.Collections.emptyList();
    /** LIST 快照携带的区域重叠提示；null 表示无提示。 */
    private String overlapWarning;
    private List<Integer> selectedMaterialIds = new ArrayList<>();
    /** 最近一次刷新响应（测试断言 RescanResponse 真实回流；null=从未收到） */
    private Boolean lastRescanResult;
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
    private static final int CONTENT_BOTTOM_MARGIN = 6;

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
        this.title = this.materialList.getTitle();
        this.useTitleHierarchy = false;

        // 设置原理图名称，用于备货区线框标注
        net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
            .setSchematicName(schematicId, schematicName);

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
        WidgetMaterialListEntry.setMaxNameLength(this.materialList.getMaterialsAll(), this.materialList.getMultiplier());
    }

    public SyncMaterialList getMaterialList() { return this.materialList; }
    public boolean isOwner() { return isOwner; }
    public boolean isPickupMode() { return net.syncmaterial.syncmaterial.client.PickupModeState.isActive(); }
    public static boolean isPickupModeStatic() { return net.syncmaterial.syncmaterial.client.PickupModeState.isActive(); }

    /**
     * 更新数据新鲜度警告（从协作状态包接收）
     */
    public void updateFreshnessWarnings(List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> info) {
        this.freshnessWarnings = info != null ? info : java.util.Collections.emptyList();
    }

    public List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> getFreshnessWarnings() {
        return freshnessWarnings;
    }

    /** 成功 LIST 响应的重叠提示；空串/null 清除。 */
    public void updateOverlapWarning(String message) {
        this.overlapWarning = AreaOverlapWarningRenderer.normalize(message);
    }

    public String getOverlapWarning() {
        return overlapWarning;
    }

    public boolean isMainOwner() { return isMainOwner; }
    public String getOwnerName() { return ownerName; }
    public List<String> getDeputyOwners() { return deputyOwners; }
    public boolean isAllowSelfClaim() { return allowSelfClaim; }
    public boolean isFilterMyMaterials() { return filterMyMaterials; }
    public List<Integer> getSelectedMaterialIds() { return selectedMaterialIds; }

    @Override
    protected int getBrowserWidth() {
        // owner 时列表右边缘停在管理栏左侧 6px 处（列表 listX=10，见构造的 super(10, CONTENT_TOP)）
        return isOwner ? this.mgmtPanelLeft() - 10 - 6
                       : this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        // 分配/踢出按钮已移入右栏，列表可延伸到接近屏幕底部；
        // 底部留 CONTENT_BOTTOM_MARGIN 供右栏边框的下边线落地（否则被屏幕裁掉）
        return this.getScreenHeight() - CONTENT_TOP - CONTENT_BOTTOM_MARGIN;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    // ========== 初始化 ==========

    @Override
    public void initGui() {
        this.title = this.materialList.getTitle();
        super.initGui();
        int gap = 2;
        int x = this.getScreenWidth() - 20;
        x -= this.createButtonClose(x, 24) + gap;
        x -= this.createButtonToggleHud(x, 24) + gap;
        x -= this.createButtonRefresh(x, 24) + gap;
        x -= this.createButtonToggleStagingRender(x, 24) + gap;
        x -= this.createButtonTogglePickupMode(x, 24) + gap;
        x -= this.createButtonStagingArea(x, 24) + gap;
        x -= this.createButtonFilterMyMaterials(x, 24) + gap;
        if (isOwner) {
            this.createManagementPanel();
        }
        this.materialList.requestCollaborationStatus();
        // 自动订阅备货区更新，使协作者能看到游戏内线框渲染
        String schematicId = this.materialList.getSchematicId();
        if (schematicId != null && !schematicId.isEmpty()) {
            ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket(schematicId, "LIST", -1, java.util.Optional.empty()));
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
        return button.getWidth();
    }

    /** 刷新按钮动作：发送备货区重扫请求（测试钩子与按钮走同一方法） */
    public boolean refreshStagingAreas() {
        String schematicId = this.materialList.getSchematicId();
        if (schematicId != null && !schematicId.isEmpty()) {
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
        return button.getWidth();
    }

    private int createButtonToggleStagingRender(int x, int y) {
        String label = StringUtils.translate("syncmaterial.gui.button.staging_wireframe",
                StringUtils.translate(stagingRenderEnabled ? "syncmaterial.gui.label.show" : "syncmaterial.gui.label.hide"));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            stagingRenderEnabled = !stagingRenderEnabled;
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null) {
                net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance().setRenderEnabled(schematicId, stagingRenderEnabled);
            }
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.staging_wireframe",
                    StringUtils.translate(stagingRenderEnabled ? "syncmaterial.gui.label.show" : "syncmaterial.gui.label.hide")));
        });
        return button.getWidth();
    }

    // Phase 5: 取货模式切换按钮
    private int createButtonTogglePickupMode(int x, int y) {
        String label = StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                StringUtils.translate(isPickupMode() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            boolean next = togglePickupMode();
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                    StringUtils.translate(next ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")));
        });
        return button.getWidth();
    }

    /**
     * 取货模式切换按钮动作：切换本地状态 + 订阅/退订仓库容器数据，返回新状态
     * （测试钩子与按钮走同一方法）。
     */
    public boolean togglePickupMode() {
        boolean next = !isPickupMode();
        net.syncmaterial.syncmaterial.client.PickupModeState.setActive(next);
        // 订阅/取消订阅仓库容器数据
        String schematicId = this.materialList.getSchematicId();
        if (schematicId != null && !schematicId.isEmpty()) {
            ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket(schematicId, next));
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
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.hud_toggle",
                    StringUtils.translate(this.materialList.getHudRenderer().getShouldRender() ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")));
        });
        return button.getWidth();
    }

    private int createButtonFilterMyMaterials(int x, int y) {
        String label = filterMyMaterials ? StringUtils.translate("syncmaterial.gui.button.show_all") : StringUtils.translate("syncmaterial.gui.button.show_my_materials");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            filterMyMaterials = !filterMyMaterials;
            btn.setDisplayString(filterMyMaterials ? StringUtils.translate("syncmaterial.gui.button.show_all") : StringUtils.translate("syncmaterial.gui.button.show_my_materials"));
            this.getListWidget().refreshEntries();
        });
        return button.getWidth();
    }

    // ========== 右侧管理栏（Litematica 放置配置同款双栏布局）==========

    private static final int MGMT_PANEL_W = 160;
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
        return CONTENT_TOP + this.getBrowserHeight();
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
        int y = CONTENT_TOP + MGMT_PAD;
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
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            openKickDialog();
        });
        by -= 21;

        ButtonGeneric assignBtn = new ButtonGeneric(x, by, w, 18,
                StringUtils.translate("syncmaterial.gui.button.assign_to"));
        this.addButton(assignBtn, (btn, mouseBtn) -> {
            if (selectedMaterialIds.isEmpty()) {
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
        super.drawScreenBackground(drawContext, mouseX, mouseY);

        if (!isOwner) {
            return;
        }

        int x = this.mgmtPanelLeft();
        int w = MGMT_PANEL_W + MGMT_PAD * 2;
        int top = CONTENT_TOP;
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

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.close"));
        this.addButton(button, (btn, mouseButton) -> this.closeGui(true));
        return button.getWidth();
    }

    // ========== 网络回调 ==========

    public void onMaterialCatalogUpdated() {
        this.title = this.materialList.getTitle();
        var ids = this.materialList.getMaterialsAll().stream().map(MaterialListEntry::getDatabaseId)
                .collect(java.util.stream.Collectors.toSet());
        this.selectedMaterialIds.removeIf(id -> !ids.contains(id));
        WidgetMaterialListEntry.setMaxNameLength(this.materialList.getMaterialsAll(), this.materialList.getMultiplier());
        this.getListWidget().refreshEntries();
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

    /** 批量分配结果落库：成功则清空勾选并重新拉取协作状态 */
    public void applyBatchAssignResult(boolean success) {
        if (success) {
            selectedMaterialIds.clear();
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
            this.getListWidget().refreshEntries();
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
        int warningY = 46;
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
                    this.selectedMaterialIds.size());
            this.drawString(drawContext, countLabel,
                    this.mgmtContentLeft(), this.mgmtSelectedLabelY,
                    this.selectedMaterialIds.isEmpty() ? 0xFF888888 : 0xFFE0E0E0);
        }
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

        // 构建警告文本
        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.translate("syncmaterial.gui.label.freshness_warning"));
        sb.append(" ");
        for (int i = 0; i < freshnessWarnings.size(); i++) {
            var info = freshnessWarnings.get(i);
            if (i > 0) sb.append(", ");
            sb.append(info.areaName()).append(" — ").append(info.status());
        }
        String text = sb.toString();

        int textWidth = this.mc.font.width(text);
        int boxWidth = textWidth + 10;
        int boxHeight = 16;
        drawContext.fill(x, y, x + boxWidth, y + boxHeight, bgColor);
        drawContext.drawString(this.mc.font, "⚠ " + text, x + 4, y + 3, textColor, false);
    }

    public void closeGui(boolean showParent) {
        // 关闭界面不解除背包监听、不退订状态广播：HUD 的生命周期长于 GUI，
        // 数据源必须随 HUD 存续（与 1.21.7 实际行为一致——当时的 close() 重写
        // 是 malilib Esc 路径不会调用的死代码，clearContext 从未执行过）。
        // 真正的清理在原理图删除时由 SyncMaterialClient.clearActiveSchematic 统一做。
        TeamManagementClient.onMaterialListClosed(this);
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
