package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientHandshakeState;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;

/** Compatibility facade for the current connection's immutable handshake snapshot. */
public final class ClientProtocolState {
    public enum Status { PENDING, ACCEPTED, REJECTED }

    /**
     * 只读回退快照：无可用会话时查询返回 PENDING/0/空、serverSupports false。
     * 任何入口都不得写入该实例——无会话的 direct handshake/reset 是空操作。
     */
    private static final ClientHandshakeState DISCONNECTED = new ClientHandshakeState();

    private ClientProtocolState() {}

    private static ClientHandshakeState state() {
        return ClientSessionManager.current().map(ClientConnectionSession::handshake)
                .orElse(DISCONNECTED);
    }

    public static void onHandshakeSent() {
        SyncMaterial.LOGGER.info("已向服务端发出版本握手：本地协议版本 {}（mod 版本 {}）",
                ProtocolVersion.CURRENT, SyncMaterial.getModVersion());
    }

    /**
     * Test/direct entry: 仅供测试（显式 Manager.join 之后调用），无会话时不写任何
     * 状态；网络接收必须走会话 dispatcher（INIT 绑定的原 handler）。
     */
    public static void onHandshakeResponse(int protocolVersion, String modVersion, boolean accepted) {
        ClientConnectionSession session = ClientSessionManager.current().orElse(null);
        if (session != null) {
            session.handshake().response(protocolVersion, modVersion, accepted);
        }
        logResponse(protocolVersion, modVersion, accepted);
    }

    /**
     * Compat fenced entry（保留给既有测试）：handler 与 token 一致且仍是当前开放
     * 会话才生效。生产接收路径使用 {@link #applySessionHandshake}。
     */
    public static boolean onHandshakeResponse(Object connection, long token,
            int protocolVersion, String modVersion, boolean accepted) {
        boolean[] applied = {false};
        boolean dispatched = ClientSessionManager.dispatch(connection, session -> {
            if (session.token() == token) {
                session.handshake().response(protocolVersion, modVersion, accepted);
                applied[0] = true;
            }
        });
        if (applied[0]) {
            logResponse(protocolVersion, modVersion, accepted);
        }
        return dispatched && applied[0];
    }

    /**
     * Session-scoped Hello application for the INIT-bound receiver: the dispatcher
     * already fenced the original handler; only an open session can be written.
     */
    public static boolean applySessionHandshake(ClientConnectionSession session,
            int protocolVersion, String modVersion, boolean accepted) {
        if (session == null || !session.isOpen()) return false;
        session.handshake().response(protocolVersion, modVersion, accepted);
        logResponse(protocolVersion, modVersion, accepted);
        return true;
    }

    private static void logResponse(int protocolVersion, String modVersion, boolean accepted) {
        if (accepted) {
            SyncMaterial.LOGGER.info("版本握手成功：服务端协议版本 {}（mod 版本 {}），本地协议版本 {}",
                    protocolVersion, modVersion == null ? "" : modVersion, ProtocolVersion.CURRENT);
        } else {
            SyncMaterial.LOGGER.warn("服务端拒绝了本客户端：服务端协议版本 {}（mod 版本 {}），本地协议版本 {}，请升级 SyncMaterial",
                    protocolVersion, modVersion == null ? "" : modVersion, ProtocolVersion.CURRENT);
        }
    }

    /** 无会话时空操作；只在当前可用会话内重置。 */
    public static void reset() {
        ClientConnectionSession session = ClientSessionManager.current().orElse(null);
        if (session != null) {
            session.handshake().reset();
        }
    }

    public static Status getStatus() {
        return Status.valueOf(state().status().name());
    }

    public static boolean isUsable() {
        return state().isUsable();
    }

    public static int getServerProtocolVersion() {
        return state().serverProtocolVersion();
    }

    public static String getServerModVersion() {
        return state().serverModVersion();
    }

    public static boolean serverSupports(int requiredVersion) {
        return state().serverSupports(requiredVersion);
    }

    public static boolean isServerNewer() {
        return state().isServerNewer();
    }
}
