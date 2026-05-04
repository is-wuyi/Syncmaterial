package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;

/**
 * 服务端网络处理器
 */
public class ModNetworkHandler {
    private static DatabaseQueryService queryService;

    /**
     * 初始化服务端服务组件
     */
    public static void initializeServices(DatabaseQueryService queryService) {
        ModNetworkHandler.queryService = queryService;
        SyncMaterial.LOGGER.info("服务端网络服务初始化完成");
    }

    /**
     * 在服务端初始化网络协议和监听器。
     */
    public static void register() {
        if (queryService == null) {
            SyncMaterial.LOGGER.error("DatabaseQueryService未初始化！");
            return;
        }

        // 1. 注册 Payload 类型 (Fabric API)
        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);

        // 2. 注册 C2S 包监听器 (服务端处理请求)
        ServerPlayNetworking.registerGlobalReceiver(MaterialStatsRequestC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();

            context.server().execute(() -> {
                try {
                    SyncMaterial.LOGGER.info(
                        "收到玩家 {} 的材料统计请求: {}", player.getGameProfile().getName(), schematicId);

                    // 从数据库查询统计结果
                    var materials = queryService.getMaterials(schematicId);

                    SyncMaterial.LOGGER.info("数据库查询结果: schematic={}, 材料数量={}", schematicId, materials.size());

                    // 获取原理图名称
                    String schematicName = PlacementsUtil.getDisplayName(schematicId);
                    
                    // 发送结果给客户端
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicName, materials));

                    SyncMaterial.LOGGER.debug("发送统计结果: {} 项材料", materials.size());

                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理材料统计请求失败", e);
                    // 发送空的统计结果表示出错
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, java.util.List.of()));
                }
            });
        });
    }
}
