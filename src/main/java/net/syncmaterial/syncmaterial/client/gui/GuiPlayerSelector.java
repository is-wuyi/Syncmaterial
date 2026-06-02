package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import net.minecraft.client.gui.DrawContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家选择界面 - MaLiLib GuiBase 风格弹窗
 */
public class GuiPlayerSelector extends GuiBase {
    private final List<PlayerInfo> players;
    private final String action;
    private final String schematicId;
    private final List<Integer> materialIds;
    private final List<String> selectedPlayers = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_WIDTH = 300;

    private final boolean singleSelect;
    private GuiTextFieldGeneric searchField;
    private String searchText = "";
    private int confirmTimer = 0;

    public GuiPlayerSelector(List<PlayerInfo> players, String action, String schematicId, List<Integer> materialIds, net.minecraft.client.gui.screen.Screen parent) {
        this.players = players;
        this.action = action;
        this.schematicId = schematicId;
        this.materialIds = materialIds;
        this.singleSelect = "TRANSFER".equals(action) || "ADD_DEPUTY".equals(action);
        this.title = getTitleForAction(action);
        this.useTitleHierarchy = false;
        this.setParent(parent);
    }

    private static String getTitleForAction(String action) {
        return switch (action) {
            case "ASSIGN" -> "选择分配玩家";
            case "KICK" -> "选择踢出玩家";
            case "TRANSFER" -> "转让负责人";
            case "ADD_DEPUTY" -> "选择副负责人";
            default -> "选择玩家";
        };
    }

    @Override
    public void initGui() {
        super.initGui();

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = getPanelY();
        int listY = panelY + 40 + 22; // 标题 + 提示 + 搜索框
        int bottomY = listY + VISIBLE_ROWS * ROW_HEIGHT + 8;

        // 搜索框
        int searchX = panelX + 10;
        searchField = new GuiTextFieldGeneric(searchX, panelY + 36, PANEL_WIDTH - 20, 16, this.textRenderer);
        searchField.setText(searchText);
        this.addTextField(searchField, new SearchFieldListener());

        // 确认按钮（多选模式）
        if (!singleSelect) {
            int btnY = bottomY + 14;
            int btnW = 60;
            ButtonGeneric confirmBtn = new ButtonGeneric(this.width / 2 - btnW - 10, btnY, btnW, false, "确认");
            this.addButton(confirmBtn, (btn, mouseButton) -> handleConfirm());
        }

        // 取消按钮
        int btnY = bottomY + 14;
        int btnW = 60;
        int cancelX = singleSelect ? this.width / 2 - btnW / 2 : this.width / 2 + 10;
        ButtonGeneric cancelBtn = new ButtonGeneric(cancelX, btnY, btnW, false, "取消");
        this.addButton(cancelBtn, (btn, mouseButton) -> closeGui(true));
    }

    private int getPanelY() {
        int h = 40 + 22 + VISIBLE_ROWS * ROW_HEIGHT + 8 + 14 + 24;
        return Math.max(10, (this.height - h) / 2);
    }

    private int getPanelHeight() {
        return 40 + 22 + VISIBLE_ROWS * ROW_HEIGHT + 8 + 14 + 24;
    }

    @Override
    protected void drawScreenBackground(DrawContext drawContext, int mouseX, int mouseY) {
        drawContext.fill(0, 0, this.width, this.height, 0xC0000000);
    }

    @Override
    protected void drawTitle(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        // 自定义绘制
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = getPanelY();
        int panelH = getPanelHeight();
        int centerX = this.width / 2;
        int listX = panelX + 10;
        int listWidth = PANEL_WIDTH - 20;
        int listY = panelY + 58;
        int listHeight = VISIBLE_ROWS * ROW_HEIGHT;

        // 面板背景
        drawContext.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 4, COLOR_HORIZONTAL_BAR);
        drawContext.fill(panelX, panelY + 4, panelX + PANEL_WIDTH, panelY + panelH, 0xE0101014);

        // 标题
        drawCenteredText(drawContext, this.title, centerX, panelY + 10, COLOR_WHITE);

        // 提示
        String hint = switch (action) {
            case "ASSIGN" -> "勾选要分配给的玩家";
            case "KICK" -> "勾选要踢出的玩家";
            case "TRANSFER" -> "点击选择新的主负责人";
            case "ADD_DEPUTY" -> "点击选择要添加的副负责人";
            default -> "选择玩家";
        };
        drawCenteredText(drawContext, hint, centerX, panelY + 24, 0xFFAAAAAA);

        // 列表背景
        drawContext.fill(listX, listY, listX + listWidth, listY + listHeight, 0xDD101014);

