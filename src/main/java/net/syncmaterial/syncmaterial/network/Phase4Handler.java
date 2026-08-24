package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Phase4Handler {
    private static final Map<String, Set<ServerPlayerEntity>> materialListSubscribers = new ConcurrentHashMap<>();

    static Map<String, Set<ServerPlayerEntity>> getMaterialListSubscribers() {
        return materialListSubscribers;
    }

    public static void registerPhase4Handlers() {
        // 负责人操作
        ServerPlayNetworking.registerGlobalReceiver(OwnerActionC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleOwnerAction(payload, player, context.server());
            });
        });

        // 批量分配
        ServerPlayNetworking.registerGlobalReceiver(BatchAssignC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleBatchAssign(payload, player, context.server());
            });
        });

        // 按材料踢出
        ServerPlayNetworking.registerGlobalReceiver(KickFromMaterialC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handleKickFromMaterial(payload, player, context.server());
            });
        });

        // 玩家列表请求
        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(payload.schematicId())) return;
            context.server().execute(() -> {
                handlePlayerListRequest(payload, player, context.server());
            });
        });
    }

    // ========== 材料列表订阅管理 ==========

    public static void subscribeMaterialList(ServerPlayerEntity player, String schematicId) {
        materialListSubscribers.computeIfAbsent(schematicId, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public static void unsubscribeMaterialList(ServerPlayerEntity player, String schematicId) {
        Set<ServerPlayerEntity> set = materialListSubscribers.get(schematicId);
        if (set != null) {
            set.remove(player);
            // 两参数 remove 做值比对：避免误删并发场景下刚被其他玩家重新填充的集合
            if (set.isEmpty()) {
                materialListSubscribers.remove(schematicId, set);
            }
        }
    }

    public static void unsubscribeAllMaterialList(ServerPlayerEntity player) {
        for (var entry : materialListSubscribers.entrySet()) {
            entry.getValue().remove(player);
        }
        materialListSubscribers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * 该玩家当前打开着材料清单的原理图集合。
     * 仓库线框据此判断哪些仓库属于"当前关注的原理图"，用高亮色区分。
     */
    public static Set<String> getSubscribedSchematics(ServerPlayerEntity player) {
        Set<String> result = new java.util.HashSet<>();
        for (var entry : materialListSubscribers.entrySet()) {
            if (entry.getValue().contains(player)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // ========== Phase 4: 负责人管理 ==========

    static void validateOwnerAction(String action) {
        if (!action.equals("TRANSFER") && !action.equals("ADD_DEPUTY") &&
            !action.equals("REMOVE_DEPUTY") && !action.equals("TOGGLE_SELF_CLAIM")) {
            throw new IllegalArgumentException("未知操作: " + action);
        }
    }

    static void handleOwnerAction(OwnerActionC2SPacket payload, ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        String action = payload.action();
        String targetPlayer = payload.targetPlayerName();
        String playerName = player.getGameProfile().getName();

        try {
            validateOwnerAction(action);

            var db = SyncMaterial.getSharedDatabase();
            boolean currentAllowSelfClaim = db.getAllowSelfClaim(schematicId);

            switch (action) {
                case "TRANSFER" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能转让", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    db.transferOwnership(schematicId, targetPlayer);
                    sendOwnerActionSuccess(server, player, db, schematicId, "已转让负责人给 " + targetPlayer);
                }
                case "ADD_DEPUTY" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能添加副负责人", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    db.addDeputyOwner(schematicId, targetPlayer);
                    sendOwnerActionSuccess(server, player, db, schematicId, "已添加副负责人 " + targetPlayer);
                }
                case "REMOVE_DEPUTY" -> {
                    if (!db.isMainOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "只有主负责人才能移除副负责人", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    if (targetPlayer == null || targetPlayer.isBlank()) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    db.removeDeputyOwner(schematicId, targetPlayer);
                    sendOwnerActionSuccess(server, player, db, schematicId, "已移除副负责人 " + targetPlayer);
                }
                case "TOGGLE_SELF_CLAIM" -> {
                    if (!db.isOwner(schematicId, playerName)) {
                        ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "没有权限", "", List.of(), currentAllowSelfClaim));
                        return;
                    }
                    db.setAllowSelfClaim(schematicId, !currentAllowSelfClaim);
                    sendOwnerActionSuccess(server, player, db, schematicId, "自行认领已" + (!currentAllowSelfClaim ? "开启" : "关闭"));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理负责人操作失败", e);
            try {
                var db2 = SyncMaterial.getSharedDatabase();
                boolean fallbackAllow = db2 != null ? db2.getAllowSelfClaim(schematicId) : true;
                ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "操作失败: " + e.getMessage(), "", List.of(), fallbackAllow));
            } catch (Exception ignored) {
                ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "操作失败: " + e.getMessage(), "", List.of(), true));
            }
        }
    }

    /** 发送带最新状态的成功响应，并广播给所有正在查看该原理图材料列表的玩家 */
    private static void sendOwnerActionSuccess(MinecraftServer server, ServerPlayerEntity player, net.syncmaterial.syncmaterial.server.SchematicDatabase db, String schematicId, String message) throws java.sql.SQLException {
        String ownerName = db.getUploadedBy(schematicId);
        var deputyOwners = db.getDeputyOwners(schematicId);
        boolean allowSelfClaim = db.getAllowSelfClaim(schematicId);
        var packet = new OwnerActionResponseS2CPacket(true, message, ownerName, deputyOwners, allowSelfClaim);

        // 广播给所有正在查看该原理图材料列表的玩家
        Set<ServerPlayerEntity> subscribers = materialListSubscribers.get(schematicId);
        if (subscribers != null) {
            ModNetworkHandler.sendToPlayers(subscribers, packet);
        }
        // 确保操作者也能收到（操作者一定在 subscribers 中，但保持防御性）
        ServerPlayNetworking.send(player, packet);
    }

    static void handleBatchAssign(BatchAssignC2SPacket payload, ServerPlayerEntity player, MinecraftServer server) {
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
            var collaborationManager = ModNetworkHandler.getCollaborationManager();
            for (int materialId : materialIds) {
                for (String target : targetPlayers) {
                    if (collaborationManager.joinCollaboration(schematicId, materialId, target)) {
                        assignedCount++;
                    }
                }
            }

            // 广播涉及的材料状态
            for (int materialId : materialIds) {
                ModNetworkHandler.broadcastStatus(server, schematicId, materialId);
            }

            SyncMaterial.LOGGER.info("批量分配: schematicId={}, 材料数={}, 玩家数={}, 成功={}", schematicId, materialIds.size(), targetPlayers.size(), assignedCount);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(true, "已分配 " + assignedCount + " 个任务"));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("批量分配失败", e);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "分配失败: " + e.getMessage()));
        }
    }

    static void handleKickFromMaterial(KickFromMaterialC2SPacket payload, ServerPlayerEntity player, MinecraftServer server) {
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
            var collaborationManager = ModNetworkHandler.getCollaborationManager();
            for (int materialId : materialIds) {
                if (collaborationManager.leaveCollaboration(schematicId, materialId, targetPlayer)) {
                    kickedCount++;
                }
            }

            // 广播涉及的材料状态
            for (int materialId : materialIds) {
                ModNetworkHandler.broadcastStatus(server, schematicId, materialId);
            }

            SyncMaterial.LOGGER.info("踢出玩家: schematicId={}, target={}, 材料数={}, 成功={}", schematicId, targetPlayer, materialIds.size(), kickedCount);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(true, "已从 " + kickedCount + " 个材料中踢出 " + targetPlayer));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("踢出玩家失败", e);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "踢出失败: " + e.getMessage()));
        }
    }

    static void handlePlayerListRequest(PlayerListRequestC2SPacket payload, ServerPlayerEntity player, MinecraftServer server) {
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

    /** 广播某原理图所有材料的最新状态给参与者 + 订阅者（备货区/背包变动后调用） */
    public static void broadcastAllMaterialStatus(MinecraftServer server, String schematicId) {
        var collaborationManager = ModNetworkHandler.getCollaborationManager();
        if (collaborationManager == null) return;
        List<Integer> materialIds = collaborationManager.getAllMaterialIds(schematicId);
        for (int materialId : materialIds) {
            ModNetworkHandler.broadcastStatus(server, schematicId, materialId);
        }
    }

    // ========== 内部工具方法 ==========

    static boolean validateSchematicId(String schematicId) {
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

    static boolean validatePlayer(ServerPlayerEntity player) {
        if (player == null) {
            SyncMaterial.LOGGER.warn("收到来自 null player 的网络包");
            return false;
        }
        return true;
    }
}
