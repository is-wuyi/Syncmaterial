package net.syncmaterial.syncmaterial.client.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.ClientProjectViewStore;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryScanner;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.gui.MaterialListUtils;
import net.syncmaterial.syncmaterial.client.ProjectInterestLeases;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.CollaborationResultLabels;
import net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/** Owns one session's active project projection and its screen-independent consumers. */
public final class ClientProjectCoordinator {
    private final ClientConnectionSession session;
    private final ProjectViewClient view;
    private final Map<Long, ConfigurationSubscription> configurations = new HashMap<>();
    private SyncMaterialList model;
    private GuiMaterialList materialScreen;
    private String requestedProject;
    private boolean legacyMainOwner;
    private String legacyOwnerName = "";
    private List<String> legacyDeputyOwners = List.of();
    private long nextSubscription;
    private boolean closed;

    public ClientProjectCoordinator(ClientConnectionSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.view = session.projectView();
        this.view.setOpenedListener(this::opened);
        this.view.setStateListener(this::state);
        this.view.setActiveProjectListener(this::projectAccepted);
        this.view.setOperationListener(this::operation);
        this.view.setOperationTimeoutListener(this::operationTimeout);
        this.view.setDeletionListener(scope -> {
            if (scope.isProject()) this.deleted(scope.projectId());
            else this.globalDeleted();
        });
    }

    public SyncMaterialList materialList() {
        // The session field is a read-only compatibility alias for existing direct fixtures.
        return this.model != null ? this.model : this.session.materialList();
    }

    public GuiMaterialList materialScreen() {
        return this.materialScreen;
    }

    /** A material request is a UI intent; project data itself survives closing that UI. */
    public boolean requestMaterialList(String projectId) {
        if (!this.session.isOpen() || this.closed || projectId == null || projectId.isBlank()) {
            return false;
        }
        this.requestedProject = projectId;
        if (supportsViews()) {
            return this.view.openProject(projectId, ProjectInterestLeases.BASE_MASK) > 0;
        }
        this.session.sendPacket(
                new net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket(projectId));
        return true;
    }

    /** A frozen legacy response is accepted only when the peer lacks protocol 5. */
    public void acceptLegacyMaterialList(String projectId, String name, List<MaterialEntry> materials,
            boolean owner, boolean mainOwner, String ownerName, List<String> deputies,
            boolean allowSelfClaim) {
        if (supportsViews()) return;
        presentMaterialList(projectId, name, materials, owner, mainOwner, ownerName, deputies, allowSelfClaim);
    }

    /** Trusted direct presentation for synthetic UI fixtures and local callers, never a packet route. */
    public void presentMaterialList(String projectId, String name, List<MaterialEntry> materials,
            boolean owner, boolean mainOwner, String ownerName, List<String> deputies,
            boolean allowSelfClaim) {
        if (!this.session.isOpen() || this.closed) return;
        SyncMaterialList previous = this.model;
        if (previous != null && !previous.getSchematicId().equals(projectId)) {
            if (supportsViews()) this.view.closeProject(true);
            else this.session.sendPacket(
                    new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(
                            previous.getSchematicId()));
        }
        SyncMaterialList next = model(projectId, name);
        next.setIsOwner(owner || mainOwner);
        next.setAllowSelfClaim(allowSelfClaim);
        this.legacyMainOwner = mainOwner;
        this.legacyOwnerName = ownerName == null ? "" : ownerName;
        this.legacyDeputyOwners = deputies == null ? List.of() : List.copyOf(deputies);
        next.setMaterialEntries(materials);
        this.session.catalogRevision(-1);
        this.requestedProject = projectId;
        showMaterialScreen();
        syncInventory();
    }

    private void projectAccepted(String projectId) {
        if (this.closed || this.model == null || this.model.getSchematicId().equals(projectId)) return;
        closeMaterialScreen(this.materialScreen);
        this.model.clearBindings();
        this.model = null;
        this.session.materialList(null);
        this.session.catalogRevision(-1);
        InventoryWatcher.clearContext();
        this.session.pickup().clear();
        if (projectId.equals(this.requestedProject)) model(projectId, projectId);
    }

