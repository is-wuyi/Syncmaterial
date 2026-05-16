package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");
        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
    }

    public static void openMaterialListScreen(String schematicName, List<MaterialEntry> materials) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。", materials.size());
        MinecraftClient.getInstance().setScreen(new GuiMaterialList(schematicName, materials));
    }
}
