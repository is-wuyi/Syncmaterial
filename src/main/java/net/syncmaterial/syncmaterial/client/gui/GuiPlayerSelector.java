package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家选择界面 - 用于分配/踢出/转让/添加副负责人 (Phase 4)
 * action: ASSIGN, KICK, TRANSFER, ADD_DEPUTY
 */
public class GuiPlayerSelector extends Screen {
    private final List<PlayerInfo> players;
    private final String action;
    private final String schematicId;
    private final List<Integer> materialIds;
    private final Screen parent;
    private final List<String> selectedPlayers = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 20;

    /** 单选模式：转让/添加副负责人 */
    private final boolean singleSelect;

    public GuiPlayerSelector(List<PlayerInfo> players, String action, String schematicId, List<Integer> materialIds, Screen parent) {
        super(Text.literal(getTitleForAction(action)));
        this.players = players;
        this.action = action;
        this.schematicId = schematicId;
        this.materialIds = materialIds;
        this.parent = parent;
        this.singleSelect = "TRANSFER".equals(action) || "ADD_DEPUTY".equals(action);
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
    protected void init() {
        int centerX = this.width / 2;
        int bottomY = this.height - 30;

        // 确认按钮（单选模式自动确认，不需要）
        if (!singleSelect) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("确认"), btn -> {
                onConfirm();
            }).dimensions(centerX - 80, bottomY, 70, 20).build());
        }

        // 取消按钮
        this.addDrawableChild(ButtonWidget.builder(Text.literal("取消"), btn -> {
            this.client.setScreen(parent);
        }).dimensions(centerX + (singleSelect ? -35 : 10), bottomY, 70, 20).build());
    }

    private void onConfirm() {
        if (selectedPlayers.isEmpty()) {
            return;
        }

        switch (action) {
            case "ASSIGN" -> ClientPlayNetworking.send(new BatchAssignC2SPacket(schematicId, materialIds, new ArrayList<>(selectedPlayers)));
            case "KICK" -> {
                for (String player : selectedPlayers) {
                    ClientPlayNetworking.send(new KickFromMaterialC2SPacket(schematicId, materialIds, player));
                }
            }
            case "TRANSFER" -> {
                String target = selectedPlayers.get(0);
                ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "TRANSFER", target));
            }
            case "ADD_DEPUTY" -> {
                String target = selectedPlayers.get(0);
                ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "ADD_DEPUTY", target));
            }
        }

        this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        // super.render 渲染背景 + 所有 widgets（按钮等）
        super.render(drawContext, mouseX, mouseY, delta);

        // 标题
        drawContext.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFF);

        // 操作提示
        String hint = switch (action) {
            case "ASSIGN" -> "勾选要分配给的玩家";
            case "KICK" -> "勾选要踢出的玩家";
            case "TRANSFER" -> "点击选择新的主负责人";
            case "ADD_DEPUTY" -> "点击选择要添加的副负责人";
            default -> "选择玩家";
        };
        drawContext.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, 25, 0xAAAAAA);

        // 玩家列表
        int listX = this.width / 2 - 150;
        int listY = 40;
        int listWidth = 300;
        int listHeight = VISIBLE_ROWS * ROW_HEIGHT;

        // 列表背景
        drawContext.fill(listX, listY, listX + listWidth, listY + listHeight, 0xC0101010);

        int endIndex = Math.min(scrollOffset + VISIBLE_ROWS, players.size());
        for (int i = scrollOffset; i < endIndex; i++) {
            int rowY = listY + (i - scrollOffset) * ROW_HEIGHT;
            PlayerInfo player = players.get(i);

            boolean selected = selectedPlayers.contains(player.name());
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            // 行背景
            if (hovered) {
                drawContext.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, 0x40FFFFFF);
            }

            // 复选框
            int checkboxX = listX + 4;
            int checkboxY = rowY + 3;
            drawContext.fill(checkboxX, checkboxY, checkboxX + 14, checkboxY + 14, 0xFF333333);
            drawContext.fill(checkboxX + 1, checkboxY + 1, checkboxX + 13, checkboxY + 13, selected ? 0xFF00AA00 : 0xFF222222);
            if (selected) {
                drawContext.drawCenteredTextWithShadow(this.textRenderer, "✓", checkboxX + 7, checkboxY + 2, 0xFFFFFF);
            }

            // 玩家名
            int textColor = player.online() ? 0x55FF55 : 0xAAAAAA;
            drawContext.drawTextWithShadow(this.textRenderer, player.name(), listX + 24, rowY + 6, textColor);

            // 在线状态
            if (player.online()) {
                String statusText = "在线";
                int statusWidth = this.textRenderer.getWidth(statusText);
                drawContext.drawTextWithShadow(this.textRenderer, statusText, listX + listWidth - statusWidth - 8, rowY + 6, 0x55FF55);
            }
        }

        // 滚动条
        if (players.size() > VISIBLE_ROWS) {
            int scrollbarX = listX + listWidth - 6;
            int scrollbarHeight = listHeight;
            int thumbHeight = Math.max(10, scrollbarHeight * VISIBLE_ROWS / players.size());
            int thumbY = listY + (scrollbarHeight - thumbHeight) * scrollOffset / (players.size() - VISIBLE_ROWS);
            drawContext.fill(scrollbarX, listY, scrollbarX + 4, listY + scrollbarHeight, 0xFF333333);
            drawContext.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xFF888888);
        }

        // 底部提示
        drawContext.drawTextWithShadow(this.textRenderer,
            "已选择 " + selectedPlayers.size() + " 个玩家 | 材料 " + materialIds.size() + " 个",
            this.width / 2 - 80, this.height - 45, 0xCCCCCC);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = this.width / 2 - 150;
        int listY = 40;
        int listWidth = 300;

        if (mouseX >= listX && mouseX < listX + listWidth) {
            int row = (int) ((mouseY - listY) / ROW_HEIGHT) + scrollOffset;
            if (row >= 0 && row < players.size()) {
                String playerName = players.get(row).name();
                if (singleSelect) {
                    selectedPlayers.clear();
                    selectedPlayers.add(playerName);
                    onConfirm();
                    return true;
                } else {
                    if (selectedPlayers.contains(playerName)) {
                        selectedPlayers.remove(playerName);
                    } else {
                        selectedPlayers.add(playerName);
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (players.size() > VISIBLE_ROWS) {
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) Math.signum(verticalAmount), players.size() - VISIBLE_ROWS));
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
