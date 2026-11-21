package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.session.ClientHandshakeState;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;

/** Compatibility facade for the current connection's immutable handshake snapshot. */
public final class ClientProtocolState {
    public enum Status { PENDING, ACCEPTED, REJECTED }

    private static final ClientHandshakeState DISCONNECTED = new ClientHandshakeState();

    private ClientProtocolState() {}

    private static ClientHandshakeState state() {
        return ClientSessionManager.current().map(session -> session.handshake())
                .orElse(DISCONNECTED);
    }

    public static void onHandshakeSent() {
        SyncMaterial.LOGGER.info("已向服务端发出版本握手：本地协议版本 {}（mod 版本 {}）",
                ProtocolVersion.CURRENT, SyncMaterial.getModVersion());
    }

    /** Test/direct entry. Network receivers must use the connection-fenced overload. */
    public static void onHandshakeResponse(int protocolVersion, String modVersion, boolean accepted) {
        state().response(protocolVersion, modVersion, accepted);
        logResponse(protocolVersion, modVersion, accepted);
    }

    /** Applies a Hello only to the connection that was current when it was received. */
    public static boolean onHandshakeResponse(Object connection, long token,
            int protocolVersion, String modVersion, boolean accepted) {
        return ClientSessionManager.ifCurrent(connection, token, () ->
                onHandshakeResponse(protocolVersion, modVersion, accepted));
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

    public static void reset() {
        state().reset();
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
