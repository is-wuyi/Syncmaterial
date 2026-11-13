package net.syncmaterial.syncmaterial.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket;
import net.syncmaterial.syncmaterial.network.BatchAssignResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.KickFromMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.TeamCommandC2SPacket;

/**
 * 项目级团队管理的客户端关联层（协议 4）。
 *
 * <p>所有管理请求都在发起时捕获四样东西：连接代次、项目、原始材料列表界面、
 * 发起请求的弹窗。响应只按 requestId 回到这份捕获，不按"当前屏幕是什么类型"
 * 猜归属；断线、换项目、原理图删除、列表或弹窗关闭都会使捕获失效。requestId 为 0
 * 的包是服务端主动推送的权威团队状态，只更新已知角色，绝不打开界面、绝不清空
 * 材料目录或区域库存状态。</p>
 *
 * <p>旧服务端（协议 &lt; 4）继续使用冻结的 Phase 4 包。旧响应没有 requestId 与
 * 项目字段，只能靠"同一项目 + 每种包 FIFO"关联；因此不支持并发的跨项目请求，
 * 也不会把任何没有对应请求的广播套到当前界面上。旧服务端仍可能向订阅者广播
 * 额外响应，这种无法消除的错配风险在旧模式中明确保留，不假装可以完全关联。</p>
 */
public final class TeamManagementClient {
    private enum LegacyKind {
        OWNER_ACTION,
        ASSIGN,
        KICK,
        PLAYERS
    }

    private static final class PendingRequest {
        final long requestId;
        final String projectId;
        final TeamAction action;
        final long generation;
        final GuiMaterialList list;
        final GuiPlayerSelectDialog dialog;
        final OperationGroup group;

        PendingRequest(long requestId, String projectId, TeamAction action, long generation,
                       GuiMaterialList list, GuiPlayerSelectDialog dialog, OperationGroup group) {
            this.requestId = requestId;
            this.projectId = projectId;
            this.action = action;
            this.generation = generation;
            this.list = list;
            this.dialog = dialog;
            this.group = group;
        }

        boolean isPlayersRequest() {
            return this.action == TeamAction.PLAYERS;
        }
    }

    private static final class OperationGroup {
        final String projectId;
        final long generation;
        final GuiMaterialList list;
        final GuiPlayerSelectDialog dialog;
        final TeamAction action;
        final List<Integer> materialIds;
        final List<PlayerIdentity> allTargets;
        final Deque<PlayerIdentity> remaining = new ArrayDeque<>();
        final boolean singleRequest;
        final boolean scoped;
        boolean awaiting;
        boolean finished;
        boolean anySuccess;
        int failures;
        String successMessage = "";
        String failureMessage = "";

        OperationGroup(String projectId, long generation, GuiMaterialList list,
                       GuiPlayerSelectDialog dialog, TeamAction action, List<Integer> materialIds,
                       List<PlayerIdentity> targets, boolean singleRequest, boolean scoped) {
            this.projectId = projectId;
            this.generation = generation;
            this.list = list;
            this.dialog = dialog;
            this.action = action;
            this.materialIds = List.copyOf(materialIds);
            this.allTargets = List.copyOf(targets);
            this.singleRequest = singleRequest;
            this.scoped = scoped;
            if (!singleRequest) {
                this.remaining.addAll(targets);
            }
        }
    }

    private static final class LegacyPending {
        final LegacyKind kind;
        final String projectId;
        final long generation;
        final GuiMaterialList list;
        final GuiPlayerSelectDialog dialog;
        final OperationGroup group;

        LegacyPending(LegacyKind kind, String projectId, long generation,
                      GuiMaterialList list, GuiPlayerSelectDialog dialog, OperationGroup group) {
            this.kind = kind;
            this.projectId = projectId;
            this.generation = generation;
            this.list = list;
            this.dialog = dialog;
            this.group = group;
        }
    }

    private static final Consumer<CustomPacketPayload> DEFAULT_TRANSPORT =
            payload -> ClientPlayNetworking.send(payload);

    private static final Map<Long, PendingRequest> PENDING = new LinkedHashMap<>();
    private static final Map<LegacyKind, Deque<LegacyPending>> LEGACY_QUEUES =
            new EnumMap<>(LegacyKind.class);
    private static final Map<String, TeamState> TEAM_STATES = new HashMap<>();
    private static final Map<String, Long> TEAM_SEQUENCES = new HashMap<>();

    private static Consumer<CustomPacketPayload> transport = DEFAULT_TRANSPORT;
    private static Supplier<PlayerIdentity> identityProvider = TeamManagementClient::minecraftIdentity;
    private static Supplier<Screen> screenProvider = TeamManagementClient::minecraftScreen;

    private static final Object CONNECTION_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicLong CONNECTION_GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong PENDING_DISCONNECT = new java.util.concurrent.atomic.AtomicLong(-1);
    private static Object activeConnection;
    private static volatile boolean connected;
    private static final java.util.concurrent.atomic.AtomicLong NEXT_REQUEST_ID = new java.util.concurrent.atomic.AtomicLong(1);
    private static String activeProject;
    private static GuiMaterialList activeGui;
    private static SyncMaterialList activeList;

