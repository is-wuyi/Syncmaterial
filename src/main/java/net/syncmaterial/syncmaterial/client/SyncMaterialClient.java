package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.HudEditModeHandler;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

import org.lwjgl.glfw.GLFW;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");

        // 加载配置
        Configs.loadFromFile();
        fi.dy.masa.malilib.config.ConfigManager.getInstance()
                .registerConfigHandler(SyncMaterial.MOD_ID, new Configs());

        // HUD_ENABLED 值变更时同步到 shouldRender（设置 GUI 或热键触发）
        Configs.Generic.HUD_ENABLED.setValueChangeCallback(config -> {
            if (activeMaterialList != null) {
                activeMaterialList.getHudRenderer().setShouldRender(config.getBooleanValue());
            }
        });

        // HUD_EDIT_MODE 热键切换编辑模式
        Configs.Generic.HUD_EDIT_MODE.setValueChangeCallback(config -> {
            HudEditModeHandler.getInstance().toggleEditMode();
        });

        // 注册热键到 MaLiLib 输入系统（必须用 IKeybindProvider，addHotkeysForCategory 只做展示）
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().registerKeybindProvider(
                new fi.dy.masa.malilib.hotkeys.IKeybindProvider() {
                    @Override
                    public void addKeysToMap(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.HUD_EDIT_MODE.getKeybind());
                    }
                    @Override
                    public void addHotkeys(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.HUD_EDIT_MODE.getKeybind());
                    }
                });

        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
        InventoryWatcher.register();

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList.getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList.getHudRenderer().render(drawContext, x, y, alignment);
            }
            if (activeMaterialList != null && HudEditModeHandler.getInstance().isEditMode()) {
                HudEditModeHandler.getInstance().renderEditOverlay(drawContext, activeMaterialList.getHudRenderer());
            }
        });

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StagingAreaSelector.getInstance().onTick();
        });

        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });

        // 断开连接时清除编辑器状态
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal.clearCurrentEditor();
        });

        // 准星选区模式下屏蔽方块交互
        net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
            return StagingAreaSelector.getInstance().isActive();
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
        });

        // HUD 编辑模式全局鼠标交互（通过 ClientTickEvents 轮询 GLFW 状态实现）
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HudEditModeHandler handler = HudEditModeHandler.getInstance();
            if (!handler.isEditMode()) return;
            if (client.getWindow() == null) return;
            long handle = client.getWindow().getHandle();

            // 鼠标位置（已缩放坐标）
            double[] mx = new double[1];
            double[] my = new double[1];
            GLFW.glfwGetCursorPos(handle, mx, my);
            int scale = client.options.getGuiScale().getValue();
            if (scale <= 0) scale = 1;
            int scaledMx = (int)(mx[0] / scale);
            int scaledMy = (int)(my[0] / scale);

            // 左键状态
            boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

            if (leftDown && !handler.wasLeftDown()) {
                handler.onMouseClicked(scaledMx, scaledMy, 0);
            } else if (!leftDown && handler.wasLeftDown()) {
                handler.onMouseReleased(scaledMx, scaledMy, 0);
            } else if (leftDown && handler.wasLeftDown()) {
                handler.onMouseDragged(scaledMx, scaledMy, 0, 0, 0);
            }
            handler.setLeftDown(leftDown);
        });
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。isOwner={}, isMainOwner={}", materials.size(), isOwner, isMainOwner);
        // 继承旧 HUD 状态，首次打开时使用 HUD_ENABLED 配置值
        boolean hudState;
        if (activeMaterialList != null) {
            hudState = activeMaterialList.getHudRenderer().getShouldRender();
        } else {
            hudState = Configs.Generic.HUD_ENABLED.getBooleanValue();
        }
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        activeMaterialList = gui.getMaterialList();
        activeMaterialList.getHudRenderer().setShouldRender(hudState);
        MinecraftClient.getInstance().setScreen(gui);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {})", status.materialId(), status.totalCount(), status.stagingCount());
        if (activeMaterialList != null) {
            activeMaterialList.onCollaborationStatus(status);
        }
    }
}