        // 过滤后的玩家列表
        List<PlayerInfo> filtered = getFilteredPlayers();
        int endIndex = Math.min(scrollOffset + VISIBLE_ROWS, filtered.size());
        for (int i = scrollOffset; i < endIndex; i++) {
            int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
            PlayerInfo player = filtered.get(i);
            boolean selected = selectedPlayers.contains(player.name());
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            // 行高亮
            if (hovered) {
                drawContext.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, 0x30FFFFFF);
            }
            if (selected) {
                drawContext.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, 0x204488FF);
            }

            // 复选框
            int checkboxX = listX + 4;
            int checkboxY = rowY + 3;
            drawContext.fill(checkboxX, checkboxY, checkboxX + 14, checkboxY + 14, COLOR_HORIZONTAL_BAR);
            drawContext.fill(checkboxX + 1, checkboxY + 1, checkboxX + 13, checkboxY + 13, selected ? 0xFF00AA00 : 0xDD202024);
            if (selected) {
                drawCenteredText(drawContext, "✓", checkboxX + 7, checkboxY + 2, COLOR_WHITE);
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
        if (filtered.size() > VISIBLE_ROWS) {
            int scrollbarX = listX + listWidth - 6;
            int thumbHeight = Math.max(10, listHeight * VISIBLE_ROWS / filtered.size());
            int thumbY = listY + (listHeight - thumbHeight) * scrollOffset / Math.max(1, filtered.size() - VISIBLE_ROWS);
            drawContext.fill(scrollbarX, listY, scrollbarX + 4, listY + listHeight, 0xFF333355);
            drawContext.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xFF8888AA);
        }

        // 空列表提示
        if (filtered.isEmpty()) {
            drawCenteredText(drawContext, "没有匹配的玩家", centerX, listY + listHeight / 2 - 6, 0xFF666688);
        }

        // 底部提示
        int bottomY = listY + listHeight + 8;
        String infoText = "已选择 " + selectedPlayers.size() + " 个玩家 | 材料 " + materialIds.size() + " 个";
        drawCenteredText(drawContext, infoText, centerX, bottomY, 0xFFCCCCCC);

        // 确认反馈
        if (confirmTimer > 0) {
            confirmTimer--;
            drawCenteredText(drawContext, "已发送 ✓", centerX, bottomY + 18, 0xFF55FF55);
            if (confirmTimer == 0) {
                closeGui(true);
            }
        }
    }

    private void drawCenteredText(DrawContext drawContext, String text, int centerX, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        drawContext.drawTextWithShadow(this.textRenderer, text, centerX - w / 2, y, color);
    }

    private List<PlayerInfo> getFilteredPlayers() {
        if (searchText.isEmpty()) return players;
        String lower = searchText.toLowerCase();
        return players.stream()
                .filter(p -> p.name().toLowerCase().contains(lower))
                .toList();
    }

    private void handleConfirm() {
        if (selectedPlayers.isEmpty() || confirmTimer > 0) return;

        switch (action) {
            case "ASSIGN" -> ClientPlayNetworking.send(new BatchAssignC2SPacket(schematicId, materialIds, new ArrayList<>(selectedPlayers)));
            case "KICK" -> {
                for (String player : selectedPlayers) {
                    ClientPlayNetworking.send(new KickFromMaterialC2SPacket(schematicId, materialIds, player));
                }
            }
            case "TRANSFER" -> {
                if (!selectedPlayers.isEmpty()) {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "TRANSFER", selectedPlayers.get(0)));
                }
            }
            case "ADD_DEPUTY" -> {
                if (!selectedPlayers.isEmpty()) {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "ADD_DEPUTY", selectedPlayers.get(0)));
                }
            }
        }

        if (singleSelect) {
            // 单选模式直接返回
            closeGui(true);
        } else {
            // 多选模式显示确认反馈
            confirmTimer = 60;
        }
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton) {
        // 先让搜索框处理点击
        if (searchField != null && searchField.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }

        // 再让按钮处理
        if (super.onMouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = getPanelY();
        int listX = panelX + 10;
        int listWidth = PANEL_WIDTH - 20;
        int listY = panelY + 58;

        // 点击玩家行
        if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= listY) {
            List<PlayerInfo> filtered = getFilteredPlayers();
            int row = (int) ((mouseY - listY) / ROW_HEIGHT) + scrollOffset;
            if (row >= 0 && row < filtered.size()) {
                String playerName = filtered.get(row).name();
                if (singleSelect) {
                    selectedPlayers.clear();
                    selectedPlayers.add(playerName);
                    handleConfirm();
                } else {
                    if (selectedPlayers.contains(playerName)) {
                        selectedPlayers.remove(playerName);
                    } else {
                        selectedPlayers.add(playerName);
                    }
                }
                return true;
            }
        }

        // 面板外部点击关闭
        int panelH = getPanelHeight();
        if (mouseX < panelX || mouseX > panelX + PANEL_WIDTH || mouseY < panelY || mouseY > panelY + panelH) {
            closeGui(true);
            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount) {
        List<PlayerInfo> filtered = getFilteredPlayers();
        if (filtered.size() > VISIBLE_ROWS) {
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) Math.signum(verticalAmount), filtered.size() - VISIBLE_ROWS));
        }
        return true;
    }

    @Override
    protected void closeGui(boolean showParent) {
        super.closeGui(true);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private class SearchFieldListener implements ITextFieldListener<GuiTextFieldGeneric> {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric field) {
            searchText = field.getText();
            scrollOffset = 0;
            return false;
        }
    }
}