    private TeamManagementClient() {
    }

    // ========== 生命周期 ==========

    /** 进入服务器：开启新的连接代次，旧连接的所有关联与状态全部作废。 */
    public static void onJoin() {
        onJoin(new Object());
    }

    public static void onJoin(Object connection) {
        java.util.Objects.requireNonNull(connection, "connection");
        synchronized (CONNECTION_LOCK) {
            CONNECTION_GENERATION.incrementAndGet();
            PENDING_DISCONNECT.set(-1);
            activeConnection = connection;
            connected = true;
        }
        PENDING.clear();
        LEGACY_QUEUES.clear();
        TEAM_STATES.clear();
        TEAM_SEQUENCES.clear();
        activeProject = null;
        activeGui = null;
        activeList = null;
        // requestId 在进程内保持单调递增，不随连接重置：连接代次已经负责隔离旧回包。
    }

    /** Client-thread convenience; networking callbacks invalidate the original connection only. */
    public static void onDisconnect() {
        finishDisconnect(invalidateConnection());
    }

    /** Netty may invalidate the connection, but only the client thread owns mutable maps. */
    public static long invalidateConnection() {
        synchronized (CONNECTION_LOCK) {
            return invalidateActiveConnection();
        }
    }

    public static long invalidateConnection(Object connection) {
        synchronized (CONNECTION_LOCK) {
            if (connection == null || activeConnection != connection) return -1;
            return invalidateActiveConnection();
        }
    }

    private static long invalidateActiveConnection() {
        if (!connected) return PENDING_DISCONNECT.get();
        connected = false;
        long token = CONNECTION_GENERATION.incrementAndGet();
        PENDING_DISCONNECT.set(token);
        return token;
    }

    /** Client ticks own this backstop because Minecraft.disconnect may cancel queued tasks. */
    public static boolean finishPendingDisconnect() {
        return finishDisconnect(PENDING_DISCONNECT.get());
    }

    public static boolean finishDisconnect(long token) {
        synchronized (CONNECTION_LOCK) {
            if (token < 0 || token != CONNECTION_GENERATION.get() || connected
                    || !PENDING_DISCONNECT.compareAndSet(token, -1)) return false;
            activeConnection = null;
        }
        PENDING.clear();
        LEGACY_QUEUES.clear();
        TEAM_STATES.clear();
        TEAM_SEQUENCES.clear();
        activeProject = null;
        activeGui = null;
        activeList = null;
        return true;
    }

    /** 材料列表界面打开（或同一项目重新打开）：重新绑定原始界面并应用已知角色。 */
    public static void onMaterialListOpened(GuiMaterialList gui) {
        if (gui == null) {
            return;
        }
        String project = gui.getSchematicId();
        if (project == null || project.isBlank()) {
            return;
        }
        if (activeGui != null && activeGui != gui) {
            invalidateForScreen(activeGui);
        }
        if (activeProject != null && !activeProject.equals(project)) {
            invalidateAllRequests();
        }
        activeProject = project;
        bindActiveGui(gui);
        activeList = gui.getMaterialList();

        TeamState known = TEAM_STATES.get(project);
        if (known != null) {
            gui.applyTeamState(known, localIdentity());
            applyListPermissions(project, known);
        }
    }

    /**
     * 活跃界面引用只在客户端线程写入。单列一个方法避免 SpotBugs 把
     * “把可变的 Screen 参数存进静态字段”误判为对外暴露内部状态。
     */
    private static void bindActiveGui(GuiMaterialList gui) {
        activeGui = gui;
    }

    /** 材料列表界面关闭：属于该界面的待处理请求全部失效，但角色状态仍可供 HUD 使用。 */
    public static void onMaterialListClosed(GuiMaterialList gui) {
        if (gui == null) {
            return;
        }
        invalidateForScreen(gui);
        if (activeGui == gui) {
            activeGui = null;
        }
    }

    /**
     * 材料列表被标准 setScreen 移除（不经过 closeGui）。打开子弹窗时父列表也会收到
     * removed()，因此这里只丢弃直接绑定父界面的请求；弹窗自己的请求由弹窗 removed() 处理。
     */
    public static void onMaterialListRemoved(GuiMaterialList gui) {
        if (gui == null) {
            return;
        }
        invalidateDirectRequests(gui);
    }

    /** 弹窗被标准 setScreen 移除：不再接收该弹窗的任何回包或结束回调。 */
    public static void onDialogRemoved(GuiPlayerSelectDialog dialog) {
        onDialogDismissed(dialog);
    }

    /** 原理图删除：项目级状态与所有待处理请求一并丢弃。 */
    public static void onProjectDeleted(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        invalidateProject(projectId);
        TEAM_STATES.remove(projectId);
        TEAM_SEQUENCES.remove(projectId);
        if (projectId.equals(activeProject)) {
            activeProject = null;
            activeGui = null;
            activeList = null;
        }
    }

    /** 弹窗被用户关闭：弹窗发起的玩家列表与操作组都不再接收回包。 */
    public static void onDialogDismissed(GuiPlayerSelectDialog dialog) {
        if (dialog == null) {
            return;
        }
        PENDING.values().removeIf(pending -> pending.dialog == dialog);
        for (Deque<LegacyPending> queue : LEGACY_QUEUES.values()) {
            queue.removeIf(pending -> pending.dialog == dialog);
        }
    }

