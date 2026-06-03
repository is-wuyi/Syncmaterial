package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import net.syncmaterial.syncmaterial.network.RescanStagingAreaC2SPacket;
import net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final SyncMaterialList materialList;
    private boolean isOwner;
    private boolean isMainOwner;
    private String ownerName;
    private List<String> deputyOwners;
    private boolean allowSelfClaim;
    private boolean filterMyMaterials = false;
    private List<Integer> selectedMaterialIds = new ArrayList<>();

    // ========== Overlay 状态 ==========
    private enum OverlayType { NONE, PLAYER_SELECT, MANAGEMENT }
    private OverlayType activeOverlay = OverlayType.NONE;
    private String overlayPlayerAction = null;
    private List<PlayerInfo> overlayPlayers = List.of();
    private List<String> overlaySelectedPlayers = new ArrayList<>();
    private int overlayScrollOffset = 0;
    private String overlaySearchText = "";
    private int overlayConfirmTimer = 0;
    private String mgmtStatusMessage = "";
    private int mgmtStatusColor = 0xFFFFFFFF;
    private int mgmtStatusTimer = 0;

    private static final int OVERLAY_VISIBLE_ROWS = 10;
    private static final int OVERLAY_ROW_HEIGHT = 20;
    private static final int OVERLAY_PANEL_WIDTH = 300;

    // Litematica 风格配色（与 MaLiLib/主界面对齐）
    private static final int CLR_OVERLAY_BG     = 0xB0000000; // 全屏遮罩
    private static final int CLR_PANEL_BG       = 0xFF000000; // 面板背景（纯黑，与主界面 tooltip 一致）
    private static final int CLR_PANEL_BORDER   = GuiBase.COLOR_HORIZONTAL_BAR; // 面板边框 0xFF999999
    private static final int CLR_SECTION_BG     = 0xCC1A1A1A; // 区块背景
    private static final int CLR_LIST_BG        = 0xFF0A0A0A; // 列表背景
    private static final int CLR_ROW_ODD        = 0xA0101010; // 奇数行（与主列表一致）
    private static final int CLR_ROW_EVEN       = 0xA0303030; // 偶数行（与主列表一致）
    private static final int CLR_CHECKBOX_BORDER= 0xFF555555; // 复选框边框
    private static final int CLR_CHECKBOX_UNSEL = 0xFF2A2A2A; // 复选框未选中
    private static final int CLR_BTN_DEFAULT    = 0xFF464646; // 按钮默认
    private static final int CLR_BTN_HOVER      = 0xFF5A5A5A; // 按钮悬停
    private static final int CLR_BTN_DISABLED   = 0xFF2A2A2A; // 按钮禁用
    private static final int CLR_BTN_BORDER     = 0xFF1A1A1A; // 按钮边框（暗色凸起感）
    private static final int CLR_SCROLLBAR_BG   = 0xFF333333; // 滚动条背景
    private static final int CLR_SCROLLBAR_THUMB= 0xFF666666; // 滚动条滑块
    private static final int CLR_TEXT_WHITE     = 0xFFE0E0E0; // 默认文字（与 ButtonGeneric 一致）
    private static final int CLR_TEXT_GRAY      = 0xFFAAAAAA; // 副标题
    private static final int CLR_TEXT_DIM       = 0xFF888888; // 说明文字
    private static final int CLR_TEXT_MUTED     = 0xFFA0A0A0; // 禁用文字（与 ButtonGeneric 禁用色一致）
    private static final int CLR_TEXT_GREEN     = 0xFF55FF55; // 在线/负责人名/成功
    private static final int CLR_TEXT_RED       = 0xFFFF5555; // 失败/删除
    private static final int CLR_HOVER_ROW      = 0xA0707070; // 行悬停（与主列表一致）

    public GuiMaterialList(String schematicId, String schematicName, List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        super(10, 44);

        this.isOwner = isOwner;
        this.isMainOwner = isMainOwner;
        this.ownerName = ownerName != null ? ownerName : "";
        this.deputyOwners = deputyOwners != null ? new ArrayList<>(deputyOwners) : new ArrayList<>();
        this.allowSelfClaim = allowSelfClaim;
        this.materialList = new SyncMaterialList(schematicId, schematicName);
        this.materialList.setOnStatusUpdate(() -> this.getListWidget().refreshEntries());
        this.materialList.setMaterialEntries(entries);
        this.title = this.materialList.getTitle();
        this.useTitleHierarchy = false;

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
        WidgetMaterialListEntry.setMaxNameLength(this.materialList.getMaterialsAll(), this.materialList.getMultiplier());
    }

    public SyncMaterialList getMaterialList() { return this.materialList; }
    public boolean isOwner() { return isOwner; }
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
        int bottomMargin = isOwner ? 88 + 24 : 88;
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
        x -= this.createButtonStagingArea(x, 24) + gap;
        x -= this.createButtonFilterMyMaterials(x, 24) + gap;
        if (isOwner) {
            x -= this.createButtonManagement(x, 24);
        }
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
        this.addButton(button, (btn, mouseButton) -> openManagementOverlay());
        return button.getWidth();
    }

    private void createBottomButtons() {
        int listBottom = 48 + this.getBrowserHeight();
        int y = listBottom + 4;
        int x = 10;

        ButtonGeneric btnAssign = new ButtonGeneric(x, y, -1, true, "分配给...");
        this.addButton(btnAssign, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, "请先勾选材料");
                return;
            }
            requestPlayerList("ASSIGN");
        });
        x += btnAssign.getWidth() + 2;

        ButtonGeneric btnKick = new ButtonGeneric(x, y, -1, true, "踢出");
        this.addButton(btnKick, (btn, mouseButton) -> {
            if (selectedMaterialIds.isEmpty()) {
                InfoUtils.showGuiOrActionBarMessage(MessageType.WARNING, "请先勾选材料");
                return;
            }
            requestPlayerList("KICK");
        });
    }

    private int createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
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

    public void onOwnerActionResponse(boolean success, String message, String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        this.ownerName = newOwnerName != null ? newOwnerName : this.ownerName;
        this.deputyOwners = newDeputyOwners != null ? new ArrayList<>(newDeputyOwners) : this.deputyOwners;
        this.allowSelfClaim = newAllowSelfClaim;

        mgmtStatusMessage = message;
        mgmtStatusColor = success ? CLR_TEXT_GREEN : CLR_TEXT_RED;
        mgmtStatusTimer = 100;

        if (success && "TRANSFER".equals(overlayPlayerAction)) {
            closeOverlay();
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, message);
        }
    }

    public void onBatchAssignResponse(boolean success, String message) {
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        if (success) this.materialList.requestCollaborationStatus();
    }

    public void onKickResponse(boolean success, String message) {
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        if (success) this.materialList.requestCollaborationStatus();
    }

    public void onPlayerListResponse(List<PlayerInfo> players) {
        if (overlayPlayerAction == null) return;
        overlayPlayers = players;
        overlaySelectedPlayers.clear();
        overlayScrollOffset = 0;
        overlaySearchText = "";
        overlayConfirmTimer = 0;
        activeOverlay = OverlayType.PLAYER_SELECT;
    }

    // ========== Overlay 管理 ==========

    private void requestPlayerList(String action) {
        overlayPlayerAction = action;
        ClientPlayNetworking.send(new PlayerListRequestC2SPacket(this.materialList.getSchematicId()));
    }

    private void openManagementOverlay() {
        activeOverlay = OverlayType.MANAGEMENT;
        mgmtStatusMessage = "";
        mgmtStatusTimer = 0;
    }

    private void closeOverlay() {
        activeOverlay = OverlayType.NONE;
        overlayPlayerAction = null;
        overlayPlayers = List.of();
        overlaySelectedPlayers.clear();
        overlaySearchText = "";
        overlayScrollOffset = 0;
        overlayConfirmTimer = 0;
    }

    private boolean isOverlayActive() {
        return activeOverlay != OverlayType.NONE;
    }

    // ========== 输入事件拦截 ==========

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (isOverlayActive()) {
            handleOverlayClick(mouseX, mouseY);
            return true;
        }
        return super.onMouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount) {
        if (isOverlayActive()) {
            if (activeOverlay == OverlayType.PLAYER_SELECT) {
                List<PlayerInfo> filtered = getFilteredPlayers();
                if (filtered.size() > OVERLAY_VISIBLE_ROWS) {
                    overlayScrollOffset = Math.max(0, Math.min(overlayScrollOffset - (int) Math.signum(verticalAmount), filtered.size() - OVERLAY_VISIBLE_ROWS));
                }
            }
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers) {
        if (isOverlayActive()) {
            if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ESCAPE) {
                closeOverlay();
                return true;
            }
            if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ENTER && activeOverlay == OverlayType.PLAYER_SELECT && overlayConfirmTimer == 0) {
                handleOverlayConfirm();
                return true;
            }
            return true;
        }
        return super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (isOverlayActive()) {
            if (activeOverlay == OverlayType.PLAYER_SELECT && overlayConfirmTimer == 0) {
                if (chr == '\b') {
                    if (!overlaySearchText.isEmpty()) {
                        overlaySearchText = overlaySearchText.substring(0, overlaySearchText.length() - 1);
                        overlayScrollOffset = 0;
                    }
                } else if (chr >= 32 && chr < 127 || Character.isLetterOrDigit(chr)) {
                    overlaySearchText += chr;
                    overlayScrollOffset = 0;
                }
            }
            return true;
        }
        return super.onCharTyped(chr, modifiers);
    }

    // ========== Overlay 渲染 ==========

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        if (this.materialList.getHudRenderer().getShouldRender()) {
            this.materialList.getHudRenderer().render(drawContext, 10, 44, HudAlignment.TOP_LEFT);
        }

        if (isOverlayActive()) {
            renderOverlay(drawContext, mouseX, mouseY);
        }
    }

    private void renderOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        drawContext.fill(0, 0, this.width, this.height, CLR_OVERLAY_BG);

        switch (activeOverlay) {
            case PLAYER_SELECT -> renderPlayerSelectOverlay(drawContext, mouseX, mouseY);
            case MANAGEMENT -> renderManagementOverlay(drawContext, mouseX, mouseY);
            default -> {}
        }
    }

    // ========== 玩家选择 overlay ==========

    private List<PlayerInfo> getFilteredPlayers() {
        if (overlaySearchText.isEmpty()) return overlayPlayers;
        String lower = overlaySearchText.toLowerCase();
        return overlayPlayers.stream().filter(p -> p.name().toLowerCase().contains(lower)).toList();
    }

    private void renderPlayerSelectOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        int panelX = (this.width - OVERLAY_PANEL_WIDTH) / 2;
        int panelHeight = 44 + 26 + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 10 + 24 + 10;
        int panelY = (this.height - panelHeight) / 2;

        // 面板背景 + 描边
        drawOutlinedPanel(drawContext, panelX, panelY, OVERLAY_PANEL_WIDTH, panelHeight);

        // 标题
        String title = switch (overlayPlayerAction != null ? overlayPlayerAction : "") {
            case "ASSIGN" -> "选择分配玩家";
            case "KICK" -> "选择踢出玩家";
            default -> "选择玩家";
        };
        drawContext.drawCenteredTextWithShadow(this.textRenderer, title, this.width / 2, panelY + 10, CLR_TEXT_WHITE);

        // 提示
        String hint = switch (overlayPlayerAction != null ? overlayPlayerAction : "") {
            case "ASSIGN" -> "勾选要分配给的玩家";
            case "KICK" -> "勾选要踢出的玩家";
            default -> "选择玩家";
        };
        drawContext.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, panelY + 24, CLR_TEXT_GRAY);

        int listY = panelY + 40;

        // 搜索框
        int searchX = panelX + 10;
        int searchY = listY;
        int searchW = OVERLAY_PANEL_WIDTH - 20;
        drawContext.fill(searchX, searchY, searchX + searchW, searchY + 18, CLR_SECTION_BG);
        String searchText = overlaySearchText.isEmpty() ? "搜索玩家..." : overlaySearchText;
        int searchColor = overlaySearchText.isEmpty() ? CLR_TEXT_MUTED : CLR_TEXT_WHITE;
        drawContext.drawTextWithShadow(this.textRenderer, searchText, searchX + 4, searchY + 5, searchColor);
        if (!overlaySearchText.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cursorX = searchX + 4 + this.textRenderer.getWidth(overlaySearchText);
            drawContext.fill(cursorX, searchY + 4, cursorX + 1, searchY + 14, CLR_TEXT_WHITE);
        }
        listY += 22;

        // 列表
        int listX = panelX + 10;
        int listWidth = OVERLAY_PANEL_WIDTH - 20;
        int listHeight = OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT;
        drawContext.fill(listX, listY, listX + listWidth, listY + listHeight, CLR_LIST_BG);

        List<PlayerInfo> filtered = getFilteredPlayers();
        int endIndex = Math.min(overlayScrollOffset + OVERLAY_VISIBLE_ROWS, filtered.size());
        for (int i = overlayScrollOffset; i < endIndex; i++) {
            int rowY = listY + (i - overlayScrollOffset) * OVERLAY_ROW_HEIGHT;
            PlayerInfo player = filtered.get(i);
            boolean selected = overlaySelectedPlayers.contains(player.name());
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + OVERLAY_ROW_HEIGHT;

            if (hovered) {
                drawContext.fill(listX, rowY, listX + listWidth, rowY + OVERLAY_ROW_HEIGHT, CLR_HOVER_ROW);
            } else {
                // 斑马纹（与主列表 WidgetMaterialListEntry 一致）
                int rowBg = ((i - overlayScrollOffset) % 2 == 0) ? CLR_ROW_EVEN : CLR_ROW_ODD;
                drawContext.fill(listX, rowY, listX + listWidth, rowY + OVERLAY_ROW_HEIGHT, rowBg);
            }

            // 复选框
            int checkboxX = listX + 4;
            int checkboxY = rowY + 3;
            drawContext.fill(checkboxX, checkboxY, checkboxX + 14, checkboxY + 14, CLR_CHECKBOX_BORDER);
            drawContext.fill(checkboxX + 1, checkboxY + 1, checkboxX + 13, checkboxY + 13, selected ? 0xFF00AA00 : CLR_CHECKBOX_UNSEL);
            if (selected) {
                drawContext.drawCenteredTextWithShadow(this.textRenderer, "✓", checkboxX + 7, checkboxY + 2, CLR_TEXT_WHITE);
            }

            // 玩家名
            int textColor = player.online() ? CLR_TEXT_GREEN : CLR_TEXT_GRAY;
            drawContext.drawTextWithShadow(this.textRenderer, player.name(), listX + 24, rowY + 6, textColor);

            // 在线状态
            if (player.online()) {
                String statusText = "在线";
                int statusWidth = this.textRenderer.getWidth(statusText);
                drawContext.drawTextWithShadow(this.textRenderer, statusText, listX + listWidth - statusWidth - 8, rowY + 6, CLR_TEXT_GREEN);
            }
        }

        // 滚动条
        if (filtered.size() > OVERLAY_VISIBLE_ROWS) {
            int scrollbarX = listX + listWidth - 6;
            int thumbHeight = Math.max(10, listHeight * OVERLAY_VISIBLE_ROWS / filtered.size());
            int thumbY = listY + (listHeight - thumbHeight) * overlayScrollOffset / Math.max(1, filtered.size() - OVERLAY_VISIBLE_ROWS);
            drawContext.fill(scrollbarX, listY, scrollbarX + 4, listY + listHeight, CLR_SCROLLBAR_BG);
            drawContext.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, CLR_SCROLLBAR_THUMB);
        }

        if (filtered.isEmpty()) {
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "没有匹配的玩家", this.width / 2, listY + listHeight / 2 - 6, CLR_TEXT_MUTED);
        }

        // 底部信息 + 按钮
        int bottomY = listY + listHeight + 8;
        String infoText = "已选择 " + overlaySelectedPlayers.size() + " 个玩家 | 材料 " + selectedMaterialIds.size() + " 个";
        drawContext.drawCenteredTextWithShadow(this.textRenderer, infoText, this.width / 2, bottomY, CLR_TEXT_GRAY);

        int btnY = bottomY + 14;
        if (overlayConfirmTimer > 0) {
            overlayConfirmTimer--;
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "已发送 ✓", this.width / 2, btnY + 4, CLR_TEXT_GREEN);
            if (overlayConfirmTimer == 0) {
                // ADD_DEPUTY / TRANSFER 完成后回到管理界面，其他操作直接关闭
                boolean returnToMgmt = "ADD_DEPUTY".equals(overlayPlayerAction) || "TRANSFER".equals(overlayPlayerAction);
                closeOverlay();
                if (returnToMgmt) openManagementOverlay();
            }
        } else {
            int btnW = 60;
            int confirmX = this.width / 2 - btnW - 10;
            int cancelX = this.width / 2 + 10;

            boolean confirmHovered = mouseX >= confirmX && mouseX < confirmX + btnW && mouseY >= btnY && mouseY < btnY + 20;
            boolean confirmDisabled = overlaySelectedPlayers.isEmpty();
            drawButton(drawContext, confirmX, btnY, btnW, 20, confirmHovered, confirmDisabled);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "确认", confirmX + btnW / 2, btnY + 6, confirmDisabled ? CLR_TEXT_MUTED : (confirmHovered ? 0xFFFFFFFF : CLR_TEXT_WHITE));

            boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + btnW && mouseY >= btnY && mouseY < btnY + 20;
            drawButton(drawContext, cancelX, btnY, btnW, 20, cancelHovered, false);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "取消", cancelX + btnW / 2, btnY + 6, cancelHovered ? 0xFFFFFFFF : CLR_TEXT_WHITE);
        }
    }

    // ========== 管理 overlay ==========

    /** 管理面板内部各区段的 Y 偏移（相对 panelY） */
    private static final int MGMT_PAD = 10;
    private static final int MGMT_TITLE_H = 14;
    private static final int MGMT_SUBTITLE_H = 18;
    private static final int MGMT_DESC_H = 54;
    private static final int MGMT_SECTION_TITLE_H = 24;
    private static final int MGMT_ROW_H = 22;
    private static final int MGMT_ADD_DEPUTY_H = 24;
    private static final int MGMT_TOGGLE_H = 48;
    private static final int MGMT_STATUS_H = 20;
    private static final int MGMT_CLOSE_BTN_H = 22;
    private static final int MGMT_GAP = 8;
    private static final int MGMT_PANEL_W = 300;
    private static final int MGMT_INNER_W = MGMT_PANEL_W - 30;
    private static final int MGMT_LEFT_PAD = 15;

    /** 计算管理面板总高度 */
    private int calcManagementPanelHeight() {
        int h = MGMT_PAD;
        h += MGMT_TITLE_H + MGMT_SUBTITLE_H;
        h += MGMT_DESC_H + MGMT_GAP;
        h += MGMT_SECTION_TITLE_H;
        h += Math.max(1, deputyOwners.size()) * MGMT_ROW_H;
        if (isMainOwner) h += 2 + MGMT_ADD_DEPUTY_H;
        h += MGMT_GAP;
        h += MGMT_TOGGLE_H;
        h += MGMT_STATUS_H;
        h += MGMT_GAP + MGMT_CLOSE_BTN_H;
        h += MGMT_PAD;
        return h;
    }

    private void renderManagementOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        int panelX = (this.width - MGMT_PANEL_W) / 2;
        int panelHeight = calcManagementPanelHeight();
        int panelY = (this.height - panelHeight) / 2;
        int centerX = this.width / 2;
        int leftX = panelX + MGMT_LEFT_PAD;

        // 面板背景 + 描边
        drawOutlinedPanel(drawContext, panelX, panelY, MGMT_PANEL_W, panelHeight);

        int y = panelY + MGMT_PAD;

        // 标题
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "负责人管理", centerX, y, CLR_TEXT_WHITE);
        y += MGMT_TITLE_H;
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "原理图: " + this.materialList.getTitle(), centerX, y, CLR_TEXT_GRAY);
        y += MGMT_SUBTITLE_H;

        // 说明区块 — 用左侧竖线装饰，避免像输入框
        int descY = y;
        drawContext.fill(leftX, descY, leftX + 3, descY + MGMT_DESC_H, CLR_TEXT_DIM);
        drawTextWrapped(drawContext, "负责人可以管理材料的认领与分配。主负责人拥有全部管理权限，可转让负责人、添加/移除副负责人。副负责人可以批量分配材料和踢出玩家。开启「自行认领」后，所有玩家可以自行认领材料。", leftX + 8, descY + 4, MGMT_INNER_W - 12, CLR_TEXT_DIM);
        y = descY + MGMT_DESC_H + MGMT_GAP;

        // 区块标题
        drawContext.fill(leftX, y, leftX + MGMT_INNER_W, y + MGMT_SECTION_TITLE_H, CLR_SECTION_BG);
        drawContext.drawTextWithShadow(this.textRenderer, "当前负责人", leftX + 6, y + 5, CLR_TEXT_WHITE);
        y += MGMT_SECTION_TITLE_H;

        // 主负责人行
        drawContext.drawTextWithShadow(this.textRenderer, "主负责人: " + ownerName, leftX + 6, y + 2, CLR_TEXT_GREEN);
        if (isMainOwner) {
            int btnX = leftX + MGMT_INNER_W - 50;
            boolean hovered = mouseX >= btnX && mouseX < btnX + 44 && mouseY >= y && mouseY < y + 18;
            drawButton(drawContext, btnX, y, 44, 18, hovered, false);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "转让", btnX + 22, y + 5, hovered ? 0xFFFFFFFF : CLR_TEXT_WHITE);
        }
        y += MGMT_ROW_H;

        // 副负责人列表
        for (int i = 0; i < deputyOwners.size(); i++) {
            String deputy = deputyOwners.get(i);
            drawContext.drawTextWithShadow(this.textRenderer, "副负责人: " + deputy, leftX + 6, y + 2, CLR_TEXT_GREEN);
            if (isMainOwner) {
                int delX = leftX + MGMT_INNER_W - 22;
                boolean hovered = mouseX >= delX && mouseX < delX + 18 && mouseY >= y && mouseY < y + 18;
                int delBg = hovered ? 0xFFAA3333 : 0xFF993333;
                drawContext.fill(delX, y, delX + 18, y + 18, delBg);
                drawContext.fill(delX, y, delX + 18, y + 1, 0x40FFFFFF);
                drawContext.fill(delX, y, delX + 1, y + 18, 0x40FFFFFF);
                drawContext.fill(delX, y + 17, delX + 18, y + 18, 0xFF441111);
                drawContext.fill(delX + 17, y, delX + 18, y + 18, 0xFF441111);
                drawContext.drawCenteredTextWithShadow(this.textRenderer, "×", delX + 9, y + 3, CLR_TEXT_WHITE);
            }
            y += MGMT_ROW_H;
        }
        if (deputyOwners.isEmpty()) {
            drawContext.drawTextWithShadow(this.textRenderer, "副负责人: 无", leftX + 6, y + 2, CLR_TEXT_MUTED);
            y += MGMT_ROW_H;
        }

        // 添加副负责人按钮
        if (isMainOwner) {
            y += 2;
            boolean hovered = mouseX >= leftX && mouseX < leftX + MGMT_INNER_W && mouseY >= y && mouseY < y + MGMT_ADD_DEPUTY_H;
            drawButton(drawContext, leftX, y, MGMT_INNER_W, MGMT_ADD_DEPUTY_H, hovered, false);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "添加副负责人", centerX, y + 6, CLR_TEXT_GREEN);
            y += MGMT_ADD_DEPUTY_H;
        }

        y += MGMT_GAP;

        // 自行认领区块
        drawContext.fill(leftX, y, leftX + MGMT_INNER_W, y + MGMT_TOGGLE_H, CLR_SECTION_BG);
        drawContext.drawTextWithShadow(this.textRenderer, "自行认领: " + (allowSelfClaim ? "开启" : "关闭"), leftX + 6, y + 5, CLR_TEXT_WHITE);
        int toggleX = leftX + MGMT_INNER_W - 60;
        boolean toggleHovered = mouseX >= toggleX && mouseX < toggleX + 54 && mouseY >= y + 2 && mouseY < y + 18;
        int toggleBg = allowSelfClaim
                ? (toggleHovered ? 0xFF2A7A2A : 0xFF225522)
                : (toggleHovered ? CLR_TEXT_RED : 0xFF993333);
        drawContext.fill(toggleX, y + 2, toggleX + 54, y + 18, toggleBg);
        drawContext.fill(toggleX, y + 2, toggleX + 54, y + 3, 0x40FFFFFF);
        drawContext.fill(toggleX, y + 2, toggleX + 1, y + 18, 0x40FFFFFF);
        drawContext.fill(toggleX, y + 17, toggleX + 54, y + 18, 0xFF111111);
        drawContext.fill(toggleX + 53, y + 2, toggleX + 54, y + 18, 0xFF111111);
        drawContext.drawCenteredTextWithShadow(this.textRenderer, allowSelfClaim ? "关闭" : "开启", toggleX + 27, y + 7, toggleHovered ? 0xFFFFFFFF : CLR_TEXT_WHITE);
        y += MGMT_TOGGLE_H;

        // 状态消息
        if (mgmtStatusTimer > 0) {
            mgmtStatusTimer--;
            drawContext.drawCenteredTextWithShadow(this.textRenderer, mgmtStatusMessage, centerX, y + 4, mgmtStatusColor);
        }
        y += MGMT_STATUS_H;

        y += MGMT_GAP;

        // 关闭按钮
        int closeBtnW = 80;
        int closeBtnX = centerX - closeBtnW / 2;
        boolean closeHovered = mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW && mouseY >= y && mouseY < y + MGMT_CLOSE_BTN_H;
        drawButton(drawContext, closeBtnX, y, closeBtnW, MGMT_CLOSE_BTN_H, closeHovered, false);
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "关闭", centerX, y + 5, closeHovered ? 0xFFFFFFFF : CLR_TEXT_WHITE);
    }

    /** 带描边的面板绘制（对齐 MaLiLib drawOutlinedBox 风格） */
    private void drawOutlinedPanel(DrawContext drawContext, int x, int y, int w, int h) {
        RenderUtils.drawRect(drawContext, x, y, w, h, CLR_PANEL_BG);
        RenderUtils.drawOutline(drawContext, x - 1, y - 1, w + 2, h + 2, 1, CLR_PANEL_BORDER);
    }

    /** 带边框的按钮绘制（对齐 ButtonGeneric 凸起感） */
    private void drawButton(DrawContext drawContext, int x, int y, int w, int h, boolean hovered, boolean disabled) {
        int bg = disabled ? CLR_BTN_DISABLED : (hovered ? CLR_BTN_HOVER : CLR_BTN_DEFAULT);
        drawContext.fill(x, y, x + w, y + h, bg);
        // 1px 暗色边框：上/左亮，下/右暗，模拟 ButtonGeneric 的凸起效果
        drawContext.fill(x, y, x + w, y + 1, 0x40FFFFFF);       // 上边亮线
        drawContext.fill(x, y, x + 1, y + h, 0x40FFFFFF);       // 左边亮线
        drawContext.fill(x, y + h - 1, x + w, y + h, CLR_BTN_BORDER); // 下边暗线
        drawContext.fill(x + w - 1, y, x + w, y + h, CLR_BTN_BORDER); // 右边暗线
    }

    private void drawTextWrapped(DrawContext drawContext, String text, int x, int y, int maxWidth, int color) {
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            line.append(c);
            if (this.textRenderer.getWidth(line.toString()) > maxWidth) {
                String drawLine = line.substring(0, line.length() - 1);
                drawContext.drawTextWithShadow(this.textRenderer, drawLine, x, lineY, color);
                lineY += 12;
                line = new StringBuilder();
                line.append(c);
            }
        }
        if (!line.isEmpty()) {
            drawContext.drawTextWithShadow(this.textRenderer, line.toString(), x, lineY, color);
        }
    }

    // ========== Overlay 点击处理 ==========

    private void handleOverlayClick(int mouseX, int mouseY) {
        switch (activeOverlay) {
            case PLAYER_SELECT -> handlePlayerSelectClick(mouseX, mouseY);
            case MANAGEMENT -> handleManagementClick(mouseX, mouseY);
            default -> {}
        }
    }

    private void handlePlayerSelectClick(int mouseX, int mouseY) {
        int panelX = (this.width - OVERLAY_PANEL_WIDTH) / 2;
        int panelHeight = 44 + 26 + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 10 + 24 + 10;
        int panelY = (this.height - panelHeight) / 2;

        if (mouseX < panelX || mouseX > panelX + OVERLAY_PANEL_WIDTH || mouseY < panelY || mouseY > panelY + panelHeight) {
            closeOverlay();
            return;
        }
        if (overlayConfirmTimer > 0) return;

        int listY = panelY + 62;
        int listX = panelX + 10;
        int listWidth = OVERLAY_PANEL_WIDTH - 20;

        if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= listY) {
            List<PlayerInfo> filtered = getFilteredPlayers();
            int row = (int) ((mouseY - listY) / OVERLAY_ROW_HEIGHT) + overlayScrollOffset;
            if (row >= 0 && row < filtered.size()) {
                String playerName = filtered.get(row).name();
                if (overlaySelectedPlayers.contains(playerName)) {
                    overlaySelectedPlayers.remove(playerName);
                } else {
                    overlaySelectedPlayers.add(playerName);
                }
                return;
            }
        }

        int bottomY = listY + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 8 + 14;
        int btnW = 60;
        int centerX = this.width / 2;
        int confirmX = centerX - btnW - 10;
        int cancelX = centerX + 10;

        if (mouseY >= bottomY && mouseY < bottomY + 20) {
            if (mouseX >= confirmX && mouseX < confirmX + btnW && !overlaySelectedPlayers.isEmpty()) {
                handleOverlayConfirm();
            } else if (mouseX >= cancelX && mouseX < cancelX + btnW) {
                closeOverlay();
            }
        }
    }

    private void handleOverlayConfirm() {
        if (overlaySelectedPlayers.isEmpty() || overlayConfirmTimer > 0) return;

        String action = overlayPlayerAction;
        List<String> players = new ArrayList<>(overlaySelectedPlayers);
        String schematicId = this.materialList.getSchematicId();
        List<Integer> materialIds = new ArrayList<>(selectedMaterialIds);

        switch (action) {
            case "ASSIGN" -> ClientPlayNetworking.send(new BatchAssignC2SPacket(schematicId, materialIds, players));
            case "KICK" -> {
                for (String player : players) {
                    ClientPlayNetworking.send(new KickFromMaterialC2SPacket(schematicId, materialIds, player));
                }
            }
            case "ADD_DEPUTY" -> {
                for (String player : players) {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "ADD_DEPUTY", player));
                }
            }
            case "TRANSFER" -> {
                if (!players.isEmpty()) {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "TRANSFER", players.get(0)));
                }
            }
        }

        overlayConfirmTimer = 60;
    }

    private void handleManagementClick(int mouseX, int mouseY) {
        int panelX = (this.width - MGMT_PANEL_W) / 2;
        int panelHeight = calcManagementPanelHeight();
        int panelY = (this.height - panelHeight) / 2;
        int centerX = this.width / 2;
        int leftX = panelX + MGMT_LEFT_PAD;

        // 点击面板外关闭
        if (mouseX < panelX || mouseX > panelX + MGMT_PANEL_W || mouseY < panelY || mouseY > panelY + panelHeight) {
            closeOverlay();
            return;
        }

        // —— 与 renderManagementOverlay 完全一致的 Y 坐标追踪 ——
        int y = panelY + MGMT_PAD;
        y += MGMT_TITLE_H + MGMT_SUBTITLE_H;
        y += MGMT_DESC_H + MGMT_GAP;
        y += MGMT_SECTION_TITLE_H;

        // 主负责人行 — "转让" 按钮
        if (isMainOwner) {
            int btnX = leftX + MGMT_INNER_W - 50;
            if (mouseX >= btnX && mouseX < btnX + 44 && mouseY >= y && mouseY < y + 18) {
                requestPlayerList("TRANSFER");
                return;
            }
        }
        y += MGMT_ROW_H;

        // 副负责人行 — "×" 按钮
        for (int i = 0; i < deputyOwners.size(); i++) {
            if (isMainOwner) {
                int delX = leftX + MGMT_INNER_W - 22;
                if (mouseX >= delX && mouseX < delX + 18 && mouseY >= y && mouseY < y + 18) {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "REMOVE_DEPUTY", deputyOwners.get(i)));
                    return;
                }
            }
            y += MGMT_ROW_H;
        }
        if (deputyOwners.isEmpty()) y += MGMT_ROW_H;

        // "添加副负责人" 按钮
        if (isMainOwner) {
            y += 2;
            if (mouseX >= leftX && mouseX < leftX + MGMT_INNER_W && mouseY >= y && mouseY < y + MGMT_ADD_DEPUTY_H) {
                requestPlayerList("ADD_DEPUTY");
                return;
            }
            y += MGMT_ADD_DEPUTY_H;
        }

        y += MGMT_GAP;

        // "自行认领" 开关
        {
            int toggleX = leftX + MGMT_INNER_W - 60;
            if (mouseX >= toggleX && mouseX < toggleX + 54 && mouseY >= y + 2 && mouseY < y + 18) {
                ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "TOGGLE_SELF_CLAIM", ""));
                allowSelfClaim = !allowSelfClaim;
                mgmtStatusMessage = "请求已发送...";
                mgmtStatusColor = CLR_TEXT_GRAY;
                mgmtStatusTimer = 60;
                return;
            }
        }
        y += MGMT_TOGGLE_H;
        y += MGMT_STATUS_H;
        y += MGMT_GAP;

        // "关闭" 按钮
        {
            int closeBtnW = 80;
            int closeBtnX = centerX - closeBtnW / 2;
            if (mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW && mouseY >= y && mouseY < y + MGMT_CLOSE_BTN_H) {
                closeOverlay();
                return;
            }
        }
    }

    @Override
    public void close() {
        if (isOverlayActive()) {
            closeOverlay();
            return;
        }
        net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
