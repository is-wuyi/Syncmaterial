package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListUtils;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static net.syncmaterial.syncmaterial.client.session.ClientConnectionSession session() {
        return net.syncmaterial.syncmaterial.client.session.ClientSessionManager.current().orElse(null);
    }

    private static SyncMaterialList activeMaterialList() {
        var session = session();
        return session == null ? null : session.materialList();
    }

    private static long catalogRevision() {
        var session = session();
        return session == null ? -1 : session.catalogRevision();
    }

    private static void catalogRevision(long revision) {
        var session = session();
        if (session != null) session.catalogRevision(revision);
    }

    /** 取货需求重算周期（tick）。10 tick ≈ 0.5 秒，比原先挂在 HUD 的 2 秒更及时 */
    private static final int PICKUP_RECOMPUTE_INTERVAL = 10;
    private static int pickupRecomputeTicks;

    public static SyncMaterialList getActiveMaterialList() { return activeMaterialList(); }

    /**
     * 就地计算取货需求量，供箱子格子高亮使用。
     *
     * 与 net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().getNeeds()（每 10 tick 采样一次）的区别在采样时刻：
     * 格子高亮是"从第一格往后累加直到凑够需求量"，需求量必须与容器内容
     * 同一瞬间采样，否则差多少就多点亮几格。
     *
     * 举例：需要 128，箱子每格 64。取走第 0 格后容器变成 [空, 64, 64]，
     * 若需求还是旧的 128，贪心会选中第 1、2 格 —— 第 2 格本不该亮，
     * 等下一次 tick 把需求修正为 64 才恢复，表现为"后面的物品闪一下"。
     */
    public static java.util.Map<String, Long> livePickupNeeds() {
        if (!net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().isActive() || activeMaterialList() == null) {
            return java.util.Map.of();
        }
        return PickupModeState.computeNeeds(
                activeMaterialList().getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");

        // 加载配置
        Configs.loadFromFile();
        fi.dy.masa.malilib.config.ConfigManager.getInstance()
                .registerConfigHandler(SyncMaterial.MOD_ID, new Configs());

        // 注册热键到 MaLiLib 输入系统（必须用 IKeybindProvider，addHotkeysForCategory 只做展示）
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().registerKeybindProvider(
                new fi.dy.masa.malilib.hotkeys.IKeybindProvider() {
                    @Override
                    public void addKeysToMap(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                    @Override
                    public void addHotkeys(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                });

        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
        InventoryWatcher.register();

        // disconnect 会取消客户端队列；永久 tick 保证已封闭连接的清理不会丢。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (TeamManagementClient.finishPendingDisconnect()) clearDisconnectedState();
            ProjectViewClient.current().ifPresent(ProjectViewClient::tick);
            net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter.tick();
        });

        // 取货需求量在 tick 中重算，不挂渲染回调：挂渲染会让 HUD 关闭后
        // 需求永不更新，格子高亮与仓库线框停在陈旧数据上
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().isActive()) return;
            if (++pickupRecomputeTicks % PICKUP_RECOMPUTE_INTERVAL != 0) return;
            // 背包实测数就地采集：服务端回传的 countAvailable 有整条上报往返的
            // 延迟，用它算需求会在快速取货时把后面不需要的同种物品也点亮
            net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().recompute(
                    activeMaterialList() == null ? null : activeMaterialList().getMaterialsAll(),
                    InventoryScanner.liveCountsByItemId(client.player));
        });

        // 仓库容器缓存按维度隔离，但响应包不带维度；换维度时清缓存并按当前原理图重订阅，
        // 否则新维度里同坐标的箱子会沿用上个维度的取货高亮。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var session = session();
            if (session == null) return;
            if (client.player == null || client.player.level() == null) {
                session.dimensions().observe(null);
                return;
            }
            String worldId = net.syncmaterial.syncmaterial.compat.McCompat.dimensionId(client.player.level());
            if (session.dimensions().observe(worldId)) {
                onClientDimensionChanged();
            }
        });

        // 26.2 移除了 HudRenderCallback，改用 HudElementRegistry；
        // 1.21.7/1.21.8 仍用 HudRenderCallback，渲染上下文是 GuiGraphics
        //? if >=26.2 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "material_list_hud"),
                (drawContext, tickDelta) -> {
                    if (activeMaterialList() != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                            && activeMaterialList().getHudRenderer().getShouldRender()) {
                        fi.dy.masa.malilib.config.HudAlignment alignment =
                                ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                        int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                        int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                        activeMaterialList().getHudRenderer().render(fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext), x, y, alignment);
                    }
                });
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList() != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList().getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList().getHudRenderer().render(drawContext, x, y, alignment);
            }
        });
        *///?}

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        fi.dy.masa.malilib.event.InputEventHandler.getInputManager().registerKeyboardInputHandler(
                new fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler() {
                    //? if >=26.2 {
                    @Override
                    public boolean onKeyInput(net.minecraft.client.input.KeyEvent event, boolean pressed) {
                        return StagingAreaSelector.getInstance().onKeyInput(event.key(), 0, 0, pressed);
                    }
                    //?} else {
                    /*@Override
                    public boolean onKeyInput(int keyCode, int scanCode, int modifiers, boolean pressed) {
                        return StagingAreaSelector.getInstance().onKeyInput(keyCode, scanCode, modifiers, pressed);
                    }
                    *///?}
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StagingAreaSelector.getInstance().onTick();
        });

        //? if >=26.2 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "staging_selector_hud"),
                (drawContext, tickDelta) -> {
                    StagingAreaSelector.getInstance().onRenderHUD(fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext));
                });
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });
        *///?}

        // 进服后立刻发版本握手：服务端据此得知本客户端装了本 mod 及其协议版本，
        // 握手通过后才会推送备货区与仓库的初始数据
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // 快速重连可能先于断线清理 tick；唯一会话在客户端线程先封闭上一连接。
            var session = net.syncmaterial.syncmaterial.client.session.ClientSessionManager.join(handler);
            configureProjectView(session.projectView());
            sender.sendPacket(new net.syncmaterial.syncmaterial.network.HelloC2SPacket(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.CURRENT,
                    SyncMaterial.getModVersion()));
            net.syncmaterial.syncmaterial.network.ClientProtocolState.onHandshakeSent();
        });

        // 断开连接时清除编辑器状态
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            long token = TeamManagementClient.invalidateConnection(handler);
            if (token < 0) return;
            client.execute(() -> {
                if (TeamManagementClient.finishDisconnect(token)) clearDisconnectedState();
            });
        });

        // 准星选区模式下屏蔽方块交互
        net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
            return StagingAreaSelector.getInstance().isActive();
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
        });
    }

    private static void clearDisconnectedState() {
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.discard();
    }

    private static void configureProjectView(ProjectViewClient session) {
        session.setOpenedListener(SyncMaterialClient::onProjectViewOpened);
        session.setStateListener(SyncMaterialClient::onProjectViewState);
        session.setOperationListener(SyncMaterialClient::onProjectViewOperation);
        session.setOperationTimeoutListener(SyncMaterialClient::onProjectViewOperationTimeout);
        session.setDeletionListener(scope -> {
            if (scope.isProject()) SyncMaterialClient.clearActiveSchematic(scope.projectId());
        });
    }

    private static void onProjectViewOpened(net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket packet) {
        if (!packet.scope().isProject() || packet.requestId() <= 0) return;
        String projectId = packet.scope().projectId();
        var current = ClientScreenAccess.currentScreen(Minecraft.getInstance());
        if (!(current instanceof GuiMaterialList visible) || !visible.getSchematicId().equals(projectId)) {
            if (activeMaterialList() != null) activeMaterialList().clearBindings();
            List<MaterialEntry> entries = activeMaterialList() != null
                    && activeMaterialList().getSchematicId().equals(projectId)
                    ? activeMaterialList().getMaterialsAll().stream().map(entry -> new MaterialEntry(
                            entry.getDatabaseId(), entry.getStack(), entry.getCountTotal(),
                            entry.getCountMissing(), entry.getCountMismatched(), entry.getCountAvailable()))
                    .toList() : List.of();
            GuiMaterialList gui = new GuiMaterialList(projectId, projectId, entries,
                    false, false, "", List.of(), true);
            session().materialList(gui.getMaterialList());
            gui.getMaterialList().getHudRenderer().setShouldRender(session().hudVisible());
            TeamManagementClient.onMaterialListOpened(gui);
            ClientScreenAccess.showScreen(Minecraft.getInstance(), gui);
        }
        ProjectViewClient.current().ifPresent(session -> session.openGui(projectId));
    }

    private static void onProjectViewState(ClientProjectViewStore store) {
        if (store.scope() != null && store.scope().isProject()) {
            String projectId = store.scope().projectId();
            StagingAreaRenderer renderer = StagingAreaRenderer.getInstance();
            renderer.activateProject(projectId);
            if (store.valid()) {
                store.configuration("LIST").ifPresent(areas ->
                        renderer.applyProjectConfiguration(projectId, areas));
                store.configuration("LIST_WAREHOUSE_REFS").ifPresent(renderer::applyWarehouseReferences);
            } else {
                renderer.clearWarehouseContainers();
            }
        } else if (store.scope() != null && store.scope().global() && store.valid()) {
            store.configuration("LIST_WAREHOUSES").ifPresent(areas ->
                    StagingAreaRenderer.getInstance().applyWarehouseDefinitions(areas));
        }
        if (!store.valid() && store.scope() != null && store.scope().isProject()
                && activeMaterialList() != null
                && activeMaterialList().getSchematicId().equals(store.scope().projectId())) {
            activeMaterialList().invalidateProjectProgress();
        }
        store.progress().ifPresent(progress -> {
            if (activeMaterialList() != null && activeMaterialList().getSchematicId().equals(progress.projectId())
                    && activeMaterialList().applyProjectProgress(progress)) {
                var mc = Minecraft.getInstance();
                MaterialListUtils.updateAvailableCounts(activeMaterialList().getMaterialsAll(), mc.player);
                net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().recompute(
                        activeMaterialList().getMaterialsAll(), InventoryScanner.liveCountsByItemId(mc.player));
            }
        });
        if (store.scope() != null && store.scope().global()
                && ClientScreenAccess.currentScreen(Minecraft.getInstance())
                instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager manager) {
            store.configuration("LIST_WAREHOUSES").ifPresent(manager::applyProjectViewConfiguration);
        } else if (store.scope() != null && store.scope().global()
                && ClientScreenAccess.currentScreen(Minecraft.getInstance())
                instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup popup) {
            store.configuration("LIST_WAREHOUSES").ifPresent(popup::applyProjectViewConfiguration);
        }
        if (store.scope() != null && store.scope().isProject()
                && ClientScreenAccess.currentScreen(Minecraft.getInstance())
                instanceof net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal editor
                && editor.getSchematicId().equals(store.scope().projectId())) {
            for (String action : List.of("LIST", "LIST_WAREHOUSE_REFS")) {
                store.configuration(action).ifPresent(areas -> {
                    if (editor.getSchematicId() != null) editor.applyProjectViewConfiguration(action, areas);
                });
            }
        }
        if (store.valid() && store.hasWarehouseSnapshot() && store.pendingWarehouseParts() == 0) {
            var entries = store.warehouse().stream().map(observation ->
                    new net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry(
                            observation.key().x(), observation.key().y(), observation.key().z(),
                            observation.presentItemIds().stream().sorted().toList()))
                    .toList();
            StagingAreaRenderer.getInstance().updateWarehouseContainers(entries, store.warehouseWorld());
        }
    }

    /** 超时或视图退场只解除原请求的等待状态；不推断提交结果，不重放操作。 */
    private static void onProjectViewOperationTimeout(ProjectViewClient.OperationTimeout timeout) {
        boolean cleared = false;
        if (timeout.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.AREA_CONFIG) {
            cleared = net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter.timeout(timeout.requestId());
        } else if ((timeout.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.JOIN
                || timeout.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.LEAVE)
                && timeout.scope().isProject() && activeMaterialList() != null
                && activeMaterialList().getSchematicId().equals(timeout.scope().projectId())) {
            cleared = activeMaterialList().claimTimeout(timeout.requestId());
        } else if (timeout.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.RESCAN
                && timeout.scope().isProject()
                && ClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof GuiMaterialList gui
                && gui.getSchematicId().equals(timeout.scope().projectId())) {
            gui.onRescanUnconfirmed();
            cleared = true;
        }
        if (cleared) fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.result.unconfirmed"));
    }

    private static void onProjectViewOperation(net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket packet) {
        net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter.complete(packet);
        if (activeMaterialList() != null) activeMaterialList().completeClaim(packet);
        if (packet.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.RESCAN
                && ClientScreenAccess.currentScreen(Minecraft.getInstance()) instanceof GuiMaterialList gui
                && packet.scope().isProject() && gui.getSchematicId().equals(packet.scope().projectId())) {
            gui.onRescanResponse(packet.success(), packet.message());
        }
        String message;
        if (packet.result() == net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket.Result.OPERATION
                && (packet.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.JOIN
                || packet.action() == net.syncmaterial.syncmaterial.core.ProjectViewAction.LEAVE)) {
            message = net.syncmaterial.syncmaterial.client.gui.CollaborationResultLabels.message(packet.message());
        } else {
            message = packet.message() == null || packet.message().isBlank()
                    ? fi.dy.masa.malilib.util.StringUtils.translate(
                    packet.success() ? "syncmaterial.gui.result.applied" : "syncmaterial.gui.result.failed")
                    : packet.message();
        }
        fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                packet.success() ? fi.dy.masa.malilib.gui.Message.MessageType.SUCCESS
                        : fi.dy.masa.malilib.gui.Message.MessageType.ERROR, message);
    }

    /** Shared entry for the Syncmatica material button and other project-open actions. */
    public static boolean requestMaterialList(String schematicId) {
        if (session() == null || !session().isOpen()) return false;
        if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            return ProjectViewClient.current().map(session -> session.openProject(schematicId,
                    net.syncmaterial.syncmaterial.client.ProjectInterestLeases.BASE_MASK) > 0).orElse(false);
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket(schematicId));
        return true;
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        var session = session();
        if (session == null || !session.isOpen()) return;
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。isOwner={}, isMainOwner={}", materials.size(), isOwner, isMainOwner);
        // 分闸状态独立于 HUD_ENABLED 总闸：两条打开路径都继承同一项目显示状态。
        boolean hudState = activeMaterialList() == null ? session.hudVisible()
                : activeMaterialList().getHudRenderer().getShouldRender();
        session.hudVisible(hudState);
        if (activeMaterialList() != null && !activeMaterialList().getSchematicId().equals(schematicId)) {
            if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
                ProjectViewClient.current().ifPresent(view -> view.closeProject(true));
            } else {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(
                                activeMaterialList().getSchematicId()));
            }
        }
        if (activeMaterialList() != null) activeMaterialList().clearBindings();
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        session().materialList(gui.getMaterialList());
        // 捕获项目/原始界面；同一项目重新打开时应用已知权威角色，换项目时使旧请求失效
        TeamManagementClient.onMaterialListOpened(gui);
        catalogRevision(-1);
        activeMaterialList().getHudRenderer().setShouldRender(hudState);
        ClientScreenAccess.showScreen(Minecraft.getInstance(), gui);
        if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            ProjectViewClient.current().ifPresent(view -> {
                view.openProject(schematicId, net.syncmaterial.syncmaterial.client.ProjectInterestLeases.BASE_MASK);
                view.openGui(schematicId);
                if (net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().isActive()) view.setPickup(schematicId, true);
            });
        }
    }

    public static void onMaterialCatalogUpdate(net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket update) {
        if (!net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.MATERIAL_CATALOG_UPDATES)
                || activeMaterialList() == null || !activeMaterialList().getSchematicId().equals(update.schematicId())
                || update.revision() <= catalogRevision()) return;
        catalogRevision(update.revision());
        activeMaterialList().applyManagementCatalog(update);
        if (activeMaterialList().hasProjectProgress()) return;
        // 目录更新同时失效旧区域快照：旧序号/计数不能继续冒充当前目录的权威值。
        activeMaterialList().setMaterialEntries(update.materials(), update.revision());
        activeMaterialList().setTitle(update.schematicName());
        activeMaterialList().useFoundationBackend();
        InventoryWatcher.enablePeriodicReports();
        StagingAreaRenderer.getInstance().setSchematicName(update.schematicId(), update.schematicName());
        var mc = Minecraft.getInstance();
        if (mc.player != null) MaterialListUtils.updateAvailableCounts(activeMaterialList().getMaterialsAll(), mc.player);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        onCollaborationStatus(TeamManagementClient.connectionToken(), status);
    }

    /** 接收线程捕获连接代次；旧连接排队的广播不能落入新连接的同项目列表。 */
    public static void onCollaborationStatus(long connectionToken, CollaborationStatusS2CPacket status) {
        if (connectionToken != TeamManagementClient.connectionToken()) return;
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {}, 仓库: {})", status.materialId(), status.totalCount(), status.stagingCount(), status.warehouseCount());
        if (activeMaterialList() != null && activeMaterialList().getSchematicId().equals(status.schematicId())) {
            activeMaterialList().onCollaborationStatus(status);
        }
    }

    /**
     * v3 区域库存权威快照入口。先做服务端能力闸门（旧服务端不发/客户端不认），
     * 再交给当前材料目录做目录序号、快照序号与材料全集校验。
     */
    public static void onAreaInventorySnapshot(net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket snapshot) {
        if (!net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) return;
        if (activeMaterialList() == null || !activeMaterialList().getSchematicId().equals(snapshot.schematicId())) return;
        if (!activeMaterialList().applyAreaInventorySnapshot(snapshot)) return;
    }

    /**
     * 维度切换后的仓库缓存处理：丢掉旧维度容器，若正处于取货模式则按当前原理图
     * 重发订阅（复用既有 WarehouseContainerRequestC2SPacket，不新增包）。
     */
    public static void onClientDimensionChanged() {
        var session = session();
        if (session == null) return;
        StagingAreaRenderer.getInstance().clearWarehouseContainers();
        session.selector().onDimensionChanged();
        if (activeMaterialList() == null) {
            return;
        }
        boolean scoped = net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS);
        if (scoped) {
            ProjectViewClient.current().ifPresent(view ->
                    view.dimensionChanged(activeMaterialList().getSchematicId()));
        }
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().recompute(activeMaterialList().getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
        if (!scoped && net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().isActive()) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket(
                            activeMaterialList().getSchematicId(), true));
        }
    }

    /**
     * 原理图被删除时清理客户端状态：关闭 GUI、清除 HUD、清理渲染。
     * 由 SchematicUploadListener 调用（已通过 Minecraft.execute() 调度到渲染线程）。
     */
    public static void clearActiveSchematic(String schematicId) {
        // 项目删除：所有绑定该项目的团队请求与权威角色立即作废
        TeamManagementClient.onProjectDeleted(schematicId);
        StagingAreaRenderer renderer = StagingAreaRenderer.getInstance();
        renderer.clearProject(schematicId);
        renderer.clearWarehouseContainers();
        renderer.applyWarehouseReferences(List.of());
        var selection = StagingAreaSelector.getInstance().getScope();
        if (selection != null && schematicId.equals(selection.projectId())) {
            StagingAreaSelector.getInstance().discard();
        }
        // 关闭当前打开的材料列表或属于该项目的编辑/引用界面
        Minecraft mc = Minecraft.getInstance();
        var visible = ClientScreenAccess.currentScreen(mc);
        boolean deletedProjectScreen = switch (visible) {
            case null -> false;
            case net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal editor ->
                    schematicId.equals(editor.getSchematicId());
            case net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion editor ->
                    schematicId.equals(editor.getSchematicId());
            case net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup popup ->
                    schematicId.equals(popup.getSchematicId());
            default -> false;
        };
        if (deletedProjectScreen) {
            ClientScreenAccess.showScreen(mc, null);
        }
        if (ClientScreenAccess.currentScreen(mc) instanceof GuiMaterialList gui) {
            if (schematicId.equals(gui.getMaterialList().getSchematicId())) {
                ClientScreenAccess.showScreen(mc, null);
            }
        }


        // 清除 HUD（如果属于被删除的原理图）。
        // HUD 退场时背包监听与服务端订阅一并清理：GUI 关闭不再做这件事
        // （HUD 比 GUI 活得久，数据源必须随 HUD 存续，见 GuiMaterialList.closeGui）
        if (activeMaterialList() != null && schematicId.equals(activeMaterialList().getSchematicId())) {
            LOGGER.info("原理图 {} 已删除，清除 HUD", schematicId);
            activeMaterialList().clearBindings();
            session().materialList(null);
            catalogRevision(-1);
            InventoryWatcher.clearContext();
            net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().clear();
            if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
                ProjectViewClient.current().ifPresent(view -> view.closeProject(true));
            } else {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(schematicId));
            }
        }
    }
}
