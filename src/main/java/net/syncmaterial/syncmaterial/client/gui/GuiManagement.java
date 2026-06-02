package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责人管理界面 - MaLiLib GuiBase 风格弹窗
 */
public class GuiManagement extends GuiBase {
    private final String schematicId;
    private final String schematicName;
    private String ownerName;
    private List<String> deputyOwners;
    private boolean allowSelfClaim;
    private final boolean isMainOwner;

    private String statusMessage = "";
    private int statusColor = COLOR_WHITE;
    private int statusTimer = 0;

    private static final int PANEL_WIDTH = 260;

    /** 记录玩家列表请求的操作类型 */
    private String pendingPlayerListAction = null;

    public GuiManagement(String schematicId, String schematicName, String ownerName, List<String> deputyOwners, boolean allowSelfClaim, boolean isMainOwner) {
        this.schematicId = schematicId;
        this.schematicName = schematicName;
        this.ownerName = ownerName != null ? ownerName : "";
        this.deputyOwners = deputyOwners != null ? new ArrayList<>(deputyOwners) : new ArrayList<>();
        this.allowSelfClaim = allowSelfClaim;
        this.isMainOwner = isMainOwner;
        this.title = Text.literal("负责人管理").getString();
        this.useTitleHierarchy = false;
    }

