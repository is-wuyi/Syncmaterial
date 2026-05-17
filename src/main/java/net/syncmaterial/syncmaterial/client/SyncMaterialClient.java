package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListHudRenderer;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.network.MaterialStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");
        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && activeMaterialList.getHudRenderer().getShouldRender()) {
                activeMaterialList.getHudRenderer().render(drawContext, 10, 44,
                        fi.dy.masa.malilib.config.HudAlignment.TOP_LEFT);
            }
        });
    }

    public static void openMaterialListScreen(String schematicName, List<MaterialEntry> materials) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。", materials.size());
        GuiMaterialList gui = new GuiMaterialList(schematicName, materials);
        activeMaterialList = gui.getMaterialList();
        MinecraftClient.getInstance().setScreen(gui);
    }

    public static void onClaimResult(boolean success, String message, int materialId) {
        LOGGER.info("认领结果: {} (材料: {})", message, materialId);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(success ? "§a[认领] §r" + message : "§c[认领] §r" + message));
        }
    }

    public static void onMaterialStatus(List<MaterialStatusS2CPacket.MaterialStatusEntry> statuses) {
        LOGGER.info("收到材料状态更新，共 {} 项。", statuses.size());
        if (activeMaterialList != null) {
            activeMaterialList.updateMaterialStatus(statuses);
        }
    }

    public static SyncMaterialList getActiveMaterialList() {
        return activeMaterialList;
    }
}
