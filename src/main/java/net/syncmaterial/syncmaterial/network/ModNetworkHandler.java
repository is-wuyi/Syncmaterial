package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;
import net.syncmaterial.syncmaterial.server.TeamManager;

import java.util.ArrayList;
import java.util.List;

public class ModNetworkHandler {
    private static DatabaseQueryService queryService;
    private static TeamManager teamManager;

    public static void initializeServices(DatabaseQueryService queryService, TeamManager teamManager) {
        ModNetworkHandler.queryService = queryService;
        ModNetworkHandler.teamManager = teamManager;
        SyncMaterial.LOGGER.info("服务端网络服务初始化完成");
    }

    public static void register() {
        if (queryService == null) {
            SyncMaterial.LOGGER.error("DatabaseQueryService未初始化！");
            return;
        }

        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ClaimMaterialC2SPacket.ID, ClaimMaterialC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(ClaimResultS2CPacket.ID, ClaimResultS2CPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(MaterialStatusS2CPacket.ID, MaterialStatusS2CPacket.CODEC);

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
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicName, materials));
                    SyncMaterial.LOGGER.debug("发送统计结果: {} 项材料", materials.size());

                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理材料统计请求失败", e);
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, java.util.List.of()));
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ClaimMaterialC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            int count = payload.count();
            var player = context.player();
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                try {
                    boolean success = teamManager.claimMaterial(schematicId, materialId, playerName, count);
                    String message = success ? "认领成功" : "认领失败：该材料已被认领";
                    ServerPlayNetworking.send(player, new ClaimResultS2CPacket(success, message, materialId));
                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理认领请求失败", e);
                    ServerPlayNetworking.send(player, new ClaimResultS2CPacket(false, "认领失败：系统错误", materialId));
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();

            context.server().execute(() -> {
                try {
                    var statusMap = teamManager.getMaterialStatus(schematicId);
                    List<MaterialStatusS2CPacket.MaterialStatusEntry> entries = new ArrayList<>();
                    for (var status : statusMap.values()) {
                        entries.add(new MaterialStatusS2CPacket.MaterialStatusEntry(
                            status.materialId,
                            status.itemId,
                            status.totalCount,
                            status.claimedCount,
                            status.claimer != null ? status.claimer : ""
                        ));
                    }
                    ServerPlayNetworking.send(player, new MaterialStatusS2CPacket(entries));
                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("查询材料状态失败", e);
                    ServerPlayNetworking.send(player, new MaterialStatusS2CPacket(List.of()));
                }
            });
        });
    }
}
