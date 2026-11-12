package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import net.syncmaterial.syncmaterial.server.runtime.BackendMode;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModNetworkHandler {
    /**
     * 接收器只注册一次。服务端上下文现在按 {@link MinecraftServer} 存放在
     * {@link LegacyServerRegistry}，重进世界不需要也不能重装全局 receiver。
     */
    private static final AtomicBoolean RECEIVERS_REGISTERED = new AtomicBoolean();

    /** 广播入口：只发给仍属于原始上下文且完成握手的连接。 */
    public static void sendToPlayers(LegacyServerContext owner, Set<ServerPlayer> players, CustomPacketPayload packet) {
        if (owner == null || !owner.isCurrent() || players == null || packet == null) {
            return;
        }
        for (var player : players) {
            if (player != null && owner.isConnected(player)
                    && ProtocolHandshake.hasHandshaked(player)) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

    /**
     * 注册所有 PayloadType（必须在客户端和服务器初始化之前调用）。
     * 客户端注册 receiver 时需要这些类型已经存在。
     */
    public static void registerPayloadTypes() {
        // C2S (客户端到服务端)
        PayloadRegistrar.c2s(HelloC2SPacket.ID, HelloC2SPacket.CODEC);
        PayloadRegistrar.c2s(MaterialStatsRequestC2SPacket.ID, MaterialStatsRequestC2SPacket.CODEC);
        PayloadRegistrar.c2s(JoinCollaborationC2SPacket.ID, JoinCollaborationC2SPacket.CODEC);
        PayloadRegistrar.c2s(LeaveCollaborationC2SPacket.ID, LeaveCollaborationC2SPacket.CODEC);
        PayloadRegistrar.c2s(InventoryUpdateC2SPacket.ID, InventoryUpdateC2SPacket.CODEC);
        PayloadRegistrar.c2s(QueryMaterialStatusC2SPacket.ID, QueryMaterialStatusC2SPacket.CODEC);
        PayloadRegistrar.c2s(StagingAreaConfigC2SPacket.ID, StagingAreaConfigC2SPacket.CODEC);
        PayloadRegistrar.c2s(RescanStagingAreaC2SPacket.ID, RescanStagingAreaC2SPacket.CODEC);
        // Phase 4
        PayloadRegistrar.c2s(OwnerActionC2SPacket.ID, OwnerActionC2SPacket.CODEC);
        PayloadRegistrar.c2s(BatchAssignC2SPacket.ID, BatchAssignC2SPacket.CODEC);
        PayloadRegistrar.c2s(KickFromMaterialC2SPacket.ID, KickFromMaterialC2SPacket.CODEC);
        PayloadRegistrar.c2s(PlayerListRequestC2SPacket.ID, PlayerListRequestC2SPacket.CODEC);
        PayloadRegistrar.c2s(MaterialListCloseC2SPacket.ID, MaterialListCloseC2SPacket.CODEC);
        // Phase 5
        PayloadRegistrar.c2s(WarehouseContainerRequestC2SPacket.ID, WarehouseContainerRequestC2SPacket.CODEC);

        // S2C (服务端到客户端)
        PayloadRegistrar.s2c(HelloS2CPacket.ID, HelloS2CPacket.CODEC);
        PayloadRegistrar.s2c(MaterialStatsResponseS2CPacket.ID, MaterialStatsResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(MaterialCatalogUpdateS2CPacket.ID, MaterialCatalogUpdateS2CPacket.CODEC);
        PayloadRegistrar.s2c(AreaInventorySnapshotS2CPacket.ID, AreaInventorySnapshotS2CPacket.CODEC);
        PayloadRegistrar.s2c(CollaborationStatusS2CPacket.ID, CollaborationStatusS2CPacket.CODEC);
        PayloadRegistrar.s2c(StagingAreaConfigResponseS2CPacket.ID, StagingAreaConfigResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(RescanStagingAreaResponseS2CPacket.ID, RescanStagingAreaResponseS2CPacket.CODEC);
        // Phase 4
        PayloadRegistrar.s2c(OwnerActionResponseS2CPacket.ID, OwnerActionResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(BatchAssignResponseS2CPacket.ID, BatchAssignResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(KickFromMaterialResponseS2CPacket.ID, KickFromMaterialResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(PlayerListResponseS2CPacket.ID, PlayerListResponseS2CPacket.CODEC);
        // Phase 5
        PayloadRegistrar.s2c(WarehouseContainerResponseS2CPacket.ID, WarehouseContainerResponseS2CPacket.CODEC);
        PayloadRegistrar.s2c(WarehouseAreaResponseS2CPacket.ID, WarehouseAreaResponseS2CPacket.CODEC);
    }

    /** 握手记录只能落在准入时捕获的 server 后端，后端缺失时不回退。 */
    private static void registerHandshakeReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(HelloC2SPacket.ID, ModNetworkHandler::handleHello);

        SyncMaterial.LOGGER.info("版本握手接收器已注册，服务端协议版本 {}（mod 版本 {}）",
                ProtocolVersion.CURRENT, SyncMaterial.getModVersion());
    }

    /** 包私有便于握手生命周期测试。 */
    static void handleHello(HelloC2SPacket payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        MinecraftServer server = context.server();
        if (!validatePlayer(player) || server == null) return;

        // 先捕获该 server 当前的后端；Foundation 缺 runtime 时不回退 legacy。
        var registeredRuntime = ServerRuntimeRegistry.get(server);
        var runtime = registeredRuntime != null && registeredRuntime.isOpen() ? registeredRuntime : null;
        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (runtime == null && (BackendMode.isFoundationEnabled() || owner == null)) {
            return;
        }
        if (!isConnectedTo(server, player)) {
            return;
        }

        var connection = player.connection;
        server.execute(() -> {
            if (player.connection != connection) return;

            if (runtime != null) {
                if (ServerRuntimeRegistry.get(server) != runtime
                        || !runtime.isOpen()
                        || !isConnectedTo(server, player)) {
                    return;
                }
                boolean accepted = ProtocolHandshake.recordHandshake(
                        server, player, payload.protocolVersion(), payload.modVersion());
                ServerPlayNetworking.send(player, new HelloS2CPacket(
                        ProtocolVersion.CURRENT, SyncMaterial.getModVersion(), accepted));
                if (accepted) {
                    runtime.connected(player.getUUID());
                    FoundationNetworkAdapter.sendInitialData(server, player);
                }
                return;
            }

            // 原上下文必须仍是该 server 的登记上下文，且玩家仍持有原始连接。
            if (LegacyServerRegistry.get(server) != owner
                    || !owner.isOpen()
                    || !owner.isConnected(player)) {
                return;
            }

            boolean accepted = ProtocolHandshake.recordHandshake(
                    server, player, payload.protocolVersion(), payload.modVersion());
            ServerPlayNetworking.send(player, new HelloS2CPacket(
                    ProtocolVersion.CURRENT, SyncMaterial.getModVersion(), accepted));
            if (accepted) {
                sendInitialData(owner, player);
            }
        });
    }

    private static boolean isConnectedTo(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null || player.connection == null) {
            return false;
        }
        if (LegacyNetworkDispatch.serverOf(player) != server) {
            return false;
        }
        var playerList = server.getPlayerList();
        return playerList != null && playerList.getPlayer(player.getUUID()) == player;
    }

    /**
     * 握手通过后向该玩家推送初始数据：各原理图的备货区，以及全局仓库区域。
     * 仅在确认对端装了本 mod 且版本兼容后调用。
     */
    private static void sendInitialData(LegacyServerContext owner, ServerPlayer player) {
        StagingAreaManager manager = owner.staging();
        if (manager == null || !owner.isConnected(player)) return;

        try {
            for (var entry : owner.placementNames().entrySet()) {
                String schematicId = entry.getKey();
                var areas = manager.getStagingAreas(schematicId);
                if (areas.isEmpty()) continue;

                ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "LIST", schematicId, lookupSchematicName(owner, schematicId), true, "",
                        StagingAreaManager.buildAreaInfos(areas)));
            }

            // 仓库是全局资源，与原理图无关，单独推送一次供线框渲染
            sendWarehouseAreasTo(owner, player);
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("握手后推送初始数据失败", e);
        }
    }

    private static String lookupSchematicName(LegacyServerContext owner, String schematicId) {
        var database = owner.database();
        if (database == null) return "";

        try (var rs = database.executeQuery("SELECT name FROM schematics WHERE id = ?", schematicId)) {
            if (rs.next()) {
                String name = rs.getString("name");
                return name != null ? name : "";
            }
        } catch (Exception ignored) {
            // 名称仅用于界面展示，取不到就留空，不影响备货区数据本身
        }
        return "";
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

    static boolean validatePlayer(ServerPlayer player) {
        if (player == null) {
            SyncMaterial.LOGGER.warn("收到来自 null player 的网络包");
            return false;
        }
        return true;
    }

    /**
     * 业务包的统一准入检查：玩家有效且已完成版本握手。
     *
     * 未握手有两种情况，都不该继续处理：
     * 一是客户端协议版本被服务端拒绝（recordHandshake 返回 false 时不写入记录），
     * 二是伪造的包 —— 正常客户端必定先握手再发业务包。
     *
     * 握手包本身不走这里，否则永远无法完成第一次握手。
     */
    static boolean validateHandshakedPlayer(ServerPlayer player) {
        if (!validatePlayer(player)) {
            return false;
        }
        if (!ProtocolHandshake.hasHandshaked(player)) {
            SyncMaterial.LOGGER.warn("拒绝处理来自未完成版本握手的玩家 {} 的业务包", player.getName().getString());
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

    /**
     * 不属于任何原理图的全局仓库操作。
     *
     * 只有仓库本体的 CRUD / 列表允许 schematicId 为空；仓库引用操作
     * ADD_WAREHOUSE_REF / REMOVE_WAREHOUSE_REF / LIST_WAREHOUSE_REFS
     * 属于具体原理图，必须继续走 validateSchematicId。
     *
     * 必须用精确白名单，不能用 startsWith("ADD_WAREHOUSE")：
     * ADD_WAREHOUSE_REF 也有同一前缀，会被误判为全局操作并跳过校验，
     * 空 schematicId 最终写出一条无法被任何原理图读取的孤儿引用记录。
     */
    static boolean isGlobalWarehouseAction(String action) {
        return "LIST_WAREHOUSES".equals(action)
            || "ADD_WAREHOUSE".equals(action)
            || "UPDATE_WAREHOUSE".equals(action)
            || "DELETE_WAREHOUSE".equals(action);
    }

    /** 生产入口只注册一次；legacy 服务从上下文读取，不再依赖全局 setter。 */
    public static void register() {
        if (!RECEIVERS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        registerHandshakeReceiver();
        if (BackendMode.isFoundationEnabled()) {
            FoundationNetworkAdapter.register();
            return;
        }
        registerLegacyReceivers();
    }

    /** 测试捕获 receiver 的入口，不重置生产静态状态。 */
    static void registerReceiversForTests() {
        registerHandshakeReceiver();
        registerLegacyReceivers();
    }

    private static void registerLegacyReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(MaterialStatsRequestC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleMaterialStatsRequest(owner, payload, player, server));
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinCollaborationC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())
                    || !validateMaterialId(payload.materialId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleJoinCollaboration(owner, payload, player, server));
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())
                    || !validateMaterialId(payload.materialId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleLeaveCollaboration(owner, payload, player, server));
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())
                    || !validateMaterialId(payload.materialId()) || !validateCount(payload.count())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleContainerUpdate(owner, payload, player, server));
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleQueryMaterialStatus(owner, payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(MaterialListCloseC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleMaterialListClose(owner, payload, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(StagingAreaConfigC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateStagingAction(payload.action())) return;
            // 全局仓库本体操作不隶属于原理图，允许 schematicId 为空；
            // 仓库引用操作必须带合法 schematicId，不能被前缀匹配误放行
            if (!isGlobalWarehouseAction(payload.action())
                    && !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleStagingAreaConfig(owner, payload, player, server));
        });

        ServerPlayNetworking.registerGlobalReceiver(RescanStagingAreaC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player)) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleRescanStagingArea(owner, payload, player, server));
        });

        // Phase 4 handlers (owner action, batch assign, kick, player list)
        Phase4Handler.registerPhase4Handlers();

        // Phase 5: 取货模式容器数据订阅
        ServerPlayNetworking.registerGlobalReceiver(WarehouseContainerRequestC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!validateHandshakedPlayer(player)) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleWarehouseContainerRequest(owner, payload, player));
        });
    }

    static void handleJoinCollaboration(LegacyServerContext owner, JoinCollaborationC2SPacket payload,
                                        ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        Map<Integer, Integer> inventoryCounts = payload.inventoryCounts();
        String playerName = player.getName().getString();

        try {
            var db = owner.database();
            if (db == null) {
                SyncMaterial.LOGGER.warn("数据库不可用，拒绝加入协作: {}", schematicId);
                return;
            }
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

        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        if (collaboration.joinCollaboration(schematicId, materialId, playerName)) {
            for (Map.Entry<Integer, Integer> entry : inventoryCounts.entrySet()) {
                collaboration.updatePlayerInventory(playerName, schematicId, entry.getKey(), entry.getValue());
            }
            broadcastStatus(owner, schematicId, materialId);
        }
    }

    static void handleContainerUpdate(LegacyServerContext owner, InventoryUpdateC2SPacket payload,
                                      ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        int count = payload.count();
        String playerName = player.getName().getString();

        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        SyncMaterial.LOGGER.debug("收到玩家 {} 的库存更新: 材料 {}, 数量 {}", playerName, materialId, count);
        if (collaboration.isCollaborating(schematicId, materialId, playerName)) {
            collaboration.updatePlayerInventory(playerName, schematicId, materialId, count);
            broadcastStatus(owner, schematicId, materialId);
        } else {
            SyncMaterial.LOGGER.debug("玩家 {} 未协作材料 {}，忽略库存更新", playerName, materialId);
        }
    }

    static void handleMaterialStatsRequest(LegacyServerContext owner, MaterialStatsRequestC2SPacket payload,
                                           ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        String schematicId = payload.schematicId();
        try {
            SyncMaterial.LOGGER.debug("收到玩家 {} 的材料统计请求: {}", player.getName().getString(), schematicId);

            var queryService = owner.queryService();
            var collaboration = owner.collaboration();
            if (queryService == null || collaboration == null) {
                ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(
                        schematicId, "", List.of(), false, false, "", List.of(), true));
                return;
            }

            var materials = queryService.getMaterials(schematicId);
            var statuses = new ArrayList<CollaborationStatusS2CPacket>();

            for (var entry : materials) {
                var status = collaboration.getCollaborationStatus(schematicId, entry.getDatabaseId());
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

            String schematicName = PlacementsUtil.getDisplayName(owner, schematicId);

            // Phase 4: 查询负责人状态
            var db = owner.database();
            String playerName = player.getName().getString();
            boolean isOwner = db != null && db.isOwner(schematicId, playerName);
            boolean isMainOwner = db != null && db.isMainOwner(schematicId, playerName);
            String ownerName = "";
            var deputyOwners = new ArrayList<String>();
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

            ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(
                    schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim));

            for (var status : statuses) {
                ServerPlayNetworking.send(player, status);
            }

        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理材料统计请求失败", e);
            ServerPlayNetworking.send(player, new MaterialStatsResponseS2CPacket(
                    schematicId, "", List.of(), false, false, "", List.of(), true));
        }
    }

    static void handleLeaveCollaboration(LegacyServerContext owner, LeaveCollaborationC2SPacket payload,
                                         ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        String schematicId = payload.schematicId();
        int materialId = payload.materialId();
        String playerName = player.getName().getString();

        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        if (collaboration.leaveCollaboration(schematicId, materialId, playerName)) {
            broadcastStatus(owner, schematicId, materialId);
            sendStatusToPlayer(owner, player, schematicId, materialId);
        }
    }

    static void handleQueryMaterialStatus(LegacyServerContext owner, QueryMaterialStatusC2SPacket payload,
                                          ServerPlayer player) {
        if (!LegacyNetworkDispatch.accepts(owner, player)) return;

        String schematicId = payload.schematicId();
        SyncMaterial.LOGGER.debug("收到玩家 {} 的原理图 {} 协作状态查询请求", player.getName().getString(), schematicId);
        owner.subscriptions().subscribeMaterials(player, schematicId);
        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        List<Integer> materialIds = collaboration.getAllMaterialIds(schematicId);
        for (int materialId : materialIds) {
            sendStatusToPlayer(owner, player, schematicId, materialId);
        }
        // 订阅集合变了：该原理图引用的仓库现在应当高亮显示
        sendWarehouseAreasTo(owner, player);
    }

    static void handleMaterialListClose(LegacyServerContext owner, MaterialListCloseC2SPacket payload,
                                        ServerPlayer player) {
        if (!LegacyNetworkDispatch.accepts(owner, player)) return;

        owner.subscriptions().unsubscribeMaterials(player, payload.schematicId());
        sendWarehouseAreasTo(owner, player);
    }

    // Phase 5: 取货模式订阅管理（状态由上下文持有的 LegacySubscriptions 隔离）
    static void handleWarehouseContainerRequest(LegacyServerContext owner, WarehouseContainerRequestC2SPacket payload,
                                                ServerPlayer player) {
        if (!LegacyNetworkDispatch.accepts(owner, player)) return;

        String schematicId = payload.schematicId();
        StagingAreaManager manager = owner.staging();
        if (manager == null) return;

        if (payload.subscribe()) {
            // 订阅：获取该原理图引用的仓库 ID 集合
            var warehouses = manager.getWarehousesForSchematic(schematicId);
            Set<Integer> warehouseIds = warehouses.stream()
                    .map(StagingAreaManager.Warehouse::id)
                    .collect(java.util.stream.Collectors.toSet());

            owner.subscriptions().subscribeWarehouses(player, schematicId, warehouseIds);

            // 计算该玩家所有活跃原理图的仓库并集
            Set<Integer> allWarehouseIds = owner.subscriptions().warehouseIds(player);

            // 查询所有仓库的容器数据
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers =
                    manager.getContainerEntriesForWarehouses(allWarehouseIds);
            sendToPlayers(owner, Set.of(player), new WarehouseContainerResponseS2CPacket(containers));
            SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 订阅取货模式: 原理图 {}, 仓库 {}",
                    player.getName().getString(), schematicId, warehouseIds);
        } else {
            // 取消订阅
            owner.subscriptions().unsubscribeWarehouses(player, schematicId);
            SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 取消订阅取货模式: 原理图 {}",
                    player.getName().getString(), schematicId);
        }
    }

    /**
     * 供上下文/区域管理器调用：容器数据变化后推送给取货模式中的玩家。
     */
    public static void pushWarehouseContainerUpdate(LegacyServerContext owner) {
        if (owner == null || !owner.isCurrent()) return;

        StagingAreaManager manager = owner.staging();
        if (manager == null) return;

        Map<ServerPlayer, Set<Integer>> subscribers = owner.subscriptions().warehouseSubscribers();
        for (var playerEntry : subscribers.entrySet()) {
            ServerPlayer player = playerEntry.getKey();
            Set<Integer> allWarehouseIds = playerEntry.getValue();
            if (allWarehouseIds.isEmpty() || !owner.isConnected(player)) {
                continue;
            }
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers =
                    manager.getContainerEntriesForWarehouses(allWarehouseIds);
            sendToPlayers(owner, Set.of(player), new WarehouseContainerResponseS2CPacket(containers));
        }
    }

    /**
     * 广播仓库区域数据给该上下文中的全部在线玩家（线框渲染用）。
     *
     * <p>仓库是全局资源而非按原理图订阅，所以是全员广播；
     * 但 referencedIds 因人而异（取决于各自打开了哪个原理图的材料清单），
     * 因此逐玩家单独组包。</p>
     */
    public static void broadcastWarehouseAreas(LegacyServerContext owner) {
        if (owner == null || !owner.isCurrent()) return;

        StagingAreaManager manager = owner.staging();
        if (manager == null) return;

        // 线框广播是纯展示功能，不能因为它出错而中断调用方的业务流程
        try {
            var server = owner.server();
            var playerList = server == null || server.getPlayerList() == null
                ? null : server.getPlayerList().getPlayers();
            if (playerList == null) return;

            var warehouseInfos = StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses());
            for (var player : playerList) {
                sendWarehouseAreas(owner, player, manager, warehouseInfos);
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("[Phase5] 广播仓库区域数据失败", e);
        }
    }

    /** 显式 server 兼容入口：只做同步上下文解析，排队后不再重新查注册表。 */
    public static void broadcastWarehouseAreas(MinecraftServer server) {
        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (owner != null) {
            broadcastWarehouseAreas(owner);
        }
    }

    /**
     * 向单个玩家推送仓库区域数据（玩家加入时的初始同步）。
     */
    public static void sendWarehouseAreasTo(LegacyServerContext owner, ServerPlayer player) {
        if (owner == null || !owner.isCurrent()) return;

        StagingAreaManager manager = owner.staging();
        if (manager == null) return;

        try {
            sendWarehouseAreas(owner, player, manager,
                StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses()));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("[Phase5] 推送仓库区域数据失败", e);
        }
    }

    private static void sendWarehouseAreas(LegacyServerContext owner, ServerPlayer player,
                                           StagingAreaManager manager,
                                           List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouseInfos) {
        if (player == null || !owner.isConnected(player)) {
            return;
        }

        // 这是全员广播的共同出口：没握手过说明对方没装本 mod（或版本被拒），不必白发
        if (!ProtocolHandshake.hasHandshaked(player)) {
            return;
        }

        Set<Integer> referenced = new HashSet<>();
        for (String schematicId : owner.subscriptions().projects(player)) {
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
     * 若不清理则 ServerPlayer 引用永久驻留（内存泄漏 + 向死连接发包）。
     */
    public static void onPlayerDisconnect(ServerPlayer player) {
        if (player == null) return;

        MinecraftServer server = LegacyNetworkDispatch.serverOf(player);
        if (server == null) {
            ProtocolHandshake.remove(player);
            return;
        }

        if (BackendMode.isFoundationEnabled()) {
            var runtime = ServerRuntimeRegistry.get(server);
            var current = server.getPlayerList().getPlayer(player.getUUID());
            if (runtime != null && (current == null || current == player)) {
                FoundationNetworkAdapter.onPlayerDisconnect(runtime, player.getUUID());
                runtime.disconnected(player.getUUID());
            }
        }

        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (owner != null) {
            owner.execute(() -> {
                // 排队期间的上下文切换不能再清另一个实例的订阅。
                if (LegacyServerRegistry.get(server) != owner) return;
                owner.subscriptions().forgetPlayer(player);
                var manager = owner.staging();
                if (manager != null) {
                    manager.unsubscribeAll(player);
                }
            });
        }
        ProtocolHandshake.remove(player);
    }

    /** 原版世界边界，坐标绝对值上限 */
    private static final int COORD_LIMIT = 30_000_000;
    /** 区域水平区块数上限：挡住 ±2^31 坐标导致的天量 chunk 扫描把主线程冻死 */
    private static final int MAX_AREA_CHUNKS = 1024;

    /** 区域几何是否合理（坐标范围 + 区块跨度上限）。抽成静态便于单测。 */
    static boolean isAreaGeometryValid(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (Math.abs(x1) > COORD_LIMIT || Math.abs(z1) > COORD_LIMIT
                || Math.abs(x2) > COORD_LIMIT || Math.abs(z2) > COORD_LIMIT) {
            return false;
        }
        if (Math.abs(y1) > 4096 || Math.abs(y2) > 4096) {
            return false;
        }
        long spanX = (long) (Math.max(x1, x2) >> 4) - (Math.min(x1, x2) >> 4) + 1;
        long spanZ = (long) (Math.max(z1, z2) >> 4) - (Math.min(z1, z2) >> 4) + 1;
        return spanX * spanZ <= MAX_AREA_CHUNKS;
    }

    /**
     * 校验客户端上报的区域数据；合法返回 null，否则返回中文错误原因。
     * world 是客户端可控字符串，语法非法会让服务端扫描时解析资源 ID 崩溃，
     * 这里只挡语法非法（用 tryParse）；语法合法但维度不存在由扫描侧安全跳过（world==null 返回）。
     */
    private static String validateAreaData(StagingAreaConfigC2SPacket.AreaData d) {
        if (!isAreaGeometryValid(d.x1(), d.y1(), d.z1(), d.x2(), d.y2(), d.z2())) {
            return "区域坐标超出范围或区域过大";
        }
        if (d.world().isPresent() && McCompat.tryParseId(d.world().get()) == null) {
            return "维度 ID 格式非法";
        }
        return null;
    }

    static void handleStagingAreaConfig(LegacyServerContext owner, StagingAreaConfigC2SPacket payload,
                                        ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        String schematicId = payload.schematicId();
        StagingAreaManager manager = owner.staging();
        if (manager == null) {
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                    "", schematicId, "", false, "备货区服务未初始化", List.of()));
            return;
        }
        String action = payload.action();

        try {
            switch (action) {
                case "LIST" -> {
                    manager.subscribe(player, schematicId);
                    sendStagingAreaResponse(owner, player, manager, schematicId, true, "");
                }
                case "ADD" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "ADD", schematicId, "", false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    String geomErr = validateAreaData(data);
                    if (geomErr != null) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "ADD", schematicId, "", false, geomErr, List.of()));
                        return;
                    }
                    String world = data.world().orElse(McCompat.dimensionId(player.level()));
                    int areaId = manager.addStagingArea(schematicId, world, data.name(),
                            data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    if (areaId > 0) {
                        manager.rescanStagingArea(areaId);
                    }
                    sendStagingAreaResponse(owner, player, manager, schematicId, true, "备货区已添加");
                    manager.broadcastUpdate(schematicId);
                }
                case "RENAME" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "RENAME", schematicId, "", false, "缺少区域数据", List.of()));
                        return;
                    }
                    manager.renameStagingArea(payload.areaId(), schematicId, ad.get().name());
                    sendStagingAreaResponse(owner, player, manager, schematicId, true, "备货区已重命名");
                    manager.broadcastUpdate(schematicId);
                }
                case "DELETE" -> {
                    manager.removeStagingArea(payload.areaId(), schematicId);
                    sendStagingAreaResponse(owner, player, manager, schematicId, true, "备货区已删除");
                    manager.broadcastUpdate(schematicId);
                }
                case "UPDATE" -> {
                    var ad = payload.areaData();
                    if (ad.isEmpty()) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "UPDATE", schematicId, "", false, "缺少区域数据", List.of()));
                        return;
                    }
                    AreaData data = ad.get();
                    String updGeomErr = validateAreaData(data);
                    if (updGeomErr != null) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "UPDATE", schematicId, "", false, updGeomErr, List.of()));
                        return;
                    }
                    SyncMaterial.LOGGER.debug("[StagingArea] UPDATE: areaId={} schematicId='{}' name='{}'",
                            payload.areaId(), schematicId, data.name());
                    manager.updateStagingArea(payload.areaId(), schematicId, data.name(),
                            data.world().orElse(null),
                            data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    manager.rescanStagingArea(payload.areaId());
                    sendStagingAreaResponse(owner, player, manager, schematicId, true, "备货区已更新");
                    manager.broadcastUpdate(schematicId);
                }
                case "CLEAR" -> {
                    var areas = manager.getStagingAreas(schematicId);
                    for (var area : areas) {
                        manager.removeStagingArea(area.id(), schematicId);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                            "CLEAR", schematicId, "", true, "已清除所有备货区", List.of()));
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
                    String whGeomErr = validateAreaData(data);
                    if (whGeomErr != null) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "ADD_WAREHOUSE", schematicId, "", false, whGeomErr, List.of()));
                        return;
                    }
                    int id = manager.addWarehouse(data.name(), data.world().orElse("minecraft:overworld"),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    if (id > 0) {
                        // 立即扫描：否则新仓库一直是"未初始化"状态，前端显示数据过时且库存为 0，
                        // 直到玩家手动改动某个容器才被动触发扫描
                        manager.rescanWarehouseAndMarkChunks(id);
                        pushWarehouseContainerUpdate(owner);
                        broadcastWarehouseAreas(owner);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                            "ADD_WAREHOUSE", schematicId, "", id > 0,
                            id > 0 ? "仓库已创建" : "创建失败", List.of()));
                }
                case "UPDATE_WAREHOUSE" -> {
                    var data = payload.areaData().orElseThrow();
                    String whUpdErr = validateAreaData(data);
                    if (whUpdErr != null) {
                        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                                "UPDATE_WAREHOUSE", schematicId, "", false, whUpdErr, List.of()));
                        return;
                    }
                    manager.updateWarehouse(payload.areaId(), data.name(), data.world().orElse(null),
                            data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                    // 范围可能变了：updateWarehouse 已重置初始化状态，这里按新范围立即重扫
                    manager.rescanWarehouseAndMarkChunks(payload.areaId());
                    pushWarehouseContainerUpdate(owner);
                    broadcastWarehouseAreas(owner);
                    for (String affected : manager.getSchematicsReferencingWarehouse(payload.areaId())) {
                        Phase4Handler.broadcastAllMaterialStatus(owner, affected);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                            "UPDATE_WAREHOUSE", schematicId, "", true, "仓库已更新", List.of()));
                }
                case "DELETE_WAREHOUSE" -> {
                    // 先取引用方：deleteWarehouse 会级联清掉 schematic_warehouses
                    var affectedSchematics = manager.getSchematicsReferencingWarehouse(payload.areaId());
                    manager.deleteWarehouse(payload.areaId());
                    pushWarehouseContainerUpdate(owner);
                    broadcastWarehouseAreas(owner);
                    for (String affected : affectedSchematics) {
                        Phase4Handler.broadcastAllMaterialStatus(owner, affected);
                    }
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                            "DELETE_WAREHOUSE", schematicId, "", true, "仓库已删除", List.of()));
                }
                case "ADD_WAREHOUSE_REF" -> {
                    manager.addWarehouseReference(schematicId, payload.areaId());
                    // 引用关系变了：材料的仓库计数与新鲜度提示都会变，必须重新广播状态，
                    // 否则界面停留在旧数据（仓库计数为 0 且显示"数据可能过时"）
                    Phase4Handler.broadcastAllMaterialStatus(owner, schematicId);
                    // 引用集合变化会改变线框高亮色，必须重推区域数据
                    broadcastWarehouseAreas(owner);
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                        "ADD_WAREHOUSE_REF", schematicId, "", true, "已添加仓库引用",
                        StagingAreaManager.buildWarehouseInfos(manager.getWarehousesForSchematic(schematicId))));
                }
                case "REMOVE_WAREHOUSE_REF" -> {
                    manager.removeWarehouseReference(schematicId, payload.areaId());
                    Phase4Handler.broadcastAllMaterialStatus(owner, schematicId);
                    broadcastWarehouseAreas(owner);
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
                    ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                            "", schematicId, "", false, "未知操作: " + action, List.of()));
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理备货区配置失败", e);
            ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                    "", schematicId, "", false, "操作失败: " + e.getMessage(), List.of()));
        }
    }

    static void handleRescanStagingArea(LegacyServerContext owner, RescanStagingAreaC2SPacket payload,
                                        ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;

        try {
            String schematicId = payload.schematicId();
            if (!validateSchematicId(schematicId)) {
                ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(false, "无效的原理图ID"));
                return;
            }

            var manager = owner.staging();
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

            Phase4Handler.broadcastAllMaterialStatus(owner, schematicId);

            SyncMaterial.LOGGER.info("[StagingArea] 手动重新扫描完成: schematicId={}, 区域数={}",
                    schematicId, rescannedCount);
            ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(
                    true, "成功重新扫描 " + rescannedCount + " 个备货区"));
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("处理重新扫描请求失败", e);
            ServerPlayNetworking.send(player, new RescanStagingAreaResponseS2CPacket(
                    false, "重新扫描失败: " + e.getMessage()));
        }
    }

    /**
     * 发送备货区列表响应
     */
    private static void sendStagingAreaResponse(LegacyServerContext owner, ServerPlayer player,
                                                StagingAreaManager manager, String schematicId,
                                                boolean success, String message) {
        String schematicName = getSchematicName(owner, schematicId);
        var areas = manager.getStagingAreas(schematicId);
        var areaInfos = StagingAreaManager.buildAreaInfos(areas);
        ServerPlayNetworking.send(player, new StagingAreaConfigResponseS2CPacket(
                "LIST", schematicId, schematicName, success, message, areaInfos));
    }

    private static String getSchematicName(LegacyServerContext owner, String schematicId) {
        try {
            var db = owner.database();
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

    static void broadcastStatus(LegacyServerContext owner, String schematicId, int materialId) {
        if (owner == null || !owner.isCurrent()) return;
        var collaboration = owner.collaboration();
        if (collaboration == null) return;

        var status = collaboration.getCollaborationStatus(schematicId, materialId);
        if (status == null) return;

        // 收集所有接收者：参与者 + 材料列表订阅者（去重）
        Set<ServerPlayer> recipients = java.util.concurrent.ConcurrentHashMap.newKeySet();

        List<String> participants = collaboration.getParticipants(schematicId, materialId);
        for (String name : participants) {
            var player = McCompat.findPlayerByName(owner.server(), name);
            if (player != null) {
                recipients.add(player);
            }
        }

        recipients.addAll(owner.subscriptions().materialSubscribers(schematicId));

        SyncMaterial.LOGGER.info("广播材料 {} 的状态给 {} 个玩家（参与者 + 订阅者）",
                materialId, recipients.size());
        sendToPlayers(owner, recipients, status);
    }

    private static void sendStatusToPlayer(LegacyServerContext owner, ServerPlayer player,
                                           String schematicId, int materialId) {
        if (!LegacyNetworkDispatch.accepts(owner, player)) return;
        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        var status = collaboration.getCollaborationStatus(schematicId, materialId);
        if (status != null) {
            ServerPlayNetworking.send(player, status);
        }
    }
}
