package net.syncmaterial.syncmaterial.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;

/** One client-connection owner for scoped views, consumer leases and atomic content state. */
public final class ProjectViewClient {
    private final Object connection;
    private final long connectionGeneration;
    private final Consumer<CustomPacketPayload> transport;
    private final LongSupplier requestIds;
    private final BooleanSupplier protocol5;
    private final ProjectInterestLeases leases = new ProjectInterestLeases();
    private final ProjectViewAssembler assembler = new ProjectViewAssembler();
    static final int OPERATION_TIMEOUT_TICKS = 200;

    private final Map<Long, PendingRequest> pendingOpens = new HashMap<>();
    private final Map<Long, PendingOperation> pendingOperations = new HashMap<>();
    private final Map<Long, Integer> globalLeases = new HashMap<>();
    /** Guards the two primitives that the static connection entry points may write. */
    private final Object stateLock = new Object();
    private ViewState project;
    private ViewState global;
    private PendingProjectSwitch pendingProjectSwitch;
    private int globalServerMask;
    private long nextViewId;
    private long nextViewGeneration;
    private long nextGlobalLeaseToken;
    private boolean closed;
    private Consumer<ProjectViewS2CPacket> openedListener = packet -> {};
    private Consumer<ProjectViewS2CPacket> operationListener = packet -> {};
    private Consumer<OperationTimeout> operationTimeoutListener = timeout -> {};
    private Consumer<ClientProjectViewStore> stateListener = store -> {};
    private Consumer<ProjectViewScope> deletionListener = scope -> {};
    private Consumer<String> activeProjectListener = projectId -> {};