    @Override
    public void initGui() {
        super.initGui();

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = getPanelY();
        int leftX = panelX + 12;
        int innerW = PANEL_WIDTH - 24;
        int y = panelY + 50 + 16; // 跳过标题区域 + 说明区块

        // 区块标题："当前负责人"
        y += 22;

        // 主负责人行 + [转让] 按钮
        if (isMainOwner) {
            int btnX = leftX + innerW - 50;
            ButtonGeneric transferBtn = new ButtonGeneric(btnX, y, 44, false, "转让");
            this.addButton(transferBtn, (btn, mouseButton) -> {
                pendingPlayerListAction = "TRANSFER";
                ClientPlayNetworking.send(new PlayerListRequestC2SPacket(schematicId));
            });
        }
        y += 22;

        // 副负责人行 + [×] 删除按钮
        for (int i = 0; i < deputyOwners.size(); i++) {
            if (isMainOwner) {
                final String deputy = deputyOwners.get(i);
                int delX = leftX + innerW - 22;
                ButtonGeneric delBtn = new ButtonGeneric(delX, y, 18, false, "×");
                this.addButton(delBtn, (btn, mouseButton) -> {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "REMOVE_DEPUTY", deputy));
                });
            }
            y += 22;
        }
        if (deputyOwners.isEmpty()) {
            y += 22;
        }

        // [添加副负责人] 按钮
        if (isMainOwner) {
            y += 2;
            ButtonGeneric addBtn = new ButtonGeneric(leftX, y, innerW, false, "添加副负责人");
            this.addButton(addBtn, (btn, mouseButton) -> {
                pendingPlayerListAction = "ADD_DEPUTY";
                ClientPlayNetworking.send(new PlayerListRequestC2SPacket(schematicId));
            });
            y += 22;
        }

        y += 10;

        // 自行认领开关
        String toggleLabel = "自行认领: " + (allowSelfClaim ? "开启" : "关闭");
        ButtonGeneric toggleBtn = new ButtonGeneric(leftX, y, innerW, false, toggleLabel);
        this.addButton(toggleBtn, (btn, mouseButton) -> {
            ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "TOGGLE_SELF_CLAIM", ""));
            allowSelfClaim = !allowSelfClaim;
            btn.setDisplayString("自行认领: " + (allowSelfClaim ? "开启" : "关闭"));
            statusMessage = "请求已发送...";
            statusColor = 0xFFAAAAAA;
            statusTimer = 60;
        });
    }

    private int getPanelY() {
        // 面板高度估算
        int h = 50 + 16; // 标题 + 说明
        h += 22; // 区块标题
        h += 22; // 主负责人
        h += Math.max(1, deputyOwners.size()) * 22; // 副负责人
        if (isMainOwner) h += 26; // 添加按钮
        h += 10 + 22; // 自行认领
        h += 20; // 底部状态
        return Math.max(10, (this.height - h) / 2);
    }

    @Override
    protected void drawScreenBackground(DrawContext drawContext, int mouseX, int mouseY) {
        // 全屏半透明遮罩
        drawContext.fill(0, 0, this.width, this.height, 0xC0000000);
    }

    @Override
    protected void drawTitle(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        // 不使用默认标题绘制（在 drawContents 中自定义绘制）
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = getPanelY();
        int centerX = this.width / 2;
        int leftX = panelX + 12;
        int innerW = PANEL_WIDTH - 24;

        // 面板背景
        drawContext.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 4, COLOR_HORIZONTAL_BAR);
        drawContext.fill(panelX, panelY + 4, panelX + PANEL_WIDTH, panelY + getPanelHeight(), 0xE0101014);

        int y = panelY + 10;

        // 标题
        this.drawStringCentered(drawContext, "负责人管理", centerX, y, COLOR_WHITE);
        y += 14;
        this.drawStringCentered(drawContext, "原理图: " + schematicName, centerX, y, 0xFFAAAAAA);
        y += 16;

        // 说明区块
        int infoBoxY = y;
        drawContext.fill(leftX, y, leftX + innerW, y + 58, 0xDD202024);
        y += 4;
        drawWrappedText(drawContext, "负责人可以管理材料的认领与分配。", leftX + 6, y, innerW - 12, 0xFFAAAAAA);
        y += 12;
        drawWrappedText(drawContext, "主负责人拥有全部管理权限。副负责人可以批量分配材料和踢出玩家。", leftX + 6, y, innerW - 12, 0xFFAAAAAA);
        y += 12;
        drawWrappedText(drawContext, "开启「自行认领」后，所有玩家可以自行认领材料。", leftX + 6, y, innerW - 12, 0xFFAAAAAA);
        y = infoBoxY + 58 + 6;

        // 区块标题
        drawContext.fill(leftX, y, leftX + innerW, y + 18, 0xDD202024);
        this.drawString(drawContext, "当前负责人", leftX + 6, y + 4, COLOR_WHITE);
        y += 22;

        // 主负责人行
        this.drawString(drawContext, "主负责人: " + ownerName, leftX + 6, y + 5, 0xFF55FF55);
        y += 22;

        // 副负责人行
        for (int i = 0; i < deputyOwners.size(); i++) {
            this.drawString(drawContext, "副负责人: " + deputyOwners.get(i), leftX + 6, y + 5, 0xFF55FF55);
            y += 22;
        }
        if (deputyOwners.isEmpty()) {
            this.drawString(drawContext, "副负责人: 无", leftX + 6, y + 5, 0xFF666688);
            y += 22;
        }

        if (isMainOwner) {
            y += 2 + 22; // 添加按钮占位
        }

        y += 10 + 22; // 自行认领按钮占位

        // 状态消息
        if (statusTimer > 0) {
            statusTimer--;
            this.drawStringCentered(drawContext, statusMessage, centerX, y, statusColor);
        }
    }

    private int getPanelHeight() {
        int h = 10 + 14 + 16; // 标题
        h += 58 + 6; // 说明
        h += 18 + 22; // 区块标题 + 主负责人
        h += Math.max(1, deputyOwners.size()) * 22; // 副负责人
        if (isMainOwner) h += 24 + 22; // 添加按钮
        h += 10 + 22; // 自行认领
        h += 20; // 底部
        return h;
    }

    private void drawStringCentered(DrawContext drawContext, String text, int centerX, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        drawContext.drawTextWithShadow(this.textRenderer, text, centerX - w / 2, y, color);
    }

    private void drawWrappedText(DrawContext drawContext, String text, int x, int y, int maxWidth, int color) {
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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

    /** 处理负责人操作响应 */
    public void onOwnerActionResponse(boolean success, String message, String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim) {
        this.ownerName = newOwnerName != null ? newOwnerName : this.ownerName;
        this.deputyOwners = newDeputyOwners != null ? new ArrayList<>(newDeputyOwners) : this.deputyOwners;
        this.allowSelfClaim = newAllowSelfClaim;

        statusMessage = message;
        statusColor = success ? 0xFF55FF55 : 0xFFFF5555;
        statusTimer = 100;

        // 重新创建按钮（副负责人列表可能变了）
        this.initGui();
    }

    /** 处理玩家列表响应 */
    public void onPlayerListResponse(List<net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo> players) {
        if (pendingPlayerListAction == null) return;
        String action = pendingPlayerListAction;
        pendingPlayerListAction = null;

        GuiPlayerSelector selector = new GuiPlayerSelector(players, action, schematicId, List.of(), this);
        this.mc.setScreen(selector);
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    protected void closeGui(boolean showParent) {
        super.closeGui(true);
    }
}
