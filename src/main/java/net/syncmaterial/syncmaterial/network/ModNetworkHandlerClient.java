package net.syncmaterial.syncmaterial.network;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;

public class ModNetworkHandlerClient {

    /**
     * 窄注册接口：生产实现转发到 Fabric 的连接级 registerReceiver（INIT 阶段）；
     * 测试用捕获实现直接取出每个原始 handler 实际安装的 receiver。
     */
    public interface S2CRegistrar {
        <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type,
                BiConsumer<T, ClientPlayNetworking.Context> receiver);
    }

    /** 生产 registrar：连接级 receiver，Fabric 保证只在 INIT 阶段接受该调用。 */
    private static final S2CRegistrar CONNECTION_REGISTRAR = new S2CRegistrar() {
        @Override
        public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type,
                BiConsumer<T, ClientPlayNetworking.Context> receiver) {
            ClientPlayNetworking.registerReceiver(type, receiver::accept);
        }
    };

    public static void register() {
        // 生产路径：每个原始 ClientPacketListener 在 INIT 阶段绑定本模组的全部 S2C；
        // 接收器只捕获 INIT 传入的 handler 身份，不再注册 global receiver 作为第二路径。
        // 注册事件本身每进程只登记一次。
        ClientPlayConnectionEvents.INIT.register((handler, client) ->
                installReceivers(handler, CONNECTION_REGISTRAR));
    }

    /**
     * 为一个原始连接安装全部 S2C：每个 receiver 捕获该 handler 身份并把业务排队到
     * 客户端线程；到线程执行时由 {@link ClientSessionManager#dispatch} 复核原连接
     * 仍是当前开放会话，找不到有效会话则整包丢弃。公开为测试捕获入口。
     */
    public static void installReceivers(Object handler, S2CRegistrar registrar) {
        bind(handler, registrar, HelloS2CPacket.ID, ModNetworkHandlerClient::onHello);
        bind(handler, registrar, ProjectViewS2CPacket.ID, ModNetworkHandlerClient::onProjectView);
        bind(handler, registrar, MaterialStatsResponseS2CPacket.ID, ModNetworkHandlerClient::onMaterialStats);
        bind(handler, registrar, MaterialCatalogUpdateS2CPacket.ID, ModNetworkHandlerClient::onCatalogUpdate);
        bind(handler, registrar, CollaborationStatusS2CPacket.ID, ModNetworkHandlerClient::onCollaborationStatus);
        bind(handler, registrar, AreaInventorySnapshotS2CPacket.ID, ModNetworkHandlerClient::onAreaInventorySnapshot);
        bind(handler, registrar, StagingAreaConfigResponseS2CPacket.ID, ModNetworkHandlerClient::onStagingAreaConfigResponse);
        bind(handler, registrar, RescanStagingAreaResponseS2CPacket.ID, ModNetworkHandlerClient::onRescanResponse);
        bind(handler, registrar, TeamResponseS2CPacket.ID, ModNetworkHandlerClient::onTeamResponse);
        bind(handler, registrar, OwnerActionResponseS2CPacket.ID, ModNetworkHandlerClient::onLegacyOwnerAction);
        bind(handler, registrar, BatchAssignResponseS2CPacket.ID, ModNetworkHandlerClient::onLegacyBatchAssign);
        bind(handler, registrar, KickFromMaterialResponseS2CPacket.ID, ModNetworkHandlerClient::onLegacyKick);
        bind(handler, registrar, PlayerListResponseS2CPacket.ID, ModNetworkHandlerClient::onLegacyPlayerList);
        bind(handler, registrar, WarehouseContainerResponseS2CPacket.ID, ModNetworkHandlerClient::onWarehouseContainer);
        bind(handler, registrar, WarehouseAreaResponseS2CPacket.ID, ModNetworkHandlerClient::onWarehouseArea);
    }

    private static <T extends CustomPacketPayload> void bind(Object handler, S2CRegistrar registrar,
            CustomPacketPayload.Type<T> type, BiConsumer<Object, T> dispatch) {
        registrar.register(type, (payload, context) ->
                context.client().execute(() -> dispatch.accept(handler, payload)));
    }

    // ========== 每个包的客户端线程 dispatch：来源是捕获的原 handler ==========

    private static void onHello(Object handler, HelloS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> {
            boolean applied = ClientProtocolState.applySessionHandshake(session,
                    payload.protocolVersion(), payload.modVersion(), payload.accepted());
            if (!applied) return;
            if (!payload.accepted()) {
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.ERROR,
                        fi.dy.masa.malilib.util.StringUtils.translate(
                                "syncmaterial.message.protocol.client_too_old",
                                payload.modVersion()));
            } else if (session.handshake().isServerNewer()) {
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                        fi.dy.masa.malilib.util.StringUtils.translate(
                                "syncmaterial.message.protocol.server_newer",
                                payload.modVersion()));
            }
        });
    }

    private static void onProjectView(Object handler, ProjectViewS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                session.projectView().onPacket(session.connection(), session.token(), payload));
    }

    private static void onMaterialStats(Object handler, MaterialStatsResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> SyncMaterialClient.onLegacyMaterialStats(
                payload.schematicId(), payload.schematicName(), payload.materials(), payload.isOwner(),
                payload.isMainOwner(), payload.ownerName(), payload.deputyOwners(), payload.allowSelfClaim()));
    }

    private static void onCatalogUpdate(Object handler, MaterialCatalogUpdateS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                SyncMaterialClient.onMaterialCatalogUpdate(payload));
    }

    private static void onCollaborationStatus(Object handler, CollaborationStatusS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                SyncMaterialClient.onCollaborationStatus(session.token(), payload));
    }

    private static void onAreaInventorySnapshot(Object handler, AreaInventorySnapshotS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                SyncMaterialClient.onAreaInventorySnapshot(payload));
    }

    private static void onStagingAreaConfigResponse(Object handler,
            StagingAreaConfigResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> {
            if (session.handshake().serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) return;
            routeStagingAreaResponse(payload);
        });
    }

    private static void onRescanResponse(Object handler, RescanStagingAreaResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> {
            if (session.handshake().serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) return;
            net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info(
                    "[Rescan] 收到重新扫描响应: success={}, message={}", payload.success(), payload.message());
            var screen = currentScreen();
            if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                materialListScreen.onRescanResponse(payload.success(), payload.message());
            }
        });
    }

    // 协议 4：项目/请求关联的团队状态与操作结果。dispatcher 传原 session/固定 token，
    // 不再读取全局 connection identity/token。
    private static void onTeamResponse(Object handler, TeamResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                net.syncmaterial.syncmaterial.client.TeamManagementClient
                        .onTeamResponse(session, payload.response()));
    }

    // 旧服务端（协议 < 4）：冻结包没有 requestId/项目，交给旧模式 FIFO 关联；
    // 只有匹配到原始请求的回包才会落地，不再按“当前屏幕类型”直接更新界面。
    private static void onLegacyOwnerAction(Object handler, OwnerActionResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                net.syncmaterial.syncmaterial.client.TeamManagementClient
                        .onLegacyOwnerActionResponse(session, payload));
    }

    private static void onLegacyBatchAssign(Object handler, BatchAssignResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                net.syncmaterial.syncmaterial.client.TeamManagementClient
                        .onLegacyBatchAssignResponse(session, payload));
    }

    private static void onLegacyKick(Object handler, KickFromMaterialResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                net.syncmaterial.syncmaterial.client.TeamManagementClient
                        .onLegacyKickResponse(session, payload));
    }

    // 旧服务端玩家列表：只能回到发起请求的那个弹窗
    private static void onLegacyPlayerList(Object handler, PlayerListResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session ->
                net.syncmaterial.syncmaterial.client.TeamManagementClient
                        .onLegacyPlayerListResponse(session, payload.players()));
    }

    // Phase 5: 仓库容器数据响应
    private static void onWarehouseContainer(Object handler, WarehouseContainerResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> {
            if (session.handshake().serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) return;
            handleWarehouseContainerResponse(payload);
        });
    }

    // Phase 5: 仓库区域线框数据（全局广播）
    private static void onWarehouseArea(Object handler, WarehouseAreaResponseS2CPacket payload) {
        ClientSessionManager.dispatch(handler, session -> {
            if (session.handshake().serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) return;
            handleWarehouseAreaResponse(payload);
        });
    }

    /**
     * 当前打开的界面；客户端未完全初始化（gui 为 null）时返回 null。
     */
    static net.minecraft.client.gui.screens.Screen currentScreen() {
        return ClientScreenAccess.currentScreen(Minecraft.getInstance());
    }

    /**
     * 按 action/success 路由备货区配置响应。列表快照只更新它自己的列表，
     * 敌对操作的确认/错误不得清空其它列表。
     */
    static void routeStagingAreaResponse(StagingAreaConfigResponseS2CPacket payload) {
        String action = payload.action() == null ? "" : payload.action();
        if (!payload.success()) {
            fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                    fi.dy.masa.malilib.gui.Message.MessageType.ERROR, payload.message());
            return;
        }
        if ("SCHEMATIC_DELETED".equals(action)) {
            handleStagingAreaConfigResponseForWorld(payload);
            return;
        }
        var screen = currentScreen();
        String scope = payload.schematicId();

        if ("LIST_WAREHOUSES".equals(action)) {
            String warning = overlapWarningMessage(payload);
            if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager manager) {
                if (isBlank(scope)) {
                    manager.onWarehouseListResponse(payload.areas());
                    manager.updateOverlapWarning(warning);
                }
                return;
            }
            if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup popup) {
                if (isBlank(scope) || popup.getSchematicId().equals(scope)) {
                    popup.onWarehouseListResponse(payload.areas());
                    popup.updateOverlapWarning(warning);
                }
                return;
            }
            // 没有对应的仓库列表界面时不要把它当备货区列表处理
            return;
        }

        if ("LIST_WAREHOUSE_REFS".equals(action)) {
            if (screen instanceof GuiStagingAreaEditorNormal editor) {
                if (matchesProject(scope, editor.getSchematicId())) {
                    editor.updateOverlapWarning(overlapWarningMessage(payload));
                    editor.onServerResponse(payload);
                }
            } else if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList list) {
                if (matchesProject(scope, list.getSchematicId())) {
                    list.updateOverlapWarning(overlapWarningMessage(payload));
                }
            }
            return;
        }

        if ("LIST".equals(action)) {
            if (screen instanceof GuiStagingAreaEditorNormal editor) {
                if (matchesProject(scope, editor.getSchematicId())) {
                    editor.updateOverlapWarning(overlapWarningMessage(payload));
                    editor.onServerResponse(payload);
                }
                return;
            }
            if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList list
                    && matchesProject(scope, list.getSchematicId())) {
                list.updateOverlapWarning(overlapWarningMessage(payload));
            }
            handleStagingAreaConfigResponseForWorld(payload);
            return;
        }

        // 变更确认/错误：只交给编辑器显示；仓库列表界面保持原列表不动。
        if (screen instanceof GuiStagingAreaEditorNormal editor) {
            editor.onServerResponse(payload);
            return;
        }
        if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager
                || screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup) {
            return;
        }
        handleStagingAreaConfigResponseForWorld(payload);
    }

    /**
     * 只有新服务端在成功 LIST 类响应里写入的翻译键标记才算区域重叠提示。
     * 旧服务端的 "ok"、变更确认（如“备货区已添加”）以及任意其它文本都不是；
     * 协议 2 及以下完全不解析该字段。
     */
    static String overlapWarningMessage(StagingAreaConfigResponseS2CPacket payload) {
        if (!payload.success() || !ClientProtocolState.serverSupports(ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) {
            return null;
        }
        return AreaOverlapStatus.isWarning(payload.message()) ? AreaOverlapStatus.MESSAGE_KEY : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 项目级 LIST 只更新对应项目的界面；空 scope 无法归属，不误报。 */
    private static boolean matchesProject(String scope, String ownProject) {
        return scope != null && !scope.isBlank() && scope.equals(ownProject);
    }

    /** Current project view when confirmed; otherwise the legacy active material model. */
    private static String activeSessionProject() {
        String viewed = net.syncmaterial.syncmaterial.client.ProjectViewClient.current()
                .flatMap(net.syncmaterial.syncmaterial.client.ProjectViewClient::projectStore)
                .map(net.syncmaterial.syncmaterial.client.ClientProjectViewStore::scope)
                .filter(net.syncmaterial.syncmaterial.core.ProjectViewScope::isProject)
                .map(net.syncmaterial.syncmaterial.core.ProjectViewScope::projectId)
                .orElse(null);
        if (viewed != null) return viewed;
        return ClientSessionManager.current()
                .map(ClientConnectionSession::projectCoordinator)
                .map(net.syncmaterial.syncmaterial.client.session.ClientProjectCoordinator::materialList)
                .map(net.syncmaterial.syncmaterial.client.gui.SyncMaterialList::getSchematicId)
                .orElse(null);
    }

    /**
     * 备货区配置响应的世界侧处理（不在任何相关界面打开时）：
     * 同步渲染器的选区数据、处理原理图删除/备货区清空的清理。
     */
    static void handleStagingAreaConfigResponseForWorld(StagingAreaConfigResponseS2CPacket payload) {
        if (!payload.success()) {
            fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                    fi.dy.masa.malilib.gui.Message.MessageType.ERROR, payload.message());
            return;
        }
        var renderer = StagingAreaRenderer.getInstance();

        // 仓库类响应的 schematicId 是空串，其 areas 是仓库而非备货区。
        // 若不拦截，仓库会被写进 key 为 "" 的 selections 并按备货区颜色渲染。
        if (payload.schematicId() == null || payload.schematicId().isEmpty()) {
            return;
        }

        // 设置原理图名称（用于框线文字标注）
        if (payload.schematicName() != null && !payload.schematicName().isEmpty()) {
            renderer.setSchematicName(payload.schematicId(), payload.schematicName());
        }

        // 原理图被删除：清理渲染 + HUD
        if ("SCHEMATIC_DELETED".equals(payload.action())) {
            renderer.removeRenderData(payload.schematicId());
            net.syncmaterial.syncmaterial.client.SyncMaterialClient.clearActiveSchematic(payload.schematicId());
            return;
        }

        String activeProject = activeSessionProject();
        // 空 areas 列表表示该原理图的备货区已被删除
        if (payload.areas().isEmpty())
        {
            renderer.removeRenderData(payload.schematicId());
        }
        else if (activeProject == null || activeProject.equals(payload.schematicId()))
        {
            var selection = renderer.getSelection(payload.schematicId());
            if (selection == null)
            {
                selection = new net.syncmaterial.syncmaterial.selection.AreaSelection();
                renderer.updateSelection(payload.schematicId(), selection);
            }
            java.util.Set<String> serverNames = new java.util.HashSet<>();
            for (var area : payload.areas())
            {
                serverNames.add(area.name());
                var box = new net.syncmaterial.syncmaterial.selection.Box(
                    new net.minecraft.core.BlockPos(area.x1(), area.y1(), area.z1()),
                    new net.minecraft.core.BlockPos(area.x2(), area.y2(), area.z2()),
                    area.name());
                box.setWorld(area.world());
                selection.addSubRegionBox(box, true);
                selection.setServerId(area.name(), area.areaId());
            }
            // 全量响应即权威列表：删除服务端已不存在的区域，否则被删/改名的区域会
            // 残留幽灵线框和陈旧 serverId（与编辑器 onServerResponse 的差集同步一致）。
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String name : selection.getAllSubRegionNames())
            {
                if (!serverNames.contains(name))
                {
                    toRemove.add(name);
                }
            }
            for (String name : toRemove)
            {
                selection.removeSubRegionBox(name);
                selection.removeServerId(name);
            }
        }
    }

    /** 仓库区域数据响应：更新客户端仓库线框缓存 */
    static void handleWarehouseAreaResponse(WarehouseAreaResponseS2CPacket payload) {
        StagingAreaRenderer.getInstance()
            .updateWarehouseAreas(payload.warehouses(), payload.referencedIds());
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.debug(
            "[Phase5] 收到仓库区域数据: {} 个仓库，其中 {} 个被当前原理图引用",
            payload.warehouses().size(), payload.referencedIds().size());
    }

    /** 仓库容器数据响应：更新客户端容器缓存（取货模式高亮用） */
    static void handleWarehouseContainerResponse(WarehouseContainerResponseS2CPacket payload) {
        Minecraft mc = Minecraft.getInstance();
        String worldId = mc.player != null
            ? McCompat.dimensionId(mc.player.level())
            : "";
        net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
            .updateWarehouseContainers(payload.containers(), worldId);
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Phase5] 收到仓库容器数据: {} 个箱子", payload.containers().size());
    }
}
