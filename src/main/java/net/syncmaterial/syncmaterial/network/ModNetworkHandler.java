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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ModNetworkHandler {
    private static DatabaseQueryService queryService;
    private static CollaborationManager collaborationManager;

    static CollaborationManager getCollaborationManager() {
        return collaborationManager;
    }

    public static void sendToPlayers(Set<net.minecraft.server.network.ServerPlayerEntity> players, net.minecraft.network.packet.CustomPayload packet) {
        for (var player : players) {
            if (player.isAlive() && player.networkHandler != null) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

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
        PayloadTypeRegistry.playC2S().register(MaterialListCloseC2SPacket.ID, MaterialListCloseC2SPacket.CODEC);
        // Phase 5
        PayloadTypeRegistry.playC2S().register(WarehouseContainerRequestC2SPacket.ID, WarehouseContainerRequestC2SPacket.CODEC);

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
        // Phase 5
        PayloadTypeRegistry.playS2C().register(WarehouseContainerResponseS2CPacket.ID, WarehouseContainerResponseS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(WarehouseAreaResponseS2CPacket.ID, WarehouseAreaResponseS2CPacket.CODEC);
    }

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

    static boolean validateMaterialId(int materialId) {
        if (materialId < 0) {
            SyncMaterial.LOGGER.warn("收到无效的 materialId: {}", materialId);
            return false;
        }
        return true;
    }

    static boolean validateCount(int count) {
        if (count < 0) {
            SyncMaterial.LOGGER.warn("收到无效的 count: {}", count);
            return false;
        }
        return true;
    }

    static boolean validatePlayer(net.minecraft.server.network.ServerPlayerEntity player) {
        if (player == null) {
            SyncMaterial.LOGGER.warn("收到来自 null player 的网络包");
            return false;
        }
        return true;
    }

    static boolean validateStagingAction(String action) {
        return action != null && (action.equals("LIST") || action.equals("ADD") || action.equals("RENAME")
            || action.equals("DELETE") || action.equals("UPDATE") || action.equals("CLEAR")
            || action.equals("LIST_WAREHOUSES") || action.equals("ADD_WAREHOUSE")
            || action.equals("UPDATE_WAREHOUSE") || action.equals("DELETE_WAREHOUSE")
            || action.equals("ADD_WAREHOUSE_REF") || action.equals("REMOVE_WAREHOUSE_REF")
            || action.equals("LIST_WAREHOUSE_REFS"));
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
                handleMaterialStatsRequest(payload, player, context.server());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId)) return;

            context.server().execute(() -> {
                handleJoinCollaboration(payload, player, context.server());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId)) return;

            context.server().execute(() -> {
                handleLeaveCollaboration(payload, player, context.server());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            int materialId = payload.materialId();
            int count = payload.count();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId) || !validateMaterialId(materialId) || !validateCount(count)) return;

            context.server().execute(() -> {
                handleInventoryUpdate(payload, player, context.server());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId)) return;

            context.server().execute(() -> {
                handleQueryMaterialStatus(payload, player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(MaterialListCloseC2SPacket.ID, (payload, context) -> {
            String schematicId = payload.schematicId();
            var player = context.player();
            if (!validatePlayer(player) || !validateSchematicId(schematicId)) return;

            context.server().execute(() -> {
                handleMaterialListClose(payload, player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StagingAreaConfigC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player) || !validateStagingAction(payload.action())) return;
            // 仓库操作不需要 schematicId，备货区操作需要
            boolean isWarehouseAction = payload.action().startsWith("LIST_WAREHOUSES")
                || payload.action().startsWith("ADD_WAREHOUSE")
                || payload.action().startsWith("UPDATE_WAREHOUSE")
                || payload.action().startsWith("DELETE_WAREHOUSE");
            if (!isWarehouseAction && !validateSchematicId(payload.schematicId())) return;
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

        // Phase 4 handlers (owner action, batch assign, kick, player list)
        Phase4Handler.registerPhase4Handlers();

        // Phase 5: 取货模式容器数据订阅
        ServerPlayNetworking.registerGlobalReceiver(WarehouseContainerRequestC2SPacket.ID, (payload, context) -> {
            var player = context.player();
            if (!validatePlayer(player)) return;
            context.server().execute(() -> {
                handleWarehouseContainerRequest(payload, player);
            });
        });
    }

    static void handleJoinCollaboration(JoinCollaborationC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        Map<Integer, Integer> inventoryCounts = payload.inventoryCounts();
        String playerName = player.getGameProfile().getName();

        try {
            var db = SyncMaterial.getSharedDatabase();
            boolean allowSelfClaim = db.getAllowSelfClaim(schematicId);
            boolean isOwner = db.isOwner(schematicId, playerName);

            if (!allowSelfClaim && !isOwner) {
                // 不允许自行认领且非负责人，拒绝
                return;
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("检查自行认领权限失败", e);
            return;
        }

        if (collaborationManager.joinCollaboration(schematicId, materialId, playerName)) {
            for (Map.Entry<Integer, Integer> entry : inventoryCounts.entrySet()) {
                collaborationManager.updatePlayerInventory(playerName, schematicId, entry.getKey(), entry.getValue());
            }
            broadcastStatus(server, schematicId, materialId);
        }
    }

    static void handleInventoryUpdate(InventoryUpdateC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        int count = payload.count();
        String playerName = player.getGameProfile().getName();

        SyncMaterial.LOGGER.debug("收到玩家 {} 的库存更新: 材料 {}, 数量 {}", playerName, materialId, count);
        if (collaborationManager.isCollaborating(schematicId, materialId, playerName)) {
            collaborationManager.updatePlayerInventory(playerName, schematicId, materialId, count);
            broadcastStatus(server, schematicId, materialId);
        } else {
            SyncMaterial.LOGGER.debug("玩家 {} 未协作材料 {}，忽略库存更新", playerName, materialId);
        }
    }

    static void handleMaterialStatsRequest(MaterialStatsRequestC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        try {
            SyncMaterial.LOGGER.debug("收到玩家 {} 的材料统计请求: {}", player.getGameProfile().getName(), schematicId);

            var materials = queryService.getMaterials(schematicId);
            var statuses = new java.util.ArrayList<CollaborationStatusS2CPacket>();

            for (var entry : materials) {
                var status = collaborationManager.getCollaborationStatus(schematicId, entry.getDatabaseId());
                if (status != null) {
                    statuses.add(status);
                    int playersSum = 0;
                    for (var p : status.participants()) {
                        playersSum += p.count();
                    }
                    int collected = status.stagingCount() + status.warehouseCount() + playersSum;
                    entry.setCountAvailable(collected);
                    entry.setCountMissing(net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                        entry.getCountTotal(), status.stagingCount(), status.warehouseCount(), playersSum));
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
    }

    static void handleLeaveCollaboration(LeaveCollaborationC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        String playerName = player.getGameProfile().getName();

        if (collaborationManager.leaveCollaboration(schematicId, materialId, playerName)) {
            broadcastStatus(server, schematicId, materialId);
            sendStatusToPlayer(player, schematicId, materialId);
        }
    }

    static void handleQueryMaterialStatus(QueryMaterialStatusC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player) {
        String schematicId = payload.schematicId();
        SyncMaterial.LOGGER.debug("收到玩家 {} 的原理图 {} 协作状态查询请求", player.getGameProfile().getName(), schematicId);
        Phase4Handler.subscribeMaterialList(player, schematicId);
        List<Integer> materialIds = collaborationManager.getAllMaterialIds(schematicId);
        for (int materialId : materialIds) {
            sendStatusToPlayer(player, schematicId, materialId);
        }
        // 订阅集合变了：该原理图引用的仓库现在应当高亮显示
        sendWarehouseAreasTo(player);
    }

    static void handleMaterialListClose(MaterialListCloseC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player) {
        Phase4Handler.unsubscribeMaterialList(player, payload.schematicId());
        sendWarehouseAreasTo(player);
    }

    // Phase 5: 取货模式订阅管理
    private static final Map<net.minecraft.server.network.ServerPlayerEntity, Map<String, Set<Integer>>> playerSchematicWarehouses = new ConcurrentHashMap<>();

    static void handleWarehouseContainerRequest(WarehouseContainerRequestC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player) {
        String schematicId = payload.schematicId();
        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) return;

        if (payload.subscribe()) {
            // 订阅：获取该原理图引用的仓库 ID 集合
            var warehouses = manager.getWarehousesForSchematic(schematicId);
            Set<Integer> warehouseIds = warehouses.stream().map(StagingAreaManager.Warehouse::id).collect(java.util.stream.Collectors.toSet());

            playerSchematicWarehouses.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(schematicId, warehouseIds);

            // 计算该玩家所有活跃原理图的仓库并集
            Set<Integer> allWarehouseIds = new HashSet<>();
            for (var entry : playerSchematicWarehouses.get(player).values()) {
                allWarehouseIds.addAll(entry);
            }

            // 查询所有仓库的容器数据
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers = manager.getContainerEntriesForWarehouses(allWarehouseIds);
            ServerPlayNetworking.send(player, new WarehouseContainerResponseS2CPacket(containers));
            SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 订阅取货模式: 原理图 {}, 仓库 {}", player.getName().getString(), schematicId, warehouseIds);
        } else {
            // 取消订阅
            Map<String, Set<Integer>> schematicMap = playerSchematicWarehouses.get(player);
            if (schematicMap != null) {
                schematicMap.remove(schematicId);
                if (schematicMap.isEmpty()) {
                    playerSchematicWarehouses.remove(player);
                }
            }
            SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 取消订阅取货模式: 原理图 {}", player.getName().getString(), schematicId);
        }
    }

    /**
     * 供 StagingAreaManager 调用：容器数据变化后推送给取货模式中的玩家
     */
    public static void pushWarehouseContainerUpdate(StagingAreaManager manager) {
        for (var playerEntry : playerSchematicWarehouses.entrySet()) {
            var player = playerEntry.getKey();
            // 存活检查：断线玩家的条目可能尚未被清理，避免向失效连接发包
            if (!player.isAlive() || player.networkHandler == null) {
                continue;
            }
            Set<Integer> allWarehouseIds = new HashSet<>();
            for (var ids : playerEntry.getValue().values()) {
                allWarehouseIds.addAll(ids);
            }
            if (allWarehouseIds.isEmpty()) continue;

            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers = manager.getContainerEntriesForWarehouses(allWarehouseIds);
            ServerPlayNetworking.send(player, new WarehouseContainerResponseS2CPacket(containers));
        }
    }

    /**
     * 广播仓库区域数据给全部在线玩家（线框渲染用）。
     *
     * 仓库是全局资源而非按原理图订阅，所以是全员广播；
     * 但 referencedIds 因人而异（取决于各自打开了哪个原理图的材料清单），
     * 因此逐玩家单独组包。
     */
    public static void broadcastWarehouseAreas(MinecraftServer server) {
        if (server == null) return;

        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) return;

        // 线框广播是纯展示功能，不能因为它出错而中断调用方的业务流程
        try {
            var playerList = server.getPlayerManager() != null
                ? server.getPlayerManager().getPlayerList() : null;
            if (playerList == null) return;

            var warehouseInfos = StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses());
            for (var player : playerList) {
                sendWarehouseAreas(player, manager, warehouseInfos);
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("[Phase5] 广播仓库区域数据失败", e);
        }
    }

    /**
     * 向单个玩家推送仓库区域数据（玩家加入时的初始同步）。
     */
    public static void sendWarehouseAreasTo(net.minecraft.server.network.ServerPlayerEntity player) {
        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) return;

        try {
            sendWarehouseAreas(player, manager,
                StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses()));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("[Phase5] 推送仓库区域数据失败", e);
        }
    }

    private static void sendWarehouseAreas(net.minecraft.server.network.ServerPlayerEntity player,
                                           StagingAreaManager manager,
                                           List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouseInfos) {
        if (player == null || !player.isAlive() || player.networkHandler == null) {
            return;
        }

        Set<Integer> referenced = new HashSet<>();
        for (String schematicId : Phase4Handler.getSubscribedSchematics(player)) {
            for (var wh : manager.getWarehousesForSchematic(schematicId)) {
                referenced.add(wh.id());
            }
        }

        ServerPlayNetworking.send(player,
            new WarehouseAreaResponseS2CPacket(warehouseInfos, List.copyOf(referenced)));
    }

    /**
     * 玩家断开连接时清理其全部订阅状态。
     * 客户端正常退出会主动发退订包，但拔线/崩溃不会，
     * 若不清理则 ServerPlayerEntity 引用永久驻留（内存泄漏 + 向死连接发包）。
     */
    public static void onPlayerDisconnect(net.minecraft.server.network.ServerPlayerEntity player) {
        if (player == null) return;

        playerSchematicWarehouses.remove(player);
        Phase4Handler.unsubscribeAllMaterialList(player);

        var manager = SyncMaterial.getServerStagingAreaManager();
        if (manager != null) {
            manager.unsubscribeAll(player);
        }
    }

    static void handleStagingAreaConfig(StagingAreaConfigC2SPacket payload, net.minecraft.server.network.ServerPlayerEntity player, MinecraftServer server) {
        String schematicId = payload.schematicId();
        StagingAreaManager manager = SyncMaterial.getServerStagingAreaManager();
        if (manager == null) {
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("", schematicId, "", false, "备货区服务未初始化", List.of()));
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("ADD", schematicId, "", false, "缺少区域数据", List.of()));
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("RENAME", schematicId, "", false, "缺少区域数据", List.of()));
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
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("UPDATE", schematicId, "", false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    SyncMaterial.LOGGER.debug("[StagingArea] UPDATE: areaId={} schematicId='{}' name='{}'",
                            payload.areaId(), schematicId, data.name());
                    manager.updateStagingArea(payload.areaId(), schematicId, data.name(), data.world().orElse(null),
                            data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    manager.rescanStagingArea(payload.areaId());
                    sendStagingAreaResponse(player, manager, schematicId, true, "备货区已更新");
                    manager.broadcastUpdate(schematicId);
                }
                case "CLEAR" -> {
                    var areas = manager.getStagingAreas(schematicId);
                    for (var area : areas) {
                        manager.removeStagingArea(area.id(), schematicId);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("CLEAR", schematicId, "", true, "已清除所有备货区", List.of()));
                    manager.broadcastUpdate(schematicId);
                }
                // Phase 5: 仓库管理操作
                case "LIST_WAREHOUSES" -> {
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "LIST_WAREHOUSES", schematicId, "", true, "ok",
                        StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses())));
                }
                case "ADD_WAREHOUSE" -> {
                    var data = payload.areaData().orElseThrow();
                    int id = manager.addWarehouse(data.name(), data.world().orElse("minecraft:overworld"),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    if (id > 0) {
                        // 立即扫描：否则新仓库一直是"未初始化"状态，前端显示数据过时且库存为 0，
                        // 直到玩家手动改动某个容器才被动触发扫描
                        manager.rescanWarehouseAndMarkChunks(id);
                        pushWarehouseContainerUpdate(manager);
                        broadcastWarehouseAreas(server);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("ADD_WAREHOUSE", schematicId, "", id > 0, id > 0 ? "仓库已创建" : "创建失败", List.of()));
                }
                case "UPDATE_WAREHOUSE" -> {
                    var data = payload.areaData().orElseThrow();
                    manager.updateWarehouse(payload.areaId(), data.name(), data.world().orElse(null),
                            data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    // 范围可能变了：updateWarehouse 已重置初始化状态，这里按新范围立即重扫
                    manager.rescanWarehouseAndMarkChunks(payload.areaId());
                    pushWarehouseContainerUpdate(manager);
                    broadcastWarehouseAreas(server);
                    for (String affected : manager.getSchematicsReferencingWarehouse(payload.areaId())) {
                        Phase4Handler.broadcastAllMaterialStatus(server, affected);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("UPDATE_WAREHOUSE", schematicId, "", true, "仓库已更新", List.of()));
                }
                case "DELETE_WAREHOUSE" -> {
                    // 先取引用方：deleteWarehouse 会级联清掉 schematic_warehouses
                    var affectedSchematics = manager.getSchematicsReferencingWarehouse(payload.areaId());
                    manager.deleteWarehouse(payload.areaId());
                    pushWarehouseContainerUpdate(manager);
                    broadcastWarehouseAreas(server);
                    for (String affected : affectedSchematics) {
                        Phase4Handler.broadcastAllMaterialStatus(server, affected);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("DELETE_WAREHOUSE", schematicId, "", true, "仓库已删除", List.of()));
                }
                case "ADD_WAREHOUSE_REF" -> {
                    manager.addWarehouseReference(schematicId, payload.areaId());
                    // 引用关系变了：材料的仓库计数与新鲜度提示都会变，必须重新广播状态，
                    // 否则界面停留在旧数据（仓库计数为 0 且显示"数据可能过时"）
                    Phase4Handler.broadcastAllMaterialStatus(server, schematicId);
                    // 引用集合变化会改变线框高亮色，必须重推区域数据
                    broadcastWarehouseAreas(server);
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "ADD_WAREHOUSE_REF", schematicId, "", true, "已添加仓库引用",
                        StagingAreaManager.buildWarehouseInfos(manager.getWarehousesForSchematic(schematicId))));
                }
                case "REMOVE_WAREHOUSE_REF" -> {
                    manager.removeWarehouseReference(schematicId, payload.areaId());
                    Phase4Handler.broadcastAllMaterialStatus(server, schematicId);
                    broadcastWarehouseAreas(server);
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "REMOVE_WAREHOUSE_REF", schematicId, "", true, "已移除仓库引用",
                        StagingAreaManager.buildWarehouseInfos(manager.getWarehousesForSchematic(schematicId))));
                }
                case "LIST_WAREHOUSE_REFS" -> {
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "LIST_WAREHOUSE_REFS", schematicId, "", true, "ok",
                        StagingAreaManager.buildWarehouseInfos(manager.getWarehousesForSchematic(schematicId))));
                }
                default -> {
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("", schematicId, "", false, "未知操作: " + action, List.of()));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理备货区配置失败", e);
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("", schematicId, "", false, "操作失败: " + e.getMessage(), List.of()));
        }
    }

    static void handleRescanStagingArea(RescanStagingAreaC2SPacket payload, ServerPlayNetworking.Context context) {
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

            Phase4Handler.broadcastAllMaterialStatus(context.server(), schematicId);

            SyncMaterial.LOGGER.info("[StagingArea] 手动重新扫描完成: schematicId={}, 区域数={}", schematicId, rescannedCount);
            ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(true, "成功重新扫描 " + rescannedCount + " 个备货区"));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理重新扫描请求失败", e);
            ServerPlayNetworking.send(context.player(), new RescanStagingAreaResponseS2CPacket(false, "重新扫描失败: " + e.getMessage()));
        }
    }

    /**
     * 发送备货区列表响应
     */
    private static void sendStagingAreaResponse(net.minecraft.server.network.ServerPlayerEntity player,
                                                 StagingAreaManager manager, String schematicId,
                                                 boolean success, String message) {
        String schematicName = getSchematicName(schematicId);
        var areas = manager.getStagingAreas(schematicId);
        var areaInfos = StagingAreaManager.buildAreaInfos(areas);
        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket("LIST", schematicId, schematicName, success, message, areaInfos));
    }

    private static String getSchematicName(String schematicId) {
        try {
            var db = SyncMaterial.getSharedDatabase();
            if (db != null) {
                try (var rs = db.executeQuery("SELECT name FROM schematics WHERE id = ?", schematicId)) {
                    if (rs.next()) return rs.getString("name");
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.warn("获取原理图名称失败: {}", schematicId);
        }
        return "";
    }

    static void broadcastStatus(MinecraftServer server, String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status == null) return;

        // 收集所有接收者：参与者 + 材料列表订阅者（去重）
        Set<net.minecraft.server.network.ServerPlayerEntity> recipients = java.util.concurrent.ConcurrentHashMap.newKeySet();

        List<String> participants = collaborationManager.getParticipants(schematicId, materialId);
        for (String name : participants) {
            var player = server.getPlayerManager().getPlayer(name);
            if (player != null) {
                recipients.add(player);
            }
        }

        Set<net.minecraft.server.network.ServerPlayerEntity> subscribers = Phase4Handler.getMaterialListSubscribers().get(schematicId);
        if (subscribers != null) {
            recipients.addAll(subscribers);
        }

        SyncMaterial.LOGGER.info("广播材料 {} 的状态给 {} 个玩家（参与者 + 订阅者）", materialId, recipients.size());
        sendToPlayers(recipients, status);
    }

    private static void sendStatusToPlayer(net.minecraft.server.network.ServerPlayerEntity player, String schematicId, int materialId) {
        var status = collaborationManager.getCollaborationStatus(schematicId, materialId);
        if (status != null) {
            ServerPlayNetworking.send(player, status);
        }
    }
}
