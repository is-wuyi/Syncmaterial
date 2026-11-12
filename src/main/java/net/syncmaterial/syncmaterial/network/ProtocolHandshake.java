package net.syncmaterial.syncmaterial.network;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;

/** 服务端握手按服务器和连接归属，旧连接的清理不能删除新连接记录。 */
public final class ProtocolHandshake {
    private static final Object LOCK = new Object();
    private static final Map<MinecraftServer, Map<UUID, Handshake>> SERVERS = new IdentityHashMap<>();

    private record Handshake(Object connection, int version) {}

    private ProtocolHandshake() {}

    public static void registerServer(MinecraftServer server) {
        synchronized (LOCK) {
            if (server != null) SERVERS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
    }

    public static boolean recordHandshake(MinecraftServer server, ServerPlayer player,
                                          int clientProtocolVersion, String clientModVersion) {
        synchronized (LOCK) {
            Map<UUID, Handshake> players = SERVERS.get(server);
            if (players == null || player == null || getServer(player) != server
                    || player.connection == null || server.getPlayerList() == null
                    || server.getPlayerList().getPlayer(player.getUUID()) != player) return false;
            UUID id = player.getUUID();
            String name = player.getName().getString();
            if (!ProtocolVersion.isClientAcceptable(clientProtocolVersion)) {
                players.computeIfPresent(id, (ignored, previous) -> previous.connection() == player.connection ? null : previous);
                SyncMaterial.LOGGER.warn("拒绝客户端 {}：协议版本 {} 低于服务端最低版本 {}（mod 版本 {}）",
                        name, clientProtocolVersion, ProtocolVersion.MIN_COMPATIBLE, clientModVersion);
                return false;
            }
            players.put(id, new Handshake(player.connection, clientProtocolVersion));
            SyncMaterial.LOGGER.info("版本握手成功：玩家 {} 协议版本 {}（mod 版本 {}），服务端协议版本 {}",
                    name, clientProtocolVersion, clientModVersion, ProtocolVersion.CURRENT);
            if (ProtocolVersion.isPeerNewer(clientProtocolVersion)) {
                SyncMaterial.LOGGER.info("玩家 {} 的客户端比服务端新，服务端按自身版本 {} 提供功能", name, ProtocolVersion.CURRENT);
            }
            return true;
        }
    }

    public static MinecraftServer getServer(ServerPlayer player) {
        return player == null || player.level() == null ? null : player.level().getServer();
    }

    public static int getVersion(ServerPlayer player) {
        synchronized (LOCK) {
            MinecraftServer server = getServer(player);
            Map<UUID, Handshake> players = SERVERS.get(server);
            if (players == null || player == null || player.connection == null || server.getPlayerList() == null) return 0;
            Handshake handshake = players.get(player.getUUID());
            return handshake != null && handshake.connection() == player.connection
                    && server.getPlayerList().getPlayer(player.getUUID()) == player ? handshake.version() : 0;
        }
    }

    public static boolean hasHandshaked(ServerPlayer player) {
        return getVersion(player) != 0;
    }

    public static boolean supports(ServerPlayer player, int requiredVersion) {
        return ProtocolVersion.atLeast(getVersion(player), requiredVersion);
    }

    public static void remove(ServerPlayer player) {
        synchronized (LOCK) {
            MinecraftServer server = getServer(player);
            Map<UUID, Handshake> players = SERVERS.get(server);
            if (players == null || player == null || server.getPlayerList() == null) return;
            var current = server.getPlayerList().getPlayer(player.getUUID());
            removeConnection(players, player.getUUID(), player.connection, current);
        }
    }

    private static void removeConnection(Map<UUID, Handshake> players, UUID playerId,
                                         @javax.annotation.Nullable Object connection,
                                         @javax.annotation.Nullable ServerPlayer current) {
        if (connection == null && current != null) return;
        players.computeIfPresent(playerId, (ignored, previous) ->
                previous.connection() == connection || (connection == null && current == null) ? null : previous);
    }

    public static void clear(MinecraftServer server) {
        synchronized (LOCK) {
            SERVERS.remove(server);
        }
    }

    /** 无服务器的历史读取只在该 UUID 的归属唯一时返回版本。 */
    public static int getVersion(UUID playerId) {
        synchronized (LOCK) {
            if (playerId == null) return 0;
            Handshake result = null;
            for (Map<UUID, Handshake> players : SERVERS.values()) {
                Handshake candidate = players.get(playerId);
                if (candidate == null) continue;
                if (result != null) return 0;
                result = candidate;
            }
            return result == null ? 0 : result.version();
        }
    }

    public static boolean hasHandshaked(UUID playerId) {
        return getVersion(playerId) != 0;
    }

    static void clearAll() {
        synchronized (LOCK) {
            SERVERS.clear();
        }
    }
}
