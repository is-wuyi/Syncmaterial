package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditor;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSimple;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;

public class ModNetworkHandlerClient {

    public static void register() {
        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        
        PayloadTypeRegistry.playC2S().register(JoinCollaborationC2SPacket.ID, JoinCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveCollaborationC2SPacket.ID, LeaveCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(InventoryUpdateC2SPacket.ID, InventoryUpdateC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(CollaborationStatusS2CPacket.ID, CollaborationStatusS2CPacket.CODEC);

        PayloadTypeRegistry.playC2S().register(StagingAreaConfigC2SPacket.ID, StagingAreaConfigC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(StagingAreaConfigResponseS2CPacket.ID, StagingAreaConfigResponseS2CPacket.CODEC);

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
                if (screen instanceof GuiStagingAreaEditor editor) {
                    editor.onServerResponse(payload);
                } else if (screen instanceof GuiStagingAreaEditorNormal editor) {
                    editor.onServerResponse(payload);
                } else if (screen instanceof GuiStagingAreaEditorSimple editor) {
                    editor.onServerResponse(payload);
                } else if (screen instanceof GuiStagingAreaEditorSubRegion editor) {
                    editor.onServerResponse(payload);
                }
            });
        });
    }
}