    public ProjectViewClient(Object connection, long connectionGeneration,
            Consumer<CustomPacketPayload> transport, LongSupplier requestIds,
            BooleanSupplier protocol5) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (connectionGeneration <= 0) throw new IllegalArgumentException("connectionGeneration must be > 0");
        this.connectionGeneration = connectionGeneration;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
        this.protocol5 = Objects.requireNonNull(protocol5, "protocol5");
    }

    /** Compatibility entry; production JOIN uses ClientSessionManager directly. */
    public static void join(Object connection) {
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.join(connection);
    }

    public static Optional<ProjectViewClient> current() {
        return net.syncmaterial.syncmaterial.client.session.ClientSessionManager.current()
                .map(net.syncmaterial.syncmaterial.client.session.ClientConnectionSession::projectView);
    }

    /** Client-tick backstop for disconnect cleanup and fast reconnects. */
    public static void discard() {
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.discard();
    }

    public static void disconnect(Object connection, long generation) {
        long pending = net.syncmaterial.syncmaterial.client.session.ClientSessionManager
                .invalidateConnection(connection);
        if (pending >= 0) {
            net.syncmaterial.syncmaterial.client.session.ClientSessionManager.finishDisconnect(pending);
        }
    }

    public void setOpenedListener(Consumer<ProjectViewS2CPacket> listener) {
        openedListener = Objects.requireNonNull(listener, "listener");
    }

    public void setOperationListener(Consumer<ProjectViewS2CPacket> listener) {
        operationListener = Objects.requireNonNull(listener, "listener");
    }

    /** A request that never received a result; callers may clear pending UI, never mark success. */
    public void setOperationTimeoutListener(Consumer<OperationTimeout> listener) {
        operationTimeoutListener = Objects.requireNonNull(listener, "listener");
    }

    public void setStateListener(Consumer<ClientProjectViewStore> listener) {
        stateListener = Objects.requireNonNull(listener, "listener");
    }

    public void setDeletionListener(Consumer<ProjectViewScope> listener) {
        deletionListener = Objects.requireNonNull(listener, "listener");
    }

    /** Called only after an OPEN was accepted, including a direct editor without a material GUI. */
    public void setActiveProjectListener(Consumer<String> listener) {
        this.activeProjectListener = Objects.requireNonNull(listener, "listener");
    }

    public long openProject(String projectId, int mask) {
        return openProject(projectId, mask, true);
    }

    /**
     * A live project is not closed when another project is requested: the new view is
     * opened in parallel and the old scope stays authoritative until the server acknowledges
     * the new OPEN. A failed OPEN therefore leaves the previous view, leases and consumer
     * state untouched. The server tracker keys views by scope, so two different projects may
     * coexist for this short hand-over; a same-scope refresh also waits for OPENED, and no
     * CLOSE for the old generation is sent because the server replaces that scope in place.
     */
    private long openProject(String projectId, int mask, boolean notifyOpened) {
        if (isClosed() || !protocol5.getAsBoolean()) return -1;
        ProjectViewScope scope = ProjectViewScope.project(projectId);
        PendingProjectSwitch pending = pendingProjectSwitch;
        if (pending != null) {
            if (pending.target.scope.equals(scope)) {
                if (notifyOpened) pending.target.notifyOpened = true;
                return pending.requestId;
            }
            // A superseding request for another scope may be queued only after the server
            // has processed the old OPEN. Close the abandoned candidate explicitly.
            cancelPendingProjectSwitch(true);
        }
        ViewState live = project;
        if (live == null) return openInitialProject(scope, mask, notifyOpened);
        return beginProjectSwitch(scope, mask, notifyOpened);
    }

    private long openInitialProject(ProjectViewScope scope, int mask, boolean notifyOpened) {
        long generation = nextViewGeneration();
        ViewState view = createView(scope, nextViewId(), generation);
        view.notifyOpened = notifyOpened;
        view.requestedMask = mask;
        long requestId = send(view, ProjectViewAction.OPEN, mask, 0, 0, null, null, List.of());
        if (requestId <= 0) {
            assembler.closeView(connection, connectionGeneration, view.viewId);
            view.store.clear();
            return -1;
        }
        project = view;
        leases.activate(scope.projectId(), generation);
        return requestId;
    }

    private long beginProjectSwitch(ProjectViewScope scope, int mask, boolean notifyOpened) {
        ViewState candidate = newView(scope, nextViewId(), nextViewGeneration());
        candidate.notifyOpened = notifyOpened;
        candidate.requestedMask = mask;
        long requestId = send(candidate, ProjectViewAction.OPEN, mask, 0, 0, null, null, List.of());
        if (requestId <= 0) {
            candidate.store.clear();
            return -1;
        }
        pendingProjectSwitch = new PendingProjectSwitch(requestId, candidate);
        return requestId;
    }

    /** Editors may be opened directly, without creating or reopening the material-list GUI. */
    public void openEditor(String projectId) {
        if (isClosed() || !protocol5.getAsBoolean()) return;
        if (!controlsProject(projectId)) openProject(projectId, ProjectInterestLeases.BASE_MASK, false);
        setEditor(projectId, true);
    }

    /**
     * Refreshes the active project view without creating or reopening any GUI. The caller
     * already owns the visible list/HUD and only wants a new authoritative publication.
     */
    public long refreshProject(String projectId) {
        if (isClosed() || !protocol5.getAsBoolean() || project == null
                || !project.scope.isProject() || !project.scope.projectId().equals(projectId)) {
            return -1;
        }
        return openProject(projectId, desiredMask(), false);
    }

    /**
     * Acquires one global-warehouse consumer. The first holder opens the global scope; the
     * last release sends CLOSE. Tokens are connection-scoped and idempotent on close.
     */
    public GlobalLease acquireGlobalWarehouse(int mask) {
        if (isClosed() || !protocol5.getAsBoolean()) return null;
        if (mask == 0 || (mask & ~InterestKind.ALL_MASK) != 0) {
            throw new IllegalArgumentException("invalid global interest mask: 0x"
                    + Integer.toHexString(mask));
        }
        long token = nextGlobalLeaseToken = Math.incrementExact(nextGlobalLeaseToken);
        int previousMask = desiredGlobalMask();
        globalLeases.put(token, mask);
        int desiredMask = desiredGlobalMask();
        try {
            if (global == null) {
                openGlobalView(desiredMask);
            } else if (desiredMask != previousMask) {
                syncGlobalMask();
            }
        } catch (RuntimeException failure) {
            globalLeases.remove(token);
            throw failure;
        }
        return new GlobalLease(this, token);
    }

    private void openGlobalView(int mask) {
        ViewState view = createView(ProjectViewScope.globalWarehouse(),
                nextViewId(), nextViewGeneration());
        global = view;
        long requestId = send(view, ProjectViewAction.OPEN, mask, 0, 0, null, null, List.of());
        if (requestId <= 0) {
            assembler.closeView(connection, connectionGeneration, view.viewId);
            view.store.clear();
            global = null;
            setGlobalServerMask(0);
        } else {
            setGlobalServerMask(mask);
        }
    }

    private void syncGlobalMask() {
        if (global == null) return;
        int desiredMask = desiredGlobalMask();
        if (desiredMask == 0) {
            closeGlobalView(true);
            return;
        }
        if (desiredMask == globalServerMask()) return;
        long requestId = send(global, ProjectViewAction.SET_INTERESTS, desiredMask,
                0, 0, null, null, List.of());
        if (requestId > 0) setGlobalServerMask(desiredMask);
    }

    private void closeGlobalView(boolean notifyServer) {
        ViewState previous = global;
        global = null;
        setGlobalServerMask(0);
        if (previous == null) return;
        if (notifyServer) send(previous, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
        assembler.closeView(connection, connectionGeneration, previous.viewId);
        previous.store.clear();
        removePending(previous);
    }

    private void releaseGlobalLease(long token) {
        if (globalLeases.remove(token) == null) return;
        if (globalLeases.isEmpty()) {
            closeGlobalView(true);
        } else {
            syncGlobalMask();
        }
    }

    private boolean retryGlobalLease(long token) {
        if (isClosed() || !globalLeases.containsKey(token)) return false;
        try {
            if (global == null) openGlobalView(desiredGlobalMask());
            else syncGlobalMask();
        } catch (RuntimeException failure) {
            return false;
        }
        return global != null;
    }

    private int desiredGlobalMask() {
        int mask = 0;
        for (Integer value : globalLeases.values()) mask |= value;
        return mask;
    }

    private void restoreConsumers(boolean gui, boolean pickup, boolean outlines, boolean editor) {
        if (project == null) return;
        String id = project.scope.projectId();
        long generation = project.generation;
        if (gui) applyLease(leases.openGui(id, generation));
        if (pickup) applyLease(leases.setPickup(id, generation, true));
        if (outlines) applyLease(leases.setOutlines(id, generation, true));
        if (editor) applyLease(leases.setEditor(id, generation, true));
    }

    public void openGui(String projectId) {
        if (controlsProject(projectId)) applyLease(leases.openGui(projectId, project.generation));
    }

    public void closeGui(String projectId) {
        if (controlsProject(projectId)) {
            suppressPendingOpened(projectId);
            applyLease(leases.closeGui(projectId, project.generation));
        } else if (pendingProjectSwitch != null
                && pendingProjectSwitch.target.scope.isProject()
                && pendingProjectSwitch.target.scope.projectId().equals(projectId)) {
            // The original list was closed before the late OPENED: keep the data view but
            // do not reopen a GUI the user explicitly dismissed.
            pendingProjectSwitch.target.notifyOpened = false;
        }
    }

    private void suppressPendingOpened(String projectId) {
        if (project != null && project.scope.isProject()
                && project.scope.projectId().equals(projectId)
                && hasPendingOpen(project)) {
            project.notifyOpened = false;
        }
        if (pendingProjectSwitch != null
                && pendingProjectSwitch.target.scope.projectId().equals(projectId)) {
            pendingProjectSwitch.target.notifyOpened = false;
        }
    }

    private boolean hasPendingOpen(ViewState view) {
        for (PendingRequest pending : pendingOpens.values()) {
            if (pending.action() == ProjectViewAction.OPEN
                    && pending.matches(view.scope, view.viewId, view.generation)) {
                return true;
            }
        }
        return false;
    }

    public void setPickup(String projectId, boolean active) {
        if (controlsProject(projectId)) applyLease(leases.setPickup(projectId, project.generation, active));
    }

    public void setOutlines(String projectId, boolean active) {
        if (controlsProject(projectId)) applyLease(leases.setOutlines(projectId, project.generation, active));
    }

    public void setEditor(String projectId, boolean active) {
        if (controlsProject(projectId)) applyLease(leases.setEditor(projectId, project.generation, active));
    }

    public void dimensionChanged(String projectId) {
        if (!controlsProject(projectId)) return;
        boolean gui = leases.isGuiOpen();
        boolean pickup = leases.isPickupActive();
        boolean outlines = leases.isOutlinesActive();
        boolean editor = leases.isEditorOpen();
        cancelPendingProjectSwitch(true);
        long generation = nextViewGeneration();
        ViewState previous = project;
        ViewState replacement = newView(ProjectViewScope.project(projectId), previous.viewId, generation);
        int openMask = leases.desiredMask();
        replacement.requestedMask = openMask;
        // Send the in-place OPEN before touching the live view. The server keys views by
        // scope, so this OPEN replaces the same-scope generation; a local send failure must
        // leave the old generation and its consumer leases intact.
        long requestId = send(replacement, ProjectViewAction.OPEN, openMask, 0, 0,
                null, null, List.of());
        if (requestId <= 0) {
            replacement.store.clear();
            return;
        }
        ProjectInterestLeases.Change change = leases.dimensionChanged(projectId, generation);
        if (!change.changed()) {
            send(replacement, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
            replacement.store.clear();
            removePending(replacement);
            return;
        }
        assembler.closeView(connection, connectionGeneration, previous.viewId);
        previous.store.clear();
        removePending(previous);
        if (!assembler.openView(connection, connectionGeneration, replacement.scope,
                replacement.viewId, replacement.generation)) {
            send(replacement, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
            replacement.store.clear();
            project = replacement;
            clearUnconfirmedProject(replacement);
            return;
        }
        project = replacement;
        restoreConsumers(gui, pickup, outlines, editor);
        synchronizeProjectMask(replacement);
    }

    public long join(String projectId, int materialId,
                     List<ProjectViewCommandC2SPacket.InventoryCount> inventory) {
        if (!controlsProject(projectId)) return -1;
        PendingOperation pending = new PendingOperation(project.scope, project.viewId,
                project.generation, ProjectViewAction.JOIN, materialId, 0);
        long requestId = send(project, ProjectViewAction.JOIN, 0, materialId, 0, null, null, inventory);
        if (requestId > 0) pendingOperations.put(requestId, pending);
        return requestId;
    }

    public long leave(String projectId, int materialId) {
        if (!controlsProject(projectId)) return -1;
        PendingOperation pending = new PendingOperation(project.scope, project.viewId,
                project.generation, ProjectViewAction.LEAVE, materialId, 0);
        long requestId = send(project, ProjectViewAction.LEAVE, 0, materialId, 0, null, null, List.of());
        if (requestId > 0) pendingOperations.put(requestId, pending);
        return requestId;
    }

    public long rescan(String projectId, int areaId) {
        if (!controlsProject(projectId)) return -1;
        PendingOperation pending = new PendingOperation(project.scope, project.viewId,
                project.generation, ProjectViewAction.RESCAN, 0, areaId);
        long requestId = send(project, ProjectViewAction.RESCAN, 0, 0, areaId,
                null, null, List.of());
        if (requestId > 0) pendingOperations.put(requestId, pending);
        return requestId;
    }

    public long areaConfig(ProjectViewScope scope, int areaId, String areaAction,
                           Optional<StagingAreaConfigC2SPacket.AreaData> areaData) {
        ViewState view = viewForAreaConfig(scope);
        if (view == null || !protocol5.getAsBoolean()) return -1;
        PendingOperation pending = new PendingOperation(scope, view.viewId,
                view.generation, ProjectViewAction.AREA_CONFIG, 0, areaId);
        long requestId = send(view, ProjectViewAction.AREA_CONFIG, 0, 0, areaId,
                areaAction, areaData, List.of());
        if (requestId > 0) pendingOperations.put(requestId, pending);
        return requestId;
    }

    public void onPacket(Object packetConnection, long packetGeneration, ProjectViewS2CPacket packet) {
        if (packetConnection != connection || packetGeneration != connectionGeneration
                || !protocol5.getAsBoolean()) return;
        if (handlePendingProjectSwitch(packet)) return;
        ViewState target = view(packet.scope());
        if (packet.result() == ProjectViewS2CPacket.Result.OPENED) {
            PendingRequest pending = pendingOpens.get(packet.requestId());
            if (pending != null && pending.action() == ProjectViewAction.OPEN
                    && pending.matches(packet.scope(), packet.viewId(), packet.generation())
                    && target != null && target.matches(packet.scope(), packet.viewId(), packet.generation())) {
                pendingOpens.remove(packet.requestId(), pending);
                target.confirmed = true;
                if (target == project) {
                    synchronizeProjectMask(target);
                    activeProjectListener.accept(target.scope.projectId());
                }
                if (target.notifyOpened) openedListener.accept(packet);
            }
            return;
        }
        if (packet.result() == ProjectViewS2CPacket.Result.PROGRESS_PART) {
            ProjectViewAssembler.Outcome result = assembler.accept(connection, connectionGeneration, packet);
            if (result instanceof ProjectViewAssembler.Complete complete && target != null
                    && target.store.install(complete.view())) stateListener.accept(target.store);
            return;
        }
        if (target == null || !target.matches(packet.scope(), packet.viewId(), packet.generation())) return;
        boolean changed = switch (packet.result()) {
            case CONFIG -> configuration(target, packet);
            case WAREHOUSE -> target.store.warehouse(packet.scope(), packet.viewId(), packet.generation(),
                    packet.sequence(), packet.warehouse(), packet.warehouse().isEmpty()
                            && "COMPLETE".equals(packet.message()));
            case OPERATION -> operation(packet);
            case INVALIDATED -> target.store.invalidated(packet.scope(), packet.viewId(),
                    packet.generation(), packet.message());
            case ERROR -> error(target, packet);
            case DELETED -> delete(target, packet);
            default -> false;
        };
        if (changed) stateListener.accept(target.store);
    }

    private boolean handlePendingProjectSwitch(ProjectViewS2CPacket packet) {
        PendingProjectSwitch pending = pendingProjectSwitch;
        if (pending == null || packet.requestId() != pending.requestId()
                || !pending.target.matches(packet.scope(), packet.viewId(), packet.generation())) {
            return false;
        }
        if (packet.result() == ProjectViewS2CPacket.Result.OPENED) {
            PendingRequest open = pendingOpens.get(packet.requestId());
            if (open == null || open.action() != ProjectViewAction.OPEN
                    || !open.matches(packet.scope(), packet.viewId(), packet.generation())) {
                pendingProjectSwitch = null;
                send(pending.target, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
                discardProjectCandidate(pending.target);
                return true;
            }
            pendingOpens.remove(packet.requestId(), open);
            pendingProjectSwitch = null;
            completeProjectSwitch(pending, packet);
            return true;
        }
        if (packet.result() == ProjectViewS2CPacket.Result.ERROR) {
            pendingOpens.remove(packet.requestId());
            pendingProjectSwitch = null;
            discardProjectCandidate(pending.target);
            if (project != null && !project.confirmed) clearUnconfirmedProject(project);
            operationListener.accept(packet);
            return true;
        }
        return false;
    }

    private static boolean targetScopeRefreshes(ViewState current, ProjectViewScope target) {
        return current != null && current.scope.equals(target);
    }

    private void completeProjectSwitch(PendingProjectSwitch pending, ProjectViewS2CPacket packet) {
        boolean gui = leases.isGuiOpen() && (targetScopeRefreshes(project, pending.target.scope)
                || pending.target.notifyOpened);
        boolean pickup = leases.isPickupActive();
        boolean outlines = leases.isOutlinesActive();
        ViewState previous = project;
        // The editor is a screen, not a transferable user mode: only a same-scope refresh
        // may keep it. A different project's editor must not gain this project's lease.
        boolean editor = leases.isEditorOpen()
                && previous != null && previous.scope.equals(pending.target.scope);
        ViewState target = pending.target;
        if (!assembler.openView(connection, connectionGeneration, target.scope,
                target.viewId, target.generation)) {
            send(target, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
            target.store.clear();
            removePending(target);
            return;
        }
        if (previous != null) {
            // Different scopes coexist on the server until this OPENED; only then may the
            // old view be closed. A same-scope OPEN has already replaced it server-side.
            if (!previous.scope.equals(target.scope)) {
                send(previous, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
            }
            assembler.closeView(connection, connectionGeneration, previous.viewId);
            previous.store.clear();
            removePending(previous);
        }
        leases.activate(target.scope.projectId(), target.generation);
        target.confirmed = true;
        project = target;
        activeProjectListener.accept(target.scope.projectId());
        restoreConsumers(gui, pickup, outlines, editor);
        synchronizeProjectMask(target);
        if (target.notifyOpened) openedListener.accept(packet);
    }

    private void discardProjectCandidate(ViewState view) {
        assembler.closeView(connection, connectionGeneration, view.viewId);
        view.store.clear();
        removePending(view);
    }

    private void clearUnconfirmedProject(ViewState view) {
        if (project != view) return;
        assembler.closeView(connection, connectionGeneration, view.viewId);
        view.store.clear();
        project = null;
        leases.closeProject(view.scope.projectId(), view.generation);
        removePending(view);
    }

    private void cancelPendingProjectSwitch(boolean notifyServer) {
        PendingProjectSwitch pending = pendingProjectSwitch;
        if (pending == null) return;
        pendingProjectSwitch = null;
        pendingOpens.remove(pending.requestId);
        if (notifyServer) send(pending.target, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
        discardProjectCandidate(pending.target);
    }

    private boolean configuration(ViewState target, ProjectViewS2CPacket packet) {
        boolean changed = target.store.configuration(packet.scope(), packet.viewId(), packet.generation(),
                packet.sequence(), packet.configuration().orElseThrow().action(),
                packet.configuration().orElseThrow().areas());
        if (packet.requestId() > 0) return operation(packet) || changed;
        return changed;
    }

    private boolean error(ViewState target, ProjectViewS2CPacket packet) {
        boolean changed = target.store.error(packet.scope(), packet.viewId(),
                packet.generation(), packet.message());
        PendingRequest opened = pendingOpens.get(packet.requestId());
        if (opened != null && opened.matches(packet.scope(), packet.viewId(), packet.generation())) {
            pendingOpens.remove(packet.requestId(), opened);
            if (target == global && !target.confirmed) {
                closeGlobalView(false);
            } else if (target == project && !target.confirmed) {
                clearUnconfirmedProject(target);
            }
            operationListener.accept(packet);
            return true;
        }
        return operation(packet) || changed;
    }

    private boolean operation(ProjectViewS2CPacket packet) {
        if (packet.requestId() == 0) return false;
        PendingOperation pending = pendingOperations.get(packet.requestId());
        if (pending == null || pending.action != packet.action()
                || !pending.matches(packet.scope(), packet.viewId(), packet.generation())) {
            return false;
        }
        pendingOperations.remove(packet.requestId(), pending);
        operationListener.accept(packet);
        return true;
    }

    private boolean delete(ViewState target, ProjectViewS2CPacket packet) {
        boolean changed = target.store.deleted(packet.scope(), packet.viewId(), packet.generation());
        assembler.closeView(connection, connectionGeneration, packet.viewId());
        if (target == project) {
            if (pendingProjectSwitch != null
                    && pendingProjectSwitch.target.scope.equals(packet.scope())) {
                cancelPendingProjectSwitch(false);
            }
            leases.projectDeleted(packet.scope().projectId(), packet.generation());
            project = null;
        } else if (target == global) {
            global = null;
            setGlobalServerMask(0);
        } else if (pendingProjectSwitch != null && pendingProjectSwitch.target == target) {
            cancelPendingProjectSwitch(false);
        }
        pendingOpens.entrySet().removeIf(entry -> entry.getValue().matches(
                packet.scope(), packet.viewId(), packet.generation()));
        notifyOperationTimeouts(takePendingOperations(packet.scope(),
                packet.viewId(), packet.generation()));
        deletionListener.accept(packet.scope());
        return changed;
    }

    public void tick() {
        for (var timeout : assembler.tick()) {
            ViewState target = view(timeout.viewId());
            if (target != null && target.store.error(target.scope, target.viewId,
                    target.generation, "progress fragment timeout")) stateListener.accept(target.store);
        }
        expireOperations();
    }

    /**
     * Removes operations whose reply never arrived and reports them as unconfirmed. A late
     * acknowledgement finds no pending entry, so it can neither complete a newer request nor
     * resurrect this one; nothing is retried here.
     */
    private void expireOperations() {
        List<OperationTimeout> expired = new ArrayList<>();
        Iterator<Map.Entry<Long, PendingOperation>> iterator = pendingOperations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingOperation> entry = iterator.next();
            PendingOperation pending = entry.getValue();
            if (--pending.ticksRemaining > 0) continue;
            iterator.remove();
            expired.add(timeoutOf(entry.getKey(), pending));
        }
        notifyOperationTimeouts(expired);
    }

    private static OperationTimeout timeoutOf(long requestId, PendingOperation pending) {
        return new OperationTimeout(requestId, pending.scope, pending.viewId,
                pending.generation, pending.action, pending.materialId, pending.areaId);
    }

    private void notifyOperationTimeouts(List<OperationTimeout> timeouts) {
        for (OperationTimeout timeout : timeouts) operationTimeoutListener.accept(timeout);
    }

    /**
     * Drops operations that can no longer be answered because their view generation was
     * replaced, closed or deleted. Each canceled intent is reported once as unconfirmed;
     * only entries matching this exact scope/view/generation are touched.
     */
    private List<OperationTimeout> takePendingOperations(ProjectViewScope scope,
                                                         long viewId, long generation) {
        List<OperationTimeout> canceled = new ArrayList<>();
        Iterator<Map.Entry<Long, PendingOperation>> iterator = pendingOperations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingOperation> entry = iterator.next();
            PendingOperation pending = entry.getValue();
            if (!pending.matches(scope, viewId, generation)) continue;
            iterator.remove();
            canceled.add(timeoutOf(entry.getKey(), pending));
        }
        return canceled;
    }

    public void closeProject(boolean notifyServer) {
        cancelPendingProjectSwitch(notifyServer);
        ViewState previous = project;
        if (previous == null) return;
        if (notifyServer) send(previous, ProjectViewAction.CLOSE, 0, 0, 0, null, null, List.of());
        assembler.closeView(connection, connectionGeneration, previous.viewId);
        previous.store.clear();
        project = null;
        leases.closeProject(previous.scope.projectId(), previous.generation);
        removePending(previous);
    }

    public void closeGlobal(boolean notifyServer) {
        globalLeases.clear();
        closeGlobalView(notifyServer);
    }

    /** Idempotent close owned by the enclosing ClientConnectionSession. */
    public void close() {
        closeConnection();
    }

    private void closeConnection() {
        if (!beginClose()) return;
        if (pendingProjectSwitch != null) {
            pendingProjectSwitch.target.store.clear();
            pendingProjectSwitch = null;
        }
        if (project != null) assembler.closeView(connection, connectionGeneration, project.viewId);
        if (global != null) assembler.closeView(connection, connectionGeneration, global.viewId);
        assembler.closeConnection(connection, connectionGeneration);
        leases.disconnected();
        if (project != null) project.store.clear();
        if (global != null) global.store.clear();
        project = null;
        global = null;
        globalLeases.clear();
        setGlobalServerMask(0);
        pendingOpens.clear();
        pendingOperations.clear();
    }

    private void applyLease(ProjectInterestLeases.Change change) {
        if (!change.changed() || project == null
                || !project.scope.projectId().equals(change.projectId())
                || project.generation != change.generation()) return;
        long requestId = send(project, ProjectViewAction.SET_INTERESTS, change.desiredMask(),
                0, 0, null, null, List.of());
        if (requestId > 0) project.requestedMask = change.desiredMask();
    }

    /** Reconciles the server mask when consumers changed while an OPENED was still pending. */
    private void synchronizeProjectMask(ViewState view) {
        if (view == null || view != project) return;
        int desiredMask = leases.desiredMask();
        if (desiredMask == view.requestedMask) return;
        long requestId = send(view, ProjectViewAction.SET_INTERESTS, desiredMask,
                0, 0, null, null, List.of());
        if (requestId > 0) view.requestedMask = desiredMask;
    }

    private ViewState createView(ProjectViewScope scope, long viewId, long generation) {
        ViewState view = newView(scope, viewId, generation);
        if (!assembler.openView(connection, connectionGeneration, scope, viewId, generation)) {
            view.store.clear();
            throw new IllegalStateException("project view generation is no longer current");
        }
        return view;
    }

    private ViewState newView(ProjectViewScope scope, long viewId, long generation) {
        ClientProjectViewStore store = new ClientProjectViewStore();
        if (!store.open(scope, viewId, generation)) throw new IllegalStateException("view scope changed in place");
        return new ViewState(scope, viewId, generation, store);
    }

    private long send(ViewState view, ProjectViewAction action, int mask, int materialId, int areaId,
            String areaAction, Optional<StagingAreaConfigC2SPacket.AreaData> areaData,
            List<ProjectViewCommandC2SPacket.InventoryCount> inventory) {
        if (action == ProjectViewAction.OPEN && pendingOpens.size() >= 4
                || (action == ProjectViewAction.JOIN || action == ProjectViewAction.LEAVE
                || action == ProjectViewAction.RESCAN || action == ProjectViewAction.AREA_CONFIG)
                && pendingOperations.size() >= 64) return -1;
        long requestId = requestIds.getAsLong();
        if (requestId <= 0 || !protocol5.getAsBoolean()) return -1;
        ProjectViewCommandC2SPacket packet = new ProjectViewCommandC2SPacket(view.scope, requestId,
                view.viewId, view.generation, action, areaAction, materialId, areaId, mask,
                areaData == null ? Optional.empty() : areaData, inventory);
        transport.accept(packet);
        if (action == ProjectViewAction.OPEN) {
            pendingOpens.put(requestId, new PendingRequest(view.scope, view.viewId,
                    view.generation, action));
        }
        return requestId;
    }

    private boolean controlsProject(String projectId) {
        return protocol5.getAsBoolean() && project != null && project.scope.isProject()
                && project.scope.projectId().equals(projectId);
    }

    private boolean isClosed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    /** Returns false when another thread already began the close. */
    private boolean beginClose() {
        synchronized (stateLock) {
            if (closed) return false;
            closed = true;
            return true;
        }
    }

    private int globalServerMask() {
        synchronized (stateLock) {
            return globalServerMask;
        }
    }

    private void setGlobalServerMask(int mask) {
        synchronized (stateLock) {
            globalServerMask = mask;
        }
    }

    private ViewState view(ProjectViewScope scope) {
        if (project != null && project.scope.equals(scope)) return project;
        if (global != null && global.scope.equals(scope)) return global;
        if (pendingProjectSwitch != null && pendingProjectSwitch.target.scope.equals(scope)) {
            return pendingProjectSwitch.target;
        }
        return null;
    }

    private ViewState viewForAreaConfig(ProjectViewScope scope) {
        if (pendingProjectSwitch != null && pendingProjectSwitch.target.scope.equals(scope)) {
            return pendingProjectSwitch.target;
        }
        return view(scope);
    }

    /**
     * Snapshot of the exact view generation that would receive an area-config write now.
     * Delayed editor intents capture this and must be dropped once it is no longer current.
     */
    public ViewToken viewToken(ProjectViewScope scope) {
        Objects.requireNonNull(scope, "scope");
        ViewState view = viewForAreaConfig(scope);
        return view == null ? null : new ViewToken(this, scope, view.viewId, view.generation);
    }

    /** True only after this exact view generation was accepted by the server. */
    public boolean isAcceptedView(ViewToken token) {
        if (token == null || token.session() != this) return false;
        ViewState view = token.scope().global() ? this.global : this.project;
        return view != null && view.confirmed
                && view.matches(token.scope(), token.viewId(), token.generation());
    }

    /** Whether {@code token} still names this session's live view for its scope. */
    public boolean isCurrentView(ViewToken token) {
        if (token == null || token.session() != this) return false;
        ViewState view = viewForAreaConfig(token.scope());
        return view != null && view.viewId == token.viewId() && view.generation == token.generation();
    }

    private ViewState view(long viewId) {
        if (project != null && project.viewId == viewId) return project;
        if (global != null && global.viewId == viewId) return global;
        return null;
    }

    private long nextViewId() {
        nextViewId = Math.incrementExact(nextViewId);
        return nextViewId;
    }

    private long nextViewGeneration() {
        nextViewGeneration = Math.incrementExact(nextViewGeneration);
        return nextViewGeneration;
    }

    private void removePending(ViewState view) {
        pendingOpens.entrySet().removeIf(entry -> entry.getValue().matches(
                view.scope, view.viewId, view.generation));
        notifyOperationTimeouts(takePendingOperations(view.scope, view.viewId, view.generation));
    }

    public Optional<ClientProjectViewStore> projectStore() {
        return project == null ? Optional.empty() : Optional.of(project.store);
    }

    public Optional<ClientProjectViewStore> globalStore() {
        return global == null ? Optional.empty() : Optional.of(global.store);
    }

    public int desiredMask() {
        return leases.desiredMask();
    }

    /**
     * Identity of one exact view generation. {@code session} is intentionally part of the
     * identity: view ids restart on a new connection, so ids alone cannot fence old intents.
     */
    public record ViewToken(ProjectViewClient session, ProjectViewScope scope,
                            long viewId, long generation) {
        public ViewToken {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(scope, "scope");
        }

        public boolean matches(ProjectViewScope scope, long viewId, long generation) {
            return this.scope.equals(scope) && this.viewId == viewId && this.generation == generation;
        }
    }

    /** One reference-counted consumer of the global warehouse view. */
    public static final class GlobalLease {
        private ProjectViewClient owner;
        private final long token;

        private GlobalLease(ProjectViewClient owner, long token) {
            this.owner = owner;
            this.token = token;
        }

        public boolean isHeld() {
            return owner != null;
        }

        /** Re-opens the global view after a failed OPEN, or reconciles a missing mask. */
        public boolean retry() {
            ProjectViewClient session = owner;
            return session != null && session.retryGlobalLease(token);
        }

        public void close() {
            ProjectViewClient session = owner;
            owner = null;
            if (session != null) session.releaseGlobalLease(token);
        }
    }

    private record PendingProjectSwitch(long requestId, ViewState target) {
    }

    private static final class ViewState {
        private final ProjectViewScope scope;
        private final long viewId;
        private final long generation;
        private final ClientProjectViewStore store;
        private boolean notifyOpened;
        private boolean confirmed;
        private int requestedMask;

        private ViewState(ProjectViewScope scope, long viewId, long generation,
                          ClientProjectViewStore store) {
            this.scope = scope;
            this.viewId = viewId;
            this.generation = generation;
            this.store = store;
        }

        private boolean matches(ProjectViewScope scope, long viewId, long generation) {
            return this.scope.equals(scope) && this.viewId == viewId && this.generation == generation;
        }
    }

    /** One operation whose reply is still awaited; the tick countdown is client-thread only. */
    private static final class PendingOperation {
        private final ProjectViewScope scope;
        private final long viewId;
        private final long generation;
        private final ProjectViewAction action;
        private final int materialId;
        private final int areaId;
        private int ticksRemaining = OPERATION_TIMEOUT_TICKS;

        private PendingOperation(ProjectViewScope scope, long viewId, long generation,
                                 ProjectViewAction action, int materialId, int areaId) {
            this.scope = scope;
            this.viewId = viewId;
            this.generation = generation;
            this.action = action;
            this.materialId = materialId;
            this.areaId = areaId;
        }

        private boolean matches(ProjectViewScope scope, long viewId, long generation) {
            return this.scope.equals(scope) && this.viewId == viewId && this.generation == generation;
        }
    }

    /** A correlated operation that exhausted its reply window; it is neither failed nor applied. */
    public record OperationTimeout(long requestId, ProjectViewScope scope, long viewId,
                                   long generation, ProjectViewAction action, int materialId,
                                   int areaId) {
    }

    private record PendingRequest(ProjectViewScope scope, long viewId, long generation,
                                  ProjectViewAction action) {
        private boolean matches(ProjectViewScope scope, long viewId, long generation) {
            return this.scope.equals(scope) && this.viewId == viewId && this.generation == generation;
        }
    }
}
