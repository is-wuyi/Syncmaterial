package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
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
    private String overlayPlayerAction = null; // ASSIGN / KICK
    private List<PlayerInfo> overlayPlayers = List.of();
    private List<String> overlaySelectedPlayers = new ArrayList<>();
    private int overlayScrollOffset = 0;
    private String overlaySearchText = "";
    private int overlayConfirmTimer = 0;
    // 管理 overlay 状态
    private String mgmtStatusMessage = "";
    private int mgmtStatusColor = 0xFFFFFFFF;
    private int mgmtStatusTimer = 0;

    private static final int OVERLAY_VISIBLE_ROWS = 10;
    private static final int OVERLAY_ROW_HEIGHT = 20;
    private static final int OVERLAY_PANEL_WIDTH = 300;

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

    // ========== 列表布局 ==========

    @Override
    protected int getBrowserWidth() {
        return this.getScreenWidth() - 20;
    }

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

        // 顶部按钮栏：从右往左排列
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

    /** 处理负责人操作响应 */
    public void onOwnerActionResponse(boolean success, String message, String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        // 更新本地状态
        this.ownerName = newOwnerName != null ? newOwnerName : this.ownerName;
        this.deputyOwners = newDeputyOwners != null ? new ArrayList<>(newDeputyOwners) : this.deputyOwners;
        this.allowSelfClaim = newAllowSelfClaim;

        // 更新管理 overlay 状态消息
        mgmtStatusMessage = message;
        mgmtStatusColor = success ? 0xFF55FF55 : 0xFFFF5555;
        mgmtStatusTimer = 100;

        // 更新是否还是主负责人（转让后可能不再是）
        if (success && "TRANSFER".equals(overlayPlayerAction)) {
            // 转让后不再是主负责人（但仍可能是副负责人）
            // 实际上转让后 ownerName 变了，当前玩家不再是负责人
            // 需要重新判断，但简单起见关闭 overlay
            closeOverlay();
            InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        }
    }

    /** 处理批量分配响应 */
    public void onBatchAssignResponse(boolean success, String message) {
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        if (success) {
            this.materialList.requestCollaborationStatus();
        }
    }

    /** 处理踢出响应 */
    public void onKickResponse(boolean success, String message) {
        InfoUtils.showGuiOrActionBarMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
        if (success) {
            this.materialList.requestCollaborationStatus();
        }
    }

    /** 处理玩家列表响应 */
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
            // ESC 关闭 overlay
            if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ESCAPE) {
                closeOverlay();
                return true;
            }
            // Enter 确认（多选模式）
            if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ENTER && activeOverlay == OverlayType.PLAYER_SELECT && overlayConfirmTimer == 0) {
                handleOverlayConfirm();
                return true;
            }
            return true; // 消费所有键盘事件，不让底层列表处理
        }
        return super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (isOverlayActive()) {
            if (activeOverlay == OverlayType.PLAYER_SELECT && overlayConfirmTimer == 0) {
                // 搜索框输入
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

        // Overlay 渲染
        if (isOverlayActive()) {
            renderOverlay(drawContext, mouseX, mouseY);
        }
    }

    private void renderOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        // 全屏半透明遮罩
        drawContext.fill(0, 0, this.width, this.height, 0xC0000000);

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
        return overlayPlayers.stream()
                .filter(p -> p.name().toLowerCase().contains(lower))
                .toList();
    }

    private void renderPlayerSelectOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        int panelX = (this.width - OVERLAY_PANEL_WIDTH) / 2;
        int panelHeight = 44 + 26 + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 10 + 24 + 10;
        int panelY = (this.height - panelHeight) / 2;

        // 面板背景
        drawContext.fill(panelX, panelY, panelX + OVERLAY_PANEL_WIDTH, panelY + panelHeight, 0xFF1A1A2E);
        drawContext.fill(panelX, panelY, panelX + OVERLAY_PANEL_WIDTH, panelY + 2, 0xFF4A90D9); // 顶部蓝色线条

        // 标题
        String title = switch (overlayPlayerAction != null ? overlayPlayerAction : "") {
            case "ASSIGN" -> "选择分配玩家";
            case "KICK" -> "选择踢出玩家";
            default -> "选择玩家";
        };
        drawContext.drawCenteredTextWithShadow(this.textRenderer, title, this.width / 2, panelY + 10, 0xFFFFFFFF);

        // 提示
        String hint = switch (overlayPlayerAction != null ? overlayPlayerAction : "") {
            case "ASSIGN" -> "勾选要分配给的玩家";
            case "KICK" -> "勾选要踢出的玩家";
            default -> "选择玩家";
        };
        drawContext.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, panelY + 24, 0xFFAAAAAA);

        int listY = panelY + 40;

        // 搜索框
        int searchX = panelX + 10;
        int searchY = listY;
        int searchW = OVERLAY_PANEL_WIDTH - 20;
        drawContext.fill(searchX, searchY, searchX + searchW, searchY + 18, 0xFF222244);
        String searchText = overlaySearchText.isEmpty() ? "搜索玩家..." : overlaySearchText;
        int searchColor = overlaySearchText.isEmpty() ? 0xFF666688 : 0xFFFFFFFF;
        drawContext.drawTextWithShadow(this.textRenderer, searchText, searchX + 4, searchY + 5, searchColor);
        if (!overlaySearchText.isEmpty()) {
            // 闪烁光标
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int cursorX = searchX + 4 + this.textRenderer.getWidth(overlaySearchText);
                drawContext.fill(cursorX, searchY + 4, cursorX + 1, searchY + 14, 0xFFFFFFFF);
            }
        }
        listY += 22;

        // 列表背景
        int listX = panelX + 10;
        int listWidth = OVERLAY_PANEL_WIDTH - 20;
        int listHeight = OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT;
        drawContext.fill(listX, listY, listX + listWidth, listY + listHeight, 0xFF111122);

        // 过滤后的玩家列表
        List<PlayerInfo> filtered = getFilteredPlayers();
        int endIndex = Math.min(overlayScrollOffset + OVERLAY_VISIBLE_ROWS, filtered.size());
        for (int i = overlayScrollOffset; i < endIndex; i++) {
            int rowY = listY + (i - overlayScrollOffset) * OVERLAY_ROW_HEIGHT;
            PlayerInfo player = filtered.get(i);
            boolean selected = overlaySelectedPlayers.contains(player.name());
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + OVERLAY_ROW_HEIGHT;

            if (hovered) {
                drawContext.fill(listX, rowY, listX + listWidth, rowY + OVERLAY_ROW_HEIGHT, 0x30FFFFFF);
            }

            // 复选框
            int checkboxX = listX + 4;
            int checkboxY = rowY + 3;
            drawContext.fill(checkboxX, checkboxY, checkboxX + 14, checkboxY + 14, 0xFF333355);
            drawContext.fill(checkboxX + 1, checkboxY + 1, checkboxX + 13, checkboxY + 13, selected ? 0xFF00AA00 : 0xFF222244);
            if (selected) {
                drawContext.drawCenteredTextWithShadow(this.textRenderer, "✓", checkboxX + 7, checkboxY + 2, 0xFFFFFFFF);
            }

            // 玩家名
            int textColor = player.online() ? 0xFF55FF55 : 0xFFAAAAAA;
            drawContext.drawTextWithShadow(this.textRenderer, player.name(), listX + 24, rowY + 6, textColor);

            // 在线状态
            if (player.online()) {
                String statusText = "在线";
                int statusWidth = this.textRenderer.getWidth(statusText);
                drawContext.drawTextWithShadow(this.textRenderer, statusText, listX + listWidth - statusWidth - 8, rowY + 6, 0xFF55FF55);
            }
        }

        // 滚动条
        if (filtered.size() > OVERLAY_VISIBLE_ROWS) {
            int scrollbarX = listX + listWidth - 6;
            int scrollbarHeight = listHeight;
            int thumbHeight = Math.max(10, scrollbarHeight * OVERLAY_VISIBLE_ROWS / filtered.size());
            int thumbY = listY + (scrollbarHeight - thumbHeight) * overlayScrollOffset / Math.max(1, filtered.size() - OVERLAY_VISIBLE_ROWS);
            drawContext.fill(scrollbarX, listY, scrollbarX + 4, listY + scrollbarHeight, 0xFF333355);
            drawContext.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xFF8888AA);
        }

        // 空列表提示
        if (filtered.isEmpty()) {
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "没有匹配的玩家", this.width / 2, listY + listHeight / 2 - 6, 0xFF666688);
        }

        // 底部：已选择信息 + 按钮
        int bottomY = listY + listHeight + 8;
        String infoText = "已选择 " + overlaySelectedPlayers.size() + " 个玩家 | 材料 " + selectedMaterialIds.size() + " 个";
        drawContext.drawCenteredTextWithShadow(this.textRenderer, infoText, this.width / 2, bottomY, 0xFFCCCCCC);

        // 确认/取消按钮
        int btnY = bottomY + 14;
        if (overlayConfirmTimer > 0) {
            overlayConfirmTimer--;
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "已发送 ✓", this.width / 2, btnY + 4, 0xFF55FF55);
            if (overlayConfirmTimer == 0) {
                closeOverlay();
            }
        } else {
            int btnW = 60;
            int confirmX = this.width / 2 - btnW - 10;
            int cancelX = this.width / 2 + 10;

            // 确认按钮
            boolean confirmHovered = mouseX >= confirmX && mouseX < confirmX + btnW && mouseY >= btnY && mouseY < btnY + 20;
            int confirmBg = overlaySelectedPlayers.isEmpty() ? 0xFF333355 : (confirmHovered ? 0xFF4A90D9 : 0xFF336699);
            drawContext.fill(confirmX, btnY, confirmX + btnW, btnY + 20, confirmBg);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "确认", confirmX + btnW / 2, btnY + 6, overlaySelectedPlayers.isEmpty() ? 0xFF666688 : 0xFFFFFFFF);

            // 取消按钮
            boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + btnW && mouseY >= btnY && mouseY < btnY + 20;
            drawContext.fill(cancelX, btnY, cancelX + btnW, btnY + 20, cancelHovered ? 0xFF666688 : 0xFF444466);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "取消", cancelX + btnW / 2, btnY + 6, 0xFFFFFFFF);
        }
    }

    // ========== 管理 overlay ==========

    private void renderManagementOverlay(DrawContext drawContext, int mouseX, int mouseY) {
        int panelWidth = 300;
        int panelX = (this.width - panelWidth) / 2;

        // 动态计算高度
        int contentHeight = 20 + 16; // 标题 + 原理图名
        contentHeight += 70; // 说明区块
        contentHeight += 30; // 区块标题
        contentHeight += 24; // 主负责人行
        contentHeight += Math.max(1, deputyOwners.size()) * 22 + 4; // 副负责人列表
        if (isMainOwner) contentHeight += 26; // 添加副负责人按钮
        contentHeight += 10; // 间距
        contentHeight += 50; // 自行认领区块
        contentHeight += 30; // 底部
        int panelHeight = contentHeight;
        int panelY = (this.height - panelHeight) / 2;

        // 面板背景
        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF1A1A2E);
        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFF4A90D9);

        int y = panelY + 10;
        int centerX = this.width / 2;
        int leftX = panelX + 15;
        int innerWidth = panelWidth - 30;

        // 标题
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "负责人管理", centerX, y, 0xFFFFFFFF);
        y += 14;
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "原理图: " + this.materialList.getTitle(), centerX, y, 0xFFAAAAAA);
        y += 18;

        // 说明区块
        int infoBoxY = y;
        int infoBoxH = 66;
        drawContext.fill(leftX, y, leftX + innerWidth, y + infoBoxH, 0xFF222244);
        y += 4;
        drawTextWrapped(drawContext, "§7负责人可以管理材料的认领与分配。主负责人拥有全部管理权限，可转让负责人、添加/移除副负责人。副负责人可以批量分配材料和踢出玩家。开启「自行认领」后，所有玩家可以自行认领材料。", leftX + 6, y, innerWidth - 12, 0xFF888888);
        y = infoBoxY + infoBoxH + 8;

        // 当前负责人区块标题
        drawContext.fill(leftX, y, leftX + innerWidth, y + 20, 0xFF222244);
        drawContext.drawTextWithShadow(this.textRenderer, "当前负责人", leftX + 6, y + 5, 0xFFFFFFFF);
        y += 24;

        // 主负责人行
        drawContext.drawTextWithShadow(this.textRenderer, "主负责人: " + ownerName, leftX + 6, y + 2, 0xFF55FF55);
        if (isMainOwner) {
            int btnX = leftX + innerWidth - 50;
            boolean transferHovered = mouseX >= btnX && mouseX < btnX + 44 && mouseY >= y && mouseY < y + 18;
            drawContext.fill(btnX, y, btnX + 44, y + 18, transferHovered ? 0xFF4A90D9 : 0xFF336699);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "转让", btnX + 22, y + 5, 0xFFFFFFFF);
        }
        y += 22;

        // 副负责人列表
        for (int i = 0; i < deputyOwners.size(); i++) {
            String deputy = deputyOwners.get(i);
            drawContext.drawTextWithShadow(this.textRenderer, "副负责人: " + deputy, leftX + 6, y + 2, 0xFF55FF55);
            if (isMainOwner) {
                int delX = leftX + innerWidth - 22;
                boolean delHovered = mouseX >= delX && mouseX < delX + 18 && mouseY >= y && mouseY < y + 18;
                drawContext.fill(delX, y, delX + 18, y + 18, delHovered ? 0xFFCC4444 : 0xFF993333);
                drawContext.drawCenteredTextWithShadow(this.textRenderer, "×", delX + 9, y + 3, 0xFFFFFFFF);
            }
            y += 22;
        }
        if (deputyOwners.isEmpty()) {
            drawContext.drawTextWithShadow(this.textRenderer, "副负责人: 无", leftX + 6, y + 2, 0xFF666688);
            y += 22;
        }

        // 添加副负责人按钮（仅主负责人）
        if (isMainOwner) {
            y += 2;
            int addBtnX = leftX;
            int addBtnW = innerWidth;
            boolean addHovered = mouseX >= addBtnX && mouseX < addBtnX + addBtnW && mouseY >= y && mouseY < y + 20;
            drawContext.fill(addBtnX, y, addBtnX + addBtnW, y + 20, addHovered ? 0xFF2A5A2A : 0xFF224422);
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "添加副负责人", centerX, y + 6, 0xFF55FF55);
            y += 24;
        }

        y += 8;

        // 自行认领区块
        drawContext.fill(leftX, y, leftX + innerWidth, y + 40, 0xFF222244);
        drawContext.drawTextWithShadow(this.textRenderer, "自行认领: " + (allowSelfClaim ? "开启" : "关闭"), leftX + 6, y + 5, 0xFFFFFFFF);
        int toggleX = leftX + innerWidth - 60;
        boolean toggleHovered = mouseX >= toggleX && mouseX < toggleX + 54 && mouseY >= y + 2 && mouseY < y + 18;
        drawContext.fill(toggleX, y + 2, toggleX + 54, y + 18, allowSelfClaim ? (toggleHovered ? 0xFF2A7A2A : 0xFF225522) : (toggleHovered ? 0xFF7A2A2A : 0xFF552222));
        drawContext.drawCenteredTextWithShadow(this.textRenderer, allowSelfClaim ? "关闭" : "开启", toggleX + 27, y + 7, 0xFFFFFFFF);
        y += 48;

        // 状态消息
        if (mgmtStatusTimer > 0) {
            mgmtStatusTimer--;
            drawContext.drawCenteredTextWithShadow(this.textRenderer, mgmtStatusMessage, centerX, y, mgmtStatusColor);
        }
    }

    /** 自动换行绘制文本 */
    private void drawTextWrapped(DrawContext drawContext, String text, int x, int y, int maxWidth, int color) {
        // 简单按字符宽度换行
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 跳过 MC 格式码 §x
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            line.append(c);
            if (this.textRenderer.getWidth(line.toString()) > maxWidth) {
                // 回退一个字符
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
        int centerX = this.width / 2;

        switch (activeOverlay) {
            case PLAYER_SELECT -> handlePlayerSelectClick(mouseX, mouseY, centerX);
            case MANAGEMENT -> handleManagementClick(mouseX, mouseY);
            default -> {}
        }
    }

    private void handlePlayerSelectClick(int mouseX, int mouseY, int centerX) {
        int panelX = (this.width - OVERLAY_PANEL_WIDTH) / 2;
        int panelHeight = 44 + 26 + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 10 + 24 + 10;
        int panelY = (this.height - panelHeight) / 2;

        // 面板外部点击关闭
        if (mouseX < panelX || mouseX > panelX + OVERLAY_PANEL_WIDTH || mouseY < panelY || mouseY > panelY + panelHeight) {
            closeOverlay();
            return;
        }

        if (overlayConfirmTimer > 0) return;

        int listY = panelY + 62; // 标题40 + 搜索22
        int listX = panelX + 10;
        int listWidth = OVERLAY_PANEL_WIDTH - 20;

        // 点击玩家行
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

        // 确认/取消按钮
        int bottomY = listY + OVERLAY_VISIBLE_ROWS * OVERLAY_ROW_HEIGHT + 8 + 14;
        int btnW = 60;
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
        }

        overlayConfirmTimer = 60; // 1 秒后关闭
    }

    private void handleManagementClick(int mouseX, int mouseY) {
        int panelWidth = 300;
        int panelX = (this.width - panelWidth) / 2;
        int leftX = panelX + 15;
        int innerWidth = panelWidth - 30;

        // 面板外部点击关闭
        int panelHeight = 380; // 粗略估计
        int panelY = (this.height - panelHeight) / 2;
        if (mouseX < panelX || mouseX > panelX + panelWidth || mouseY < panelY || mouseY > panelY + panelHeight) {
            closeOverlay();
            return;
        }

        // 重新计算 y 坐标（与渲染一致）
        int y = panelY + 10 + 14 + 18; // 标题 + 原理图名
        y += 70; // 说明区块
        y += 24; // 区块标题

        // 主负责人行 - 转让按钮
        if (isMainOwner) {
            int btnX = leftX + innerWidth - 50;
            if (mouseX >= btnX && mouseX < btnX + 44 && mouseY >= y && mouseY < y + 18) {
                requestPlayerList("TRANSFER");
                return;
            }
        }
        y += 22;

        // 副负责人行 - 删除按钮
        for (int i = 0; i < deputyOwners.size(); i++) {
            if (isMainOwner) {
                int delX = leftX + innerWidth - 22;
                if (mouseX >= delX && mouseX < delX + 18 && mouseY >= y && mouseY < y + 18) {
                    String deputy = deputyOwners.get(i);
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "REMOVE_DEPUTY", deputy));
                    return;
                }
            }
            y += 22;
        }
        if (deputyOwners.isEmpty()) y += 22;

        // 添加副负责人按钮
        if (isMainOwner) {
            y += 2;
            if (mouseX >= leftX && mouseX < leftX + innerWidth && mouseY >= y && mouseY < y + 20) {
                requestPlayerList("ADD_DEPUTY");
                return;
            }
            y += 24;
        }

        y += 8;

        // 自行认领开关
        int toggleX = leftX + innerWidth - 60;
        if (mouseX >= toggleX && mouseX < toggleX + 54 && mouseY >= y + 2 && mouseY < y + 18) {
            ClientPlayNetworking.send(new OwnerActionC2SPacket(this.materialList.getSchematicId(), "TOGGLE_SELF_CLAIM", ""));
            allowSelfClaim = !allowSelfClaim;
            mgmtStatusMessage = "请求已发送...";
            mgmtStatusColor = 0xFFAAAAAA;
            mgmtStatusTimer = 60;
        }
    }

    // ========== 其他 ==========

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