    private void opened(ProjectViewS2CPacket packet) {
        if (this.closed || !packet.scope().isProject() || packet.requestId() <= 0
                || !packet.scope().projectId().equals(this.requestedProject)) return;
        ProjectViewClient.ViewToken current = this.view.viewToken(packet.scope());
        if (current == null || current.viewId() != packet.viewId()
                || current.generation() != packet.generation()) return;
        String projectId = packet.scope().projectId();
        SyncMaterialList projection = model(projectId, projectId);
        this.view.projectStore().filter(store -> packet.scope().equals(store.scope()))
                .flatMap(ClientProjectViewStore::progress).ifPresent(progress -> {
                    if (projection.applyProjectProgress(progress)) this.syncInventory();
                });
        showMaterialScreen();
        this.view.openGui(projectId);
    }

    private SyncMaterialList model(String projectId, String name) {
        if (this.model != null && this.model.getSchematicId().equals(projectId)) {
            return this.model;
        }
        if (this.materialScreen != null) {
            if (!projectId.equals(this.materialScreen.getSchematicId())) {
                closeMaterialScreen(this.materialScreen);
            } else {
                this.materialScreen.suspendBinding();
                this.materialScreen = null;
            }
        }
        if (this.model != null) this.model.clearBindings();
        this.legacyMainOwner = false;
        this.legacyOwnerName = "";
        this.legacyDeputyOwners = List.of();
        this.model = new SyncMaterialList(projectId, name);
        this.model.bindSession(this.session);
        this.model.getHudRenderer().setShouldRender(this.session.hudVisible());
        this.session.materialList(this.model);
        this.session.catalogRevision(-1);
        StagingAreaRenderer.getInstance().activateProject(projectId);
        StagingAreaRenderer.getInstance().setSchematicName(projectId, name);
        return this.model;
    }

    private void showMaterialScreen() {
        if (this.model == null || this.closed) return;
        Minecraft mc = Minecraft.getInstance();
        if (this.materialScreen != null
                && ClientScreenAccess.currentScreen(mc) == this.materialScreen) {
            this.materialScreen.resumeBinding();
            return;
        }
        if (this.materialScreen != null) this.materialScreen.suspendBinding();
        GuiMaterialList next = new GuiMaterialList(this.model, this.legacyMainOwner,
                this.legacyOwnerName, this.legacyDeputyOwners);
        this.materialScreen = next;
        TeamManagementClient.onMaterialListOpened(next);
        ClientScreenAccess.showScreen(mc, next);
    }

    /** A child screen pauses only its parent's visible binding, not the project data lease. */
    public void materialScreenRemoved(GuiMaterialList screen, Screen replacement) {
        if (screen == null || screen != this.materialScreen || this.closed) return;
        if (isTemporaryTransition(screen, replacement)) {
            screen.suspendBinding();
            return;
        }
        closeMaterialScreen(screen);
    }

    public void materialScreenShown(GuiMaterialList screen) {
        if (screen != null && screen == this.materialScreen && !this.closed) {
            screen.resumeBinding();
        }
    }

    private boolean isTemporaryTransition(GuiMaterialList parent, Screen replacement) {
        if (replacement == null) {
            var selector = StagingAreaSelector.getInstance();
            var scope = selector.getScope();
            return selector.isActive() && scope != null
                    && parent.getSchematicId().equals(scope.projectId());
        }
        Screen cursor = replacement;
        while (cursor instanceof GuiBase gui) {
            if (cursor == parent) return true;
            cursor = gui.getParent();
        }
        return false;
    }

    public void closeMaterialScreen(GuiMaterialList screen) {
        if (screen == null || this.closed) return;
        boolean current = screen == this.materialScreen;
        if (current) this.materialScreen = null;
        this.session.hudVisible(screen.getMaterialList().getHudRenderer().getShouldRender());
        screen.suspendBinding();
        if (current) {
            TeamManagementClient.onMaterialListClosed(screen);
            this.view.closeGui(screen.getSchematicId());
            if (screen.getSchematicId().equals(this.requestedProject)) this.requestedProject = null;
        }
    }

    /** Reconciles third-party setScreen calls as well as the mod's explicit transitions. */
    public void reconcileScreen(Screen visible) {
        if (this.closed) return;
        this.reconcileConfigurationSubscriptions(visible);
        if (this.materialScreen == null) return;
        if (visible == this.materialScreen) {
            this.materialScreen.resumeBinding();
        } else if (!isTemporaryTransition(this.materialScreen, visible)) {
            closeMaterialScreen(this.materialScreen);
        }
    }

