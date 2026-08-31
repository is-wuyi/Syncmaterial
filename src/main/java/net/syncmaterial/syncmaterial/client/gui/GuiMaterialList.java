package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
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
    private boolean filterMyMaterials = false;
    // 取货模式状态与需求量统一由 PickupModeState 持有（见该类注释）
    private List<net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessWarnings = java.util.Collections.emptyList();
    private List<Integer> selectedMaterialIds = new ArrayList<>();
    private static boolean stagingRenderEnabled = true;
    /** 右栏「已选 N 种材料」动态文本的 y 坐标（initGui 布局时写入，非 owner 为 -1 不画） */
    private int mgmtSelectedLabelY = -1;

    public GuiMaterialList(String schematicId, String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        super(10, 44);

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
    public boolean isMainOwner() { return isMainOwner; }
    public String getOwnerName() { return ownerName; }
    public List<String> getDeputyOwners() { return deputyOwners; }
    public boolean isAllowSelfClaim() { return allowSelfClaim; }
    public boolean isFilterMyMaterials() { return filterMyMaterials; }
    public List<Integer> getSelectedMaterialIds() { return selectedMaterialIds; }

    @Override
    protected int getBrowserWidth() {
        // owner 时右侧让出管理栏 + 面板边框（Litematica GuiPlacementConfiguration 同款双栏布局）
        return isOwner ? this.getScreenWidth() - MGMT_PANEL_W - MGMT_PAD * 2 - 26
                       : this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        // 分配/踢出按钮已移入右栏，列表可延伸到热键栏上方
        return this.getScreenHeight() - 44;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    // ========== 初始化 ==========

    @Override
    public void initGui() {
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
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null && !schematicId.isEmpty()) {
                ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(schematicId));
                btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.refreshing"));
                btn.setEnabled(false);
            }
        });
        return button.getWidth();
    }

    private int createButtonStagingArea(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.staging_area_config"));
        this.addButton(button, (btn, mouseButton) -> {
            AreaSelection selection = new AreaSelection();
            GuiStagingAreaEditorNormal editor = new GuiStagingAreaEditorNormal(selection, null, this.materialList.getSchematicId());
            editor.setParent(this);
            this.mc.setScreenAndShow(editor);
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
            btn.setDisplayString(StringUtils.translate("syncmaterial.gui.button.pickup_mode",
                    StringUtils.translate(next ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")));
            // 刷新列表显示
            this.getListWidget().refreshEntries();
        });
        return button.getWidth();
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

    /** 面板底边（initGui 布局时算出，drawScreenBackground 据此画框；-1 表示无右栏） */
    private int mgmtPanelBottom = -1;
    /** 区块分隔线的 y 坐标（同上，随布局产生） */
    private final List<Integer> mgmtDividerYs = new ArrayList<>();

    /**
     * 右栏全部用 malilib 标准控件（addLabel / ButtonGeneric / ButtonOnOff）按绝对坐标
     * 竖排——与 Litematica GuiPlacementConfiguration 的右栏做法一致。
     *
     * 布局三条规矩，都是为了避免中文标签把按钮挤出面板：
     * 1. 文本各占一行，按钮各占一行，不并排（副负责人的 × 除外，它只有 14px）
     * 2. 所有主按钮统一整栏宽，左右边缘对齐
     * 3. 区块间画分隔线，配合面板边框形成视觉分组
     *
     * 唯一动态文本「已选 N 种材料」在 drawContents 每帧现画，勾选变化无需重建。
     */
    private void createManagementPanel() {
        int x = this.getScreenWidth() - MGMT_PANEL_W - 10;
        int w = MGMT_PANEL_W;
        int y = 44 + MGMT_PAD;
        this.mgmtDividerYs.clear();

        this.addLabel(x, y, w, 12, 0xFFFFFFFF,
                StringUtils.translate("syncmaterial.gui.title.management"));
        y += 16;
        this.mgmtDividerYs.add(y);
        y += 6;

        // ---- 负责人区 ----
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
        y += 24;

        this.mgmtDividerYs.add(y);
        y += 6;

        // ---- 材料操作区 ----
        this.addLabel(x, y, w, 10, 0xFF888888,
                StringUtils.translate("syncmaterial.gui.label.section_materials"));
        y += 13;

        // 动态文本「已选 N 种材料」：勾选随时变，记录 y 交给 drawContents 现画
        this.mgmtSelectedLabelY = y;
        y += 15;

        ButtonGeneric assignBtn = new ButtonGeneric(x, y, w, 18,
                StringUtils.translate("syncmaterial.gui.button.assign_to"));
        this.addButton(assignBtn, (btn, mouseBtn) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            openAssignDialog();
        });
        y += 21;

        ButtonGeneric kickBtn = new ButtonGeneric(x, y, w, 18,
                StringUtils.translate("syncmaterial.gui.button.kick"));
        this.addButton(kickBtn, (btn, mouseBtn) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            openKickDialog();
        });
        y += 18;

        this.mgmtPanelBottom = y + MGMT_PAD;
    }

    /**
     * 面板边框与分隔线。画在背景层：GuiBase.render 先调本方法，再画 widget 与按钮，
     * 因此框体一定在按钮下方，不会盖住它们。
     */
    @Override
    protected void drawScreenBackground(GuiContext drawContext, int mouseX, int mouseY) {
        super.drawScreenBackground(drawContext, mouseX, mouseY);

        if (!isOwner || this.mgmtPanelBottom < 0) {
            return;
        }

        int x = this.getScreenWidth() - MGMT_PANEL_W - 10 - MGMT_PAD;
        int w = MGMT_PANEL_W + MGMT_PAD * 2;
        int top = 44;
        int h = this.mgmtPanelBottom - top;

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
        ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "TOGGLE_SELF_CLAIM", ""));
    }

    public void removeDeputy(String target) {
        ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "REMOVE_DEPUTY", target));
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.close"));
        this.addButton(button, (btn, mouseButton) -> this.closeGui(true));
        return button.getWidth();
    }

    // ========== 网络回调 ==========

    public void onRescanResponse(boolean success, String message) {
        this.initGui();
        if (success) {
            this.materialList.requestCollaborationStatus();
            MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
            this.getListWidget().refreshEntries();
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
        } else {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, message);
        }
    }

    /**
     * 负责人体系数据更新（数据真身在本类，右栏每次刷新从这里读）。
     * 弹窗确认的响应走 receiver → 弹窗 → 本方法，之后 openGui(parent) 触发
     * setScreen → initGui 重建右栏，此时本界面不是 currentScreen，跳过避免双重重建；
     * 右栏 × 移除 / 开关的响应（无弹窗）走本类兜底回调，此时需要显式重建。
     */
    public void updateOwnerState(String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        if (newOwnerName != null) {
            this.ownerName = newOwnerName;
            // 转让后身份实时收敛：主负责人专属按钮（转让/×/添加）的显隐依赖
            // isMainOwner，不更新的话旧主负责人界面上会残留幽灵按钮，
            // 点击只会收到服务端的权限拒绝
            if (this.mc != null && this.mc.player != null) {
                this.isMainOwner = newOwnerName.equals(this.mc.player.getName().getString());
            }
        }
        if (newDeputyOwners != null) this.deputyOwners = new ArrayList<>(newDeputyOwners);
        this.allowSelfClaim = newAllowSelfClaim;
        this.materialList.setAllowSelfClaim(newAllowSelfClaim);
        if (isOwner && this.mc != null && this.mc.gui != null && this.mc.gui.screen() == this) {
            this.initGui(); // 副负责人行数/开关状态可能变了，重建右栏
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
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);

        // Phase 5: 数据新鲜度警告
        if (!freshnessWarnings.isEmpty()) {
            renderFreshnessWarnings(drawContext);
        }

        // 右栏动态文本：已选材料数（勾选实时变，不能走 initGui 静态标签）
        if (isOwner && this.mgmtSelectedLabelY >= 0) {
            String countLabel = StringUtils.translate("syncmaterial.gui.label.materials_selected_count",
                    this.selectedMaterialIds.size());
            this.drawString(drawContext, countLabel,
                    this.getScreenWidth() - MGMT_PANEL_W - 10, this.mgmtSelectedLabelY,
                    this.selectedMaterialIds.isEmpty() ? 0xFF888888 : 0xFFE0E0E0);
        }
    }

    /**
     * 在列表上方显示数据新鲜度警告
     */
    private void renderFreshnessWarnings(GuiContext drawContext) {
        int y = 46; // 按钮行下方
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

        int textWidth = this.font.width(text);
        int boxWidth = textWidth + 10;
        int boxHeight = 16;
        drawContext.fill(x, y, x + boxWidth, y + boxHeight, bgColor);
        drawContext.text(this.font, "⚠ " + text, x + 4, y + 3, textColor, false);
    }

    public void closeGui(boolean showParent) {
        // 关闭界面不解除背包监听、不退订状态广播：HUD 的生命周期长于 GUI，
        // 数据源必须随 HUD 存续（与 1.21.7 实际行为一致——当时的 close() 重写
        // 是 malilib Esc 路径不会调用的死代码，clearContext 从未执行过）。
        // 真正的清理在原理图删除时由 SyncMaterialClient.clearActiveSchematic 统一做。
        super.closeGui(showParent);
    }

    public boolean shouldPause() {
        return false;
    }
}