    // ========== 查询 ==========

    /** 服务端是否支持协议 4 的项目级团队管理。 */
    public static boolean isScopedManagementSupported() {
        return ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_TEAM_MANAGEMENT);
    }

    /** 指定项目最近一次被接受的权威团队状态；没有则返回 null。 */
    public static TeamState getTeamState(String projectId) {
        return projectId == null ? null : TEAM_STATES.get(projectId);
    }

    /**
     * 把名字解析为身份：优先使用已知 TeamState 中的 UUID，名字仅用于旧服务端或
     * 未解析身份。同名多身份时不在客户端猜一个，退回纯名字交给服务端判定。
     */
    public static PlayerIdentity resolveIdentity(String projectId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        TeamState state = projectId == null ? null : TEAM_STATES.get(projectId);
        if (state != null) {
            PlayerIdentity unique = null;
            boolean ambiguous = false;
            for (PlayerIdentity candidate : identitiesOf(state)) {
                if (candidate.name().equalsIgnoreCase(name)) {
                    if (unique == null) {
                        unique = candidate;
                    } else if (!unique.equals(candidate)) {
                        ambiguous = true;
                    }
                }
            }
            if (!ambiguous && unique != null) {
                return unique;
            }
        }
        return new PlayerIdentity(null, name);
    }

    private static List<PlayerIdentity> identitiesOf(TeamState state) {
        List<PlayerIdentity> identities = new ArrayList<>(state.deputies().size() + 1);
        identities.add(state.owner());
        identities.addAll(state.deputies());
        return identities;
    }

    // ========== 发起请求 ==========

    /** 玩家选择弹窗构造时请求目录；响应按 requestId 回到该弹窗，不要求它已经成为当前屏幕。 */
    public static void requestPlayerList(GuiPlayerSelectDialog dialog) {
        if (dialog == null) {
            return;
        }
        GuiMaterialList list = dialog.getMaterialList();
        String error = checkContext(dialog.getSchematicId(), list);
        if (error != null) {
            dialog.onPlayerListFailed(error);
            return;
        }
        if (!isCapturedScreenValid(list, dialog)) {
            dialog.onPlayerListFailed("界面已关闭，请重新打开材料列表");
            return;
        }

        if (isScopedManagementSupported()) {
            if (PENDING.size() + 1 > TeamLimits.IN_FLIGHT) {
                dialog.onPlayerListFailed("操作过于频繁，请稍后再试");
                return;
            }
            long requestId = allocateRequestId();
            if (requestId < 0) {
                dialog.onPlayerListFailed(REQUEST_IDS_EXHAUSTED);
                return;
            }
            PENDING.put(requestId, new PendingRequest(requestId, dialog.getSchematicId(),
                    TeamAction.PLAYERS, CONNECTION_GENERATION.get(), list, dialog, null));
            try {
                transport.accept(new TeamCommandC2SPacket(new TeamCommand(dialog.getSchematicId(),
                        requestId, TeamAction.PLAYERS, -1, List.of(), List.of())));
            } catch (RuntimeException sendFailure) {
                PENDING.remove(requestId);
                dialog.onPlayerListFailed("发送请求失败");
            }
            return;
        }

        if (hasLegacyPending(LegacyKind.PLAYERS)) {
            dialog.onPlayerListFailed(LEGACY_BUSY);
            return;
        }
        LegacyPending pending = new LegacyPending(LegacyKind.PLAYERS, dialog.getSchematicId(),
                CONNECTION_GENERATION.get(), list, dialog, null);
        if (!enqueueLegacy(pending)) {
            dialog.onPlayerListFailed(PROJECT_BUSY);
            return;
        }
        sendLegacy(new PlayerListRequestC2SPacket(dialog.getSchematicId()), pending);
    }

    /** 右栏开关：单条命令。 */
    public static String toggleSelfClaim(GuiMaterialList list) {
        return submitGroup(list, null, TeamAction.TOGGLE_SELF_CLAIM, List.of(), List.of());
    }

    /** 右栏 ×：从最近 TeamState 解析被移除者身份，失败时才回退纯名字。 */
    public static String removeDeputy(GuiMaterialList list, String deputyName) {
        PlayerIdentity identity = resolveIdentity(list == null ? null : list.getSchematicId(), deputyName);
        if (identity == null) {
            return "无效的玩家";
        }
        return submitGroup(list, null, TeamAction.REMOVE_DEPUTY, List.of(identity), List.of());
    }

    /**
     * 提交一个操作组。ASSIGN 把所有目标放进同一条命令；其余逐目标顺序发送，
     * 全部回包到齐后才结束。返回 null 表示已受理，否则是本地拒绝原因。
     */
    public static String submitGroup(GuiMaterialList list, GuiPlayerSelectDialog dialog,
                                     TeamAction action, List<PlayerIdentity> targets,
                                     List<Integer> materialIds) {
        if (action == null) {
            return "未知操作";
        }
        List<PlayerIdentity> safeTargets = targets == null ? List.of() : List.copyOf(targets);
        List<Integer> safeMaterials = materialIds == null ? List.of() : List.copyOf(materialIds);
        GuiMaterialList owner = list != null ? list : dialog == null ? null : dialog.getMaterialList();
        String projectId = owner == null ? null : owner.getSchematicId();
        String contextError = checkContext(projectId, owner);
        if (contextError != null) {
            return contextError;
        }
        String shapeError = validateShape(action, safeTargets, safeMaterials);
        if (shapeError != null) {
            return shapeError;
        }

        boolean singleRequest = action == TeamAction.ASSIGN
                || action == TeamAction.TOGGLE_SELF_CLAIM;
        boolean scoped = isScopedManagementSupported();
        OperationGroup group = new OperationGroup(projectId, CONNECTION_GENERATION.get(), owner, dialog,
                action, safeMaterials, safeTargets, singleRequest, scoped);

        if (scoped) {
            if (PENDING.size() + 1 > TeamLimits.IN_FLIGHT) {
                return "操作过于频繁，请稍后再试";
            }
            sendNextScoped(group);
            return null;
        }

        if (!enqueueLegacyProject(projectId)) {
            return PROJECT_BUSY;
        }
        LegacyKind kind = legacyKindFor(action);
        if (kind != null && hasLegacyPending(kind)) {
            return LEGACY_BUSY;
        }
        sendNextLegacy(group);
        return null;
    }

    private static final String PROJECT_BUSY = "另一个项目的请求尚未完成，请稍后再试";
    private static final String LEGACY_BUSY = "上一个操作尚未完成，请稍后再试";
    private static final String REQUEST_IDS_EXHAUSTED = "请求编号已耗尽，请重新连接服务器";

    private static LegacyKind legacyKindFor(TeamAction action) {
        return switch (action) {
            case ASSIGN -> LegacyKind.ASSIGN;
            case KICK -> LegacyKind.KICK;
            case TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY, TOGGLE_SELF_CLAIM -> LegacyKind.OWNER_ACTION;
            default -> null;
        };
    }

    private static String checkContext(String projectId, GuiMaterialList list) {
        if (!connected) {
            return "尚未连接到服务器";
        }
        if (projectId == null || projectId.isBlank()) {
            return "项目无效";
        }
        if (list == null) {
            return "材料列表不可用";
        }
        if (activeProject != null && !activeProject.equals(projectId)) {
            return "项目已切换，请重新打开材料列表";
        }
        if (activeGui == null) {
            activeProject = projectId;
            activeList = list.getMaterialList();
            bindActiveGui(list);
        } else if (activeGui != list) {
            // 真实场景中重新打开界面会先经过 SyncMaterialClient；测试或调试路径
            // 直接构造界面时，只有该界面确实是当前屏幕才接管，避免旧界面劫持。
            if (!isCurrentScreen(list)) {
                return "界面已关闭，请重新打开材料列表";
            }
            invalidateForScreen(activeGui);
            activeProject = projectId;
            activeList = list.getMaterialList();
            bindActiveGui(list);
        }
        return null;
    }

    private static boolean isCurrentScreen(GuiMaterialList list) {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && ClientScreenAccess.currentScreen(mc) == list;
    }

    private static String validateShape(TeamAction action, List<PlayerIdentity> targets,
                                        List<Integer> materialIds) {
        for (PlayerIdentity target : targets) {
            if (target == null || target.name().isBlank()) {
                return "无效的玩家";
            }
        }
        if (targets.size() > TeamLimits.TARGETS) {
            return "选择的玩家过多";
        }
        switch (action) {
            case TRANSFER -> {
                if (targets.size() != 1 || !materialIds.isEmpty()) {
                    return "转让需要一个目标且不能携带材料";
                }
            }
            case ADD_DEPUTY, REMOVE_DEPUTY -> {
                if (targets.isEmpty() || !materialIds.isEmpty()) {
                    return "负责人操作需要目标且不能携带材料";
                }
            }
            case TOGGLE_SELF_CLAIM -> {
                if (!targets.isEmpty() || !materialIds.isEmpty()) {
                    return "开关操作不能携带目标或材料";
                }
            }
            case ASSIGN -> {
                if (targets.isEmpty() || materialIds.isEmpty()) {
                    return "分配需要玩家和材料";
                }
            }
            case KICK -> {
                if (targets.isEmpty() || materialIds.isEmpty()) {
                    return "踢出需要玩家和材料";
                }
            }
            case PLAYERS, TEAM_STATE -> {
                return "该操作不能由界面直接提交";
            }
        }
        if (materialIds.size() > TeamLimits.MATERIALS
                || (action == TeamAction.ASSIGN
                        && (long) materialIds.size() * targets.size() > TeamLimits.ASSIGNMENTS)) {
            return "操作规模超出限制";
        }
        return null;
    }

    private static void sendNextScoped(OperationGroup group) {
        if (group.finished || group.awaiting) {
            return;
        }
        if (!isCapturedScreenValid(group.list, group.dialog)) {
            // 弹窗/列表已离开屏幕：不再向服务端发送后续目标，也不再回调关闭界面
            group.finished = true;
            return;
        }
        PlayerIdentity target = group.singleRequest ? null : group.remaining.peek();
        if (!group.singleRequest && target == null) {
            finishGroup(group);
            return;
        }
        if (PENDING.size() + 1 > TeamLimits.IN_FLIGHT) {
            group.failures++;
            group.failureMessage = "操作过于频繁，请稍后再试";
            finishGroup(group);
            return;
        }
        long requestId = allocateRequestId();
        if (requestId < 0) {
            group.failures++;
            group.failureMessage = REQUEST_IDS_EXHAUSTED;
            finishGroup(group);
            return;
        }
        TeamCommand command;
        try {
            command = commandFor(group, requestId, target);
        } catch (RuntimeException invalid) {
            group.failures++;
            group.failureMessage = "请求参数无效";
            finishGroup(group);
            return;
        }
        PENDING.put(requestId, new PendingRequest(requestId, group.projectId, group.action,
                group.generation, group.list, group.dialog, group));
        group.awaiting = true;
        try {
            transport.accept(new TeamCommandC2SPacket(command));
        } catch (RuntimeException sendFailure) {
            PENDING.remove(requestId);
            group.awaiting = false;
            group.failures++;
            group.failureMessage = "发送请求失败";
            finishGroup(group);
        }
    }

    private static TeamCommand commandFor(OperationGroup group, long requestId, PlayerIdentity target) {
        long revision = catalogRevision(group.list);
        return switch (group.action) {
            case ASSIGN -> new TeamCommand(group.projectId, requestId, group.action, revision,
                    group.materialIds, group.allTargets);
            case KICK -> new TeamCommand(group.projectId, requestId, group.action, revision,
                    group.materialIds, List.of(target));
            case TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY ->
                    new TeamCommand(group.projectId, requestId, group.action, revision,
                            List.of(), List.of(target));
            case TOGGLE_SELF_CLAIM ->
                    new TeamCommand(group.projectId, requestId, group.action, revision,
                            List.of(), List.of());
            default -> throw new IllegalArgumentException("Unsupported UI team action");
        };
    }

    private static long catalogRevision(GuiMaterialList list) {
        if (list == null || list.getMaterialList() == null) {
            return -1;
        }
        long revision = list.getMaterialList().getCatalogRevision();
        return revision < -1 ? -1 : revision;
    }

    private static long allocateRequestId() {
        long requestId = NEXT_REQUEST_ID.get();
        if (requestId <= 0 || requestId >= Long.MAX_VALUE - 1) {
            // 编号用尽即失败关闭，绝不回绕复用旧 id
            return -1;
        }
        NEXT_REQUEST_ID.incrementAndGet();
        return requestId;
    }

    // ========== 协议 4 回包 ==========

    /** 协议 4 回包入口：request0 是项目状态推送，其余按 requestId 关联原始请求。 */
    public static void onTeamResponse(TeamResponse response) {
        onTeamResponse(connectionToken(), response);
    }

    /**
     * 带连接代次的入口：接收器在排队到客户端线程前捕获代次，避免断线重进后
     * 旧连接的推送被当成新连接状态。旧服务端注入的新包在能力闸门处直接丢弃。
     */
    public static void onTeamResponse(long connectionToken, TeamResponse response) {
        if (connectionToken != CONNECTION_GENERATION.get() || response == null) {
            return;
        }
        if (!isScopedManagementSupported()) {
            SyncMaterial.LOGGER.debug("忽略服务端不支持的团队回包: project={}", response.projectId());
            return;
        }
        if (response.requestId() == 0) {
            if (!response.success() || response.action() != TeamAction.TEAM_STATE) {
                return;
            }
            if (!connected || !isActiveProject(response.projectId())) {
                return;
            }
            applyTeamState(response.projectId(), response.team(), response.sequence());
            return;
        }

        PendingRequest pending = PENDING.get(response.requestId());
        if (pending == null) {
            SyncMaterial.LOGGER.debug("忽略无对应请求的团队回包: requestId={}, project={}",
                    response.requestId(), response.projectId());
            return;
        }
        if (pending.generation != CONNECTION_GENERATION.get() || !connected
                || !pending.projectId.equals(response.projectId())
                || pending.action != response.action() || !isActiveProject(pending.projectId)
                || !isCapturedScreenValid(pending.list, pending.dialog)) {
            // 伪造/错配包不消费原请求，也不把失败写进无关界面；真正的回包仍可到达。
            SyncMaterial.LOGGER.debug("忽略错配的团队回包: requestId={}, project={}, action={}",
                    response.requestId(), response.projectId(), response.action());
            return;
        }
        PENDING.remove(response.requestId());

        if (pending.isPlayersRequest()) {
            if (response.success()) {
                pending.dialog.onPlayerDirectoryResponse(response.players());
                if (response.message() != null && !response.message().isBlank()) {
                    // 目录被服务端上限截断时只给提示，不当作失败，也不禁用列表。
                    pending.dialog.onPlayerDirectoryHint(response.message());
                }
            } else {
                pending.dialog.onPlayerListFailed(response.message());
            }
            return;
        }

        OperationGroup group = pending.group;
        if (group == null || group.finished) {
            return;
        }
        group.awaiting = false;
        if (response.team() != null) {
            applyTeamState(pending.projectId, response.team(), response.sequence());
        }
        recordResult(group, response.success(), response.message());
        if (!group.singleRequest) {
            group.remaining.poll();
        }
        advance(group);
    }

    // ========== 旧服务端回包（冻结包，无 requestId） ==========

    public static void onLegacyOwnerActionResponse(OwnerActionResponseS2CPacket payload) {
        onLegacyOwnerActionResponse(connectionToken(), payload);
    }

    public static void onLegacyOwnerActionResponse(long connectionToken,
                                                   OwnerActionResponseS2CPacket payload) {
        if (connectionToken != CONNECTION_GENERATION.get() || payload == null || isScopedManagementSupported()) {
            return;
        }
        LegacyPending pending = pollLegacy(LegacyKind.OWNER_ACTION);
        if (pending == null) {
            SyncMaterial.LOGGER.debug("忽略无对应请求的旧版负责人响应");
            return;
        }
        if (!isCurrentCapture(pending)) {
            return;
        }
        if (payload.success()) {
            pending.list.applyLegacyOwnerState(payload.ownerName(), payload.deputyOwners(),
                    payload.allowSelfClaim());
        }
        completeLegacy(pending, payload.success(), payload.message());
    }

    public static void onLegacyBatchAssignResponse(BatchAssignResponseS2CPacket payload) {
        onLegacyBatchAssignResponse(connectionToken(), payload);
    }

    public static void onLegacyBatchAssignResponse(long connectionToken,
                                                   BatchAssignResponseS2CPacket payload) {
        if (connectionToken != CONNECTION_GENERATION.get() || payload == null || isScopedManagementSupported()) {
            return;
        }
        LegacyPending pending = pollLegacy(LegacyKind.ASSIGN);
        if (pending == null) {
            return;
        }
        if (!isCurrentCapture(pending)) {
            return;
        }
        completeLegacy(pending, payload.success(), payload.message());
    }

    public static void onLegacyKickResponse(KickFromMaterialResponseS2CPacket payload) {
        onLegacyKickResponse(connectionToken(), payload);
    }

    public static void onLegacyKickResponse(long connectionToken,
                                            KickFromMaterialResponseS2CPacket payload) {
        if (connectionToken != CONNECTION_GENERATION.get() || payload == null || isScopedManagementSupported()) {
            return;
        }
        LegacyPending pending = pollLegacy(LegacyKind.KICK);
        if (pending == null) {
            return;
        }
        if (!isCurrentCapture(pending)) {
            return;
        }
        completeLegacy(pending, payload.success(), payload.message());
    }

    public static void onLegacyPlayerListResponse(List<PlayerInfo> players) {
        onLegacyPlayerListResponse(connectionToken(), players);
    }

    public static void onLegacyPlayerListResponse(long connectionToken, List<PlayerInfo> players) {
        if (connectionToken != CONNECTION_GENERATION.get() || isScopedManagementSupported()) {
            return;
        }
        LegacyPending pending = pollLegacy(LegacyKind.PLAYERS);
        if (pending == null) {
            return;
        }
        if (!isCurrentCapture(pending)) {
            return;
        }
        pending.dialog.onPlayerListResponse(players == null ? List.of() : players);
    }

    private static LegacyPending pollLegacy(LegacyKind kind) {
        Deque<LegacyPending> queue = LEGACY_QUEUES.get(kind);
        return queue == null ? null : queue.pollFirst();
    }

    private static boolean enqueueLegacy(LegacyPending pending) {
        if (!enqueueLegacyProject(pending.projectId)) {
            return false;
        }
        LEGACY_QUEUES.computeIfAbsent(pending.kind, key -> new ArrayDeque<>()).addLast(pending);
        return true;
    }

    /** 旧协议同一时刻只允许一个项目有未完成请求。 */
    private static boolean enqueueLegacyProject(String projectId) {
        for (Deque<LegacyPending> queue : LEGACY_QUEUES.values()) {
            for (LegacyPending pending : queue) {
                if (!pending.projectId.equals(projectId)) {
                    return false;
                }
            }
        }
        return activeProject == null || activeProject.equals(projectId);
    }

    private static boolean hasLegacyPending(LegacyKind kind) {
        Deque<LegacyPending> queue = LEGACY_QUEUES.get(kind);
        return queue != null && !queue.isEmpty();
    }

    /**
     * 回包必须仍属于原始捕获界面：材料列表仍是当前活动列表，且当前屏幕要么是
     * 捕获的列表，要么是捕获的弹窗。构造期请求在弹窗尚未显示时以父列表为当前屏幕，
     * 显示后则必须仍是该弹窗；被别的同项目界面替换后不再接受任何回包。
     */
    private static boolean isCapturedScreenValid(GuiMaterialList list, GuiPlayerSelectDialog dialog) {
        if (list == null || list != activeGui) {
            return false;
        }
        Screen current = screenProvider == null ? null : screenProvider.get();
        if (dialog != null && current == dialog) {
            return true;
        }
        return current == list;
    }

    private static boolean isCurrentCapture(LegacyPending pending) {
        return connected && pending.generation == CONNECTION_GENERATION.get()
                && isActiveProject(pending.projectId)
                && (pending.list == null || pending.list.getSchematicId() == null
                        || pending.list.getSchematicId().equals(pending.projectId))
                && isCapturedScreenValid(pending.list, pending.dialog);
    }

    private static void completeLegacy(LegacyPending pending, boolean success, String message) {
        OperationGroup group = pending.group;
        if (group == null || group.finished) {
            return;
        }
        group.awaiting = false;
        if (success) {
            group.anySuccess = true;
        }
        recordResult(group, success, message);
        Deque<PlayerIdentity> remaining = group.remaining;
        if (!group.singleRequest && !remaining.isEmpty()) {
            remaining.poll();
        }
        advance(group);
    }

    private static void sendNextLegacy(OperationGroup group) {
        if (group.finished || group.awaiting) {
            return;
        }
        if (!isCapturedScreenValid(group.list, group.dialog)) {
            group.finished = true;
            return;
        }
        PlayerIdentity target = group.singleRequest ? null : group.remaining.peek();
        if (!group.singleRequest && target == null) {
            finishGroup(group);
            return;
        }

        LegacyKind kind;
        CustomPacketPayload packet;
        try {
            switch (group.action) {
                case ASSIGN -> {
                    kind = LegacyKind.ASSIGN;
                    packet = new BatchAssignC2SPacket(group.projectId, group.materialIds,
                            names(group.allTargets));
                }
                case KICK -> {
                    kind = LegacyKind.KICK;
                    packet = new KickFromMaterialC2SPacket(group.projectId, group.materialIds,
                            target.name());
                }
                case TRANSFER, ADD_DEPUTY, REMOVE_DEPUTY -> {
                    kind = LegacyKind.OWNER_ACTION;
                    packet = new OwnerActionC2SPacket(group.projectId, group.action.name(),
                            target.name());
                }
                case TOGGLE_SELF_CLAIM -> {
                    kind = LegacyKind.OWNER_ACTION;
                    packet = new OwnerActionC2SPacket(group.projectId, "TOGGLE_SELF_CLAIM", "");
                }
                default -> throw new IllegalArgumentException("Unsupported legacy team action");
            }
        } catch (RuntimeException invalid) {
            group.failures++;
            group.failureMessage = "请求参数无效";
            finishGroup(group);
            return;
        }

        LegacyPending pending = new LegacyPending(kind, group.projectId, group.generation,
                group.list, group.dialog, group);
        if (!enqueueLegacy(pending)) {
            group.failures++;
            group.failureMessage = PROJECT_BUSY;
            finishGroup(group);
            return;
        }
        group.awaiting = true;
        sendLegacy(packet, pending);
    }

    private static void sendLegacy(CustomPacketPayload packet, LegacyPending pending) {
        try {
            transport.accept(packet);
        } catch (RuntimeException sendFailure) {
            Deque<LegacyPending> queue = LEGACY_QUEUES.get(pending.kind);
            if (queue != null) {
                queue.removeFirstOccurrence(pending);
            }
            OperationGroup group = pending.group;
            if (group != null) {
                group.awaiting = false;
                group.failures++;
                group.failureMessage = "发送请求失败";
                finishGroup(group);
            }
        }
    }

    private static List<String> names(List<PlayerIdentity> identities) {
        List<String> names = new ArrayList<>(identities.size());
        for (PlayerIdentity identity : identities) {
            names.add(identity.name());
        }
        return names;
    }

    // ========== 操作组 ==========

    private static void recordResult(OperationGroup group, boolean success, String message) {
        if (success) {
            group.anySuccess = true;
            if (message != null && !message.isBlank()) {
                group.successMessage = message;
            }
        } else {
            group.failures++;
            if (group.failureMessage.isEmpty() && message != null && !message.isBlank()) {
                group.failureMessage = message;
            }
        }
    }

    private static void advance(OperationGroup group) {
        if (group.finished) {
            return;
        }
        if (!group.remaining.isEmpty()) {
            if (group.scoped) {
                sendNextScoped(group);
            } else {
                sendNextLegacy(group);
            }
        } else {
            finishGroup(group);
        }
    }

    private static void finishGroup(OperationGroup group) {
        if (group.finished) {
            return;
        }
        group.finished = true;
        boolean allSucceeded = group.failures == 0 && group.anySuccess;
        if (group.dialog != null) {
            if (allSucceeded) {
                applyListSuccess(group.list, group.action);
                group.dialog.onGroupSucceeded(group.action);
            } else {
                if (group.anySuccess) {
                    applyListSuccess(group.list, group.action);
                }
                group.dialog.onGroupFailed(group.failureMessage.isEmpty()
                        ? "操作未完成" : group.failureMessage);
            }
            return;
        }
        if (group.anySuccess) {
            applyListSuccess(group.list, group.action);
        }
        if (group.list != null) {
            group.list.onTeamFeedback(allSucceeded,
                    allSucceeded ? group.successMessage : group.failureMessage.isEmpty()
                            ? "操作失败" : group.failureMessage);
        }
    }

    private static void applyListSuccess(GuiMaterialList list, TeamAction action) {
        if (list == null) {
            return;
        }
        if (action == TeamAction.ASSIGN) {
            list.applyBatchAssignResult(true);
        } else if (action == TeamAction.KICK) {
            list.applyKickResult(true);
        }
    }

    // ========== 角色状态 ==========

    private static boolean isActiveProject(String projectId) {
        return projectId != null && projectId.equals(activeProject);
    }

    /**
     * 只接受严格更新的投递序号，避免旧的项目状态覆盖新状态。传入的 team 必须属于
     * 同一项目；request0 推送与操作回包中的权威状态走同一条路径。
     */
    private static void applyTeamState(String projectId, TeamState team, long sequence) {
        if (team == null || !projectId.equals(team.projectId())) {
            return;
        }
        Long knownSequence = TEAM_SEQUENCES.get(projectId);
        if (knownSequence != null && sequence <= knownSequence) {
            return;
        }
        TEAM_SEQUENCES.put(projectId, sequence);
        TEAM_STATES.put(projectId, team);
        if (!isActiveProject(projectId)) {
            return;
        }
        applyListPermissions(projectId, team);
        GuiMaterialList gui = activeGui;
        if (gui != null && projectId.equals(gui.getSchematicId())) {
            gui.applyTeamState(team, localIdentity());
        }
    }

    private static void applyListPermissions(String projectId, TeamState team) {
        SyncMaterialList list = activeList;
        if (list == null || !projectId.equals(list.getSchematicId())) {
            return;
        }
        PlayerIdentity local = localIdentity();
        if (local != null) {
            list.setIsOwner(team.isOwner(local));
        }
        list.setAllowSelfClaim(team.allowSelfClaim());
    }

    private static PlayerIdentity localIdentity() {
        return identityProvider == null ? null : identityProvider.get();
    }

    private static PlayerIdentity minecraftIdentity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return null;
        }
        return new PlayerIdentity(mc.player.getUUID(), mc.player.getName().getString());
    }

    private static Screen minecraftScreen() {
        return ClientScreenAccess.currentScreen(Minecraft.getInstance());
    }

    /** 接收器在 mc.execute 排队前捕获，避免旧连接的回包落到重连后的同项目界面。 */
    public static long connectionToken() {
        return CONNECTION_GENERATION.get();
    }

    // ========== 失效处理 ==========

    private static void invalidateAllRequests() {
        PENDING.clear();
        LEGACY_QUEUES.clear();
    }

    private static void invalidateDirectRequests(GuiMaterialList gui) {
        PENDING.values().removeIf(pending -> pending.list == gui && pending.dialog == null);
        for (Deque<LegacyPending> queue : LEGACY_QUEUES.values()) {
            queue.removeIf(pending -> pending.list == gui && pending.dialog == null);
        }
    }

    private static void invalidateProject(String projectId) {
        PENDING.values().removeIf(pending -> projectId.equals(pending.projectId));
        for (Deque<LegacyPending> queue : LEGACY_QUEUES.values()) {
            queue.removeIf(pending -> projectId.equals(pending.projectId));
        }
    }

    private static void invalidateForScreen(GuiMaterialList gui) {
        PENDING.values().removeIf(pending -> pending.list == gui
                || (pending.dialog != null && pending.dialog.getMaterialList() == gui));
        for (Deque<LegacyPending> queue : LEGACY_QUEUES.values()) {
            queue.removeIf(pending -> pending.list == gui
                    || (pending.dialog != null && pending.dialog.getMaterialList() == gui));
        }
    }

    // ========== 测试入口 ==========

    static void setTransportForTesting(Consumer<CustomPacketPayload> testTransport) {
        transport = testTransport == null ? DEFAULT_TRANSPORT : testTransport;
    }

    static void setIdentityProviderForTesting(Supplier<PlayerIdentity> provider) {
        identityProvider = provider == null ? TeamManagementClient::minecraftIdentity : provider;
    }

    static void setScreenProviderForTesting(Supplier<Screen> provider) {
        screenProvider = provider == null ? TeamManagementClient::minecraftScreen : provider;
    }

    static void resetForTesting() {
        synchronized (CONNECTION_LOCK) {
            CONNECTION_GENERATION.set(0);
            PENDING_DISCONNECT.set(-1);
            activeConnection = null;
            connected = false;
        }
        NEXT_REQUEST_ID.set(1);
        activeProject = null;
        activeGui = null;
        activeList = null;
        PENDING.clear();
        LEGACY_QUEUES.clear();
        TEAM_STATES.clear();
        TEAM_SEQUENCES.clear();
        transport = DEFAULT_TRANSPORT;
        identityProvider = TeamManagementClient::minecraftIdentity;
        screenProvider = TeamManagementClient::minecraftScreen;
    }

    static int pendingCountForTesting() {
        return PENDING.size();
    }

    static long generationForTesting() {
        return CONNECTION_GENERATION.get();
    }

    static List<Long> pendingRequestIdsForTesting() {
        return new ArrayList<>(PENDING.keySet());
    }

    static boolean isConnectedForTesting() {
        return connected;
    }

    static long latestSequenceForTesting(String projectId) {
        Long sequence = TEAM_SEQUENCES.get(projectId);
        return sequence == null ? -1 : sequence;
    }
}
