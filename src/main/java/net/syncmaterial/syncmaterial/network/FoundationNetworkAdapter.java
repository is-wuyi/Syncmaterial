package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

public final class FoundationNetworkAdapter {
    private static final List<CollaborationStatusS2CPacket.AreaFreshnessInfo> UNKNOWN_STAGING = List.of(
            new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging_area", 0, "备货区", "尚未接入区域扫描，库存未知"));

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
            read(runtime, request.schematicId()).whenComplete((snapshot, error) -> execute(server, runtime, () -> {
                if (connection != runtime.connection(player.getUUID())) return;
                if (error != null) {
                    failed(player, error);
                    return;
                }
                if (snapshot.isEmpty()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("原理图尚未就绪，请稍后重试"));
                    return;
                }
                try {
                    ProjectData data = snapshot.get();
                    var project = data.project();
                    boolean mainOwner = isOwner(project.owner(), player);
                    boolean isOwner = mainOwner || project.deputies().contains(player.getName().getString());
                    List<MaterialEntry> materials = materialEntries(project);
                    List<CollaborationStatusS2CPacket> statuses = statuses(runtime, data);
                    runtime.subscribe(player.getUUID(), project.id());
                    send(player, new MaterialStatsResponseS2CPacket(project.id(), project.name(), materials,
                            isOwner, mainOwner, project.owner().name(), project.deputies(), project.allowSelfClaim()));
                    long deliveryRevision = runtime.catalogs().sent(player.getUUID(), connection, project);
                    if (ProtocolHandshake.supports(player, ProtocolVersion.MATERIAL_CATALOG_UPDATES)) {
                        send(player, new MaterialCatalogUpdateS2CPacket(project.id(), project.name(), deliveryRevision, materials));
                    } else {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "实验后端：旧客户端显示的收集完成不代表有效备货；静止超过 10 秒的背包库存不计入可用量。更新客户端可支持心跳与清单原地更新"));
                    }
                    for (var status : statuses) send(player, status);
                } catch (Exception conversionError) {
                    failed(player, conversionError);
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
            if (runtime != null) runtime.unsubscribe(context.player().getUUID(), request.schematicId());
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
            long connection = runtime.connection(player.getUUID());
            runtime.store().findProject(request.schematicId()).thenCompose(project -> {
                if (project.isEmpty()) return CompletableFuture.completedFuture(false);
                ProjectSnapshot value = project.get();
                if (!runtime.isMaterialSnapshotCurrent(value)) {
                    return CompletableFuture.failedFuture(new StaleMaterialSnapshotException());
                }
                if (!value.allowSelfClaim() && !isOwner(value.owner(), identity)
                        && !value.deputies().contains(identity.name())) return CompletableFuture.completedFuture(false);
                if (request.inventoryCounts().keySet().stream().anyMatch(id -> value.materials().stream().noneMatch(material -> material.id() == id))) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Inventory material does not belong to this project"));
                }
                return runtime.store().join(request.schematicId(), request.materialId(), identity);
            }).thenCompose(joined -> {
                if (!joined || connection == 0 || connection != runtime.connection(player.getUUID())) {
                    return CompletableFuture.completedFuture(null);
                }
                long count = request.inventoryCounts().getOrDefault(request.materialId(), 0);
                return runtime.reportInventory(request.schematicId(), request.materialId(), identity, count);
            }).whenComplete((ignored, error) -> execute(server, runtime, () -> {
                if (error != null) failed(player, error);
                else runtime.requestBroadcast(request.schematicId());
            }));
        });

        ServerPlayNetworking.registerGlobalReceiver(LeaveCollaborationC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId()) || request.materialId() <= 0) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            runtime.leave(request.schematicId(), request.materialId(), identity(context.player()))
                    .whenComplete((changed, error) -> execute(context.server(), runtime, () -> {
                        if (error != null) failed(context.player(), error);
                        else runtime.requestBroadcast(request.schematicId());
                    }));
        });

        ServerPlayNetworking.registerGlobalReceiver(InventoryUpdateC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId()) || request.materialId() <= 0 || request.count() < 0) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            runtime.reportInventory(request.schematicId(), request.materialId(), identity(context.player()), request.count())
                    .whenComplete((ignored, error) -> execute(context.server(), runtime, () -> {
                        if (error != null) SyncMaterial.LOGGER.debug("忽略未认领或失效材料的背包更新", error);
                        else runtime.requestBroadcast(request.schematicId());
                    }));
        });

        ServerPlayNetworking.registerGlobalReceiver(StagingAreaConfigC2SPacket.ID, (request, context) -> {
            if (!ModNetworkHandler.validateHandshakedPlayer(context.player())
                    || !ModNetworkHandler.validateStagingAction(request.action())
                    || (!ModNetworkHandler.isGlobalWarehouseAction(request.action())
                    && !ModNetworkHandler.validateSchematicId(request.schematicId()))) return;
            send(context.player(), new StagingAreaConfigResponseS2CPacket(request.action(), request.schematicId(), "",
                    false, "当前实验后端尚未接入区域配置或扫描", List.of()));
        });
        ServerPlayNetworking.registerGlobalReceiver(RescanStagingAreaC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId())) return;
            send(context.player(), new RescanStagingAreaResponseS2CPacket(false, "当前实验后端尚未接入区域扫描"));
        });
        ServerPlayNetworking.registerGlobalReceiver(WarehouseContainerRequestC2SPacket.ID, (request, context) -> {
            if (!allowed(context.player(), request.schematicId())) return;
            context.player().sendSystemMessage(net.minecraft.network.chat.Component.literal("当前实验后端尚未接入仓库库存"));
            send(context.player(), new WarehouseContainerResponseS2CPacket(List.of()));
        });
        ServerPlayNetworking.registerGlobalReceiver(OwnerActionC2SPacket.ID, (request, context) -> {
            if (allowed(context.player(), request.schematicId())) {
                context.player().sendSystemMessage(net.minecraft.network.chat.Component.literal("当前实验后端尚未接入负责人管理"));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BatchAssignC2SPacket.ID, (request, context) -> {
            if (allowed(context.player(), request.schematicId())) send(context.player(), new BatchAssignResponseS2CPacket(false, "当前实验后端尚未接入负责人管理"));
        });
        ServerPlayNetworking.registerGlobalReceiver(KickFromMaterialC2SPacket.ID, (request, context) -> {
            if (allowed(context.player(), request.schematicId())) send(context.player(), new KickFromMaterialResponseS2CPacket(false, "当前实验后端尚未接入负责人管理"));
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2SPacket.ID, (request, context) -> {
            if (allowed(context.player(), request.schematicId())) send(context.player(), new PlayerListResponseS2CPacket(List.of()));
        });
    }

    private static boolean allowed(ServerPlayer player, String projectId) {
        return ModNetworkHandler.validateHandshakedPlayer(player) && ModNetworkHandler.validateSchematicId(projectId);
    }

    private static PlayerIdentity identity(ServerPlayer player) {
        return new PlayerIdentity(player.getUUID(), player.getName().getString());
    }

    private static boolean isOwner(PlayerIdentity owner, ServerPlayer player) {
        return isOwner(owner, identity(player));
    }

    private static boolean isOwner(PlayerIdentity owner, PlayerIdentity player) {
        return owner.uuid() != null ? owner.uuid().equals(player.uuid()) : owner.name().equals(player.name());
    }

    private static CompletableFuture<Optional<ProjectData>> read(ServerRuntime runtime, String projectId) {
        return runtime.store().snapshot(projectId).thenApply(state -> state.map(value -> {
            ProjectSnapshot project = value.project();
            // 内容 hash 和材料解释必须均匹配最新公告，失败重解析留下的旧统计不可复用。
            if (!runtime.isMaterialSnapshotCurrent(project)) {
                throw new StaleMaterialSnapshotException();
            }
            return new ProjectData(project, value.claims());
        }));
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

    private static List<CollaborationStatusS2CPacket> statuses(ServerRuntime runtime, ProjectData data) {
        List<CollaborationStatusS2CPacket> result = new ArrayList<>();
        for (MaterialSnapshot material : data.project().materials()) {
            List<CollaborationStatusS2CPacket.ParticipantInfo> participants = data.claims().stream()
                    .filter(claim -> claim.materialId() == material.id())
                    .map(claim -> new CollaborationStatusS2CPacket.ParticipantInfo(claim.player().name(),
                            checkedCount(runtime.inventoryCount(claim.player().uuid(), data.project().id(), material.id()))))
                    .toList();
            checkedCount(participants.stream().mapToLong(CollaborationStatusS2CPacket.ParticipantInfo::count).sum());
            result.add(new CollaborationStatusS2CPacket(data.project().id(), material.id(), checkedCount(material.required()),
                    0, 0, participants, UNKNOWN_STAGING));
        }
        return result;
    }

    public static void broadcast(MinecraftServer server, String projectId) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null || !runtime.isOpen()) return;
        read(runtime, projectId).whenComplete((data, error) -> execute(server, runtime, () -> {
            if (error != null) {
                SyncMaterial.LOGGER.warn("新后端材料快照查询失败", error);
                for (UUID playerId : runtime.subscribers(projectId)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) failed(player, error);
                }
                return;
            }
            if (data.isEmpty()) {
                for (UUID playerId : runtime.subscribers(projectId)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        send(player, new StagingAreaConfigResponseS2CPacket("SCHEMATIC_DELETED", projectId, "", true, "", List.of()));
                    }
                    runtime.unsubscribe(playerId, projectId);
                }
                return;
            }
            try {
                ProjectSnapshot project = data.get().project();
                List<MaterialEntry> entries = materialEntries(project);
                List<CollaborationStatusS2CPacket> statuses = statuses(runtime, data.get());
                for (UUID playerId : runtime.subscribers(projectId)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) continue;
                    var deliveryRevision = runtime.catalogs().changed(playerId, runtime.connection(playerId), project);
                    if (deliveryRevision.isPresent()) {
                        if (ProtocolHandshake.supports(player, ProtocolVersion.MATERIAL_CATALOG_UPDATES)) {
                            send(player, new MaterialCatalogUpdateS2CPacket(projectId, project.name(), deliveryRevision.getAsLong(), entries));
                        } else {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("原理图材料已更新，请重新打开材料清单"));
                        }
                    }
                    for (var status : statuses) send(player, status);
                }
            } catch (IllegalArgumentException limit) {
                SyncMaterial.LOGGER.warn("实验后端无法发送材料目录", limit);
                for (UUID playerId : runtime.subscribers(projectId)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) failed(player, limit);
                }
            }
        }));
    }

    private static void sendSnapshot(MinecraftServer server, ServerRuntime runtime, String projectId, ServerPlayer player) {
        read(runtime, projectId).whenComplete((data, error) -> execute(server, runtime, () -> {
            if (error != null) failed(player, error);
            else if (data.isPresent()) {
                try {
                    for (var status : statuses(runtime, data.get())) send(player, status);
                } catch (IllegalArgumentException limit) {
                    failed(player, limit);
                }
            }
        }));
    }

    private static void execute(MinecraftServer server, ServerRuntime runtime, Runnable action) {
        if (!runtime.isOpen()) return;
        server.execute(() -> {
            if (runtime.isOpen() && ServerRuntimeRegistry.get(server) == runtime) action.run();
        });
    }

    private static int checkedCount(long count) {
        if (count < 0 || count > Integer.MAX_VALUE) throw new IllegalArgumentException("Legacy client cannot represent this material count");
        return (int) count;
    }

    private static void send(ServerPlayer player, CustomPacketPayload packet) {
        if (player.connection != null && ProtocolHandshake.hasHandshaked(player)) ServerPlayNetworking.send(player, packet);
    }

    private static void failed(ServerPlayer player, Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof StaleMaterialSnapshotException) {
            // 只给安全的通用诊断：不泄露 hash/解释版本/数据库细节，也不发任何当前统计。
            if (player.connection != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "该原理图的材料统计已过期，服务端正在重新解析，请稍后重新打开材料清单"));
            }
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

    private record ProjectData(ProjectSnapshot project, List<ClaimSnapshot> claims) {}
}
