package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;
    private final boolean isOwner;
    private final boolean isMainOwner;
    private final String ownerName;
    private final List<String> deputyOwners;
    private final boolean allowSelfClaim;
    private boolean filterMyMaterials = false;
    private List<Integer> selectedMaterialIds = new java.util.ArrayList<>();
    /** 玩家列表请求的操作模式：ASSIGN 或 KICK */
    private String pendingPlayerListAction = null;

    public GuiMaterialList(String schematicId, String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        super(10, 44);

        this.isOwner = isOwner;
        this.isMainOwner = isMainOwner;
        this.ownerName = ownerName != null ? ownerName : "";
        this.deputyOwners = deputyOwners != null ? deputyOwners : List.of();
        this.allowSelfClaim = allowSelfClaim;
        this.materialList = new SyncMaterialList(schematicId, schematicName);
        this.materialList.setOnStatusUpdate(() -> this.getListWidget().refreshEntries());
        this.materialList.setMaterialEntries(entries);
        this.title = this.materialList.getTitle();
        this.useTitleHierarchy = false;

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
        WidgetMaterialListEntry.setMaxNameLength(this.materialList.getMaterialsAll(), this.materialList.getMultiplier());
    }

    public SyncMaterialList getMaterialList() {
        return this.materialList;
    }

    public boolean isOwner() { return isOwner; }
    public boolean isMainOwner() { return isMainOwner; }
    public String getOwnerName() { return ownerName; }
    public List<String> getDeputyOwners() { return deputyOwners; }
    public boolean isAllowSelfClaim() { return allowSelfClaim; }
    public boolean isFilterMyMaterials() { return filterMyMaterials; }
    public List<Integer> getSelectedMaterialIds() { return selectedMaterialIds; }

    @Override
    protected int getBrowserWidth() {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        // 负责人底部有分配/踢出按钮，需要额外空间
        int bottomMargin = isOwner ? 88 + 24 : 88;
        return this.getScreenHeight() - bottomMargin;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    public void initGui() {
        super.initGui();

        int gap = 2;

        // 顶部按钮栏：从右往左排列
        int x = this.getScreenWidth() - 20;
        x -= this.createButtonClose(x, 24) + gap;
        x -= this.createButtonToggleHud(x, 24) + gap;
        x -= this.createButtonRefresh(x, 24) + gap;
        x -= this.createButtonStagingArea(x, 24) + gap;

        // Phase 4: 所有玩家可见的过滤按钮
        x -= this.createButtonFilterMyMaterials(x, 24) + gap;

        // Phase 4: 仅负责人可见的管理按钮
        if (isOwner) {
            x -= this.createButtonManagement(x, 24);
        }

        // Phase 4: 负责人底部操作按钮
        if (isOwner) {
            this.createBottomButtons();
        }

        this.materialList.requestCollaborationStatus();
    }

    private int createButtonRefresh(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "刷新列表");
        this.addButton(button, (btn, mouseButton) -> {
            String schematicId = this.materialList.getSchematicId();
            if (schematicId != null && !schematicId.isEmpty()) {
                ClientPlayNetworking.send(new RescanStagingAreaC2SPacket(schematicId));
                btn.setDisplayString("刷新中...");
                btn.setEnabled(false);
            }
        });
        return button.getWidth();
    }

    private int createButtonStagingArea(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "备货区配置");
        this.addButton(button, (btn, mouseButton) -> {
            AreaSelection selection = new AreaSelection();
            GuiStagingAreaEditorNormal editor = new GuiStagingAreaEditorNormal(selection, null, this.materialList.getSchematicId());
            editor.setParent(this);
            this.mc.setScreen(editor);
        });
        return button.getWidth();
    }

    private int createButtonToggleHud(int x, int y) {
        String label = "HUD信息显示：" + (this.materialList.getHudRenderer().getShouldRender() ? "开启" : "关闭");
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            btn.setDisplayString("HUD信息显示：" + (this.materialList.getHudRenderer().getShouldRender() ? "开启" : "关闭"));
        });
        return button.getWidth();
    }

    // ========== Phase 4: 负责人管理按钮 ==========

    private int createButtonFilterMyMaterials(int x, int y) {
        String label = filterMyMaterials ? "显示全部" : "仅显示我加入的";
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            filterMyMaterials = !filterMyMaterials;
            btn.setDisplayString(filterMyMaterials ? "显示全部" : "仅显示我加入的");
            this.getListWidget().refreshEntries();
        });
        return button.getWidth();
    }

    private int createButtonManagement(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "管理");
        this.addButton(button, (btn, mouseButton) -> {
            this.mc.setScreen(new GuiManagement(
                this.materialList.getSchematicId(),
                this.materialList.getTitle(),
                ownerName, deputyOwners, allowSelfClaim, isMainOwner
            ));
        });
        return button.getWidth();
    }

    private void createBottomButtons() {
        // 放在列表区域底部，底部按钮栏上方
        int y = this.getScreenHeight() - 68;
        int x = 10;

        ButtonGeneric btnAssign = new ButtonGeneric(x, y, -1, true, "分配给...");
        this.addButton(btnAssign, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, "请先勾选材料");
                return;
            }
            pendingPlayerListAction = "ASSIGN";
            ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket(this.materialList.getSchematicId()));
        });
        x += btnAssign.getWidth() + 2;

        ButtonGeneric btnKick = new ButtonGeneric(x, y, -1, true, "踢出");
        this.addButton(btnKick, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, "请先勾选材料");
                return;
            }
            pendingPlayerListAction = "KICK";
            ClientPlayNetworking.send(new net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket(this.materialList.getSchematicId()));
        });
    }

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
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Rescan] 结果: success={}, message={}", success, message);
    }

    /** 处理负责人操作响应 (Phase 4) */
    public void onOwnerActionResponse(boolean success, String message) {
        if (success) {
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
        } else {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, message);
        }
    }

    /** 处理批量分配响应 (Phase 4) */
    public void onBatchAssignResponse(boolean success, String message) {
        if (success) {
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
            // 刷新协作状态
            this.materialList.requestCollaborationStatus();
        } else {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, message);
        }
    }

    /** 处理踢出响应 (Phase 4) */
    public void onKickResponse(boolean success, String message) {
        if (success) {
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
            this.materialList.requestCollaborationStatus();
        } else {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, message);
        }
    }

    /** 处理玩家列表响应 (Phase 4) */
    public void onPlayerListResponse(List<net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo> players) {
        if (pendingPlayerListAction == null) return;
        String action = pendingPlayerListAction;
        pendingPlayerListAction = null;

        String schematicId = this.materialList.getSchematicId();
        List<Integer> materialIds = new java.util.ArrayList<>(selectedMaterialIds);
        GuiPlayerSelector selector = new GuiPlayerSelector(players, action, schematicId, materialIds, this);
        this.mc.setScreen(selector);
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
        return button.getWidth();
    }

    @Override
    public void close() {
        net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        if (this.materialList.getHudRenderer().getShouldRender()) {
            this.materialList.getHudRenderer().render(drawContext, 10, 44, HudAlignment.TOP_LEFT);
        }
    }
}