    private void state(ClientProjectViewStore store) {
        if (this.closed) return;
        ProjectViewScope scope = store.scope();
        if (scope == null) return;
        ProjectViewClient.ViewToken accepted = this.view.viewToken(scope);
        if (!this.view.isAcceptedView(accepted)) return;
        if (scope.isProject()) {
            String projectId = scope.projectId();
            StagingAreaRenderer renderer = StagingAreaRenderer.getInstance();
            renderer.activateProject(projectId);
            if (store.valid()) {
                store.configuration("LIST").ifPresent(areas ->
                        renderer.applyProjectConfiguration(projectId, areas,
                                store.configurationSequence("LIST"), accepted));
                store.configuration("LIST_WAREHOUSE_REFS").ifPresent(areas ->
                        renderer.applyWarehouseReferences(projectId, areas));
            } else if (this.model != null && projectId.equals(this.model.getSchematicId())) {
                this.model.invalidateProjectProgress();
                renderer.clearWarehouseContainers(projectId);
            }
        } else if (scope.global() && store.valid()) {
            store.configuration("LIST_WAREHOUSES").ifPresent(
                    StagingAreaRenderer.getInstance()::applyWarehouseDefinitions);
        }
        publishConfigurations(store);
        store.progress().ifPresent(progress -> {
            if (this.model != null && progress.projectId().equals(this.model.getSchematicId())
                    && this.model.applyProjectProgress(progress)) {
                this.session.catalogRevision(progress.catalogRevision());
                InventoryWatcher.enablePeriodicReports();
                this.syncInventory();
                StagingAreaRenderer.getInstance().setSchematicName(
                        progress.projectId(), progress.projectName());
            }
        });
        if (store.valid() && store.hasWarehouseSnapshot() && store.pendingWarehouseParts() == 0
                && scope.isProject()) {
            var entries = store.warehouse().stream().map(observation ->
                    new net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry(
                            observation.key().x(), observation.key().y(), observation.key().z(),
                            observation.presentItemIds().stream().sorted().toList())).toList();
            StagingAreaRenderer.getInstance().updateWarehouseContainers(scope.projectId(),
                    entries, store.warehouseWorld());
        }
        this.recomputePickup();
    }

