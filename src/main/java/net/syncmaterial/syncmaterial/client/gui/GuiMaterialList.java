package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
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
    protected int getBrowserWidth() { return this.getScreenWidth() - 20; }

    @Override
    protected int getBrowserHeight() {
        // createBottomButtons 中 y = 48 + browserHeight + 4，按钮高20px，需在热键栏上方 12px
        // 倒推：48 + browserHeight + 4 + 20 + 12 = screenHeight - 44  →  browserHeight = screenHeight - 128 → bottomMargin=128（含头部34px）
        // 非 owner 无底部按钮，列表延伸到热键栏顶部 → bottomMargin = 44
        int bottomMargin = isOwner ? 128 : 44;
        return this.getScreenHeight() - bottomMargin;
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
            x -= this.createButtonManagement(x, 24);
        }
        if (isOwner) {
            this.createBottomButtons();
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

    private int createButtonManagement(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, StringUtils.translate("syncmaterial.gui.button.manage"));
        this.addButton(button, (btn, mouseButton) ->
                fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiOwnerManagementDialog(this)));
        return button.getWidth();
    }

    private void createBottomButtons() {
        int listBottom = 48 + this.getBrowserHeight();
        int y = listBottom + 4;

        // 先计算总宽度以居中
        ButtonGeneric tmpAssign = new ButtonGeneric(0, 0, -1, false, StringUtils.translate("syncmaterial.gui.button.assign_to"));
        ButtonGeneric tmpKick = new ButtonGeneric(0, 0, -1, false, StringUtils.translate("syncmaterial.gui.button.kick"));
        int totalWidth = tmpAssign.getWidth() + 2 + tmpKick.getWidth();
        int x = (this.getScreenWidth() - totalWidth) / 2;

        ButtonGeneric btnAssign = new ButtonGeneric(x, y, -1, false, StringUtils.translate("syncmaterial.gui.button.assign_to"));
        this.addButton(btnAssign, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiPlayerSelectDialog(this, "ASSIGN", this));
        });
        x += btnAssign.getWidth() + 2;

        ButtonGeneric btnKick = new ButtonGeneric(x, y, -1, false, StringUtils.translate("syncmaterial.gui.button.kick"));
        this.addButton(btnKick, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, StringUtils.translate("syncmaterial.gui.hint.select_materials_first"));
                return;
            }
            fi.dy.masa.malilib.gui.GuiBase.openGui(new GuiPlayerSelectDialog(this, "KICK", this));
        });
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
     * 负责人体系数据更新（数据真身在本类，弹窗每次刷新从这里读）。
     * 网络响应打开着弹窗时由 receiver 路由到弹窗，弹窗再回调本方法更新数据。
     */
    public void updateOwnerState(String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        if (newOwnerName != null) this.ownerName = newOwnerName;
        if (newDeputyOwners != null) this.deputyOwners = new ArrayList<>(newDeputyOwners);
        this.allowSelfClaim = newAllowSelfClaim;
        this.materialList.setAllowSelfClaim(newAllowSelfClaim);
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
     * 无弹窗打开时的兜底数据更新（正常流程 receiver 会路由到弹窗）。
     */
    public void onOwnerActionResponse(boolean success, String message, String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        updateOwnerState(newOwnerName, newDeputyOwners, newAllowSelfClaim);
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
