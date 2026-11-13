package net.syncmaterial.syncmaterial.network;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.adapter.minecraft.MinecraftPlayerDirectory;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/** Thin Foundation management adapter. Authorization and writes belong to the shared application. */
public final class FoundationTeamNetworkAdapter {
    private FoundationTeamNetworkAdapter() {}

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(TeamCommandC2SPacket.ID, (packet, context) -> {
            if (!ProtocolHandshake.supports(context.player(), ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) return;
            handle(packet.command(), context.player(), context.server(), true);
        });
        ServerPlayNetworking.registerGlobalReceiver(OwnerActionC2SPacket.ID, (packet, context) -> {
            TeamAction action;
            try {
                action = TeamAction.valueOf(packet.action());
                if (!action.changesTeam()) throw new IllegalArgumentException("Unknown owner action");
                List<PlayerIdentity> targets = action == TeamAction.TOGGLE_SELF_CLAIM ? List.of()
                        : List.of(new PlayerIdentity(null, packet.targetPlayerName()));
                handle(new TeamCommand(packet.schematicId(), 1, action, -1, List.of(), targets),
                        context.player(), context.server(), false);
            } catch (IllegalArgumentException failure) {
                invalidLegacy(context.player(), TeamAction.TRANSFER, failure);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BatchAssignC2SPacket.ID, (packet, context) -> {
            try {
                handle(new TeamCommand(packet.schematicId(), 1, TeamAction.ASSIGN, -1, packet.materialIds(),
                        packet.targetPlayers().stream().map(name -> new PlayerIdentity(null, name)).toList()),
                        context.player(), context.server(), false);
            } catch (IllegalArgumentException failure) {
                invalidLegacy(context.player(), TeamAction.ASSIGN, failure);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(KickFromMaterialC2SPacket.ID, (packet, context) -> {
            try {
                handle(new TeamCommand(packet.schematicId(), 1, TeamAction.KICK, -1, packet.materialIds(),
                        List.of(new PlayerIdentity(null, packet.targetPlayer()))),
                        context.player(), context.server(), false);
            } catch (IllegalArgumentException failure) {
                invalidLegacy(context.player(), TeamAction.KICK, failure);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2SPacket.ID, (packet, context) -> {
            try {
                handle(new TeamCommand(packet.schematicId(), 1, TeamAction.PLAYERS, -1, List.of(), List.of()),
                        context.player(), context.server(), false);
            } catch (IllegalArgumentException failure) {
                invalidLegacy(context.player(), TeamAction.PLAYERS, failure);
            }
        });
    }

    static void handle(TeamCommand command, ServerPlayer player, MinecraftServer server, boolean scoped) {
        if (!FoundationNetworkAdapter.allowed(player, command.projectId())
                || LegacyNetworkDispatch.serverOf(player) != server
                || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        ServerRuntime runtime = ServerRuntimeRegistry.get(server);
        if (runtime == null || !runtime.isOpen()) return;
        UUID playerId = player.getUUID();
        long connection = runtime.connection(playerId);
        var listener = player.connection;
        String actorName = player.getName().getString();
        if (connection <= 0 || listener == null) return;
        if (scoped && !runtime.teamRequests().begin(playerId, connection, command.requestId())) return;
        FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer current = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection);
            if (current != player || current.connection != listener) {
                if (scoped) runtime.teamRequests().finish(playerId, connection);
                return;
            }
            BooleanSupplier admission = () -> runtime.isOpen() && runtime.connection(playerId) == connection
                    && ServerRuntimeRegistry.get(server) == runtime;
            if (scoped && command.action().usesMaterials()) {
                OptionalLong revision = runtime.catalogs().currentRevision(playerId, command.projectId(), connection);
                if (revision.isEmpty() || revision.getAsLong() != command.catalogRevision()) {
                    runtime.teams().state(command.projectId()).whenComplete((team, error) -> complete(server,
                            runtime, player, connection, listener, command, true, team == null ? null : team.orElse(null),
                            List.of(), TeamOutcome.rejected(TeamOutcome.Status.STALE_MATERIALS, null), error));
                    return;
                }
            }
            List<PlayerIdentity> online = MinecraftPlayerDirectory.online(server);
            Path cache = server.getFile("usercache.json");
            if (command.action() == TeamAction.PLAYERS) {
                runtime.teams().directory(command.projectId(), online, () -> MinecraftPlayerDirectory.cached(cache))
                        .whenComplete((directory, error) -> {
                            String notice = directory != null && directory.limited()
                                    ? "玩家目录达到显示上限，已优先显示当前项目成员与在线玩家" : "";
                            complete(server, runtime, player, connection, listener, command, scoped, null,
                                    directory == null ? List.of() : directory.players(),
                                    TeamOutcome.rejected(error == null ? TeamOutcome.Status.SUCCESS
                                            : TeamOutcome.Status.INVALID_REQUEST, null), error, notice);
                        });
            } else if (command.action() == TeamAction.TEAM_STATE) {
                runtime.teams().reconcile(new PlayerIdentity(playerId, actorName))
                        .thenCompose(ignored -> runtime.teams().state(command.projectId()))
                        .whenComplete((team, error) -> {
                            TeamState state = team == null ? null : team.orElse(null);
                            complete(server, runtime, player, connection, listener, command, scoped, state, List.of(),
                                    TeamOutcome.rejected(state == null ? TeamOutcome.Status.PROJECT_MISSING
                                            : TeamOutcome.Status.SUCCESS, state), error);
                        });
            } else {
                runtime.teams().resolveTargets(command, online, () -> MinecraftPlayerDirectory.cached(cache))
                        .thenCompose(resolved -> runtime.teams().execute(resolved,
                                new PlayerIdentity(playerId, actorName), admission))
                        .whenComplete((outcome, error) -> {
                            if (error == null) {
                                complete(server, runtime, player, connection, listener, command, scoped,
                                        outcome.state(), List.of(), outcome, null);
                            } else if (committed(error) instanceof net.syncmaterial.syncmaterial.server.storage.CommittedStorageException durable) {
                                TeamOutcome result = durable.committedResult(TeamOutcome.class);
                                if (result != null) {
                                    SyncMaterial.LOGGER.error("团队操作已经提交，但数据库连接恢复失败，不会重放事务", durable);
                                    complete(server, runtime, player, connection, listener, command, scoped,
                                            result.state(), List.of(), result, null,
                                            "操作已提交，但数据库连接不可用；请联系管理员，不要重复操作");
                                } else {
                                    complete(server, runtime, player, connection, listener, command, scoped,
                                            null, List.of(), null, error);
                                }
                            } else if (invalidTarget(error)) {
                                runtime.teams().state(command.projectId()).whenComplete((team, stateError) -> {
                                    TeamState state = team == null ? null : team.orElse(null);
                                    complete(server, runtime, player, connection, listener, command, scoped, state, List.of(),
                                            TeamOutcome.rejected(TeamOutcome.Status.INVALID_TARGET, state), stateError);
                                });
                            } else {
                                runtime.teams().state(command.projectId()).whenComplete((team, stateError) -> {
                                    TeamState state = team == null ? null : team.orElse(null);
                                    if (stateError != null) error.addSuppressed(stateError);
                                    complete(server, runtime, player, connection, listener, command, scoped,
                                            state, List.of(), null, error);
                                });
                            }
                        });
            }
        });
    }

    private static void complete(MinecraftServer server, ServerRuntime runtime, ServerPlayer original,
                                 long connection, Object listener, TeamCommand command, boolean scoped,
                                 TeamState state, List<PlayerDirectoryEntry> players, TeamOutcome outcome,
                                 Throwable error) {
        complete(server, runtime, original, connection, listener, command, scoped, state, players, outcome, error, "");
    }

    private static void complete(MinecraftServer server, ServerRuntime runtime, ServerPlayer original,
                                 long connection, Object listener, TeamCommand command, boolean scoped,
                                 TeamState state, List<PlayerDirectoryEntry> players, TeamOutcome outcome,
                                 Throwable error, String notice) {
        if (scoped) runtime.teamRequests().finish(original.getUUID(), connection);
        if (error != null) SyncMaterial.LOGGER.warn("团队管理请求失败: {} {}", command.projectId(), command.action(), error);
        long sequence = scoped && state != null ? runtime.teamRequests().nextSequence(command.projectId()) : 0;
        FoundationNetworkAdapter.execute(server, runtime, () -> {
            ServerPlayer player = FoundationNetworkAdapter.currentPlayer(server, runtime, original.getUUID(), connection);
            if (player != original || player.connection != listener) return;
            boolean success = error == null && outcome != null && outcome.success();
            String message = error == null ? (notice.isEmpty() ? message(command, outcome) : notice)
                    : "操作失败，请重试或查看服务端日志";
            int processed = outcome == null ? 0 : outcome.processedCount();
            int changed = outcome == null ? 0 : outcome.changedCount();
            if (scoped) {
                FoundationNetworkAdapter.send(player, new TeamResponseS2CPacket(new TeamResponse(command.projectId(),
                        command.requestId(), command.action(), sequence, success, message, state, players, processed, changed)));
            } else {
                sendLegacy(player, command.action(), success, message, state, players);
            }
        });
    }

    private static boolean invalidTarget(Throwable failure) {
        Throwable current = committed(failure);
        while (current != null) {
            if (current instanceof IllegalArgumentException
                    || current instanceof net.syncmaterial.syncmaterial.core.PlayerTargetException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static Throwable committed(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String message(TeamCommand command, TeamOutcome outcome) {
        if (outcome == null) return "操作失败";
        if (!outcome.success()) {
            return switch (outcome.status()) {
                case DENIED -> "没有权限";
                case PROJECT_MISSING -> "原理图尚未就绪，请稍后重试";
                case INVALID_TARGET -> "目标玩家无效或名称有歧义";
                case STALE_MATERIALS -> "材料目录已过期，请刷新后重试";
                default -> "请求无效";
            };
        }
        String target = command.targets().isEmpty() ? "" : command.targets().getFirst().name();
        return switch (command.action()) {
            case TRANSFER -> "已转让负责人给 " + target;
            case ADD_DEPUTY -> "已添加副负责人 " + target;
            case REMOVE_DEPUTY -> "已移除副负责人 " + target;
            case TOGGLE_SELF_CLAIM -> "自行认领已" + (outcome.state().allowSelfClaim() ? "开启" : "关闭");
            case ASSIGN -> "已分配 " + outcome.processedCount() + " 个任务";
            case KICK -> "已从 " + outcome.processedCount() + " 个材料中踢出 " + target;
            default -> "";
        };
    }

    static void sendLegacy(ServerPlayer player, TeamAction action, boolean success, String message,
                           TeamState state, List<PlayerDirectoryEntry> players) {
        switch (action) {
            case ASSIGN -> FoundationNetworkAdapter.send(player, new BatchAssignResponseS2CPacket(success, message));
            case KICK -> FoundationNetworkAdapter.send(player, new KickFromMaterialResponseS2CPacket(success, message));
            case PLAYERS -> FoundationNetworkAdapter.send(player, new PlayerListResponseS2CPacket(players.stream()
                    .map(entry -> new PlayerListResponseS2CPacket.PlayerInfo(entry.player().name(), entry.online())).toList()));
            default -> FoundationNetworkAdapter.send(player, new OwnerActionResponseS2CPacket(success, message,
                    state == null ? "" : state.owner().name(), state == null ? List.of() : state.deputyNames(),
                    state == null || state.allowSelfClaim()));
        }
    }

    private static void invalidLegacy(ServerPlayer player, TeamAction action, Throwable error) {
        if (ProtocolHandshake.hasHandshaked(player)) sendLegacy(player, action, false, "请求无效", null, List.of());
    }

    static void sendInitialTeam(ServerRuntime runtime, ServerPlayer player, TeamState state, long sequence) {
        if (!ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) return;
        FoundationNetworkAdapter.send(player, new TeamResponseS2CPacket(new TeamResponse(state.projectId(), 0,
                TeamAction.TEAM_STATE, sequence, true, "", state, List.of(), 0, 0)));
    }

    public static void tick(MinecraftServer server, ServerRuntime runtime) {
        for (String projectId : runtime.takeTeamChanges()) broadcast(server, runtime, projectId);
    }

    static void broadcast(MinecraftServer server, ServerRuntime runtime, String projectId) {
        TeamState durable = runtime.teams().takeCommittedState(projectId);
        runtime.teams().state(projectId).whenComplete((team, error) -> {
            TeamState state = error == null && team != null ? team.orElse(null) : durable;
            if (state == null) return;
            long sequence = runtime.teamRequests().nextSequence(projectId);
            FoundationNetworkAdapter.execute(server, runtime, () -> broadcastKnown(server, runtime, state, sequence));
        });
    }

    private static void broadcastKnown(MinecraftServer server, ServerRuntime runtime, TeamState state, long sequence) {
        for (UUID playerId : runtime.subscribers(state.projectId())) {
            long connection = runtime.connection(playerId);
            ServerPlayer player = FoundationNetworkAdapter.currentPlayer(server, runtime, playerId, connection);
            if (player == null) continue;
            if (ProtocolHandshake.supports(player, ProtocolVersion.SCOPED_TEAM_MANAGEMENT)) {
                FoundationNetworkAdapter.send(player, new TeamResponseS2CPacket(new TeamResponse(state.projectId(), 0,
                        TeamAction.TEAM_STATE, sequence, true, "", state, List.of(), 0, 0)));
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("负责人配置已更新，请重新打开材料清单"));
            }
        }
    }
}