    private void operation(ProjectViewS2CPacket packet) {
        if (this.closed) return;
        EditorResponseRouter.complete(packet);
        if (this.model != null) this.model.completeClaim(packet);
        if (packet.action() == ProjectViewAction.RESCAN && this.materialScreen != null
                && packet.scope().isProject()
                && packet.scope().projectId().equals(this.materialScreen.getSchematicId())) {
            this.materialScreen.onRescanResponse(packet.success(), packet.message());
        }
        String message;
        if (packet.result() == ProjectViewS2CPacket.Result.OPERATION
                && (packet.action() == ProjectViewAction.JOIN
                || packet.action() == ProjectViewAction.LEAVE)) {
            message = CollaborationResultLabels.message(packet.message());
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

    private void operationTimeout(ProjectViewClient.OperationTimeout timeout) {
        if (this.closed) return;
        boolean cleared = false;
        if (timeout.action() == ProjectViewAction.AREA_CONFIG) {
            cleared = EditorResponseRouter.timeout(timeout.requestId());
        } else if ((timeout.action() == ProjectViewAction.JOIN
                || timeout.action() == ProjectViewAction.LEAVE)
                && timeout.scope().isProject() && this.model != null
                && this.model.getSchematicId().equals(timeout.scope().projectId())) {
            cleared = this.model.claimTimeout(timeout.requestId());
        } else if (timeout.action() == ProjectViewAction.RESCAN && this.materialScreen != null
                && timeout.scope().isProject()
                && timeout.scope().projectId().equals(this.materialScreen.getSchematicId())) {
            this.materialScreen.onRescanUnconfirmed();
            cleared = true;
        }
        if (cleared) showUnconfirmed();
    }

    private static void showUnconfirmed() {
        fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.result.unconfirmed"));
    }

    public void managementCatalog(net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket update) {
        if (this.closed || !ClientProtocolState.serverSupports(ProtocolVersion.MATERIAL_CATALOG_UPDATES)
                || this.model == null || !this.model.getSchematicId().equals(update.schematicId())
                || update.revision() <= this.session.catalogRevision()) return;
        this.session.catalogRevision(update.revision());
        this.model.applyManagementCatalog(update);
        if (this.model.hasProjectProgress()) {
            InventoryWatcher.enablePeriodicReports();
            return;
        }
        this.model.setMaterialEntries(update.materials(), update.revision());
        this.model.setTitle(update.schematicName());
        this.model.useFoundationBackend();
        InventoryWatcher.enablePeriodicReports();
        StagingAreaRenderer.getInstance().setSchematicName(update.schematicId(), update.schematicName());
        this.syncInventory();
    }

    public void collaboration(net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket update) {
        SyncMaterialList current = materialList();
        if (!this.closed && current != null && current.getSchematicId().equals(update.schematicId())) {
            current.onCollaborationStatus(update);
        }
    }

    public void areaInventory(net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket snapshot) {
        if (this.closed || !ClientProtocolState.serverSupports(ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)
                || this.model == null || !this.model.getSchematicId().equals(snapshot.schematicId())) return;
        if (this.model.applyAreaInventorySnapshot(snapshot)) this.recomputePickup();
    }

    public void dimensionChanged() {
        if (this.closed) return;
        this.session.selector().onDimensionChanged();
        String projectId = this.view.projectStore().map(ClientProjectViewStore::scope)
                .filter(ProjectViewScope::isProject).map(ProjectViewScope::projectId).orElse(null);
        if (projectId == null) {
            StagingAreaRenderer.getInstance().clearWarehouseContainers();
            return;
        }
        StagingAreaRenderer.getInstance().invalidateProjectProjection(projectId);
        if (supportsViews()) this.view.dimensionChanged(projectId);
        this.recomputePickup();
        if (!supportsViews() && this.session.pickup().isActive()) {
            this.session.sendPacket(
                    new net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket(
                            projectId, true));
        }
    }

    public void deleted(String projectId) {
        if (this.closed || projectId == null) return;
        TeamManagementClient.onProjectDeleted(projectId);
        StagingAreaRenderer.getInstance().clearProject(projectId);
        var selection = StagingAreaSelector.getInstance().getScope();
        if (selection != null && projectId.equals(selection.projectId())) {
            StagingAreaSelector.getInstance().discard();
        }
        Minecraft mc = Minecraft.getInstance();
        Screen visible = ClientScreenAccess.currentScreen(mc);
        boolean deletedProjectScreen = switch (visible) {
            case null -> false;
            case net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal editor ->
                    projectId.equals(editor.getSchematicId());
            case net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion editor ->
                    projectId.equals(editor.getSchematicId());
            case net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup popup ->
                    projectId.equals(popup.getSchematicId());
            case GuiMaterialList gui -> projectId.equals(gui.getSchematicId());
            default -> false;
        };
        if (deletedProjectScreen) ClientScreenAccess.showScreen(mc, null);
        if (this.model == null || !this.model.getSchematicId().equals(projectId)) return;
        if (this.materialScreen != null) {
            this.materialScreen.suspendBinding();
            this.materialScreen = null;
        }
        this.model.clearBindings();
        this.model = null;
        this.session.materialList(null);
        this.session.catalogRevision(-1);
        this.requestedProject = null;
        InventoryWatcher.clearContext();
        this.session.pickup().clear();
        if (supportsViews()) this.view.closeProject(true);
        else this.session.sendPacket(
                new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(projectId));
    }

    private void globalDeleted() {
        StagingAreaRenderer.getInstance().clearWarehouseAreas();
    }

    public void syncInventory() {
        if (this.model == null || this.closed) return;
        Map<String, Integer> whitelist = new HashMap<>();
        for (var material : this.model.getMaterialsAll()) {
            whitelist.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                    material.getStack().getItem()).toString(), material.getDatabaseId());
        }
        InventoryWatcher.setContext(this.model.getSchematicId(), whitelist);
        MaterialListUtils.updateAvailableCounts(this.model.getMaterialsAll(), Minecraft.getInstance().player);
        this.recomputePickup();
    }

