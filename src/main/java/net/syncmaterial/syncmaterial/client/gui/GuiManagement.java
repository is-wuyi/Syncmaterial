package net.syncmaterial.syncmaterial.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理界面 - 负责人管理（Phase 4）
 */
public class GuiManagement extends Screen {
    private final String schematicId;
    private final String schematicName;
    private String ownerName;
    private List<String> deputyOwners;
    private boolean allowSelfClaim;
    private final boolean isMainOwner;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFFFF;
    private int statusTimer = 0;

    /** 记录玩家列表请求的操作类型 */
    private String pendingPlayerListAction = null;

    public GuiManagement(String schematicId, String schematicName, String ownerName, List<String> deputyOwners, boolean allowSelfClaim, boolean isMainOwner) {
        super(Text.literal("管理"));
        this.schematicId = schematicId;
        this.schematicName = schematicName;
        this.ownerName = ownerName != null ? ownerName : "";
        this.deputyOwners = deputyOwners != null ? new ArrayList<>(deputyOwners) : new ArrayList<>();
        this.allowSelfClaim = allowSelfClaim;
        this.isMainOwner = isMainOwner;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 70;

        // 仅主负责人可见的管理功能
        if (isMainOwner) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("转让负责人"), btn -> {
                pendingPlayerListAction = "TRANSFER";
                ClientPlayNetworking.send(new PlayerListRequestC2SPacket(schematicId));
            }).dimensions(centerX - 100, y, 95, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("添加副负责人"), btn -> {
                pendingPlayerListAction = "ADD_DEPUTY";
                ClientPlayNetworking.send(new PlayerListRequestC2SPacket(schematicId));
            }).dimensions(centerX + 5, y, 95, 20).build());
            y += 25;

            // 移除副负责人
            if (!deputyOwners.isEmpty()) {
                for (String deputy : deputyOwners) {
                    final String deputyName = deputy;
                    this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("移除 " + deputyName),
                        btn -> {
                            ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "REMOVE_DEPUTY", deputyName));
                            deputyOwners.remove(deputyName);
                            this.init();
                        }
                    ).dimensions(centerX - 60, y, 120, 18).build());
                    y += 20;
                }
            }
        }

        y += 10;

        // 自行认领开关（所有负责人可见）
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("自行认领: " + (allowSelfClaim ? "开启" : "关闭")),
            btn -> {
                ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "TOGGLE_SELF_CLAIM", ""));
                allowSelfClaim = !allowSelfClaim;
                this.init();
            }
        ).dimensions(centerX - 60, y, 120, 20).build());
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        super.render(drawContext, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        // 标题
        drawContext.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 10, 0xFFFFFFFF);

        // 原理图名
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "原理图: " + schematicName, centerX, 26, 0xFFAAAAAA);

        // 负责人信息
        drawContext.drawCenteredTextWithShadow(this.textRenderer, "主负责人: " + ownerName, centerX, 42, 0xFF55FF55);
        if (!deputyOwners.isEmpty()) {
            drawContext.drawCenteredTextWithShadow(this.textRenderer, "副负责人: " + String.join(", ", deputyOwners), centerX, 54, 0xFF55FF55);
        }

        // 状态消息
        if (statusTimer > 0) {
            drawContext.drawCenteredTextWithShadow(this.textRenderer, statusMessage, centerX, this.height - 50, statusColor);
            statusTimer--;
        }
    }

    /** 处理负责人操作响应 */
    public void onOwnerActionResponse(boolean success, String message) {
        statusMessage = message;
        statusColor = success ? 0xFF55FF55 : 0xFFFF5555;
        statusTimer = 100;
    }

    /** 处理玩家列表响应（用于转让负责人/添加副负责人） */
    public void onPlayerListResponse(List<PlayerListResponseS2CPacket.PlayerInfo> players) {
        if (pendingPlayerListAction == null) return;
        String action = pendingPlayerListAction;
        pendingPlayerListAction = null;

        GuiPlayerSelector selector = new GuiPlayerSelector(players, action, schematicId, List.of(), this);
        this.client.setScreen(selector);
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public void close() {
        this.client.setScreen(null);
    }
}
