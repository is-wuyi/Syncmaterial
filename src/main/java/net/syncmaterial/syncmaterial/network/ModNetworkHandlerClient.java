package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;

public class ModNetworkHandlerClient {

    public static void register() {
        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ClaimMaterialC2SPacket.ID, ClaimMaterialC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(ClaimResultS2CPacket.ID, ClaimResultS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatusS2CPacket.ID, MaterialStatusS2CPacket.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(MaterialStatsResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.openMaterialListScreen(payload.schematicName(), payload.materials());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ClaimResultS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.onClaimResult(payload.success(), payload.message(), payload.materialId());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(MaterialStatusS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.onMaterialStatus(payload.statuses());
            });
        });
    }
}
