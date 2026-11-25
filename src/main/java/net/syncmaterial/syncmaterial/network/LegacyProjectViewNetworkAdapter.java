package net.syncmaterial.syncmaterial.network;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.CoreChecks;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ProjectProgressFragmenter.Fragment;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyProjectProgressReader;
import net.syncmaterial.syncmaterial.server.legacy.LegacyProjectProgressReader.Facts;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.runtime.ProjectProgressService;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;

/**
 * Protocol-5 project views for the legacy backend.
 *
 * <p>View identities and interest masks are owned by the rebased main
 * {@link ProjectViewTracker}; this adapter only maps legacy command actions onto it and
 * publishes real legacy facts. OPEN is the only operation that creates a view. Content is
 * delivered for interests added by OPEN/SET_INTERESTS: progress, team state, canonical area
 * configuration, and per-physical-container warehouse observations ending in an explicit
 * COMPLETE boundary. Global warehouse views are real typed views and receive canonical
 * warehouse configuration.</p>
 *
 * <p>All reads happen on the owning legacy server thread. A progress snapshot is read in one
 * database transaction/monitor, and only online claim names resolved to their trusted UUID
 * with a connection-fresh tracker observation contribute. Legacy aggregate counts that
 * cannot be proven free of overlapping physical sources are reported as unknown, never as
 * authoritative completion.</p>
 */
public final class LegacyProjectViewNetworkAdapter implements AutoCloseable {
    private static final byte[] NO_BYTES = new byte[0];
    private static final String GLOBAL_CONFIG_ACTION = "LIST_WAREHOUSES";
    private static final String PROJECT_CONFIG_ACTION = "LIST";
    private static final String WAREHOUSE_REFS_ACTION = "LIST_WAREHOUSE_REFS";

