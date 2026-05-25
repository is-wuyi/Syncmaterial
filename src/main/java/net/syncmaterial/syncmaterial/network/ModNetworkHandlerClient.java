package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSimple;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;

public class ModNetworkHandlerClient {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MaterialStatsResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.openMaterialListScreen(payload.schematicId(), payload.schematicName(), payload.materials());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(CollaborationStatusS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.onCollaborationStatus(payload);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(StagingAreaConfigResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;

                if (screen instanceof GuiStagingAreaEditorNormal editor)
                {
                    editor.onServerResponse(payload);
                }
            });
        });
    }
}