    public void recomputePickup() {
        this.session.pickup().recompute(this.model == null ? null : this.model.getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
    }

    /** Hidden screens still receive authoritative snapshots; visibility only gates widgets. */
    public ConfigurationSubscription subscribeConfigurations(GuiBase owner, ProjectViewScope scope,
            BiConsumer<String, List<net.syncmaterial.syncmaterial.core.AreaDefinition>> listener) {
        return subscribeConfigurations(owner, scope, listener, true);
    }

    public ConfigurationSubscription subscribeConfigurations(GuiBase owner, ProjectViewScope scope,
            BiConsumer<String, List<net.syncmaterial.syncmaterial.core.AreaDefinition>> listener,
            boolean replayCurrent) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(listener, "listener");
        ClientProjectViewStore store = scope.isProject() ? this.view.projectStore().orElse(null)
                : this.view.globalStore().orElse(null);
        for (ConfigurationSubscription existing : this.configurations.values()) {
            if (existing.owner == owner && existing.scope.equals(scope)) {
                if (replayCurrent && store != null && scope.equals(store.scope()) && store.valid()) {
                    publish(existing, store);
                }
                return existing;
            }
        }
        long id = ++this.nextSubscription;
        ConfigurationSubscription subscription = new ConfigurationSubscription(id, owner, scope, listener);
        this.configurations.put(id, subscription);
        if (replayCurrent && store != null && scope.equals(store.scope()) && store.valid()) {
            publish(subscription, store);
        }
        return subscription;
    }

    private void reconcileConfigurationSubscriptions(Screen visible) {
        for (ConfigurationSubscription subscription : List.copyOf(this.configurations.values())) {
            if (!isVisibleOrAncestor(subscription, visible)) subscription.close();
        }
    }

    private boolean isVisibleOrAncestor(ConfigurationSubscription subscription, Screen visible) {
        if (visible == null) {
            var selector = this.session.selector();
            var selection = selector.getScope();
            if (selector.isActive() && selection != null) {
                return subscription.scope.global() ? selection.global()
                        : subscription.scope.isProject()
                        && subscription.scope.projectId().equals(selection.projectId());
            }
        }
        Screen owner = subscription.owner;
        Screen cursor = visible;
        while (cursor instanceof GuiBase gui) {
            if (cursor == owner) return true;
            cursor = gui.getParent();
        }
        return false;
    }

    private void publishConfigurations(ClientProjectViewStore store) {
        ProjectViewScope scope = store.scope();
        if (scope == null || !store.valid()) return;
        for (ConfigurationSubscription subscription : List.copyOf(this.configurations.values())) {
            if (!subscription.scope.equals(scope)) continue;
            publish(subscription, store);
        }
    }

    private static void publish(ConfigurationSubscription subscription, ClientProjectViewStore store) {
        for (String action : List.of("LIST", "LIST_WAREHOUSE_REFS", "LIST_WAREHOUSES")) {
            store.configuration(action).ifPresent(areas -> subscription.listener.accept(action, areas));
        }
    }

    public final class ConfigurationSubscription implements AutoCloseable {
        private final long id;
        private final GuiBase owner;
        private final ProjectViewScope scope;
        private final BiConsumer<String, List<net.syncmaterial.syncmaterial.core.AreaDefinition>> listener;

        private ConfigurationSubscription(long id, GuiBase owner, ProjectViewScope scope,
                BiConsumer<String, List<net.syncmaterial.syncmaterial.core.AreaDefinition>> listener) {
            this.id = id;
            this.owner = owner;
            this.scope = scope;
            this.listener = listener;
        }

        @Override
        public void close() {
            ClientProjectCoordinator.this.configurations.remove(this.id, this);
        }
    }

    public void close() {
        if (this.closed) return;
        this.closed = true;
        TeamManagementClient.onMaterialListClosed(this.materialScreen);
        this.configurations.clear();
        if (this.materialScreen != null) this.materialScreen.suspendBinding();
        this.materialScreen = null;
        if (this.model != null) this.model.clearBindings();
        this.model = null;
        this.requestedProject = null;
        InventoryWatcher.clearContext();
    }

    private static boolean supportsViews() {
        return ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS);
    }
}