    private final LegacyServerContext owner;
    private final ProjectProgressService progress;
    private final Map<UUID, ConnectionState> connections = new HashMap<>();
    private final Map<ViewKey, String> viewDimensions = new HashMap<>();
    private final Map<String, ProjectRevisions> revisions =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ProjectRevisions> eldest) {
                    return size() > 4096;
                }
            };
    private final Set<String> dirtyProjects = new LinkedHashSet<>();
    private final Set<ProjectViewScope> dirtyConfiguration = new LinkedHashSet<>();
    private final Set<String> dirtyWarehouses = new LinkedHashSet<>();
    private long publicationSequence;
    private long revisionSequence;
    private boolean closed;

    public LegacyProjectViewNetworkAdapter(LegacyServerContext owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.progress = new ProjectProgressService(owner.onlineInventories());
    }

    /** Handles one decoded command on the owning server thread. */
    public void handle(ProjectViewCommandC2SPacket command, ServerPlayer player, Object connection,
                       MinecraftServer server) {
        if (command == null || player == null || connection == null) {
            return;
        }
        if (!canHandle(player, connection, server)
                || !ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            return;
        }
        ProjectViewScope scope = command.scope();
        if (scope.global() && !scope.equals(ProjectViewScope.globalWarehouse())) {
            sendError(player, connection, scope, command.requestId(), command.viewId(),
                    command.generation(), command.action(), "未知的全局作用域");
            return;
        }
        long connectionGeneration = owner.teamRequests() == null ? 0L
                : owner.teamRequests().connection(player.getUUID(), connection);
        if (connectionGeneration <= 0 || !recordConnection(player.getUUID(), connection,
                connectionGeneration)) {
            return;
        }
        try {
            switch (command.action()) {
                case OPEN -> open(command, player, connection, connectionGeneration);
                case SET_INTERESTS -> setInterests(command, player, connection, connectionGeneration);
                case CLOSE -> close(command, player, connection, connectionGeneration);
                case AREA_CONFIG -> configuration(command, player, connection, connectionGeneration);
                case RESCAN -> rescan(command, player, connection, connectionGeneration);
                case JOIN, LEAVE -> collaboration(command, player, connection, connectionGeneration);
                default -> sendError(player, connection, scope, command.requestId(),
                        command.viewId(), command.generation(), command.action(),
                        "不支持的视图动作");
            }
        } catch (SQLException failure) {
            SyncMaterial.LOGGER.warn("旧后端项目视图读取失败: {}", scope, failure);
            sendError(player, connection, scope, command.requestId(), command.viewId(),
                    command.generation(), command.action(), "数据库读取失败");
        } catch (IllegalArgumentException | IllegalStateException failure) {
            SyncMaterial.LOGGER.warn("旧后端项目视图请求被拒绝: {}", scope, failure);
            sendError(player, connection, scope, command.requestId(), command.viewId(),
                    command.generation(), command.action(),
                    message(failure.getMessage(), "请求无法完成"));
        } catch (RuntimeException failure) {
            SyncMaterial.LOGGER.error("旧后端项目视图处理失败: {}", scope, failure);
            sendError(player, connection, scope, command.requestId(), command.viewId(),
                    command.generation(), command.action(), "操作失败，请查看服务端日志");
        }
    }

    /** Marks progress for a coalesced publication on the owning-thread flush. */
    public void markDirty(String projectId) {
        if (projectId == null || projectId.isBlank()
                || projectId.length() > ProjectViewLimits.PROJECT_ID_LENGTH) {
            return;
        }
        synchronized (this) {
            if (!closed) {
                dirtyProjects.add(projectId);
            }
        }
    }

    /** Marks one project or the global warehouse configuration for a broadcast. */
    public void markConfigurationDirty(String projectId) {
        if (projectId != null && projectId.length() > ProjectViewLimits.PROJECT_ID_LENGTH) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            dirtyConfiguration.add(projectId == null || projectId.isBlank()
                    ? ProjectViewScope.globalWarehouse() : ProjectViewScope.project(projectId));
        }
    }

    /** Marks every currently tracked project warehouse view for re-read after a global change. */
    public void markAllWarehouseViewsDirty() {
        synchronized (this) {
            if (closed) return;
            for (ViewKey key : viewDimensions.keySet()) {
                if (key.scope().isProject()) {
                    dirtyWarehouses.add(key.scope().projectId());
                }
            }
        }
    }

    /** Marks warehouse contents for a project for a per-container refresh. */
    public void markWarehouseDirty(String projectId) {
        if (projectId == null || projectId.isBlank()
                || projectId.length() > ProjectViewLimits.PROJECT_ID_LENGTH) {
            return;
        }
        synchronized (this) {
            if (!closed) {
                dirtyWarehouses.add(projectId);
            }
        }
    }

    /** Publishes coalesced progress/configuration/warehouse changes. */
    public void flush() {
        List<String> projects;
        List<ProjectViewScope> configurations;
        List<String> warehouses;
        synchronized (this) {
            if (closed) {
                dirtyProjects.clear();
                dirtyConfiguration.clear();
                dirtyWarehouses.clear();
                return;
            }
            if (dirtyProjects.isEmpty() && dirtyConfiguration.isEmpty()
                    && dirtyWarehouses.isEmpty()) {
                projects = List.of();
                configurations = List.of();
                warehouses = List.of();
            } else {
                projects = List.copyOf(dirtyProjects);
                configurations = List.copyOf(dirtyConfiguration);
                warehouses = List.copyOf(dirtyWarehouses);
                dirtyProjects.clear();
                dirtyConfiguration.clear();
                dirtyWarehouses.clear();
            }
        }
        if (!owner.isCurrent()) {
            return;
        }
        if (projects.isEmpty() && configurations.isEmpty() && warehouses.isEmpty()) {
            checkDimensionChanges();
            return;
        }
        for (String projectId : projects) {
            if (!owner.isCurrent()) return;
            broadcastProgress(projectId, null);
        }
        for (ProjectViewScope scope : configurations) {
            if (!owner.isCurrent()) return;
            publishConfiguration(scope);
        }
        for (String projectId : warehouses) {
            if (!owner.isCurrent()) return;
            publishWarehouse(projectId);
        }
        checkDimensionChanges();
    }

    /** Sends DELETED, closes every project-scoped view and removes interests. */
    public void projectDeleted(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        // A legacy project id may exceed the protocol scope bound. It cannot own any
        // protocol view, but deletion must still succeed and clean legacy state.
        boolean representable = projectId.length() <= ProjectViewLimits.PROJECT_ID_LENGTH;
        List<ProjectViewTracker.View> targets = representable
                ? owner.views().interestedViews(ProjectViewScope.project(projectId)) : List.of();
        synchronized (this) {
            if (closed) return;
            dirtyProjects.remove(projectId);
            dirtyWarehouses.remove(projectId);
            if (representable) {
                dirtyConfiguration.remove(ProjectViewScope.project(projectId));
            }
            revisions.remove(projectId);
        }
        for (ProjectViewTracker.View view : targets) {
            removeDimension(view);
            send(view, ProjectViewPackets.deleted(view.scope(), view.viewId(), view.generation()));
        }
        for (ProjectViewTracker.View view : targets) {
            owner.views().close(view.playerId(), view.connection(), view.scope(), view.viewId(),
                    view.generation());
        }
        if (owner.subscriptions() != null) {
            owner.subscriptions().forgetProject(projectId);
        }
        // removeProject enforces the shared project-id bound; an overlong legacy id cannot
        // have protocol views and must never abort deletion of the legacy project itself.
        if (!projectId.isBlank() && projectId.length() <= ProjectViewLimits.PROJECT_ID_LENGTH) {
            try {
                owner.interests().removeProject(projectId);
            } catch (IllegalStateException ignored) {
                // Context shutdown already closed the registry.
            }
        }
    }

    /** Removes exactly the captured connection's views and interests. */
    public void disconnected(ServerPlayer player, Object connection) {
        if (player == null) return;
        UUID playerId = player.getUUID();
        ConnectionState state;
        synchronized (this) {
            state = connections.get(playerId);
            if (state == null || connection != null && state.connection != connection) {
                return;
            }
            connections.remove(playerId, state);
            viewDimensions.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
        owner.views().disconnected(playerId, state.generation);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            connections.clear();
            viewDimensions.clear();
            revisions.clear();
            dirtyProjects.clear();
            dirtyConfiguration.clear();
            dirtyWarehouses.clear();
        }
    }

    // ------------------------------------------------------------------
    // OPEN / SET / CLOSE
    // ------------------------------------------------------------------

    private void open(ProjectViewCommandC2SPacket request, ServerPlayer player, Object connection,
                      long connectionGeneration) throws SQLException {
        ProjectViewScope scope = request.scope();
        if (scope.isProject()) {
            String projectId = scope.projectId();
            if (!LegacyProjectProgressReader.projectExists(owner, projectId)) {
                sendError(player, connection, scope, request.requestId(), request.viewId(),
                        request.generation(), request.action(), "原理图尚未就绪");
                return;
            }
            LegacyProjectProgressReader.read(owner, projectId).orElseThrow(
                    () -> new IllegalStateException("原理图在读取期间消失"));
        } else {
            LegacyProjectProgressReader.readAllWarehouses(owner);
        }
        ProjectViewTracker.View view = owner.views().open(player.getUUID(), connectionGeneration,
                scope, request.viewId(), request.generation(), request.interestMask());
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图代次已过期或被其他视图占用");
            return;
        }
        syncLegacySubscription(view, player);
        send(view, ProjectViewPackets.opened(scope, request.requestId(), request.viewId(),
                request.generation(), UUID.randomUUID()));
        deliverContent(view, request.interestMask());
    }

    private void setInterests(ProjectViewCommandC2SPacket request, ServerPlayer player,
                              Object connection, long connectionGeneration) {
        ProjectViewScope scope = request.scope();
        int previous = owner.views().mask(player.getUUID(), connectionGeneration, scope,
                request.viewId(), request.generation());
        if (previous < 0) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开");
            return;
        }
        ProjectViewTracker.View view = owner.views().setInterests(player.getUUID(),
                connectionGeneration, scope, request.viewId(), request.generation(),
                request.interestMask());
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开或兴趣上限已达");
            return;
        }
        syncLegacySubscription(view, player);
        send(view, ProjectViewPackets.operation(scope, request.requestId(), request.viewId(),
                request.generation(), ProjectViewAction.SET_INTERESTS, true,
                previous != request.interestMask(), "兴趣已更新"));
        int added = request.interestMask() & ~previous;
        deliverContent(view, added);
    }

    private void close(ProjectViewCommandC2SPacket request, ServerPlayer player, Object connection,
                       long connectionGeneration) {
        ProjectViewScope scope = request.scope();
        ProjectViewTracker.View view = matchingView(player.getUUID(), connectionGeneration,
                request.viewId(), request.generation(), scope);
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开或已关闭");
            return;
        }
        boolean changed = owner.views().close(player.getUUID(), connectionGeneration, scope,
                request.viewId(), request.generation());
        if (!changed) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开或已关闭");
            return;
        }
        removeDimension(view);
        syncLegacySubscription(player.getUUID(), connectionGeneration, scope, player);
        // The view is already removed, so deliver the acknowledgement through the captured
        // connection instead of the now-inactive view record.
        sendDirect(player, connection, ProjectViewPackets.operation(scope, request.requestId(),
                request.viewId(), request.generation(), ProjectViewAction.CLOSE, true, true,
                "视图已关闭"));
    }

    // ------------------------------------------------------------------
    // Area configuration and rescan
    // ------------------------------------------------------------------

    private void configuration(ProjectViewCommandC2SPacket request, ServerPlayer player,
                               Object connection, long connectionGeneration) throws SQLException {
        ProjectViewScope scope = request.scope();
        ProjectViewTracker.View view = matchingView(player.getUUID(), connectionGeneration,
                request.viewId(), request.generation(), scope);
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开");
            return;
        }
        ProjectViewCommandC2SPacket.requireAreaActionScope(request.areaAction(), scope);
        if (scope.isProject()
                && !LegacyProjectProgressReader.projectExists(owner, scope.projectId())) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "原理图尚未就绪");
            return;
        }
        StagingAreaManager manager = owner.staging();
        if (manager == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "备货区服务未初始化");
            return;
        }
        AreaOutcome outcome = scope.global()
                ? executeWarehouseAction(request, player, manager)
                : executeStagingAction(request, player, manager, scope.projectId());
        if (!outcome.readable()) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), outcome.message());
            return;
        }
        String listAction = listAction(scope, request.areaAction());
        List<AreaDefinition> areas = readConfiguredAreas(scope, listAction);
        send(view, ProjectViewPackets.configuration(scope, request.requestId(), request.viewId(),
                request.generation(), nextPublicationSequence(), ProjectViewAction.AREA_CONFIG,
                outcome.success(), outcome.message(),
                new ProjectViewS2CPacket.AreaConfigData(listAction, areas)));
        if (!isListing(request.areaAction())) {
            publishConfiguration(scope);
            for (String affected : outcome.affectedProjects()) {
                markDirty(affected);
                if (!scope.isProject()) {
                    markConfigurationDirty(affected);
                }
                markWarehouseDirty(affected);
            }
            if (scope.isProject()) {
                markDirty(scope.projectId());
                markWarehouseDirty(scope.projectId());
            }
            flush();
        }
    }

    private AreaOutcome executeStagingAction(ProjectViewCommandC2SPacket request,
                                             ServerPlayer player, StagingAreaManager manager,
                                             String projectId) throws SQLException {
        String action = request.areaAction();
        List<StagingAreaManager.StagingArea> before = LegacyProjectProgressReader
                .readStagingAreas(owner, projectId);
        int areaId = request.areaId();
        StagingAreaConfigC2SPacket.AreaData data = request.areaData().orElse(null);
        boolean success = true;
        boolean changed = false;
        String message = "";

        switch (action) {
            case "LIST", "LIST_WAREHOUSE_REFS" -> {
                return AreaOutcome.read();
            }
            case "ADD" -> {
                if (data == null) return AreaOutcome.failure("缺少区域数据");
                String world = data.world().orElse(dimensionId(player));
                if (world == null || world.isBlank()) {
                    return AreaOutcome.failure("无法确定区域维度");
                }
                int added = manager.addStagingArea(projectId, world, data.name(),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                if (added <= 0) {
                    return AreaOutcome.failure("添加备货区失败");
                }
                manager.rescanStagingArea(added);
                changed = true;
                message = "备货区已添加";
            }
            case "RENAME" -> {
                StagingAreaManager.StagingArea current = findStaging(before, areaId);
                if (current == null || data == null) {
                    return AreaOutcome.failure("备货区不存在");
                }
                manager.renameStagingArea(areaId, projectId, data.name());
                StagingAreaManager.StagingArea after = findStaging(
                        LegacyProjectProgressReader.readStagingAreas(owner, projectId), areaId);
                success = after != null && Objects.equals(after.name(), data.name());
                changed = success && !Objects.equals(current.name(), data.name());
                message = success ? "备货区已重命名" : "重命名失败";
            }
            case "UPDATE" -> {
                StagingAreaManager.StagingArea current = findStaging(before, areaId);
                if (current == null || data == null) {
                    return AreaOutcome.failure("备货区不存在");
                }
                manager.updateStagingArea(areaId, projectId, data.name(),
                        data.world().orElse(null),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                StagingAreaManager.StagingArea after = findStaging(
                        LegacyProjectProgressReader.readStagingAreas(owner, projectId), areaId);
                success = after != null && sameStaging(after, data);
                changed = success && !sameStaging(current, data);
                if (success) manager.rescanStagingArea(areaId);
                message = success ? "备货区已更新" : "更新备货区失败";
            }
            case "DELETE" -> {
                StagingAreaManager.StagingArea current = findStaging(before, areaId);
                manager.removeStagingArea(areaId, projectId);
                StagingAreaManager.StagingArea after = findStaging(
                        LegacyProjectProgressReader.readStagingAreas(owner, projectId), areaId);
                success = after == null;
                changed = success && current != null;
                message = success ? (changed ? "备货区已删除" : "备货区不存在") : "删除备货区失败";
            }
            case "CLEAR" -> {
                for (StagingAreaManager.StagingArea area : before) {
                    manager.removeStagingArea(area.id(), projectId);
                }
                success = LegacyProjectProgressReader.readStagingAreas(owner, projectId).isEmpty();
                changed = success && !before.isEmpty();
                message = success ? "已清除所有备货区" : "清除备货区失败";
            }
            case "ADD_WAREHOUSE_REF" -> {
                boolean existed = findWarehouse(LegacyProjectProgressReader
                        .readReferencedWarehouses(owner, projectId), areaId) != null;
                manager.addWarehouseReference(projectId, areaId);
                success = findWarehouse(LegacyProjectProgressReader
                        .readReferencedWarehouses(owner, projectId), areaId) != null;
                changed = success && !existed;
                message = success ? (changed ? "已添加仓库引用" : "仓库引用已存在") : "添加仓库引用失败";
                if (success) {
                    Phase4Handler.broadcastAllMaterialStatus(owner, projectId);
                    owner.warehouseProjection().broadcastAreas();
                    owner.warehouseProjection().refreshProjectReferences(projectId);
                }
                return new AreaOutcome(true, success, changed, message, List.of(projectId));
            }
            case "REMOVE_WAREHOUSE_REF" -> {
                boolean existed = findWarehouse(LegacyProjectProgressReader
                        .readReferencedWarehouses(owner, projectId), areaId) != null;
                manager.removeWarehouseReference(projectId, areaId);
                success = findWarehouse(LegacyProjectProgressReader
                        .readReferencedWarehouses(owner, projectId), areaId) == null;
                changed = success && existed;
                message = success ? (changed ? "已移除仓库引用" : "仓库引用不存在") : "移除仓库引用失败";
                if (success) {
                    Phase4Handler.broadcastAllMaterialStatus(owner, projectId);
                    owner.warehouseProjection().broadcastAreas();
                    owner.warehouseProjection().refreshProjectReferences(projectId);
                }
                return new AreaOutcome(true, success, changed, message,
                        changed ? List.of(projectId) : List.of());
            }
            default -> {
                return AreaOutcome.failure("未知操作: " + action);
            }
        }
        if (changed) manager.broadcastUpdate(projectId);
        boolean progressMayChange = changed || ("UPDATE".equals(action) && success);
        return new AreaOutcome(true, success, changed, message,
                progressMayChange ? List.of(projectId) : List.of());
    }

    private AreaOutcome executeWarehouseAction(ProjectViewCommandC2SPacket request,
                                               ServerPlayer player, StagingAreaManager manager)
            throws SQLException {
        String action = request.areaAction();
        int areaId = request.areaId();
        StagingAreaConfigC2SPacket.AreaData data = request.areaData().orElse(null);
        boolean success = true;
        boolean changed = false;
        String message = "";
        List<String> affected = new ArrayList<>();

        switch (action) {
            case "LIST_WAREHOUSES" -> {
                return AreaOutcome.read();
            }
            case "ADD_WAREHOUSE" -> {
                if (data == null) return AreaOutcome.failure("缺少区域数据");
                int added = manager.addWarehouse(data.name(),
                        data.world().orElse("minecraft:overworld"),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                if (added <= 0) return AreaOutcome.failure("创建仓库失败");
                manager.rescanWarehouseAndMarkChunks(added);
                owner.warehouseProjection().publishContainers();
                owner.warehouseProjection().broadcastAreas();
                changed = true;
                message = "仓库已创建";
            }
            case "UPDATE_WAREHOUSE" -> {
                StagingAreaManager.Warehouse current = findWarehouse(
                        LegacyProjectProgressReader.readAllWarehouses(owner), areaId);
                if (current == null || data == null) return AreaOutcome.failure("仓库不存在");
                affected.addAll(manager.getSchematicsReferencingWarehouse(areaId));
                manager.updateWarehouse(areaId, data.name(), data.world().orElse(null),
                        data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
                StagingAreaManager.Warehouse after = findWarehouse(
                        LegacyProjectProgressReader.readAllWarehouses(owner), areaId);
                success = after != null && sameWarehouse(after, data);
                changed = success && !sameWarehouse(current, data);
                if (success) {
                    manager.rescanWarehouseAndMarkChunks(areaId);
                    owner.warehouseProjection().publishContainers();
                    owner.warehouseProjection().broadcastAreas();
                    for (String project : affected) {
                        Phase4Handler.broadcastAllMaterialStatus(owner, project);
                    }
                }
                message = success ? "仓库已更新" : "更新仓库失败";
            }
            case "DELETE_WAREHOUSE" -> {
                StagingAreaManager.Warehouse current = findWarehouse(
                        LegacyProjectProgressReader.readAllWarehouses(owner), areaId);
                affected.addAll(manager.getSchematicsReferencingWarehouse(areaId));
                manager.deleteWarehouse(areaId);
                StagingAreaManager.Warehouse after = findWarehouse(
                        LegacyProjectProgressReader.readAllWarehouses(owner), areaId);
                success = after == null;
                changed = success && current != null;
                if (success) {
                    owner.warehouseProjection().publishContainers();
                    owner.warehouseProjection().broadcastAreas();
                    for (String project : affected) {
                        Phase4Handler.broadcastAllMaterialStatus(owner, project);
                    }
                }
                message = success ? (changed ? "仓库已删除" : "仓库不存在") : "删除仓库失败";
            }
            default -> {
                return AreaOutcome.failure("未知操作: " + action);
            }
        }
        return new AreaOutcome(true, success, changed, message, success ? affected : List.of());
    }

    private void rescan(ProjectViewCommandC2SPacket request, ServerPlayer player, Object connection,
                        long connectionGeneration) throws SQLException {
        ProjectViewScope scope = request.scope();
        ProjectViewTracker.View view = matchingView(player.getUUID(), connectionGeneration,
                request.viewId(), request.generation(), scope);
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开");
            return;
        }
        StagingAreaManager manager = owner.staging();
        if (manager == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "备货区服务未初始化");
            return;
        }
        String projectId = scope.projectId();
        List<StagingAreaManager.StagingArea> before = LegacyProjectProgressReader
                .readStagingAreas(owner, projectId);
        boolean success;
        String message;
        if (before.isEmpty()) {
            success = true;
            message = "项目没有配置区域";
        } else if (request.areaId() > 0) {
            StagingAreaManager.StagingArea area = findStaging(before, request.areaId());
            if (area == null) {
                sendError(player, connection, scope, request.requestId(), request.viewId(),
                        request.generation(), request.action(), "找不到该区域");
                return;
            }
            success = manager.rescanStagingAreaExact(area.id());
            message = success ? "备货区已重新扫描"
                    : "备货区扫描未完成（区块未加载或维度不可用）";
        } else {
            int completed = 0;
            for (StagingAreaManager.StagingArea area : before) {
                if (manager.rescanStagingAreaExact(area.id())) completed++;
            }
            success = completed == before.size();
            message = success ? "已重新扫描 " + before.size() + " 个备货区"
                    : "已尝试扫描 " + before.size() + " 个备货区，完成 " + completed + " 个";
        }
        send(view, ProjectViewPackets.operation(scope, request.requestId(), request.viewId(),
                request.generation(), ProjectViewAction.RESCAN, success, success, message));
        Phase4Handler.broadcastAllMaterialStatus(owner, projectId);
        publishConfiguration(scope);
        markDirty(projectId);
        markWarehouseDirty(projectId);
        flush();
    }

    // ------------------------------------------------------------------
    // JOIN / LEAVE
    // ------------------------------------------------------------------

    private void collaboration(ProjectViewCommandC2SPacket request, ServerPlayer player,
                               Object connection, long connectionGeneration) throws SQLException {
        ProjectViewScope scope = request.scope();
        ProjectViewTracker.View view = matchingView(player.getUUID(), connectionGeneration,
                request.viewId(), request.generation(), scope);
        if (view == null) {
            sendError(player, connection, scope, request.requestId(), request.viewId(),
                    request.generation(), request.action(), "视图尚未打开");
            return;
        }
        String projectId = scope.projectId();
        int materialId = request.materialId();
        String playerName = player.getName().getString();
        CollaborationManager collaboration = owner.collaboration();
        if (collaboration == null) {
            sendOperation(view, request, false, false, "协作服务未初始化", materialId);
            return;
        }
        if (!LegacyProjectProgressReader.projectExists(owner, projectId)) {
            sendOperation(view, request, false, false, "原理图尚未就绪", materialId);
            return;
        }
        if (!LegacyProjectProgressReader.materialExists(owner, projectId, materialId)) {
            sendOperation(view, request, false, false, "材料不存在", materialId);
            return;
        }
        if (request.action() == ProjectViewAction.JOIN) {
            join(request, player, projectId, materialId, playerName, collaboration);
        } else {
            leave(request, player, projectId, materialId, playerName, collaboration);
        }
        publishProgressAfterOperation(projectId, view);
    }

    private void join(ProjectViewCommandC2SPacket request, ServerPlayer player, String projectId,
                      int materialId, String playerName, CollaborationManager collaboration)
            throws SQLException {
        boolean allowed;
        try {
            allowed = owner.database().getAllowSelfClaim(projectId)
                    || owner.database().isOwner(projectId, playerName);
        } catch (SQLException failure) {
            sendOperation(matchingViewUnchecked(player, request), request, false, false,
                    "权限检查失败", materialId);
            return;
        }
        if (!allowed) {
            sendOperation(matchingViewUnchecked(player, request), request, false, false,
                    "没有自行认领权限", materialId);
            return;
        }
        for (ProjectViewCommandC2SPacket.InventoryCount observation : request.inventory()) {
            if (observation.count() > Integer.MAX_VALUE) {
                sendOperation(matchingViewUnchecked(player, request), request, false, false,
                        "初始库存数量超出旧后端可记录范围", materialId);
                return;
            }
            if (!LegacyProjectProgressReader.materialExists(owner, projectId, observation.materialId())) {
                sendOperation(matchingViewUnchecked(player, request), request, false, false,
                        "上报材料不属于该项目", materialId);
                return;
            }
        }
        boolean wasMember = collaboration.isCollaborating(projectId, materialId, playerName);
        boolean joined = collaboration.joinCollaboration(projectId, materialId, playerName);
        if (!joined || !collaboration.isCollaborating(projectId, materialId, playerName)) {
            sendOperation(matchingViewUnchecked(player, request), request, false, false,
                    "加入协作失败", materialId);
            return;
        }
        boolean observationsRecorded = true;
        long targetCount = request.inventory().stream()
                .filter(observation -> observation.materialId() == materialId)
                .mapToLong(ProjectViewCommandC2SPacket.InventoryCount::count)
                .findFirst().orElse(0L);
        try {
            collaboration.updatePlayerInventory(playerName, projectId, materialId, (int) targetCount);
            owner.trackInventory(player, projectId, materialId, targetCount);
        } catch (RuntimeException failure) {
            observationsRecorded = false;
            SyncMaterial.LOGGER.warn("旧后端目标材料初始库存未能记录: {}/{}", projectId,
                    materialId, failure);
        }
        boolean changed = !wasMember;
        if (changed) {
            owner.markClaimEpoch(playerName, projectId, materialId);
        }
        sendOperation(matchingViewUnchecked(player, request), request, observationsRecorded, changed,
                observationsRecorded ? (changed ? "已加入协作" : "已在协作中")
                        : "认领已提交，但目标材料初始库存写入失败，请刷新", materialId);
    }

    private void leave(ProjectViewCommandC2SPacket request, ServerPlayer player, String projectId,
                       int materialId, String playerName, CollaborationManager collaboration) {
        boolean wasMember = collaboration.isCollaborating(projectId, materialId, playerName);
        if (!wasMember) {
            sendOperation(matchingViewUnchecked(player, request), request, true, false,
                    "未参与该材料", materialId);
            return;
        }
        boolean left = collaboration.leaveCollaboration(projectId, materialId, playerName);
        boolean stillMember = collaboration.isCollaborating(projectId, materialId, playerName);
        if (!left || stillMember) {
            sendOperation(matchingViewUnchecked(player, request), request, false, false,
                    "退出协作失败", materialId);
            return;
        }
        owner.clearClaimEpoch(playerName, projectId, materialId);
        owner.forgetInventory(player.getUUID(), projectId, materialId);
        sendOperation(matchingViewUnchecked(player, request), request, true, true,
                "已退出协作", materialId);
    }

    private void sendOperation(ProjectViewTracker.View view, ProjectViewCommandC2SPacket request,
                               boolean success, boolean changed, String message, int materialId) {
        if (view == null) return;
        send(view, new ProjectViewS2CPacket(request.scope(), request.requestId(), request.viewId(),
                request.generation(), 0, ProjectViewS2CPacket.Result.OPERATION, request.action(),
                success, message(message), materialId, changed, "", 0, 0, 0, 0, 0, NO_BYTES,
                Optional.empty(), Optional.empty()));
    }

    private ProjectViewTracker.View matchingViewUnchecked(ServerPlayer player,
                                                          ProjectViewCommandC2SPacket request) {
        long generation = owner.teamRequests().connection(player.getUUID(), player.connection);
        return matchingView(player.getUUID(), generation, request.viewId(), request.generation(),
                request.scope());
    }

    // ------------------------------------------------------------------
    // Content delivery
    // ------------------------------------------------------------------

    private void deliverContent(ProjectViewTracker.View view, int addedMask) {
        if (view == null || addedMask == 0) {
            return;
        }
        if (view.scope().isProject() && (addedMask & InterestKind.MATERIALS.bit()) != 0) {
            broadcastProgress(view.scope().projectId(), null);
        }
        if (view.scope().isProject() && (addedMask & InterestKind.TEAM.bit()) != 0) {
            ServerPlayer player = player(view);
            if (player != null) Phase4Handler.sendInitialTeam(owner, player, view.scope().projectId());
        }
        if ((addedMask & (InterestKind.AREA_CONFIG.bit() | InterestKind.OUTLINES.bit())) != 0) {
            publishConfiguration(view.scope());
        }
        if (view.scope().isProject()
                && (addedMask & InterestKind.WAREHOUSE_CONTENTS.bit()) != 0) {
            sendWarehouseContents(view);
        }
    }

    private void broadcastProgress(String projectId, ProjectViewTracker.View extra) {
        List<ProjectViewTracker.View> targets = owner.views().views(projectId,
                InterestKind.MATERIALS);
        if (extra != null && targets.stream().noneMatch(view ->
                view.playerId().equals(extra.playerId()) && view.viewId() == extra.viewId())) {
            List<ProjectViewTracker.View> copy = new ArrayList<>(targets);
            copy.add(extra);
            targets = List.copyOf(copy);
        }
        if (targets.isEmpty()) return;

        Optional<Facts> read;
        try {
            read = LegacyProjectProgressReader.read(owner, projectId);
        } catch (SQLException | IllegalArgumentException failure) {
            SyncMaterial.LOGGER.warn("旧后端项目进度读取失败: {}", projectId, failure);
            for (ProjectViewTracker.View view : targets) {
                sendError(view, "项目进度读取失败");
            }
            return;
        }
        if (read.isEmpty()) {
            projectDeleted(projectId);
            return;
        }
        Facts facts = read.get();
        ProjectProgressSnapshot progressSnapshot;
        SnapshotData snapshot;
        try {
            RevisionSnapshot revision = revisionsFor(projectId, facts);
            long sequence = nextPublicationSequence();
            progressSnapshot = LegacyProjectProgressReader.toSnapshot(
                    facts, progress, revision.catalogRevision(), revision.inventoryRevision(),
                    sequence);
            snapshot = new SnapshotData(sequence, revision.catalogRevision(),
                    revision.inventoryRevision(), UUID.randomUUID(),
                    ProjectProgressFragmenter.encode(progressSnapshot));
        } catch (RuntimeException failure) {
            SyncMaterial.LOGGER.warn("旧后端项目进度无法编码: {}", projectId, failure);
            for (ProjectViewTracker.View view : targets) {
                sendError(view, "项目进度超过协议上限或数据无效");
            }
            return;
        }
        for (ProjectViewTracker.View view : targets) {
            if (!matches(view)) continue;
            for (Fragment part : snapshot.parts()) {
                send(view, ProjectViewPackets.progress(view.scope(), view.viewId(),
                        view.generation(), progressSnapshot, snapshot.snapshotId(), part));
            }
        }
    }

    private void publishProgressAfterOperation(String projectId, ProjectViewTracker.View view) {
        if (view == null) return;
        broadcastProgress(projectId, view);
    }

    private void publishConfiguration(ProjectViewScope scope) {
        List<ProjectViewTracker.View> targets = configurationTargets(scope);
        if (targets.isEmpty()) return;
        long sequence = nextPublicationSequence();
        try {
            if (scope.global()) {
                List<AreaDefinition> areas = warehouseDefinitions(
                        LegacyProjectProgressReader.readAllWarehouses(owner));
                for (ProjectViewTracker.View view : targets) {
                    send(view, ProjectViewPackets.configuration(scope, 0, view.viewId(),
                            view.generation(), sequence, ProjectViewAction.NONE, true, "",
                            new ProjectViewS2CPacket.AreaConfigData(GLOBAL_CONFIG_ACTION, areas)));
                }
                return;
            }
            List<AreaDefinition> staging = stagingDefinitions(scope.projectId(),
                    LegacyProjectProgressReader.readStagingAreas(owner, scope.projectId()));
            List<AreaDefinition> refs = warehouseDefinitions(LegacyProjectProgressReader
                    .readReferencedWarehouses(owner, scope.projectId()));
            for (ProjectViewTracker.View view : targets) {
                send(view, ProjectViewPackets.configuration(scope, 0, view.viewId(),
                        view.generation(), sequence, ProjectViewAction.NONE, true, "",
                        new ProjectViewS2CPacket.AreaConfigData(PROJECT_CONFIG_ACTION, staging)));
                send(view, ProjectViewPackets.configuration(scope, 0, view.viewId(),
                        view.generation(), sequence, ProjectViewAction.NONE, true, "",
                        new ProjectViewS2CPacket.AreaConfigData(WAREHOUSE_REFS_ACTION, refs)));
            }
        } catch (SQLException | IllegalArgumentException failure) {
            SyncMaterial.LOGGER.warn("旧后端区域配置广播失败: {}", scope, failure);
            for (ProjectViewTracker.View view : targets) {
                sendError(view, "区域配置读取失败");
            }
        }
    }

    private void publishWarehouse(String projectId) {
        for (ProjectViewTracker.View view : owner.views().views(projectId,
                InterestKind.WAREHOUSE_CONTENTS)) {
            sendWarehouseContents(view);
        }
    }

    /**
     * Sends one observation per physical container position, then a COMPLETE boundary. The
     * legacy schema stores container item presence but not per-container counts, so no
     * aggregate row is ever presented as a physical box; each observation is opaque.
     */
    private void sendWarehouseContents(ProjectViewTracker.View view) {
        if (view == null || view.scope().global()) return;
        ServerPlayer player = player(view);
        if (player == null || player.level() == null) return;
        String world = McCompat.dimensionId(player.level());
        long sequence = nextPublicationSequence();
        LegacyProjectProgressReader.WarehouseContent content;
        try {
            content = LegacyProjectProgressReader.readWarehouseContainers(owner,
                    view.scope().projectId(), world);
        } catch (SQLException | IllegalArgumentException failure) {
            SyncMaterial.LOGGER.warn("旧后端仓库容器读取失败: {}", view.scope(), failure);
            sendError(view, "仓库内容读取失败");
            return;
        }
        if (!content.error().isBlank()) {
            sendError(view, content.error());
            return;
        }
        for (ContainerObservation observation : content.observations()) {
            send(view, ProjectViewPackets.warehouse(view.scope(), view.viewId(),
                    view.generation(), sequence, observation));
        }
        if (content.complete()) {
            send(view, ProjectViewPackets.warehouseComplete(view.scope(), view.viewId(),
                    view.generation(), sequence));
        }
        synchronized (this) {
            viewDimensions.put(new ViewKey(view.playerId(), view.connection(), view.scope(), view.viewId(),
                    view.generation()), world);
        }
    }

    private void checkDimensionChanges() {
        List<ViewKey> keys;
        synchronized (this) {
            keys = new ArrayList<>(viewDimensions.keySet());
        }
        for (ViewKey key : keys) {
            if (!key.scope().isProject()) continue;
            ProjectViewTracker.View view = new ProjectViewTracker.View(key.playerId(),
                    key.connection(), key.scope(), key.viewId(), key.generation(), 0);
            ServerPlayer player = player(view);
            if (player == null || player.level() == null) continue;
            String current = McCompat.dimensionId(player.level());
            String previous;
            synchronized (this) {
                previous = viewDimensions.get(key);
            }
            if (previous != null && !previous.equals(current)) {
                sendWarehouseContents(view);
            }
        }
    }

    private List<ProjectViewTracker.View> configurationTargets(ProjectViewScope scope) {
        Map<ViewKey, ProjectViewTracker.View> targets = new LinkedHashMap<>();
        for (InterestKind kind : List.of(InterestKind.AREA_CONFIG, InterestKind.OUTLINES)) {
            for (ProjectViewTracker.View view : owner.views().views(scope, kind)) {
                targets.putIfAbsent(new ViewKey(view.playerId(), view.connection(), view.scope(), view.viewId(),
                        view.generation()), view);
            }
        }
        return List.copyOf(targets.values());
    }

    // ------------------------------------------------------------------
    // Tracker, subscription and delivery helpers
    // ------------------------------------------------------------------

    private ProjectViewTracker.View matchingView(UUID playerId, long connection,
                                                 long viewId, long generation,
                                                 ProjectViewScope scope) {
        if (!owner.views().matches(playerId, connection, scope, viewId, generation)) {
            return null;
        }
        return new ProjectViewTracker.View(playerId, connection, scope, viewId, generation,
                owner.views().mask(playerId, connection, scope, viewId, generation));
    }

    private boolean matches(ProjectViewTracker.View view) {
        return owner.views().matches(view.playerId(), view.connection(), view.scope(),
                view.viewId(), view.generation());
    }

    private void syncLegacySubscription(ProjectViewTracker.View view, ServerPlayer player) {
        if (view == null || player == null) return;
        syncLegacySubscription(view.playerId(), view.connection(), view.scope(), player);
    }

    private void syncLegacySubscription(UUID playerId, long connection, ProjectViewScope scope,
                                        ServerPlayer player) {
        if (!scope.isProject() || player == null) return;
        boolean subscribed = owner.views().hasAnyInterest(playerId, connection, scope,
                InterestKind.MATERIALS, InterestKind.TEAM, InterestKind.OUTLINES);
        if (subscribed) {
            owner.subscriptions().subscribeMaterials(player, scope.projectId());
        } else {
            owner.subscriptions().unsubscribeMaterials(player, scope.projectId());
        }
    }

    private ServerPlayer player(ProjectViewTracker.View view) {
        if (view == null || owner.server() == null || owner.server().getPlayerList() == null) {
            return null;
        }
        ServerPlayer player = owner.server().getPlayerList().getPlayer(view.playerId());
        if (player == null || !ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_PROJECT_VIEWS)
                || !owner.isConnected(player) || !matches(view)) {
            return null;
        }
        synchronized (this) {
            ConnectionState state = connections.get(view.playerId());
            if (state == null || state.connection != player.connection
                    || state.generation != view.connection()) {
                return null;
            }
        }
        return player;
    }

    private boolean send(ProjectViewTracker.View view, ProjectViewS2CPacket packet) {
        ServerPlayer player = player(view);
        if (player == null || !matches(view)) return false;
        try {
            ServerPlayNetworking.send(player, packet);
            return true;
        } catch (RuntimeException failure) {
            SyncMaterial.LOGGER.warn("发送旧后端项目视图结果失败", failure);
            return false;
        }
    }

    private void sendError(ProjectViewTracker.View view, String message) {
        if (view == null) return;
        send(view, ProjectViewPackets.error(view.scope(), 0, view.viewId(), view.generation(),
                ProjectViewAction.NONE, message(message)));
    }

    private void sendError(ServerPlayer player, Object connection, ProjectViewScope scope,
                           long requestId, long viewId, long generation,
                           ProjectViewAction action, String message) {
        sendDirect(player, connection, ProjectViewPackets.error(scope, requestId, viewId,
                generation, action, message(message)));
    }

    private void sendDirect(ServerPlayer player, Object connection, ProjectViewS2CPacket packet) {
        if (!owner.isCurrent() || player == null || player.connection != connection
                || !owner.isConnected(player)
                || !ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            return;
        }
        ServerPlayNetworking.send(player, packet);
    }

    private boolean canHandle(ServerPlayer player, Object connection, MinecraftServer server) {
        return !closed && owner.isCurrent() && owner.isOpen()
                && LegacyNetworkDispatch.accepts(owner, player, server)
                && player.connection == connection;
    }

    private boolean recordConnection(UUID playerId, Object connection, long generation) {
        synchronized (this) {
            if (closed) return false;
            ConnectionState previous = connections.get(playerId);
            if (previous != null && previous.connection == connection) {
                return generation >= previous.generation;
            }
            if (previous != null && generation <= previous.generation) {
                return false;
            }
            if (previous != null) {
                viewDimensions.keySet().removeIf(key -> key.playerId().equals(playerId)
                        && key.connection() != generation);
            }
            connections.put(playerId, new ConnectionState(connection, generation));
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Revisions and small helpers
    // ------------------------------------------------------------------

    private RevisionSnapshot revisionsFor(String projectId, Facts facts) {
        synchronized (this) {
            ProjectRevisions state = revisions.computeIfAbsent(projectId,
                    ignored -> new ProjectRevisions());
            if (!state.catalogSet || state.catalogFingerprint != facts.catalogFingerprint()) {
                state.catalogFingerprint = facts.catalogFingerprint();
                state.catalogRevision = nextRevisionLocked();
                state.catalogSet = true;
            }
            if (!state.inventorySet
                    || state.inventoryFingerprint != facts.inventoryFingerprint()) {
                state.inventoryFingerprint = facts.inventoryFingerprint();
                state.inventoryRevision = nextRevisionLocked();
                state.inventorySet = true;
            }
            return new RevisionSnapshot(state.catalogRevision, state.inventoryRevision);
        }
    }

    private long nextRevisionLocked() {
        revisionSequence = Math.incrementExact(revisionSequence);
        return revisionSequence;
    }

    private long nextPublicationSequence() {
        synchronized (this) {
            publicationSequence = Math.incrementExact(publicationSequence);
            return publicationSequence;
        }
    }

    private void removeDimension(ProjectViewTracker.View view) {
        synchronized (this) {
            viewDimensions.remove(new ViewKey(view.playerId(), view.connection(), view.scope(), view.viewId(),
                    view.generation()));
        }
    }

    private List<AreaDefinition> readConfiguredAreas(ProjectViewScope scope, String action)
            throws SQLException {
        if (scope.global()) {
            return warehouseDefinitions(LegacyProjectProgressReader.readAllWarehouses(owner));
        }
        if (WAREHOUSE_REFS_ACTION.equals(action)) {
            return warehouseDefinitions(LegacyProjectProgressReader
                    .readReferencedWarehouses(owner, scope.projectId()));
        }
        return stagingDefinitions(scope.projectId(), LegacyProjectProgressReader
                .readStagingAreas(owner, scope.projectId()));
    }

    private String listAction(ProjectViewScope scope, String action) {
        if (scope.global()) return GLOBAL_CONFIG_ACTION;
        if (action != null && (action.endsWith("WAREHOUSE_REF")
                || action.equals(WAREHOUSE_REFS_ACTION))) {
            return WAREHOUSE_REFS_ACTION;
        }
        return PROJECT_CONFIG_ACTION;
    }

    private static boolean isListing(String action) {
        return "LIST".equals(action) || GLOBAL_CONFIG_ACTION.equals(action)
                || WAREHOUSE_REFS_ACTION.equals(action);
    }

    private static String projectId(ProjectViewCommandC2SPacket command) {
        return command.scope().isProject() ? command.scope().id() : null;
    }

    private static String dimensionId(ServerPlayer player) {
        if (player == null || player.level() == null) return "";
        return McCompat.dimensionId(player.level());
    }

    private static String message(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value;
        return result.length() <= ProjectViewLimits.MESSAGE_LENGTH
                ? result : result.substring(0, ProjectViewLimits.MESSAGE_LENGTH);
    }

    private static String message(String value) {
        return message(value, "");
    }

    private static StagingAreaManager.StagingArea findStaging(
            List<StagingAreaManager.StagingArea> areas, int id) {
        for (StagingAreaManager.StagingArea area : areas) {
            if (area.id() == id) return area;
        }
        return null;
    }

    private static StagingAreaManager.Warehouse findWarehouse(
            List<StagingAreaManager.Warehouse> warehouses, int id) {
        for (StagingAreaManager.Warehouse warehouse : warehouses) {
            if (warehouse.id() == id) return warehouse;
        }
        return null;
    }

    private static boolean sameStaging(StagingAreaManager.StagingArea area,
                                       StagingAreaConfigC2SPacket.AreaData data) {
        return Objects.equals(area.name(), data.name())
                && area.x1() == data.x1() && area.y1() == data.y1() && area.z1() == data.z1()
                && area.x2() == data.x2() && area.y2() == data.y2() && area.z2() == data.z2()
                && data.world().map(world -> Objects.equals(area.world(), world)).orElse(true);
    }

    private static boolean sameWarehouse(StagingAreaManager.Warehouse warehouse,
                                         StagingAreaConfigC2SPacket.AreaData data) {
        return Objects.equals(warehouse.name(), data.name())
                && warehouse.x1() == data.x1() && warehouse.y1() == data.y1()
                && warehouse.z1() == data.z1() && warehouse.x2() == data.x2()
                && warehouse.y2() == data.y2() && warehouse.z2() == data.z2()
                && data.world().map(world -> Objects.equals(warehouse.world(), world)).orElse(true);
    }

    private static List<AreaDefinition> stagingDefinitions(
            String projectId, List<StagingAreaManager.StagingArea> areas) {
        List<AreaDefinition> definitions = new ArrayList<>(areas.size());
        for (StagingAreaManager.StagingArea area : areas) {
            definitions.add(stagingDefinition(projectId, area));
        }
        return definitions;
    }

    private static AreaDefinition stagingDefinition(String projectId,
                                                     StagingAreaManager.StagingArea area) {
        String name = displayName(area.name());
        String world = requireWorld(area.world());
        AreaBounds bounds = new AreaBounds(area.x1(), area.y1(), area.z1(),
                area.x2(), area.y2(), area.z2());
        return new AreaDefinition(area.id(), projectId, AreaKind.STAGING_AREA, name, world, bounds,
                contentRevision(AreaKind.STAGING_AREA, projectId, name, world, bounds),
                geometryRevision(world, bounds));
    }

    private static List<AreaDefinition> warehouseDefinitions(
            List<StagingAreaManager.Warehouse> warehouses) {
        List<AreaDefinition> definitions = new ArrayList<>(warehouses.size());
        for (StagingAreaManager.Warehouse warehouse : warehouses) {
            String name = displayName(warehouse.name());
            String world = requireWorld(warehouse.world());
            AreaBounds bounds = new AreaBounds(warehouse.x1(), warehouse.y1(), warehouse.z1(),
                    warehouse.x2(), warehouse.y2(), warehouse.z2());
            definitions.add(new AreaDefinition(warehouse.id(), null, AreaKind.WAREHOUSE, name,
                    world, bounds, contentRevision(AreaKind.WAREHOUSE, null, name, world, bounds),
                    geometryRevision(world, bounds)));
        }
        definitions.sort(Comparator.comparingInt(AreaDefinition::id));
        return definitions;
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "未命名" : name;
    }

    private static String requireWorld(String world) {
        CoreChecks.requireRegistryId(world, "area world");
        return world;
    }

    private static long contentRevision(AreaKind kind, String projectId, String name, String world,
                                        AreaBounds bounds) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, kind.wireName());
        hash = mix(hash, projectId == null ? "" : projectId);
        hash = mix(hash, name);
        hash = mix(hash, world);
        hash = mix(hash, bounds.x1());
        hash = mix(hash, bounds.y1());
        hash = mix(hash, bounds.z1());
        hash = mix(hash, bounds.x2());
        hash = mix(hash, bounds.y2());
        hash = mix(hash, bounds.z2());
        return (hash & 0x3fffffffffffffffL) | 1L;
    }

    private static long geometryRevision(String world, AreaBounds bounds) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, world);
        hash = mix(hash, bounds.x1());
        hash = mix(hash, bounds.y1());
        hash = mix(hash, bounds.z1());
        hash = mix(hash, bounds.x2());
        hash = mix(hash, bounds.y2());
        hash = mix(hash, bounds.z2());
        return (hash & 0x3fffffffffffffffL) | 1L;
    }

    private static long mix(long hash, String value) {
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    private record AreaOutcome(boolean readable, boolean success, boolean changed,
                               String message, List<String> affectedProjects) {
        private AreaOutcome {
            affectedProjects = List.copyOf(affectedProjects);
        }

        private static AreaOutcome read() {
            return new AreaOutcome(true, true, false, "", List.of());
        }

        private static AreaOutcome failure(String message) {
            return new AreaOutcome(false, false, false, message, List.of());
        }
    }

    private record ConnectionState(Object connection, long generation) {
    }

    private record ViewKey(UUID playerId, long connection, ProjectViewScope scope, long viewId,
                            long generation) {
    }

    private record SnapshotData(long sequence, long catalogRevision, long inventoryRevision,
                                UUID snapshotId, List<Fragment> parts) {
    }

    private record RevisionSnapshot(long catalogRevision, long inventoryRevision) {
    }

    private static final class ProjectRevisions {
        private boolean catalogSet;
        private long catalogFingerprint;
        private long catalogRevision;
        private boolean inventorySet;
        private long inventoryFingerprint;
        private long inventoryRevision;
    }
}
