package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;

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

    /**
     * 注册所有 PayloadType（必须在客户端和服务器初始化之前调用）。
     * 客户端注册 receiver 时需要这些类型已经存在。
     */
    public static void registerPayloadTypes() {
        // C2S (客户端到服务端)
        PayloadTypeRegistry.playC2S().register(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinCollaborationC2SPacket.ID, JoinCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveCollaborationC2SPacket.ID, LeaveCollaborationC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(InventoryUpdateC2SPacket.ID, InventoryUpdateC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(StagingAreaConfigC2SPacket.ID, StagingAreaConfigC2SPacket.CODEC);

        // S2C (服务端到客户端)
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(CollaborationStatusS2CPacket.ID, CollaborationStatusS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(StagingAreaConfigResponseS2CPacket.ID, StagingAreaConfigResponseS2CPacket.CODEC);
    }

    private static boolean validateSchematicId(String schematicId) {
        if (schematicId == null || schematicId.isBlank()) {
            SyncMaterial.LOGGER.warn("收到无效的 schematicId (null/blank)");
            return false;
        }
        if (schematicId.length() > 100) {
            SyncMaterial.LOGGER.warn("收到过长的 schematicId: {} 字符", schematicId.length());
            return false;
        }
        return true;
    }

    private static boolean validateMaterialId(int materialId) {
        if (materialId < 0) {
            SyncMaterial.LOGGER.warn("收到无效的 materialId: {}", materialId);
            return false;
        }
        return true;
    }

    private static boolean validateCount(int count) {
        if (count < 0) {
            SyncMaterial.LOGGER.warn("收到无效的 count: {}", count);
            return false;
        }
        return true;
    }

    private static boolean validatePlayer(net.minecraft.server.network.ServerPlayerEntity player) {
        if (player == null) {
            SyncMaterial.LOGGER.warn("收到来自 null player 的网络包");
            return false;
        }
        return true;
    }

    private static boolean validateStagingAction(String action) {
        return action != null && (action.equals("LIST") || action.equals("ADD") || action.equals("RENAME")
            || action.equals("DELETE") || action.equals("UPDATE") || action.equals("CLEAR"));
    }

    public static void register() {
        if (queryService == null) {
            SyncMaterial.LOGGER.error("DatabaseQueryService未初始化！");
            return;
        }

        ServerPlayNetworking.registerGlobalReceiver(MaterialStatsRequestC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();

            if (!validatePlayer(player) || !validateSchematicId(schematicId)) return;

            context.server().execute(() -> {
                try {
                    SyncMaterial.LOGGER.debug("收到玩家 {} 的材料统计请求: {}", player.getGameProfile().getName(), schematicId);

                    var materials = queryService.getMaterials(schematicId);
                    var statuses = new java.util.ArrayList<CollaborationStatusS2CPacket>();

                    for (var entry : materials) {
                        var status = collaborationManager.getCollaborationStatus(schematicId, entry.getDatabaseId());
                        if (status != null) {
                            statuses.add(status);
                            int collected = status.stagingCount();
                            for (var p : status.participants()) {
                                collected += p.count();
                            }
                            entry.setCountAvailable(collected);
                            entry.setCountMissing(Math.max(0, entry.getCountTotal() - collected));
                        }
                    }

                    String schematicName = PlacementsUtil.getDisplayName(schematicId);
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, schematicName, materials));

                    for (var status : statuses) {
                        ServerPlayNetworking.send(player, status);
                    }

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
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId)) return;
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.joinCollaboration(schematicId, materialId, playerName)) {
                    for (Map.Entry<Integer, Integer> entry : inventoryCounts.entrySet()) {
                        collaborationManager.updatePlayerInventory(playerName, schematicId, entry.getKey(), entry.getValue());
                    }
                    broadcastStatus(context.server(), schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId)) return;
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                if (collaborationManager.leaveCollaboration(schematicId, materialId, playerName)) {
                    broadcastStatus(context.server(), schematicId, materialId);
                    sendStatusToPlayer(player, schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            int count = payload.count();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId) || !validateCount(count)) return;
            String playerName = player.getGameProfile().getName();

            context.server().execute(() -> {
                SyncMaterial.LOGGER.debug("收到玩家 {} 的库存更新: 材料 {}, 数量 {}", playerName, materialId, count);
                if (collaborationManager.isCollaborating(schematicId, materialId, playerName)) {
                    collaborationManager.updatePlayerInventory(playerName, schematicId, materialId, count);
                    broadcastStatus(context.server(), schematicId, materialId);
                } else {
                    SyncMaterial.LOGGER.debug("玩家 {} 未协作材料 {}，忽略库存更新", playerName, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId)) return;

            context.server().execute(() -> {
                SyncMaterial.LOGGER.debug("收到玩家 {} 的原理图 {} 协作状态查询请求", player.getGameProfile().getName(), schematicId);
                List<Integer> materialIds = collaborationManager.getAllMaterialIds(schematicId);
                for (int materialId : materialIds) {
                    sendStatusToPlayer(player, schematicId, materialId);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StagingAreaConfigC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId()) || !validateStagingAction(payload.action())) return;
            context.server().execute(() -> {
                handleStagingAreaConfig(payload, player, context.server());
            });
        });
    }

    private static void handleStagingAreaConfig(StagingAreaConfigC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) {
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "备货区服务未初始化", List.of()));
            return;
        }

        String schematicId = payload.schematicId();
        String action = payload.action();

        try {
            switch (action) {
                case "LIST" -> {
                    manager.subscribe(player, schematicId);
                    sendStagingAreaResponse(player, manager, schematicId, true, "");
                }
                case "ADD" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    String world = data.world().orElse(player.getWorld().getRegistryKey().getValue().toString());
                    int areaId = manager.addStagingArea(schematicId, world, data.name(), data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    if (areaId > 0) {
                        manager.rescanStagingArea(areaId);
                    }
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已添加");
                    manager.broadcastUpdate(schematicId);
                }
                case "RENAME" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "缺少区域数据", List.of()));
                        return;
                    }
                    manager.renameStagingArea(payload.areaId(), schematicId, ad.get().name());
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已重命名");
                    manager.broadcastUpdate(schematicId);
                }
                case "DELETE" -> {
                    manager.removeStagingArea(payload.areaId(), schematicId);
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已删除");
                    manager.broadcastUpdate(schematicId);
                }
                case "UPDATE" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    SyncMaterial.LOGGER.debug("[StagingArea] UPDATE: areaId={} schematicId='{}' name='{}'",
                            payload.areaId(), schematicId, data.name());
                    manager.updateStagingArea(payload.areaId(), schematicId, data.name(), data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已更新");
                    manager.broadcastUpdate(schematicId);
                }
                case "CLEAR" -> {
                    var areas = manager.getStagingAreas(schematicId);
                    for (var area : areas) {
                        manager.removeStagingArea(area.id(), schematicId);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(true, "已清除所有备货区", List.of()));
                    manager.broadcastUpdate(schematicId);
                }
                default -> {
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "未知操作: " + action, List.of()));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理备货区配置失败", e);
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(false, "操作失败: " + e.getMessage(), List.of()));
        }
    }

    /**
     * 发送备货区列表响应
     */
    private static void sendStagingAreaResponse(net.minecraft.server.network.ServerPlayerEntity player, 
                                                 StagingAreaManager manager, String schematicId, 
                                                 boolean success, String message) {
        var areas = manager.getStagingAreas(schematicId);
        var areaInfos = areas.stream().map(a -> new StagingAreaConfigResponseS2CPacket.AreaInfo(
            a.id(), a.name(), a.x1(), a.y1(), a.z1(), a.x2(), a.y2(), a.z2(), a.world()
        )).toList();
        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(success, message, areaInfos));
    }

    private static void broadcastStatus(MinecraftServer server, String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status == null) return;

        List<String> participants = collaborationManager.getParticipants(schematicId, materialId);
        SyncMaterial.LOGGER.info("广播材料 {} 的状态给 {} 位参与者", materialId, participants.size());
        for (String name : participants) {
            var player = server.getPlayerManager().getPlayer(name);
            if (player != null) {
                ServerPlayNetworking.send(player, status);
            }
        }
    }

    private static void sendStatusToPlayer(net.minecraft.server.network.ServerPlayerEntity player, String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status != null) {
            ServerPlayNetworking.send(player, status);
        }
    }
}
