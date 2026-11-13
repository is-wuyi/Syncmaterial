package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Phase4Handler {

    public static void registerPhase4Handlers() {
        // 负责人操作
        ServerPlayNetworking.registerGlobalReceiver(OwnerActionC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleOwnerAction(owner, payload, player, server));
        });

        // 批量分配
        ServerPlayNetworking.registerGlobalReceiver(BatchAssignC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleBatchAssign(owner, payload, player, server));
        });

        // 按材料踢出
        ServerPlayNetworking.registerGlobalReceiver(KickFromMaterialC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handleKickFromMaterial(owner, payload, player, server));
        });

        // 玩家列表请求
        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = context.server();
            if (!validateHandshakedPlayer(player) || !validateSchematicId(payload.schematicId())) return;

            LegacyNetworkDispatch.dispatch(context, owner ->
                    handlePlayerListRequest(owner, payload, player, server));
        });
        ServerPlayNetworking.registerGlobalReceiver(TeamCommandC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) return;
            LegacyNetworkDispatch.dispatch(context, owner -> handleTeamCommand(owner, payload.command(), player, context.server()));
        });
    }

    // ========== 材料列表订阅管理（状态存放在上下文自己的 LegacySubscriptions） ==========

    public static void subscribeMaterialList(LegacyServerContext owner, ServerPlayer player, String schematicId) {
        if (schematicId == null || !LegacyNetworkDispatch.accepts(owner, player)) return;
        owner.subscriptions().subscribeMaterials(player, schematicId);
    }

    public static void unsubscribeMaterialList(LegacyServerContext owner, ServerPlayer player, String schematicId) {
        if (owner == null || player == null || schematicId == null) return;
        owner.subscriptions().unsubscribeMaterials(player, schematicId);
    }

    /** 只读兼容入口：订阅集合来自玩家所属上下文，不持有全局表。 */
    public static Set<String> getSubscribedSchematics(ServerPlayer player) {
        LegacyServerContext owner = LegacyNetworkDispatch.captureForPlayer(player);
        return owner == null ? Set.of() : owner.subscriptions().projects(player);
    }

    // ========== Phase 4: 负责人管理 ==========

    static void validateOwnerAction(String action) {
        if (action == null || (!action.equals("TRANSFER") && !action.equals("ADD_DEPUTY") &&
            !action.equals("REMOVE_DEPUTY") && !action.equals("TOGGLE_SELF_CLAIM"))) {
            throw new IllegalArgumentException("未知操作: " + action);
        }
    }

    static void handleOwnerAction(LegacyServerContext owner, OwnerActionC2SPacket payload,
                                  ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;
        String schematicId = payload.schematicId();
        if (("TRANSFER".equals(payload.action()) || "ADD_DEPUTY".equals(payload.action())
                || "REMOVE_DEPUTY".equals(payload.action()))
                && (payload.targetPlayerName() == null || payload.targetPlayerName().isBlank())) {
            TeamState state = readTeamOrNull(owner, schematicId);
            ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "目标玩家不能为空",
                    state == null ? "" : state.owner().name(), state == null ? List.of() : state.deputyNames(),
                    state == null || state.allowSelfClaim()));
            return;
        }
        try {
            validateOwnerAction(payload.action());
            TeamAction action = TeamAction.valueOf(payload.action());
            List<PlayerIdentity> targets = action == TeamAction.TOGGLE_SELF_CLAIM ? List.of()
                    : List.of(new PlayerIdentity(null, payload.targetPlayerName()));
            var command = new TeamCommand(schematicId, 1, action, -1, List.of(), targets);
            var result = performOwnerAction(owner, command, player);
            TeamState state = result.state();
            ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(result.success(), result.message(),
                    state == null ? "" : state.owner().name(), state == null ? List.of() : state.deputyNames(),
                    state == null || state.allowSelfClaim()));
            if (result.success()) publishTeam(owner, schematicId, state);
        } catch (Exception error) {
            SyncMaterial.LOGGER.error("处理负责人操作失败", error);
            TeamState state = readTeamOrNull(owner, schematicId);
            ServerPlayNetworking.send(player, new OwnerActionResponseS2CPacket(false, "操作失败: " + error.getMessage(),
                    state == null ? "" : state.owner().name(), state == null ? List.of() : state.deputyNames(),
                    state == null || state.allowSelfClaim()));
        }
    }

    private static LegacyResult performOwnerAction(LegacyServerContext owner, TeamCommand command,
                                                   ServerPlayer player) throws java.sql.SQLException {
        var db = owner.database();
        TeamState before = readTeam(owner, command.projectId());
        if (before == null) return new LegacyResult(false, "原理图尚未就绪，请稍后重试", null, 0);
        boolean allowed = command.action().requiresMainOwner()
                ? db.isMainOwner(command.projectId(), player.getName().getString())
                : db.isOwner(command.projectId(), player.getName().getString());
        if (!allowed) {
            String denial = switch (command.action()) {
                case TRANSFER -> "只有主负责人才能转让";
                case ADD_DEPUTY -> "只有主负责人才能添加副负责人";
                case REMOVE_DEPUTY -> "只有主负责人才能移除副负责人";
                default -> "没有权限";
            };
            return new LegacyResult(false, denial, before, 0);
        }
        String target = command.targets().isEmpty() ? "" : legacyTarget(owner, command.targets().getFirst());
        switch (command.action()) {
            case TRANSFER -> db.transferOwnership(command.projectId(), target);
            case ADD_DEPUTY -> db.addDeputyOwner(command.projectId(), target);
            case REMOVE_DEPUTY -> db.removeDeputyOwner(command.projectId(), target);
            case TOGGLE_SELF_CLAIM -> db.setAllowSelfClaim(command.projectId(), !before.allowSelfClaim());
            default -> throw new IllegalArgumentException("Unknown owner action");
        }
        TeamState state = readTeam(owner, command.projectId());
        String message = switch (command.action()) {
            case TRANSFER -> "已转让负责人给 " + target;
            case ADD_DEPUTY -> "已添加副负责人 " + target;
            case REMOVE_DEPUTY -> "已移除副负责人 " + target;
            case TOGGLE_SELF_CLAIM -> "自行认领已" + (state.allowSelfClaim() ? "开启" : "关闭");
            default -> "";
        };
        return new LegacyResult(true, message, state, 1);
    }

    static void handleBatchAssign(LegacyServerContext owner, BatchAssignC2SPacket payload,
                                  ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;
        if (payload.materialIds() == null || payload.materialIds().isEmpty()) {
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "请选择至少一个材料"));
            return;
        }
        if (payload.targetPlayers() == null || payload.targetPlayers().isEmpty()) {
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "请选择至少一个玩家"));
            return;
        }
        try {
            var command = new TeamCommand(payload.schematicId(), 1, TeamAction.ASSIGN, -1, payload.materialIds(),
                    payload.targetPlayers().stream().map(name -> new PlayerIdentity(null, name)).toList());
            LegacyResult result = performMaterials(owner, command, player);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(result.success(), result.message()));
        } catch (Exception error) {
            SyncMaterial.LOGGER.error("批量分配失败", error);
            ServerPlayNetworking.send(player, new BatchAssignResponseS2CPacket(false, "分配失败: " + error.getMessage()));
        }
    }

    static void handleKickFromMaterial(LegacyServerContext owner, KickFromMaterialC2SPacket payload,
                                       ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;
        if (payload.materialIds() == null || payload.materialIds().isEmpty()) {
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "请选择至少一个材料"));
            return;
        }
        if (payload.targetPlayer() == null || payload.targetPlayer().isBlank()) {
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "目标玩家不能为空"));
            return;
        }
        try {
            var command = new TeamCommand(payload.schematicId(), 1, TeamAction.KICK, -1, payload.materialIds(),
                    List.of(new PlayerIdentity(null, payload.targetPlayer())));
            LegacyResult result = performMaterials(owner, command, player);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(result.success(), result.message()));
        } catch (Exception error) {
            SyncMaterial.LOGGER.error("踢出玩家失败", error);
            ServerPlayNetworking.send(player, new KickFromMaterialResponseS2CPacket(false, "踢出失败: " + error.getMessage()));
        }
    }

    private static LegacyResult performMaterials(LegacyServerContext owner, TeamCommand command,
                                                 ServerPlayer player) throws java.sql.SQLException {
        var db = owner.database();
        if (!db.isOwner(command.projectId(), player.getName().getString())) {
            return new LegacyResult(false, command.action() == TeamAction.ASSIGN
                    ? "没有权限，只有负责人才能分配" : "没有权限", readTeamOrNull(owner, command.projectId()), 0);
        }
        int processed = 0;
        for (int materialId : command.materialIds()) {
            for (PlayerIdentity target : command.targets()) {
                String name = legacyTarget(owner, target);
                boolean accepted = command.action() == TeamAction.ASSIGN
                        ? owner.collaboration().joinCollaboration(command.projectId(), materialId, name)
                        : owner.collaboration().leaveCollaboration(command.projectId(), materialId, name);
                if (accepted) processed++;
            }
        }
        for (int materialId : new java.util.LinkedHashSet<>(command.materialIds())) {
            ModNetworkHandler.broadcastStatus(owner, command.projectId(), materialId);
        }
        String message = command.action() == TeamAction.ASSIGN ? "已分配 " + processed + " 个任务"
                : "已从 " + processed + " 个材料中踢出 " + command.targets().getFirst().name();
        return new LegacyResult(true, message, readTeamOrNull(owner, command.projectId()), processed);
    }

    static TeamState readTeam(LegacyServerContext owner, String projectId) throws java.sql.SQLException {
        String name = owner.database().getUploadedBy(projectId);
        if (name == null || name.isBlank()) return null;
        var deputies = owner.database().getDeputyOwners(projectId).stream()
                .map(deputy -> new PlayerIdentity(null, deputy)).toList();
        return new TeamState(projectId, new PlayerIdentity(null, name), deputies,
                owner.database().getAllowSelfClaim(projectId));
    }

    private static TeamState readTeamOrNull(LegacyServerContext owner, String projectId) {
        if (owner == null || owner.database() == null) return null;
        try {
            return readTeam(owner, projectId);
        } catch (java.sql.SQLException failure) {
            return null;
        }
    }

    private static String legacyTarget(LegacyServerContext owner, PlayerIdentity target) {
        if (!target.hasUuid()) return target.name();
        var online = owner.server().getPlayerList().getPlayer(target.uuid());
        return online == null ? target.name() : online.getName().getString();
    }

    static void publishTeam(LegacyServerContext owner, String projectId, TeamState state) {
        if (state == null || !owner.isCurrent()) return;
        long sequence = owner.teamRequests().nextSequence(projectId);
        for (ServerPlayer subscriber : owner.subscriptions().materialSubscribers(projectId)) {
            if (owner.isConnected(subscriber) && ProtocolHandshake.supports(subscriber, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) {
                ServerPlayNetworking.send(subscriber, new TeamResponseS2CPacket(new TeamResponse(projectId, 0,
                        TeamAction.TEAM_STATE, sequence, true, "", state, List.of(), 0, 0)));
            }
        }
    }

    static void handleTeamCommand(LegacyServerContext owner, TeamCommand command, ServerPlayer player,
                                  MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)
                || !ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) return;
        long connection = owner.teamRequests().connection(player.getUUID(), player.connection);
        if (!owner.teamRequests().begin(player.getUUID(), connection, command.requestId())) return;
        try {
            LegacyResult result;
            List<net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry> directory = List.of();
            if (command.action() == TeamAction.PLAYERS) {
                directory = legacyDirectory(owner);
                result = new LegacyResult(true, "", readTeamOrNull(owner, command.projectId()), 0);
            } else if (command.action() == TeamAction.TEAM_STATE) {
                TeamState state = readTeam(owner, command.projectId());
                result = new LegacyResult(state != null, state == null ? "原理图尚未就绪" : "", state, 0);
            } else if (command.action().changesTeam()) {
                result = performOwnerAction(owner, command, player);
            } else {
                result = performMaterials(owner, command, player);
            }
            long sequence = owner.teamRequests().nextSequence(command.projectId());
            ServerPlayNetworking.send(player, new TeamResponseS2CPacket(new TeamResponse(command.projectId(),
                    command.requestId(), command.action(), sequence, result.success(), result.message(), result.state(),
                    directory, result.processed(), 0)));
            if (result.success() && command.action().changesTeam()) publishTeam(owner, command.projectId(), result.state());
        } catch (Exception failure) {
            SyncMaterial.LOGGER.warn("旧后端团队请求失败", failure);
            long sequence = owner.teamRequests().nextSequence(command.projectId());
            ServerPlayNetworking.send(player, new TeamResponseS2CPacket(new TeamResponse(command.projectId(),
                    command.requestId(), command.action(), sequence, false, "操作失败，请重试或查看服务端日志",
                    readTeamOrNull(owner, command.projectId()), List.of(), 0, 0)));
        } finally {
            owner.teamRequests().finish(player.getUUID(), connection);
        }
    }

    static void sendInitialTeam(LegacyServerContext owner, ServerPlayer player, String projectId) {
        if (!ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) return;
        TeamState state = readTeamOrNull(owner, projectId);
        if (state == null) return;
        long sequence = owner.teamRequests().nextSequence(projectId);
        ServerPlayNetworking.send(player, new TeamResponseS2CPacket(new TeamResponse(projectId, 0,
                TeamAction.TEAM_STATE, sequence, true, "", state, List.of(), 0, 0)));
    }

    private static List<net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry> legacyDirectory(LegacyServerContext owner) {
        List<PlayerIdentity> stored = new ArrayList<>();
        try (var rows = owner.database().executeQuery("SELECT player_name FROM claims WHERE schematic_id IS NOT NULL "
                + "UNION SELECT uploaded_by FROM schematics UNION SELECT player_name FROM deputy_owners")) {
            while (rows.next()) {
                String name = rows.getString(1);
                if (name != null && !name.isBlank()) stored.add(new PlayerIdentity(null, name));
            }
        } catch (java.sql.SQLException failure) {
            SyncMaterial.LOGGER.warn("读取旧后端已知玩家失败，保留在线与缓存名单", failure);
        }
        return net.syncmaterial.syncmaterial.server.runtime.PlayerDirectory.merge(stored,
                net.syncmaterial.syncmaterial.adapter.minecraft.MinecraftPlayerDirectory.cached(owner.server().getFile("usercache.json")),
                net.syncmaterial.syncmaterial.adapter.minecraft.MinecraftPlayerDirectory.online(owner.server()));
    }

    private record LegacyResult(boolean success, String message, TeamState state, int processed) {}

    static void handlePlayerListRequest(LegacyServerContext owner, PlayerListRequestC2SPacket payload,
                                        ServerPlayer player, MinecraftServer server) {
        if (!LegacyNetworkDispatch.accepts(owner, player, server)) return;
        var players = legacyDirectory(owner).stream().map(entry ->
                new PlayerListResponseS2CPacket.PlayerInfo(entry.player().name(), entry.online())).toList();
        ServerPlayNetworking.send(player, new PlayerListResponseS2CPacket(players));
    }

    /** 广播某原理图所有材料的最新状态给参与者 + 订阅者（备货区/背包变动后调用）。 */
    public static void broadcastAllMaterialStatus(LegacyServerContext owner, String schematicId) {
        if (owner == null || !owner.isCurrent()) return;
        var collaboration = owner.collaboration();
        if (collaboration == null) return;
        List<Integer> materialIds = collaboration.getAllMaterialIds(schematicId);
        for (int materialId : materialIds) {
            ModNetworkHandler.broadcastStatus(owner, schematicId, materialId);
        }
    }

    /** 显式 server 兼容入口：只做同步上下文解析，排队后不再重新查注册表。 */
    public static void broadcastAllMaterialStatus(MinecraftServer server, String schematicId) {
        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (owner != null) {
            broadcastAllMaterialStatus(owner, schematicId);
        }
    }

    // ========== 内部工具方法 ==========
    // 校验逻辑统一委托给 ModNetworkHandler，避免两份拷贝各自漂移

    static boolean validateSchematicId(String schematicId) {
        return ModNetworkHandler.validateSchematicId(schematicId);
    }

    static boolean validatePlayer(ServerPlayer player) {
        return ModNetworkHandler.validatePlayer(player);
    }

    static boolean validateHandshakedPlayer(ServerPlayer player) {
        return ModNetworkHandler.validateHandshakedPlayer(player);
    }
}
