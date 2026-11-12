package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.AreaSpec;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/** Frozen area control packets bound to the Foundation store and passive scanner. */
final class FoundationAreaNetworkAdapter {
    private static final String OVERLAP_WARNING = AreaOverlapStatus.MESSAGE_KEY;

    private FoundationAreaNetworkAdapter() {}

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(StagingAreaConfigC2SPacket.ID, (request, context) -> {
            ServerPlayer player = context.player();
            if (!ModNetworkHandler.validateHandshakedPlayer(player)
                    || !ModNetworkHandler.validateStagingAction(request.action())
                    || request.schematicId() == null || request.schematicId().length() > 100
                    || (!ModNetworkHandler.isGlobalWarehouseAction(request.action())
                    && !ModNetworkHandler.validateSchematicId(request.schematicId()))) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            configure(context.server(), runtime, player, request);
        });
        ServerPlayNetworking.registerGlobalReceiver(RescanStagingAreaC2SPacket.ID, (request, context) -> {
            if (!FoundationNetworkAdapter.allowed(context.player(), request.schematicId())) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            long connection = runtime.connection(context.player().getUUID());
            UUID playerId = context.player().getUUID();
            runtime.areas().refreshConfiguration().thenCompose(ignored -> runtime.areas().rescan(request.schematicId()))
                    .whenComplete((result, error) -> FoundationNetworkAdapter.execute(context.server(), runtime, () -> {
                        ServerPlayer player = FoundationNetworkAdapter.currentPlayer(context.server(), runtime, playerId, connection);
                        if (player == null) return;
                        if (error != null) {
                            logFailure(error);
                            FoundationNetworkAdapter.send(player, new RescanStagingAreaResponseS2CPacket(false, "区域刷新失败，请查看服务端日志"));
                        } else {
                            FoundationNetworkAdapter.send(player, new RescanStagingAreaResponseS2CPacket(result.success(), result.message()));
                            runtime.requestBroadcast(request.schematicId());
                        }
                    }));
        });
        ServerPlayNetworking.registerGlobalReceiver(WarehouseContainerRequestC2SPacket.ID, (request, context) -> {
            if (!FoundationNetworkAdapter.allowed(context.player(), request.schematicId())) return;
            ServerRuntime runtime = ServerRuntimeRegistry.get(context.server());
            if (runtime == null) return;
            long connection = runtime.connection(context.player().getUUID());
            if (connection == 0) return;
            FoundationNetworkAdapter.delivery(runtime).subscribeWarehouse(context.player().getUUID(), connection,
                    request.schematicId(), request.subscribe());
            sendWarehouseContainers(context.server(), runtime, context.player());
        });
    }

    private static void configure(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                                  StagingAreaConfigC2SPacket request) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0) return;
        UUID playerId = player.getUUID();
        boolean listing = request.action().equals("LIST") || request.action().equals("LIST_WAREHOUSES")
                || request.action().equals("LIST_WAREHOUSE_REFS");
        String defaultWorld = request.action().equals("ADD") ? McCompat.dimensionId(player.level()) : "minecraft:overworld";
        runtime.store().areaCatalog().thenCompose(before -> {
            if (!ModNetworkHandler.isGlobalWarehouseAction(request.action())) requireProject(before, request.schematicId());
            if (listing) return CompletableFuture.completedFuture(new ConfigurationResult(before, before));
            return mutate(runtime, before, request, defaultWorld)
                    .thenCompose(ignored -> refreshConfiguration(server, runtime))
                    .thenCompose(ignored -> runtime.store().areaCatalog())
                    .thenApply(after -> new ConfigurationResult(before, after));
        }).whenComplete((result, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection);
            if (error != null) {
                logFailure(error);
                if (current != null) {
                    FoundationNetworkAdapter.send(current, new StagingAreaConfigResponseS2CPacket(request.action(),
                            request.schematicId(), "", false, "区域操作失败：目标不存在、范围不匹配或参数非法", List.of()));
                }
                return;
            }
            if (current != null) {
                if (!ModNetworkHandler.isGlobalWarehouseAction(request.action())) runtime.subscribe(playerId, request.schematicId());
                sendConfigurationResult(runtime, current, result.after(), request);
                if (listing) sendWarehouseAreas(runtime, current, result.after());
            }
            if (!listing) publishConfiguration(server, runtime, result.before(), result.after());
        }));
    }

    private static CompletableFuture<Void> refreshConfiguration(MinecraftServer server, ServerRuntime runtime) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        server.execute(() -> {
            if (!runtime.isOpen() || ServerRuntimeRegistry.get(server) != runtime) {
                completion.completeExceptionally(new IllegalStateException("Area runtime is closed"));
                return;
            }
            runtime.areas().refreshConfiguration().whenComplete((ignored, error) -> {
                if (error == null) completion.complete(null);
                else completion.completeExceptionally(error);
            });
        });
        return completion;
    }

    private static CompletableFuture<?> mutate(ServerRuntime runtime, AreaCatalog catalog,
                                                StagingAreaConfigC2SPacket request, String defaultWorld) {
        String projectId = request.schematicId();
        int areaId = request.areaId();
        return switch (request.action()) {
            case "ADD" -> runtime.store().addArea(projectId, AreaKind.STAGING_AREA,
                    spec(request, defaultWorld));
            case "UPDATE" -> runtime.store().updateArea(projectId, AreaKind.STAGING_AREA, areaId,
                    spec(request, existing(catalog, projectId, AreaKind.STAGING_AREA, areaId).world()));
            case "RENAME" -> runtime.store().renameArea(projectId, areaId, data(request).name());
            case "DELETE" -> runtime.store().removeArea(projectId, AreaKind.STAGING_AREA, areaId).thenApply(removed -> {
                if (!removed) throw new IllegalArgumentException("Area does not belong to this project");
                return removed;
            });
            case "CLEAR" -> runtime.store().clearStagingAreas(projectId);
            case "ADD_WAREHOUSE" -> runtime.store().addArea(null, AreaKind.WAREHOUSE,
                    spec(request, "minecraft:overworld"));
            case "UPDATE_WAREHOUSE" -> runtime.store().updateArea(null, AreaKind.WAREHOUSE, areaId,
                    spec(request, existing(catalog, null, AreaKind.WAREHOUSE, areaId).world()));
            case "DELETE_WAREHOUSE" -> runtime.store().removeArea(null, AreaKind.WAREHOUSE, areaId).thenApply(removed -> {
                if (!removed) throw new IllegalArgumentException("Warehouse does not exist");
                return removed;
            });
            case "ADD_WAREHOUSE_REF" -> runtime.store().addWarehouseReference(projectId, areaId);
            case "REMOVE_WAREHOUSE_REF" -> runtime.store().removeWarehouseReference(projectId, areaId);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown area action"));
        };
    }

    private static StagingAreaConfigC2SPacket.AreaData data(StagingAreaConfigC2SPacket request) {
        var data = request.areaData().orElseThrow(() -> new IllegalArgumentException("Missing area data"));
        if (data.name() == null || data.name().isBlank() || data.name().length() > 128) {
            throw new IllegalArgumentException("Invalid area name");
        }
        return data;
    }

    private static AreaSpec spec(StagingAreaConfigC2SPacket request, String defaultWorld) {
        var data = data(request);
        String world = data.world().orElse(defaultWorld);
        if (world == null || McCompat.tryParseId(world) == null) throw new IllegalArgumentException("Invalid dimension id");
        return new AreaSpec(data.name(), world,
                new AreaBounds(data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2()));
    }

    private static AreaDefinition existing(AreaCatalog catalog, String projectId, AreaKind kind, int id) {
        return catalog.areas().stream().filter(area -> area.id() == id && area.kind() == kind
                && java.util.Objects.equals(area.projectId(), projectId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Area does not exist in this scope"));
    }

    private static void requireProject(AreaCatalog catalog, String projectId) {
        if (!catalog.projectNames().containsKey(projectId)) throw new IllegalArgumentException("Project does not exist");
    }

    private static void sendConfigurationResult(ServerRuntime runtime, ServerPlayer player, AreaCatalog catalog,
                                                StagingAreaConfigC2SPacket request) {
        if (ModNetworkHandler.isGlobalWarehouseAction(request.action())) {
            if (!request.action().equals("LIST_WAREHOUSES")) {
                FoundationNetworkAdapter.send(player, new StagingAreaConfigResponseS2CPacket(request.action(),
                        request.schematicId(), "", true, "仓库配置已保存，扫描将按预算刷新已加载区块", List.of()));
            }
            sendList(runtime, player, catalog, "LIST_WAREHOUSES", request.schematicId());
        } else if (request.action().endsWith("WAREHOUSE_REF") || request.action().equals("LIST_WAREHOUSE_REFS")) {
            sendList(runtime, player, catalog, "LIST_WAREHOUSE_REFS", request.schematicId());
        } else {
            sendList(runtime, player, catalog, "LIST", request.schematicId());
        }
    }

    static void sendInitialData(MinecraftServer server, ServerRuntime runtime, ServerPlayer player) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0) return;
        UUID playerId = player.getUUID();
        runtime.store().areaCatalog().whenComplete((catalog, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection);
            if (current == null) return;
            if (error != null) {
                logFailure(error);
                return;
            }
            for (String projectId : catalog.projectNames().keySet()) sendList(runtime, current, catalog, "LIST", projectId);
            sendWarehouseAreas(runtime, current, catalog);
        }));
    }

    private static void publishConfiguration(MinecraftServer server, ServerRuntime runtime,
                                             AreaCatalog before, AreaCatalog after) {
        Set<String> projects = new HashSet<>(before.projectNames().keySet());
        projects.addAll(after.projectNames().keySet());
        for (String projectId : projects) {
            runtime.requestBroadcast(projectId);
            for (UUID playerId : runtime.subscribers(projectId)) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && after.projectNames().containsKey(projectId)) {
                    sendList(runtime, player, after, "LIST", projectId);
                    sendList(runtime, player, after, "LIST_WAREHOUSE_REFS", projectId);
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendWarehouseAreas(runtime, player, after);
    }

    private static void sendList(ServerRuntime runtime, ServerPlayer player, AreaCatalog catalog,
                                 String action, String projectId) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0) return;
        boolean global = action.equals("LIST_WAREHOUSES");
        String scope = global ? "W:" + projectId : "S:" + action + ":" + projectId;
        if (!FoundationNetworkAdapter.delivery(runtime).acceptConfiguration(player.getUUID(), connection, scope, catalog.revision())) return;
        List<AreaDefinition> areas;
        if (global) areas = warehouses(catalog);
        else if (action.equals("LIST_WAREHOUSE_REFS")) areas = referencedWarehouses(catalog, projectId);
        else areas = staging(catalog, projectId);
        List<AreaDefinition> overlapAreas = global && (projectId == null || projectId.isBlank())
                ? areas : projectAreas(catalog, projectId);
        String message = overlaps(overlapAreas)
                ? (ProtocolHandshake.supports(player, ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)
                ? OVERLAP_WARNING : "区域有重叠；同一原理图内库存仅计一次（备货区优先）") : "";
        FoundationNetworkAdapter.send(player, new StagingAreaConfigResponseS2CPacket(action,
                projectId == null ? "" : projectId, catalog.projectNames().getOrDefault(projectId, ""),
                true, message, infos(areas)));
    }

    private static void sendWarehouseAreas(ServerRuntime runtime, ServerPlayer player, AreaCatalog catalog) {
        if (!ProtocolHandshake.hasHandshaked(player)) return;
        long connection = runtime.connection(player.getUUID());
        if (connection == 0 || !FoundationNetworkAdapter.delivery(runtime).acceptConfiguration(
                player.getUUID(), connection, "warehouse-world", catalog.revision())) return;
        Set<Integer> references = new HashSet<>();
        for (var entry : catalog.warehouseReferences().entrySet()) {
            if (runtime.subscribers(entry.getKey()).contains(player.getUUID())) references.addAll(entry.getValue());
        }
        FoundationNetworkAdapter.send(player, new WarehouseAreaResponseS2CPacket(infos(warehouses(catalog)),
                references.stream().sorted().toList()));
    }

    static void refreshWarehouseAreas(MinecraftServer server, ServerRuntime runtime, ServerPlayer player) {
        long connection = runtime.connection(player.getUUID());
        UUID playerId = player.getUUID();
        if (connection == 0) return;
        runtime.store().areaCatalog().whenComplete((catalog, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection);
            if (current != null && error == null) sendWarehouseAreas(runtime, current, catalog);
            else if (error != null) logFailure(error);
        }));
    }

    static void sendWarehouseContainers(MinecraftServer server, ServerRuntime runtime, ServerPlayer player) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0 || player.level() == null) return;
        var token = FoundationNetworkAdapter.delivery(runtime).beginWarehouse(player.getUUID(), connection,
                McCompat.dimensionId(player.level()));
        CompletableFuture<List<ContainerObservation>> observations = CompletableFuture.completedFuture(List.of());
        for (String projectId : token.projects()) {
            observations = observations.thenCombine(runtime.store().warehouseContainers(projectId, token.world()), (left, right) -> {
                List<ContainerObservation> combined = new ArrayList<>(left);
                combined.addAll(right);
                return List.copyOf(combined);
            });
        }
        observations.whenComplete((snapshot, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = FoundationNetworkAdapter.currentPlayer(server, runtime, token.playerId(), token.connection());
            if (current == null || current.level() == null || !token.world().equals(McCompat.dimensionId(current.level()))
                    || !FoundationNetworkAdapter.delivery(runtime).acceptWarehouse(token)) return;
            if (error != null) {
                logFailure(error);
                FoundationNetworkAdapter.send(current, new WarehouseContainerResponseS2CPacket(List.of()));
                return;
            }
            Map<ContainerKey, Set<String>> contents = new LinkedHashMap<>();
            for (var observation : snapshot) {
                if (!token.world().equals(observation.key().world()) || !observation.warning().isBlank()) continue;
                Set<String> items = contents.computeIfAbsent(observation.key(), key -> new HashSet<>());
                observation.counts().forEach((item, count) -> { if (count > 0) items.add(item); });
            }
            List<WarehouseContainerResponseS2CPacket.ContainerEntry> entries = new ArrayList<>();
            for (var entry : contents.entrySet()) {
                if (!entry.getValue().isEmpty()) entries.add(new WarehouseContainerResponseS2CPacket.ContainerEntry(
                        entry.getKey().x(), entry.getKey().y(), entry.getKey().z(), entry.getValue().stream().sorted().toList()));
            }
            FoundationNetworkAdapter.send(current, new WarehouseContainerResponseS2CPacket(List.copyOf(entries)));
        }));
    }

    static List<AreaDefinition> staging(AreaCatalog catalog, String projectId) {
        return catalog.stagingAreas(projectId);
    }

    private static List<AreaDefinition> warehouses(AreaCatalog catalog) {
        return catalog.areas().stream().filter(area -> area.kind() == AreaKind.WAREHOUSE).toList();
    }

    private static List<AreaDefinition> referencedWarehouses(AreaCatalog catalog, String projectId) {
        return catalog.referencedWarehouses(projectId);
    }

    private static List<AreaDefinition> projectAreas(AreaCatalog catalog, String projectId) {
        List<AreaDefinition> areas = new ArrayList<>(staging(catalog, projectId));
        areas.addAll(referencedWarehouses(catalog, projectId));
        return List.copyOf(areas);
    }

    private static boolean overlaps(List<AreaDefinition> areas) {
        for (int left = 0; left < areas.size(); left++) {
            for (int right = left + 1; right < areas.size(); right++) {
                var first = areas.get(left);
                var second = areas.get(right);
                if (first.world().equals(second.world()) && first.bounds().intersects(second.bounds())) return true;
            }
        }
        return false;
    }

    private static List<StagingAreaConfigResponseS2CPacket.AreaInfo> infos(List<AreaDefinition> areas) {
        return areas.stream().map(area -> new StagingAreaConfigResponseS2CPacket.AreaInfo(area.id(), area.name(),
                area.bounds().x1(), area.bounds().y1(), area.bounds().z1(), area.bounds().x2(), area.bounds().y2(),
                area.bounds().z2(), area.world())).toList();
    }

    private static void logFailure(Throwable error) {
        SyncMaterial.LOGGER.warn("实验后端区域请求失败", error);
    }

    private record ConfigurationResult(AreaCatalog before, AreaCatalog after) {}
}
