package net.syncmaterial.syncmaterial.network;

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
import net.syncmaterial.syncmaterial.core.AreaCatalog;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.server.runtime.ProjectProgressRead;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;
import net.syncmaterial.syncmaterial.server.storage.CommittedStorageException;

/** Protocol-5 project views backed by the Foundation writer/read services. */
final class FoundationProjectViewNetworkAdapter {
    private FoundationProjectViewNetworkAdapter() {}

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ProjectViewCommandC2SPacket.ID, (request, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            Object handler = player.connection;
            server.execute(() -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(server);
                if (runtime == null || !runtime.isOpen() || player.connection != handler
                        || !ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_PROJECT_VIEWS)) return;
                handle(server, runtime, player, request);
            });
        });
    }

    private static void handle(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                               ProjectViewCommandC2SPacket request) {
        long connection = runtime.connection(player.getUUID());
        if (connection == 0) return;
        try {
            switch (request.action()) {
                case OPEN -> open(server, runtime, player, request);
                case SET_INTERESTS -> setInterests(server, runtime, player, request);
                case CLOSE -> close(server, runtime, player, request);
                case JOIN, LEAVE -> collaboration(server, runtime, player, request);
                case AREA_CONFIG -> configuration(server, runtime, player, request);
                case RESCAN -> rescan(server, runtime, player, request);
                default -> { }
            }
        } catch (RuntimeException invalid) {
            ServerPlayer current = player(server, runtime, player.getUUID());
            if (current != null) FoundationNetworkAdapter.send(current, ProjectViewPackets.error(
                    request.scope(), request.requestId(), request.viewId(), request.generation(),
                    request.action(), "请求未被当前项目视图接受"));
        }
    }

    private static void open(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                             ProjectViewCommandC2SPacket request) {
        ProjectViewTracker.View view = runtime.views().open(player.getUUID(),
                runtime.connection(player.getUUID()), request.scope(), request.viewId(),
                request.generation(), request.interestMask());
        if (view == null || player(server, runtime, player.getUUID()) == null) return;
        syncLegacySubscription(runtime, view);
        FoundationNetworkAdapter.send(player, ProjectViewPackets.opened(request.scope(), request.requestId(),
                request.viewId(), request.generation(), UUID.randomUUID()));
        sendContent(server, runtime, view, request.interestMask());
    }

    private static void setInterests(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                                     ProjectViewCommandC2SPacket request) {
        long connection = runtime.connection(player.getUUID());
        int previousMask = runtime.views().mask(player.getUUID(), connection, request.scope(),
                request.viewId(), request.generation());
        ProjectViewTracker.View updated = runtime.views().setInterests(player.getUUID(),
                connection, request.scope(), request.viewId(), request.generation(), request.interestMask());
        if (updated == null) return;
        syncLegacySubscription(runtime, updated);
        int added = request.interestMask() & ~previousMask;
        FoundationNetworkAdapter.send(player, ProjectViewPackets.operation(request.scope(), request.requestId(),
                request.viewId(), request.generation(), ProjectViewAction.SET_INTERESTS, true,
                previousMask != request.interestMask(), "interests updated"));
        sendContent(server, runtime, updated, added);
    }

    private static void close(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                              ProjectViewCommandC2SPacket request) {
        UUID playerId = player.getUUID();
        long connection = runtime.connection(playerId);
        boolean changed = runtime.views().close(playerId, connection,
                request.scope(), request.viewId(), request.generation());
        if (!changed) return;
        syncLegacySubscription(runtime, new ProjectViewTracker.View(playerId,
                connection, request.scope(), request.viewId(), request.generation(), 0));
        FoundationNetworkAdapter.send(player, ProjectViewPackets.operation(request.scope(), request.requestId(),
                request.viewId(), request.generation(), ProjectViewAction.CLOSE, true, true, "closed"));
    }

    private static void collaboration(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                                      ProjectViewCommandC2SPacket request) {
        UUID playerId = player.getUUID();
        if (!runtime.views().matches(playerId, runtime.connection(playerId), request.scope(),
                request.viewId(), request.generation())) return;
        var identity = new net.syncmaterial.syncmaterial.core.PlayerIdentity(playerId, player.getName().getString());
        CompletableFuture<CollaborationOutcome> operation;
        if (request.action() == ProjectViewAction.JOIN) {
            Map<Integer, Long> inventory = new LinkedHashMap<>();
            for (var count : request.inventory()) inventory.put(count.materialId(), count.count());
            operation = runtime.collaboration().join(request.scope().projectId(), request.materialId(),
                    identity, inventory);
        } else {
            operation = runtime.collaboration().leave(request.scope().projectId(), request.materialId(), identity);
        }
        operation.whenComplete((outcome, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = player(server, runtime, playerId);
            if (current == null || !runtime.views().matches(playerId, runtime.connection(playerId),
                    request.scope(), request.viewId(), request.generation())) return;
            CollaborationOutcome durable = outcome != null ? outcome : committedOutcome(error);
            if (durable != null) {
                FoundationNetworkAdapter.send(current, ProjectViewPackets.operation(request.scope(),
                        request.requestId(), request.viewId(), request.generation(), durable));
                if (error != null) {
                    FoundationNetworkAdapter.send(current, ProjectViewPackets.invalidated(request.scope(),
                            request.viewId(), request.generation(), "操作已提交，运行时投影失败，请刷新"));
                }
            } else if (error != null) {
                FoundationNetworkAdapter.send(current, ProjectViewPackets.error(request.scope(),
                        request.requestId(), request.viewId(), request.generation(), request.action(),
                        "协作操作未完成，请查看服务端日志"));
            }
        }));
    }

    private static void configuration(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                                      ProjectViewCommandC2SPacket request) {
        UUID playerId = player.getUUID();
        if (!runtime.views().matches(playerId, runtime.connection(playerId), request.scope(),
                request.viewId(), request.generation())) return;
        String projectId = request.scope().isProject() ? request.scope().projectId() : "";
        var legacy = new StagingAreaConfigC2SPacket(projectId, request.areaAction(), request.areaId(),
                request.areaData());
        FoundationAreaNetworkAdapter.configuration(server, runtime, player, legacy)
                .whenComplete((result, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = player(server, runtime, playerId);
            if (current == null || !runtime.views().matches(playerId, runtime.connection(playerId),
                    request.scope(), request.viewId(), request.generation())) return;
            if (error != null) {
                FoundationNetworkAdapter.send(current, ProjectViewPackets.error(request.scope(),
                        request.requestId(), request.viewId(), request.generation(), request.action(),
                        "区域操作失败：目标不存在、范围不匹配或参数非法"));
                return;
            }
            String listAction = listAction(request.scope(), request.areaAction());
            FoundationNetworkAdapter.send(current, ProjectViewPackets.configuration(request.scope(),
                    request.requestId(), request.viewId(), request.generation(), result.after().revision(),
                    ProjectViewAction.AREA_CONFIG, true, "",
                    new ProjectViewS2CPacket.AreaConfigData(listAction,
                            areas(result.after(), request.scope(), listAction))));
            if (!isListing(request.areaAction())) {
                FoundationAreaNetworkAdapter.publishConfiguration(server, runtime, result.before(), result.after());
            }
        }));
    }

    private static void rescan(MinecraftServer server, ServerRuntime runtime, ServerPlayer player,
                               ProjectViewCommandC2SPacket request) {
        UUID playerId = player.getUUID();
        if (!runtime.views().matches(playerId, runtime.connection(playerId), request.scope(),
                request.viewId(), request.generation())) return;
        runtime.areas().rescan(request.scope().projectId(), request.areaId())
                .whenComplete((result, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = player(server, runtime, playerId);
            if (current == null || !runtime.views().matches(playerId, runtime.connection(playerId),
                    request.scope(), request.viewId(), request.generation())) return;
            if (error != null) {
                FoundationNetworkAdapter.send(current, ProjectViewPackets.error(request.scope(),
                        request.requestId(), request.viewId(), request.generation(), request.action(),
                        "区域刷新失败，请查看服务端日志"));
                return;
            }
            FoundationNetworkAdapter.send(current, ProjectViewPackets.operation(request.scope(),
                    request.requestId(), request.viewId(), request.generation(), ProjectViewAction.RESCAN,
                    result.success(), result.success(), result.message()));
            if (result.success()) {
                runtime.requestBroadcast(request.scope().projectId());
                runtime.store().areaCatalog().whenComplete((catalog, ignored) ->
                        FoundationNetworkAdapter.execute(server, runtime, () ->
                                publishConfiguration(server, runtime, request.scope(), catalog)));
            }
        }));
    }

    private static void sendContent(MinecraftServer server, ServerRuntime runtime,
                                    ProjectViewTracker.View view, int addedMask) {
        if ((addedMask & InterestKind.MATERIALS.bit()) != 0 && view.scope().isProject()) {
            broadcast(server, runtime, view.scope().projectId());
        }
        int nonMaterials = addedMask & ~InterestKind.MATERIALS.bit();
        if (nonMaterials == 0) return;
        if (!view.scope().isProject()) {
            sendNonMaterialContent(server, runtime, view, nonMaterials);
            return;
        }
        runtime.readProgress(view.scope().projectId()).whenComplete((read, error) ->
                FoundationNetworkAdapter.execute(server, runtime, () -> {
            if (!runtime.views().matches(view.playerId(), view.connection(), view.scope(),
                    view.viewId(), view.generation())) return;
            if (error != null) {
                sendError(server, runtime, view, "项目读取失败");
                return;
            }
            if (read.isEmpty()) {
                deleteProject(server, runtime, List.of(view));
                return;
            }
            sendNonMaterialContent(server, runtime, view, nonMaterials);
        }));
    }

    private static void sendNonMaterialContent(MinecraftServer server, ServerRuntime runtime,
                                               ProjectViewTracker.View view, int addedMask) {
        if ((addedMask & InterestKind.TEAM.bit()) != 0 && view.scope().isProject()) {
            sendTeam(server, runtime, view);
        }
        int configuration = InterestKind.AREA_CONFIG.bit() | InterestKind.OUTLINES.bit();
        if ((addedMask & configuration) != 0) {
            runtime.store().areaCatalog().whenComplete((catalog, error) ->
                    FoundationNetworkAdapter.execute(server, runtime, () -> {
                        if (error == null) publishConfiguration(server, runtime, view.scope(), catalog);
                    }));
        }
        if ((addedMask & InterestKind.WAREHOUSE_CONTENTS.bit()) != 0 && view.scope().isProject()) {
            sendWarehouse(server, runtime, view);
        }
    }

    static void broadcast(MinecraftServer server, ServerRuntime runtime, String projectId) {
        ProjectViewScope scope = ProjectViewScope.project(projectId);
        List<ProjectViewTracker.View> targets = runtime.views().interestedViews(scope);
        if (targets.isEmpty()) return;
        List<ProjectViewTracker.View> materialTargets = targets.stream()
                .filter(view -> (view.mask() & InterestKind.MATERIALS.bit()) != 0)
                .toList();
        runtime.readProgress(projectId).whenComplete((read, error) ->
                FoundationNetworkAdapter.execute(server, runtime, () -> {
            if (error != null) {
                for (var view : materialTargets) sendError(server, runtime, view, "项目进度读取失败");
                return;
            }
            if (read.isEmpty()) {
                deleteProject(server, runtime, targets);
                return;
            }
            if (!materialTargets.isEmpty()) deliverProgress(server, runtime, materialTargets, read.get());
        }));
    }

    private static void deleteProject(MinecraftServer server, ServerRuntime runtime,
                                      List<ProjectViewTracker.View> targets) {
        for (var view : targets) {
            ServerPlayer player = player(server, runtime, view);
            if (player != null) {
                FoundationNetworkAdapter.send(player, ProjectViewPackets.deleted(view.scope(),
                        view.viewId(), view.generation()));
            }
        }
        for (var view : targets) {
            // Exact close also removes that connection's registry interests. A replacement
            // view has its own existence check and must not be cleared by this older read.
            runtime.views().close(view.playerId(), view.connection(), view.scope(),
                    view.viewId(), view.generation());
        }
    }

    private static void deliverProgress(MinecraftServer server, ServerRuntime runtime,
            List<ProjectViewTracker.View> targets, ProjectProgressRead read) {
        ProjectProgressSnapshot snapshot;
        try {
            var current = runtime.progressOnServer(read);
            if (current.isEmpty()) {
                for (var view : targets) sendInvalidated(server, runtime, view, "在线观测已变化，等待下一版进度");
                return;
            }
            snapshot = current.get();
        } catch (RuntimeException stale) {
            for (var view : targets) sendError(server, runtime, view, "材料目录已过期，请等待重新解析");
            return;
        }
        final UUID snapshotId;
        final List<ProjectProgressFragmenter.Fragment> fragments;
        try {
            snapshotId = UUID.randomUUID();
            fragments = ProjectProgressFragmenter.encode(snapshot);
        } catch (RuntimeException oversized) {
            for (var view : targets) sendError(server, runtime, view, "项目进度超过协议容量，未截断数据");
            return;
        }
        for (var view : targets) {
            ServerPlayer player = player(server, runtime, view);
            if (player == null) continue;
            try {
                var deliveryRevision = runtime.catalogs().changed(view.playerId(), view.connection(),
                        read.state().project());
                if (deliveryRevision.isPresent()) {
                    List<net.syncmaterial.syncmaterial.api.MaterialEntry> catalog = snapshot.materials().stream()
                            .map(material -> new net.syncmaterial.syncmaterial.api.MaterialEntry(material.materialId(),
                                    new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                                            McCompat.tryParseId(material.itemId()))), material.required())).toList();
                    FoundationNetworkAdapter.send(player, new MaterialCatalogUpdateS2CPacket(snapshot.projectId(),
                            snapshot.projectName(), deliveryRevision.getAsLong(), catalog));
                }
                for (var fragment : fragments) {
                    FoundationNetworkAdapter.send(player, ProjectViewPackets.progress(view.scope(),
                            view.viewId(), view.generation(), snapshot, snapshotId, fragment));
                }
            } catch (RuntimeException invalid) {
                sendError(server, runtime, view, "材料目录无法投影，未截断数据");
            }
        }
    }

    private static void sendTeam(MinecraftServer server, ServerRuntime runtime,
                                 ProjectViewTracker.View view) {
        if (!view.scope().isProject()) return;
        runtime.teams().state(view.scope().projectId()).whenComplete((state, error) ->
                FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer player = player(server, runtime, view);
            if (player == null || error != null || state.isEmpty()) return;
            FoundationTeamNetworkAdapter.sendInitialTeam(runtime, player, state.get(),
                    runtime.teamRequests().nextSequence(view.scope().projectId()));
        }));
    }

    static void broadcastTeam(MinecraftServer server, ServerRuntime runtime, String projectId,
                              net.syncmaterial.syncmaterial.core.TeamState state, long sequence,
                              Set<UUID> alreadySent) {
        List<ProjectViewTracker.View> targets = runtime.views().views(projectId, InterestKind.TEAM);
        if (targets.isEmpty()) return;
        for (var view : targets) {
            if (alreadySent.contains(view.playerId())) continue;
            ServerPlayer player = player(server, runtime, view);
            if (player != null) FoundationTeamNetworkAdapter.sendInitialTeam(runtime, player, state, sequence);
        }
    }

    private static void sendWarehouse(MinecraftServer server, ServerRuntime runtime,
                                      ProjectViewTracker.View view) {
        if (!view.scope().isProject()) return;
        ServerPlayer origin = player(server, runtime, view);
        if (origin == null || origin.level() == null) return;
        String world = McCompat.dimensionId(origin.level());
        long sequence = runtime.nextPublicationSequence();
        runtime.store().warehouseContainers(view.scope().projectId(), world)
                .whenComplete((observations, error) -> FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = player(server, runtime, view);
            if (current == null || current.level() == null
                    || !world.equals(McCompat.dimensionId(current.level()))) return;
            if (error != null) {
                FoundationNetworkAdapter.send(current, ProjectViewPackets.error(view.scope(), 0,
                        view.viewId(), view.generation(), ProjectViewAction.NONE, "仓库内容读取失败"));
                return;
            }
            for (var observation : observations) {
                if (!world.equals(observation.key().world()) || !observation.warning().isBlank()) continue;
                FoundationNetworkAdapter.send(current, ProjectViewPackets.warehouse(view.scope(),
                        view.viewId(), view.generation(), sequence, observation));
            }
            FoundationNetworkAdapter.send(current, ProjectViewPackets.warehouseComplete(view.scope(),
                    view.viewId(), view.generation(), sequence));
        }));
    }

    static void publishConfiguration(MinecraftServer server, ServerRuntime runtime,
                                             ProjectViewScope scope, AreaCatalog catalog) {
        Map<ProjectViewTracker.View, Boolean> targets = new LinkedHashMap<>();
        for (var kind : List.of(InterestKind.AREA_CONFIG, InterestKind.OUTLINES)) {
            for (var view : runtime.views().views(scope, kind)) targets.put(view, true);
        }
        for (var view : targets.keySet()) {
            sendConfiguration(server, runtime, view, scope, catalog, "LIST");
            if (scope.isProject()) {
                sendConfiguration(server, runtime, view, scope, catalog, "LIST_WAREHOUSE_REFS");
            }
        }
    }

    private static void sendConfiguration(MinecraftServer server, ServerRuntime runtime,
            ProjectViewTracker.View view, ProjectViewScope scope, AreaCatalog catalog, String action) {
        ServerPlayer player = player(server, runtime, view);
        if (player == null) return;
        String actual = scope.global() ? "LIST_WAREHOUSES" : action;
        FoundationNetworkAdapter.send(player, ProjectViewPackets.configuration(scope, 0,
                view.viewId(), view.generation(), catalog.revision(), ProjectViewAction.NONE,
                true, "", new ProjectViewS2CPacket.AreaConfigData(actual,
                        areas(catalog, scope, actual))));
    }

    private static List<AreaDefinition> areas(AreaCatalog catalog, ProjectViewScope scope, String action) {
        if (scope.global()) return catalog.areas().stream()
                .filter(area -> area.kind() == AreaKind.WAREHOUSE).toList();
        return action.equals("LIST_WAREHOUSE_REFS")
                ? catalog.referencedWarehouses(scope.projectId()) : catalog.stagingAreas(scope.projectId());
    }

    private static String listAction(ProjectViewScope scope, String action) {
        if (scope.global()) return "LIST_WAREHOUSES";
        if (action.endsWith("WAREHOUSE_REF") || action.equals("LIST_WAREHOUSE_REFS")) {
            return "LIST_WAREHOUSE_REFS";
        }
        return "LIST";
    }

    private static boolean isListing(String action) {
        return action.equals("LIST") || action.equals("LIST_WAREHOUSES")
                || action.equals("LIST_WAREHOUSE_REFS");
    }

    private static void syncLegacySubscription(ServerRuntime runtime, ProjectViewTracker.View view) {
        if (!view.scope().isProject() || !runtime.isOpen()) return;
        UUID playerId = view.playerId();
        long connection = view.connection();
        if (runtime.connection(playerId) != connection) return;
        boolean subscribed = runtime.views().hasAnyInterest(playerId, connection, view.scope(),
                InterestKind.MATERIALS, InterestKind.TEAM, InterestKind.OUTLINES);
        if (subscribed) runtime.subscribe(playerId, view.scope().projectId());
        else runtime.unsubscribe(playerId, view.scope().projectId());
    }

    private static ServerPlayer player(MinecraftServer server, ServerRuntime runtime,
                                       ProjectViewTracker.View view) {
        if (!runtime.views().matches(view.playerId(), view.connection(), view.scope(),
                view.viewId(), view.generation())) return null;
        return player(server, runtime, view.playerId());
    }

    private static ServerPlayer player(MinecraftServer server, ServerRuntime runtime, UUID playerId) {
        ServerPlayer player = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId,
                runtime.connection(playerId));
        return player != null && ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_PROJECT_VIEWS)
                ? player : null;
    }

    private static void sendError(MinecraftServer server, ServerRuntime runtime,
                                  ProjectViewTracker.View view, String message) {
        ServerPlayer player = player(server, runtime, view);
        if (player != null) FoundationNetworkAdapter.send(player, ProjectViewPackets.error(view.scope(),
                0, view.viewId(), view.generation(), ProjectViewAction.NONE, message));
    }

    private static void sendInvalidated(MinecraftServer server, ServerRuntime runtime,
                                        ProjectViewTracker.View view, String message) {
        ServerPlayer player = player(server, runtime, view);
        if (player != null) FoundationNetworkAdapter.send(player, ProjectViewPackets.invalidated(
                view.scope(), view.viewId(), view.generation(), message));
    }

    private static CollaborationOutcome committedOutcome(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CommittedStorageException committed) {
                return committed.committedResult(CollaborationOutcome.class);
            }
            current = current.getCause();
        }
        return null;
    }
}
