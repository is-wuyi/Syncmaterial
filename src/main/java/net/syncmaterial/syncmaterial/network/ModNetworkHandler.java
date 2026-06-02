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
        PayloadTypeRegistry.playC2S().register(RescanStagingAreaC2SPacket.ID, RescanStagingAreaC2SPacket.CODEC);
        // Phase 4
        PayloadTypeRegistry.playC2S().register(OwnerActionC2SPacket.ID, OwnerActionC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(BatchAssignC2SPacket.ID, BatchAssignC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(KickFromMaterialC2SPacket.ID, KickFromMaterialC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerListRequestC2SPacket.ID, PlayerListRequestC2SPacket.CODEC);

        // S2C (服务端到客户端)
        PayloadTypeRegistry.playS2C().register(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(CollaborationStatusS2CPacket.ID, CollaborationStatusS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(StagingAreaConfigResponseS2CPacket.ID, StagingAreaConfigResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(RescanStagingAreaResponseS2CPacket.ID, RescanStagingAreaResponseS2CPacket.CODEC);
        // Phase 4
        PayloadTypeRegistry.playS2C().register(OwnerActionResponseS2CPacket.ID, OwnerActionResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(BatchAssignResponseS2CPacket.ID, BatchAssignResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(KickFromMaterialResponseS2CPacket.ID, KickFromMaterialResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerListResponseS2CPacket.ID, PlayerListResponseS2CPacket.CODEC);
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

                    // Phase 4: 查询负责人状态
                    var db = SyncMaterial.getSharedDatabase();
                    String playerName = player.getGameProfile().getName();
                    boolean isOwner = db != null && db.isOwner(schematicId, playerName);
                    boolean isMainOwner = db != null && db.isMainOwner(schematicId, playerName);
                    String ownerName = "";
                    var deputyOwners = new java.util.ArrayList<String>();
                    boolean allowSelfClaim = true;
                    if (db != null) {
                        try {
                            ownerName = db.getUploadedBy(schematicId);
                            deputyOwners.addAll(db.getDeputyOwners(schematicId));
                            allowSelfClaim = db.getAllowSelfClaim(schematicId);
                        } catch (Exception e) {
                            SyncMaterial.LOGGER.warn("获取负责人信息失败", e);
                        }
                    }

                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim));

                    for (var status : statuses) {
                        ServerPlayNetworking.send(player, status);
                    }

                } catch (Exception e) {
                    SyncMaterial.LOGGER.error("处理材料统计请求失败", e);
                    ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(schematicId, "", java.util.List.of(), false, false, "", java.util.List.of(), true));
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

        ServerPlayNetworking.registerGlobalReceiver(RescanStagingAreaC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player)) return;
            context.server().execute(() -> {
                handleRescanStagingArea(payload, context);
            });
        });

        // Phase 4: 负责人操作
        ServerPlayNetworking.registerGlobalReceiver(OwnerActionC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleOwnerAction(payload, player, context.server());
            });
        });

        // Phase 4: 批量分配
        ServerPlayNetworking.registerGlobalReceiver(BatchAssignC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleBatchAssign(payload, player, context.server());
            });
        });

        // Phase 4: 按材料踢出
        ServerPlayNetworking.registerGlobalReceiver(KickFromMaterialC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleKickFromMaterial(payload, player, context.server());
            });
        });

        // Phase 4: 玩家列表请求
        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handlePlayerListRequest(payload, player, context.server());
            });
        });
    }

    private static void handleStagingAreaConfig(StagingAreaConfigC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) {
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "备货区服务未初始化", List.of()));
            return;
        }
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "缺少区域数据", List.of()));
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "缺少区域数据", List.of()));
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    SyncMaterial.LOGGER.debug("[StagingArea] UPDATE: areaId={} schematicId='{}' name='{}'",
                            payload.areaId(), schematicId, data.name());
                    manager.updateStagingArea(payload.areaId(), schematicId, data.name(), data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    manager.rescanStagingArea(payload.areaId());
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已更新");
                    manager.broadcastUpdate(schematicId);
                }
                case "CLEAR" -> {
                    var areas = manager.getStagingAreas(schematicId);
                    for (var area : areas) {
                        manager.removeStagingArea(area.id(), schematicId);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, true, "已清除所有备货区", List.of()));
                    manager.broadcastUpdate(schematicId);
                }
                default -> {
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "未知操作: " + action, List.of()));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理备货区配置失败", e);
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, false, "操作失败: " + e.getMessage(), List.of()));
        }
    }

    private static void handleRescanStagingArea(RescanStagingAreaC2SPacket payload, ServerPlayNetworking.Context context) {
        try {
            var player = context.player();
            if (!validatePlayer(player)) return;

            String schematicId = payload.schematicId();
            if (!validateSchematicId(schematicId)) {
                ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(false, "无效的原理图ID"));
                return;
            }

            var manager = SyncMaterial.getServerStagingAreaManager();
            if (manager == null) {
                ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(false, "备货区管理器未初始化"));
                return;
            }

            var areas = manager.getStagingAreas(schematicId);
            if (areas.isEmpty()) {
                ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(false, "没有找到备货区"));
                return;
            }

            int rescannedCount = 0;
            for (var area : areas) {
                manager.rescanStagingArea(area.id());
                rescannedCount++;
            }

            SyncMaterial.LOGGER.info("[StagingArea] 手动重新扫描完成: schematicId={}, 区域数={}", schematicId, rescannedCount);
            ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(true, "成功重新扫描 " + rescannedCount + " 个备货区"));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理重新扫描请求失败", e);
            ServerPlayNetworking.send(context.player(), new RescanStagingAreaResponseS2CPacket(false, "重新扫描失败: " + e.getMessage()));
        }
    }

    // ========== Phase 4: 负责人管理 ==========

    private static void validateOwnerAction(String action) {
        if (!action.equals("TRANSFER") && !action.equals("ADD_DEPUTY") &&
            !action.equals("REMOVE_DEPUTY") && !action.equals("TOGGLE_SELF_CLAIM")) {
            throw new IllegalArgumentException("未知操作: " + action);
        }
    }

    private static void handleOwnerAction(OwnerActionC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        String action = payload.action();
        String targetPlayer = payload.targetPlayerName();
        String playerName = player.getGameProfile().getName();

        try {
            validateOwnerAction(action);

            var db = SyncMaterial.getSharedDatabase();

            switch (action) {
                case "TRANSFER" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能转让", "", List.of(), true));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), true));
                        return;
                    }
                    db.transferOwnership(schematicId, targetPlayer);
                    sendOwnerActionSuccess(player, db, schematicId, "已转让负责人给 " + targetPlayer);
                }
                case "ADD_DEPUTY" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能添加副负责人", "", List.of(), true));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), true));
                        return;
                    }
                    db.addDeputyOwner(schematicId, targetPlayer);
                    sendOwnerActionSuccess(player, db, schematicId, "已添加副负责人 " + targetPlayer);
                }
                case "REMOVE_DEPUTY" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能移除副负责人", "", List.of(), true));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), true));
                        return;
                    }
                    db.removeDeputyOwner(schematicId, targetPlayer);
                    sendOwnerActionSuccess(player, db, schematicId, "已移除副负责人 " + targetPlayer);
                }
                case "TOGGLE_SELF_CLAIM" -> {
                    if (!db.isOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "没有权限", "", List.of(), true));
                        return;
                    }
                    boolean current = db.getAllowSelfClaim(schematicId);
                    db.setAllowSelfClaim(schematicId, !current);
                    sendOwnerActionSuccess(player, db, schematicId, "自行认领已" + (!current ? "开启" : "关闭"));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理负责人操作失败", e);
            ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "操作失败: " + e.getMessage(), "", List.of(), true));
        }
    }

    /** 发送带最新状态的成功响应 */
    private static void sendOwnerActionSuccess(net.minecraft.server.network.ServerPlayerEntity player, net.syncmaterial.syncmaterial.server.SchematicDatabase db, String schematicId, String message) throws java.sql.SQLException {
        String ownerName = db.getUploadedBy(schematicId);
        var deputyOwners = db.getDeputyOwners(schematicId);
        boolean allowSelfClaim = db.getAllowSelfClaim(schematicId);
        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(true, message, ownerName, deputyOwners, allowSelfClaim));
    }

    private static void handleBatchAssign(BatchAssignC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        var materialIds = payload.materialIds();
        var targetPlayers = payload.targetPlayers();
        String playerName = player.getGameProfile().getName();

        try {
            var db = SyncMaterial.getSharedDatabase();
            if (!db.isOwner(schematicId, playerName)) {
                ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "没有权限，只有负责人才能分配"));
                return;
            }

            if (materialIds == null || materialIds.isEmpty()) {
                ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "请选择至少一个材料"));
                return;
            }
            if (targetPlayers == null || targetPlayers.isEmpty()) {
                ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "请选择至少一个玩家"));
                return;
            }

            int assignedCount = 0;
            for (int materialId : materialIds) {
                for (String target : targetPlayers) {
                    if (collaborationManager.joinCollaboration(schematicId, materialId, target)) {
                        assignedCount++;
                    }
                }
            }

            // 广播涉及的材料状态
            for (int materialId : materialIds) {
                broadcastStatus(server, schematicId, materialId);
            }

            SyncMaterial.LOGGER.info("批量分配: schematicId={}, 材料数={}, 玩家数={}, 成功={}", schematicId, materialIds.size(), targetPlayers.size(), assignedCount);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(true, "已分配 " + assignedCount + " 个任务"));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("批量分配失败", e);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "分配失败: " + e.getMessage()));
        }
    }

    private static void handleKickFromMaterial(KickFromMaterialC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        var materialIds = payload.materialIds();
        String targetPlayer = payload.targetPlayer();
        String playerName = player.getGameProfile().getName();

        try {
            var db = SyncMaterial.getSharedDatabase();
            if (!db.isOwner(schematicId, playerName)) {
                ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "没有权限"));
                return;
            }

            if (materialIds == null || materialIds.isEmpty()) {
                ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "请选择至少一个材料"));
                return;
            }
            if (targetPlayer == null || targetPlayer.isBlank()) {
                ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "目标玩家不能为空"));
                return;
            }

            int kickedCount = 0;
            for (int materialId : materialIds) {
                if (collaborationManager.leaveCollaboration(schematicId, materialId, targetPlayer)) {
                    kickedCount++;
                }
            }

            // 广播涉及的材料状态
            for (int materialId : materialIds) {
                broadcastStatus(server, schematicId, materialId);
            }

            SyncMaterial.LOGGER.info("踢出玩家: schematicId={}, target={}, 材料数={}, 成功={}", schematicId, targetPlayer, materialIds.size(), kickedCount);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(true, "已从 " + kickedCount + " 个材料中踢出 " + targetPlayer));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("踢出玩家失败", e);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "踢出失败: " + e.getMessage()));
        }
    }

    private static void handlePlayerListRequest(PlayerListRequestC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();

        try {
            // 获取在线玩家
            var onlinePlayers = server.getPlayerManager().getPlayerList();
            var onlineNames = new java.util.HashSet<String>();
            var players = new ArrayList<PlayerListResponseS2CPacket.PlayerInfo>();

            for (var onlinePlayer : onlinePlayers) {
                String name = onlinePlayer.getGameProfile().getName();
                onlineNames.add(name);
                players.add(new PlayerListResponseS2CPacket.PlayerInfo(name, true));
            }

            // 从 usercache.json 获取离线玩家
            try {
                var userCacheFile = server.getRunDirectory().resolve("usercache.json").toFile();
                if (userCacheFile.exists()) {
                    String content = new String(java.nio.file.Files.readAllBytes(userCacheFile.toPath()));
                    // 简单解析 JSON 数组中的 name 字段
                    int idx = 0;
                    while ((idx = content.indexOf("\"name\"", idx)) >= 0) {
                        int start = content.indexOf("\"", idx + 6) + 1;
                        int end = content.indexOf("\"", start);
                        if (start > 0 && end > start) {
                            String name = content.substring(start, end);
                            if (!name.isEmpty() && !onlineNames.contains(name)) {
                                players.add(new PlayerListResponseS2CPacket.PlayerInfo(name, false));
                            }
                        }
                        idx = end + 1;
                    }
                }
            } catch (Exception e) {
                SyncMaterial.LOGGER.warn("读取 usercache.json 失败，只显示在线玩家", e);
            }

            SyncMaterial.LOGGER.debug("玩家列表请求: schematicId={}, 返回 {} 个玩家", schematicId, players.size());
            ServerPlayNetworking.send(player, new PlayerListResponseS2CPacket(players));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("获取玩家列表失败", e);
            ServerPlayNetworking.send(player, new PlayerListResponseS2CPacket(List.of()));
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
        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(schematicId, success, message, areaInfos));
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
