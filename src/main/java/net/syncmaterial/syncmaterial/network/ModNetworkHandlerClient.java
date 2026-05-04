package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;

/**
 * 网络注册与监听器 (客户端)。
 */
public class ModNetworkHandlerClient {

    /**
     * 在客户端初始化网络协议和监听器。
     */
    public static void register() {
        // 1. 注册 Payload 类型 (Fabric API)
        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);

        // 2. 注册 S2C 包监听器 (客户端处理响应)
        ClientPlayNetworking.registerGlobalReceiver(MaterialStatsResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                // 解析完成后，打开 UI 展示材料清单
                SyncMaterialClient.openMaterialListScreen(payload.schematicName(), payload.materials());
            });
        });
    }
}
