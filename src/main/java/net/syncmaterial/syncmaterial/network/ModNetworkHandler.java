package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;

import java.util.ArrayList;
import java.util.List;

public class ModNetworkHandler {
    private static DatabaseQueryService queryService;
    private static CollaborationManager collaborationManager;

    public static void initializeServices(DatabaseQueryService queryService, CollaborationManager collaborationManager) {
        ModNetworkHandler.queryService = queryService;
        ModNetworkHandler.collaborationManager = collaborationManager;
        SyncMaterial.LOGGER.info("服务端网络服务初始化完成");
    }

    public static void register() {
        if (queryService == null) {
            SyncMaterial.LOGGER.error("DatabaseQueryService未初始化！");
            return;
        }

        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        
        PayloadTypeRegistry.playC2S().register(JoinCollaborationC2SPacket.ID, JoinCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(CollaborationStatusS2CPacket.ID, CollaborationStatusS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveCollaborationC2SPacket.ID, LeaveCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(InventoryUpdateC2SPacket.ID, InventoryUpdateC2SPacket.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MaterialStatsRequestC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();

            context.server().execute(() -> {
                try {
                    SyncMaterial.LOGGER.info(
                        "收到玩家 {} 的材料统计请求: {}", player.getGameProfile().getName(), schematicId);

                    var materials = queryService.getMaterials(schematicId);
                    SyncMaterial.LOGGER.info("数据库查询结果: schematic={}, 材料数量={}", schematicId, materials.size());

                    String schematicName = PlacementsUtil.getDisplayName(schematicId);
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, schematicName, materials));

                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理材料统计请求失败", e);
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, "", java.util.List.of()));
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            String playerName = context.player().getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.joinCollaboration(schematicId, materialId, playerName)) {
                    broadcastStatus(schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            String playerName = context.player().getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.leaveCollaboration(schematicId, materialId, playerName)) {
                    broadcastStatus(schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            int count = payload.count();
            String playerName = context.player().getGameProfile().getName();

            context.server().execute(() -> {
                collaborationManager.updatePlayerInventory(playerName, schematicId, materialId, count);
                broadcastStatus(schematicId, materialId);
            });
        });
    }

    private static void broadcastStatus(String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status != null) {
        }
    }
}
