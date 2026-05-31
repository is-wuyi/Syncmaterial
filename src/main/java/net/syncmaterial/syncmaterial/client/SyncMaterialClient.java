package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;
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

        // 断开连接时清除编辑器状态
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal.clearCurrentEditor();
        });
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。", materials.size());
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials);
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
