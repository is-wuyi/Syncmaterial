package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaInventoryView;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

public final class FoundationNetworkAdapter {
    private static final Map<ServerRuntime, FoundationDeliveryTracker> DELIVERIES = new WeakHashMap<>();

    private FoundationNetworkAdapter() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(MaterialStatsRequestC2SPacket.ID, (request, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!allowed(player, request.schematicId())) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(server);
            if (runtime == null) return;
            runtime.connected(player.getUUID());
            long connection = runtime.connection(player.getUUID());
            if (connection == 0) return;
            var token = delivery(runtime).beginProject(player.getUUID(), connection, request.schematicId(), true);
            PlayerIdentity actor = identity(player);
            runtime.teams().reconcile(actor).thenCompose(ignored -> read(runtime, request.schematicId()))
                    .whenComplete((snapshot, error) -> execute(server, runtime, () -> {
                ServerPlayer current = currentPlayer(server, runtime, token.playerId(), connection);
                if (current == null || !delivery(runtime).acceptProject(token)) return;
                if (error != null) {
                    failed(current, error);
                    return;
                }
                if (snapshot.isEmpty()) {
                    current.sendSystemMessage(net.minecraft.network.chat.Component.literal("原理图尚未就绪，请稍后重试"));
                    return;
                }
                try {
                    ProjectData data = snapshot.get();
                    var project = data.project();
                    if (!runtime.isMaterialSnapshotCurrent(project)) throw new StaleMaterialSnapshotException();
                    boolean mainOwner = data.team().isMainOwner(identity(current));
                    boolean isOwner = data.team().isOwner(identity(current));
                    List<MaterialEntry> materials = materialEntries(project);
                    List<CollaborationStatusS2CPacket> statuses = statuses(runtime, data);
                    runtime.subscribe(token.playerId(), project.id());
                    send(current, new MaterialStatsResponseS2CPacket(project.id(), project.name(), materials,
                            isOwner, mainOwner, data.team().owner().name(), data.team().deputyNames(), data.team().allowSelfClaim()));
                    FoundationTeamNetworkAdapter.sendInitialTeam(runtime, current, data.team(), data.teamSequence());
                    long revision = runtime.catalogs().sent(token.playerId(), connection, project);
                    if (ProtocolHandshake.supports(current, ProtocolVersion.MATERIAL_CATALOG_UPDATES)) {
                        send(current, new MaterialCatalogUpdateS2CPacket(project.id(), project.name(), revision, materials));
                    }
                    if (!ProtocolHandshake.supports(current, ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) {
                        current.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "实验后端：库存已按物理容器去重；请更新客户端以区分准备状态的未知、过期和已确认。静止超过 10 秒的背包库存不计入可用量"));
                    }
                    for (var status : statuses) send(current, status);
                    sendInventory(runtime, current, data, revision, token.sequence());
                    runtime.requestBroadcast(project.id());
                    FoundationAreaNetworkAdapter.refreshWarehouseAreas(server, runtime, current);
                } catch (Exception conversionError) {
                    failed(current, conversionError);
                }
            }));
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryMaterialStatusC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId())) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            runtime.subscribe(context.player().getUUID(), request.schematicId());
            sendSnapshot(context.server(), runtime, request.schematicId(), context.player());
        });

        ServerPlayNetworking.registerGlobalReceiver(MaterialListCloseC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId())) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime != null) {
                delivery(runtime).forgetProject(context.player().getUUID(), runtime.connection(context.player().getUUID()), request.schematicId());
                runtime.unsubscribe(context.player().getUUID(), request.schematicId());
                FoundationAreaNetworkAdapter.refreshWarehouseAreas(context.server(), runtime, context.player());
                FoundationAreaNetworkAdapter.sendWarehouseContainers(context.server(), runtime, context.player());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(JoinCollaborationC2SPacket.ID, (request, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!allowed(player, request.schematicId()) || request.materialId() <= 0
                    || request.inventoryCounts().size() > 50
                    || request.inventoryCounts().entrySet().stream().anyMatch(entry -> entry.getKey() <= 0 || entry.getValue() < 0)) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(server);
            if (runtime == null) return;
            PlayerIdentity identity = identity(player);
            UUID playerId = player.getUUID();
            long connection = runtime.connection(playerId);
            if (connection == 0) return;
            runtime.teams().join(request.schematicId(), request.materialId(), identity,
                    request.inventoryCounts().keySet(), () -> runtime.connection(playerId) == connection).thenCompose(joined -> {
                if (!joined || connection != runtime.connection(playerId)) return CompletableFuture.completedFuture(null);
                long count = request.inventoryCounts().getOrDefault(request.materialId(), 0);
                return runtime.reportInventory(request.schematicId(), request.materialId(), identity, count);
            }).whenComplete((ignored, error) -> execute(server, runtime, () -> {
                ServerPlayer current = currentPlayer(server, runtime, playerId, connection);
                if (current == null) return;
                if (error != null) failed(current, error);
                else runtime.requestBroadcast(request.schematicId());
            }));
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId()) || request.materialId() <= 0) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            UUID playerId = context.player().getUUID();
            long connection = runtime.connection(playerId);
            runtime.leave(request.schematicId(), request.materialId(), identity(context.player()))
                    .whenComplete((changed, error) -> execute(context.server(), runtime, () -> {
                        ServerPlayer current = currentPlayer(context.server(), runtime, playerId, connection);
                        if (current == null) return;
                        if (error != null) failed(current, error);
                        else runtime.requestBroadcast(request.schematicId());
                    }));
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId()) || request.materialId() <= 0 || request.count() < 0) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            UUID playerId = context.player().getUUID();
            long connection = runtime.connection(playerId);
            runtime.reportInventory(request.schematicId(), request.materialId(), identity(context.player()), request.count())
                    .whenComplete((ignored, error) -> execute(context.server(), runtime, () -> {
                        if (currentPlayer(context.server(), runtime, playerId, connection) == null) return;
                        if (error != null) SyncMaterial.LOGGER.debug("忽略未认领或失效材料的背包更新", error);
                        else runtime.requestBroadcast(request.schematicId());
                    }));
        });

        FoundationAreaNetworkAdapter.register();
        FoundationTeamNetworkAdapter.register();
    }

    static boolean allowed(ServerPlayer player, String projectId) {
        return ModNetworkHandler.validateHandshakedPlayer(player) && ModNetworkHandler.validateSchematicId(projectId);
    }

    private static PlayerIdentity identity(ServerPlayer player) {
        return new PlayerIdentity(player.getUUID(), player.getName().getString());
    }

    private static CompletableFuture<Optional<ProjectData>> read(ServerRuntime runtime, String projectId) {
        return runtime.store().snapshot(projectId).thenCompose(state -> {
            if (state.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
            var value = state.get();
            ProjectSnapshot project = value.project();
            if (!runtime.isMaterialSnapshotCurrent(project)) throw new StaleMaterialSnapshotException();
            return runtime.teams().state(projectId).thenApply(team -> team.map(roles ->
                    new ProjectData(project, value.claims(), value.inventory(), roles,
                            runtime.teamRequests().nextSequence(projectId))));
        });
    }

    private static List<MaterialEntry> materialEntries(ProjectSnapshot project) {
        List<MaterialEntry> result = new ArrayList<>();
        for (MaterialSnapshot material : project.materials()) {
            checkedCount(material.required());
            var key = McCompat.tryParseId(material.itemId());
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
                throw new IllegalArgumentException("Unknown material: " + material.itemId());
            }
            result.add(new MaterialEntry(material.id(), new ItemStack(BuiltInRegistries.ITEM.getValue(key)), material.required()));
        }
        return List.copyOf(result);
    }

    private static List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshness(ServerRuntime runtime, ProjectData data) {
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> warnings = data.inventory().freshness().stream()
                .map(warning -> new CollaborationStatusS2CPacket.AreaFreshnessInfo(warning.kind().wireName(),
                        warning.areaId(), warning.areaName(), warning.status())).toList();
        if (warnings.isEmpty() && (!data.inventory().known() || !runtime.areas().isInventoryCurrent(data.project().id(), data.inventory()))) {
            return List.of(new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging_area", 0, "区域库存", "区域观测正在刷新，准备状态未知"));
        }
        return warnings;
    }

    private static List<CollaborationStatusS2CPacket> statuses(ServerRuntime runtime, ProjectData data) {
        List<CollaborationStatusS2CPacket> result = new ArrayList<>();
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> warnings = freshness(runtime, data);
        for (MaterialSnapshot material : data.project().materials()) {
            List<CollaborationStatusS2CPacket.ParticipantInfo> participants = data.claims().stream()
                    .filter(claim -> claim.materialId() == material.id())
                    .map(claim -> new CollaborationStatusS2CPacket.ParticipantInfo(claim.player().name(),
                            checkedCount(runtime.inventoryCount(claim.player().uuid(), data.project().id(), material.id()))))
                    .toList();
            checkedCount(participants.stream().mapToLong(CollaborationStatusS2CPacket.ParticipantInfo::count).sum());
            result.add(new CollaborationStatusS2CPacket(data.project().id(), material.id(), checkedCount(material.required()),
                    checkedCount(data.inventory().staging().getOrDefault(material.itemId(), 0L)),
                    checkedCount(data.inventory().warehouse().getOrDefault(material.itemId(), 0L)), participants, warnings));
        }
        return List.copyOf(result);
    }

    private static void sendInventory(ServerRuntime runtime, ServerPlayer player, ProjectData data,
                                      long catalogRevision, long sequence) {
        if (!ProtocolHandshake.supports(player, ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) return;
        List<AreaInventorySnapshotS2CPacket.MaterialInventory> counts = data.project().materials().stream()
                .map(material -> new AreaInventorySnapshotS2CPacket.MaterialInventory(material.id(),
                        checkedCount(data.inventory().staging().getOrDefault(material.itemId(), 0L)),
                        checkedCount(data.inventory().warehouse().getOrDefault(material.itemId(), 0L)))).toList();
        var warnings = freshness(runtime, data);
        send(player, new AreaInventorySnapshotS2CPacket(data.project().id(), catalogRevision, sequence,
                data.inventory().known() && warnings.isEmpty(), counts, warnings));
    }

    public static void broadcast(MinecraftServer server, String projectId) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null || !runtime.isOpen()) return;
        Map<UUID, FoundationDeliveryTracker.ProjectToken> tokens = new HashMap<>();
        for (UUID playerId : runtime.subscribers(projectId)) {
            long connection = runtime.connection(playerId);
            if (connection > 0) tokens.put(playerId, delivery(runtime).beginProject(playerId, connection, projectId, false));
        }
        if (tokens.isEmpty()) return;
        read(runtime, projectId).whenComplete((data, error) -> execute(server, runtime, () -> {
            for (var token : tokens.values()) {
                ServerPlayer player = currentPlayer(server, runtime, token.playerId(), token.connection());
                if (player == null || !runtime.subscribers(projectId).contains(token.playerId())
                        || !delivery(runtime).acceptProject(token)) continue;
                if (error != null) {
                    failed(player, error);
                    continue;
                }
                if (data.isEmpty()) {
                    send(player, new StagingAreaConfigResponseS2CPacket("SCHEMATIC_DELETED", projectId, "", true, "", List.of()));
                    delivery(runtime).forgetProject(token.playerId(), token.connection(), projectId);
                    runtime.unsubscribe(token.playerId(), projectId);
                    FoundationAreaNetworkAdapter.sendWarehouseContainers(server, runtime, player);
                    FoundationAreaNetworkAdapter.refreshWarehouseAreas(server, runtime, player);
                    continue;
                }
                try {
                    deliverUpdate(runtime, player, data.get(), token);
                    if (delivery(runtime).hasWarehouseSubscription(token.playerId(), token.connection(), projectId)) {
                        FoundationAreaNetworkAdapter.sendWarehouseContainers(server, runtime, player);
                    }
                } catch (IllegalArgumentException | StaleMaterialSnapshotException limit) {
                    failed(player, limit);
                }
            }
        }));
    }

    private static void deliverUpdate(ServerRuntime runtime, ServerPlayer player, ProjectData data,
                                      FoundationDeliveryTracker.ProjectToken token) {
        ProjectSnapshot project = data.project();
        if (!runtime.isMaterialSnapshotCurrent(project)) throw new StaleMaterialSnapshotException();
        List<MaterialEntry> entries = materialEntries(project);
        var revision = runtime.catalogs().changed(token.playerId(), token.connection(), project);
        if (revision.isPresent()) {
            if (ProtocolHandshake.supports(player, ProtocolVersion.MATERIAL_CATALOG_UPDATES)) {
                send(player, new MaterialCatalogUpdateS2CPacket(project.id(), project.name(), revision.getAsLong(), entries));
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("原理图材料已更新，请重新打开材料清单"));
            }
        }
        for (var status : statuses(runtime, data)) send(player, status);
        var current = runtime.catalogs().currentRevision(token.playerId(), project.id(), token.connection());
        if (current.isPresent()) sendInventory(runtime, player, data, current.getAsLong(), token.sequence());
    }

    private static void sendSnapshot(MinecraftServer server, ServerRuntime runtime, String projectId, ServerPlayer player) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0) return;
        var token = delivery(runtime).beginProject(player.getUUID(), connection, projectId, false);
        read(runtime, projectId).whenComplete((data, error) -> execute(server, runtime, () -> {
            ServerPlayer current = currentPlayer(server, runtime, token.playerId(), connection);
            if (current == null || !runtime.subscribers(projectId).contains(token.playerId())
                    || !delivery(runtime).acceptProject(token)) return;
            if (error != null) failed(current, error);
            else if (data.isPresent()) {
                try {
                    deliverUpdate(runtime, current, data.get(), token);
                } catch (IllegalArgumentException | StaleMaterialSnapshotException limit) {
                    failed(current, limit);
                }
            }
        }));
    }

    public static void sendInitialData(MinecraftServer server, ServerPlayer player) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime != null) FoundationAreaNetworkAdapter.sendInitialData(server, runtime, player);
    }

    public static void onPlayerDisconnect(ServerRuntime runtime, UUID playerId) {
        delivery(runtime).disconnected(playerId);
    }

    static synchronized FoundationDeliveryTracker delivery(ServerRuntime runtime) {
        return DELIVERIES.computeIfAbsent(runtime, ignored -> new FoundationDeliveryTracker());
    }

    static ServerPlayer currentPlayer(MinecraftServer server, ServerRuntime runtime, UUID playerId, long connection) {
        if (connection <= 0 || connection != runtime.connection(playerId)) return null;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.connection != null && ProtocolHandshake.hasHandshaked(player) ? player : null;
    }

    static void execute(MinecraftServer server, ServerRuntime runtime, Runnable action) {
        if (!runtime.isOpen()) return;
        server.execute(() -> {
            if (runtime.isOpen() && ServerRuntimeRegistry.get(server) == runtime) action.run();
        });
    }

    private static int checkedCount(long count) {
        if (count < 0 || count > Integer.MAX_VALUE) throw new IllegalArgumentException("Legacy client cannot represent this material count");
        return (int) count;
    }

    static void send(ServerPlayer player, CustomPacketPayload packet) {
        if (player.connection != null && ProtocolHandshake.hasHandshaked(player)) ServerPlayNetworking.send(player, packet);
    }

    private static void failed(ServerPlayer player, Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof StaleMaterialSnapshotException) {
            if (player.connection != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "该原理图的材料统计已过期，服务端正在重新解析，请稍后重新打开材料清单"));
            return;
        }
        SyncMaterial.LOGGER.warn("实验后端请求失败", error);
        if (player.connection != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("实验后端请求失败，请查看服务端日志"));
    }

    /** 已提交材料快照与当前内容或解释不一致；旧数据不能冒充当前统计。 */
    private static final class StaleMaterialSnapshotException extends RuntimeException {
        StaleMaterialSnapshotException() {
            super("stale material snapshot", null, false, false);
        }
    }

    private record ProjectData(ProjectSnapshot project, List<ClaimSnapshot> claims, AreaInventoryView inventory,
                               net.syncmaterial.syncmaterial.core.TeamState team, long teamSequence) {}
}
