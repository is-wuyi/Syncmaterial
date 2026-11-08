package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.syncmaterial.syncmaterial.SyncMaterial;

public final class SyncMaterialClientBootstrap implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("malilib")) {
            SyncMaterial.LOGGER.warn("SyncMaterial 客户端界面需要 MaLiLib；本次不加载界面、HUD 和选区功能");
            return;
        }
        new SyncMaterialClient().onInitializeClient();
    }
}
