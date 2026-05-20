package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        PayloadTypeRegistry.playC2S().register(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);

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
            Map<Integer, Integer> inventoryCounts = payload.inventoryCounts();
            var player = context.player();
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.joinCollaboration(schematicId, materialId, playerName)) {
                    for (Map.Entry<Integer, Integer> entry : inventoryCounts.entrySet()) {
                        collaborationManager.updatePlayerInventory(playerName, schematicId, entry.getKey(), entry.getValue());
                    }
                    sendStatusToPlayer(player, schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            var player = context.player();
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.leaveCollaboration(schematicId, materialId, playerName)) {
                    sendStatusToPlayer(player, schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            int count = payload.count();
            var player = context.player();
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                SyncMaterial.LOGGER.info("收到玩家 {} 的库存更新: 材料 {}, 数量 {}", playerName, materialId, count);
                if (collaborationManager.isCollaborating(schematicId, materialId, playerName)) {
                    collaborationManager.updatePlayerInventory(playerName, schematicId, materialId, count);
                    sendStatusToPlayer(player, schematicId, materialId);
                } else {
                    SyncMaterial.LOGGER.warn("玩家 {} 未协作材料 {}，忽略库存更新", playerName, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();

            context.server().execute(() -> {
                SyncMaterial.LOGGER.info("收到玩家 {} 的原理图 {} 协作状态查询请求", player.getGameProfile().getName(), schematicId);
                List<Integer> materialIds = collaborationManager.getAllMaterialIds(schematicId);
                for (int materialId : materialIds) {
                    sendStatusToPlayer(player, schematicId, materialId);
                }
            });
        });
    }

    private static void sendStatusToPlayer(net.minecraft.server.network.ServerPlayerEntity player, String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status != null) {
            ServerPlayNetworking.send(player, status);
        }
    }
}
