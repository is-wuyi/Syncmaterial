package net.syncmaterial.syncmaterial.network;

import net.minecraft.server.network.ServerPlayerEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧的协议握手状态：记录每个玩家客户端的协议版本。
 *
 * 用途有两个：
 * 1. 判断某玩家是否装了本 mod —— 没握手过就是没装，不该给他推任何包，
 *    否则原版客户端会收到一堆未知 payload（原版会静默丢弃，但白费带宽）。
 * 2. 将来新增功能时判断该玩家的客户端是否支持，不支持就不推。
 *
 * 用 UUID 而非 ServerPlayerEntity 做键：玩家实体在跨维度传送时会被重建，
 * 且持有实体引用容易造成内存泄漏（onPlayerDisconnect 漏清就永久驻留）。
 */
public final class ProtocolHandshake
{
    private static final Map<UUID, Integer> PLAYER_VERSIONS = new ConcurrentHashMap<>();

    private ProtocolHandshake() {}

    /**
     * 记录握手并返回该客户端是否被接受。
     * 版本过低时不写入记录 —— 后续所有"是否装了 mod"的判断都会视其为未握手，
     * 服务端因此不会给它推任何业务包。
     */
    public static boolean recordHandshake(UUID playerId, int clientProtocolVersion, String clientModVersion)
    {
        if (playerId == null)
        {
            return false;
        }

        if (!ProtocolVersion.isClientAcceptable(clientProtocolVersion))
        {
            SyncMaterial.LOGGER.warn(
                    "拒绝客户端 {}：协议版本 {} 低于服务端要求的最低版本 {}（客户端 mod 版本 {}）",
                    playerId, clientProtocolVersion, ProtocolVersion.MIN_COMPATIBLE, clientModVersion);
            return false;
        }

        PLAYER_VERSIONS.put(playerId, clientProtocolVersion);

        if (ProtocolVersion.isPeerNewer(clientProtocolVersion))
        {
            SyncMaterial.LOGGER.info(
                    "客户端 {} 的协议版本 {} 高于服务端的 {}，服务端将按自身版本提供功能（客户端 mod 版本 {}）",
                    playerId, clientProtocolVersion, ProtocolVersion.CURRENT, clientModVersion);
        }

        return true;
    }

    /** 该玩家是否完成过握手，即是否装了本 mod 且版本被接受。 */
    public static boolean hasHandshaked(UUID playerId)
    {
        return playerId != null && PLAYER_VERSIONS.containsKey(playerId);
    }

    public static boolean hasHandshaked(ServerPlayerEntity player)
    {
        return player != null && hasHandshaked(player.getUuid());
    }

    /** 该玩家的客户端协议版本；未握手过返回 0。 */
    public static int getVersion(UUID playerId)
    {
        if (playerId == null)
        {
            return 0;
        }
        return PLAYER_VERSIONS.getOrDefault(playerId, 0);
    }

    public static int getVersion(ServerPlayerEntity player)
    {
        return player == null ? 0 : getVersion(player.getUuid());
    }

    /**
     * 该玩家的客户端是否支持某个需要最低协议版本的功能。
     *
     * 新增功能时在发包处调用，例如：
     * if (ProtocolHandshake.supports(player, 2)) { ServerPlayNetworking.send(player, 新包); }
     */
    public static boolean supports(ServerPlayerEntity player, int requiredVersion)
    {
        return player != null && ProtocolVersion.atLeast(getVersion(player.getUuid()), requiredVersion);
    }

    /** 玩家断开时清理，避免版本记录无限累积。 */
    public static void remove(UUID playerId)
    {
        if (playerId != null)
        {
            PLAYER_VERSIONS.remove(playerId);
        }
    }

    public static void remove(ServerPlayerEntity player)
    {
        if (player != null)
        {
            remove(player.getUuid());
        }
    }

    /** 仅供测试使用，清空全部记录。 */
    static void clearAll()
    {
        PLAYER_VERSIONS.clear();
    }
}
