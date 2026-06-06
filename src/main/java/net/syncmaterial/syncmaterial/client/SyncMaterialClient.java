package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");
        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
        InventoryWatcher.register();

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && activeMaterialList.getHudRenderer().getShouldRender()) {
                activeMaterialList.getHudRenderer().render(drawContext, 10, 44,
                        fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT);
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
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。isOwner={}, isMainOwner={}", materials.size(), isOwner, isMainOwner);
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        activeMaterialList = gui.getMaterialList();
        MinecraftClient.getInstance().setScreen(gui);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {})", status.materialId(), status.totalCount(), status.stagingCount());
        if (activeMaterialList != null) {
            activeMaterialList.onCollaborationStatus(status);
        }
    }
}
