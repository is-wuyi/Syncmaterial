package net.syncmaterial.syncmaterial.network;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/** Legacy 接收器准入：捕获原始上下文，旧回调不得落到新上下文。 */
final class LegacyNetworkDispatch {
    private LegacyNetworkDispatch() {
    }

    /** 只认显式 server 当前登记的上下文，不跨服回退。 */
    static LegacyServerContext capture(ServerPlayNetworking.Context context) {
        if (context == null) {
            return null;
        }
        return capture(context.server(), context.player());
    }

    static LegacyServerContext capture(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return null;
        }
        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (owner == null || owner.server() != server || !owner.isOpen() || !owner.isCurrent()) {
            return null;
        }
        return owner;
    }

    /** 仅供只读兼容查询使用。 */
    static LegacyServerContext captureForPlayer(ServerPlayer player) {
        MinecraftServer server = serverOf(player);
        if (server == null) {
            return null;
        }
        LegacyServerContext owner = LegacyServerRegistry.get(server);
        if (owner == null || owner.server() != server || !owner.isOpen() || !owner.isCurrent()) {
            return null;
        }
        return owner;
    }

    /** 不要在回调里重查注册表；原始连接由 owner.execute(player, ...) 二次校验。 */
    static void dispatch(ServerPlayNetworking.Context context, Consumer<LegacyServerContext> action) {
        LegacyServerContext owner = capture(context);
        if (owner == null || action == null) {
            return;
        }
        ServerPlayer player = context.player();
        owner.execute(player, () -> {
            if (!owner.isCurrent()) {
                return;
            }
            action.accept(owner);
        });
    }

    /** 直接 handler 入口的防御性准入。 */
    static boolean accepts(LegacyServerContext owner, ServerPlayer player) {
        return owner != null && accepts(owner, player, owner.server());
    }

    static boolean accepts(LegacyServerContext owner, ServerPlayer player, MinecraftServer server) {
        if (owner == null || player == null || server == null) {
            return false;
        }
        if (owner.server() != server || !owner.isOpen() || !owner.isCurrent()) {
            return false;
        }
        if (serverOf(player) != server || player.connection == null) {
            return false;
        }
        return owner.isConnected(player) && ProtocolHandshake.hasHandshaked(player);
    }

    static MinecraftServer serverOf(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        ServerLevel level = player.level();
        //? if >=26.2 {
        if (level == null) {
            return null;
        }
        //?}
        MinecraftServer server = level.getServer();
        //? if >=26.2 {
        if (server == null) {
            return null;
        }
        //?}
        return server;
    }
}
