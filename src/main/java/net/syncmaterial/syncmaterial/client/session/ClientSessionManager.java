package net.syncmaterial.syncmaterial.client.session;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.SyncMaterial;

/** Sole owner of the current client connection and its deferred cleanup. */
public final class ClientSessionManager {
    private static final Object LOCK = new Object();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong PENDING_DISCONNECT = new AtomicLong(-1);
    /**
     * 兼容入口（既有测试与迁移期 JOIN）的默认发送端口；生产 JOIN 必须通过
     * {@link #join(Object, Consumer)} 绑定原连接的 PacketSender，旧会话的
     * 延迟发送绝不能经全局 ClientPlayNetworking.send 落到新连接。
     */
    private static final Consumer<CustomPacketPayload> GLOBAL_TRANSPORT =
            payload -> ClientPlayNetworking.send(payload);
    private static Object activeConnection;
    private static ClientConnectionSession current;
    private static boolean connected;

    private ClientSessionManager() {}

    /**
     * Test/compat entry; production JOIN binds the original PacketSender via
     * {@link #join(Object, Consumer)}.
     */
    public static ClientConnectionSession join(Object connection) {
        return join(connection, GLOBAL_TRANSPORT);
    }

    /**
     * Client-thread JOIN：先封闭上一连接的会话，再发布新 current；旧 close
     * 绝不清新会话。generation 延续同一计数器，不新建第二计数器。
     * {@code transport} 是原连接 PacketSender 的窄发送端口（由 Fabric JOIN 事件的
     * {@code sender::sendPacket} 适配而来），会话发送与回收全部走这一份捕获。
     */
    public static ClientConnectionSession join(Object connection,
            Consumer<CustomPacketPayload> transport) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(transport, "transport");
        ClientConnectionSession previous;
        ClientConnectionSession created;
        synchronized (LOCK) {
            previous = current;
            long token = GENERATION.incrementAndGet();
            created = new ClientConnectionSession(connection, token, transport);
            if (previous != null) {
                try {
                    previous.close();
                } catch (RuntimeException closeFailure) {
                    SyncMaterial.LOGGER.warn("旧连接会话清理失败，继续发布新会话", closeFailure);
                }
            }
            current = created;
            activeConnection = connection;
            connected = true;
            PENDING_DISCONNECT.set(-1);
        }
        return created;
    }

    /**
     * 只暴露当前可用会话：已失效但仍待回收（断开待清理）或已封闭的会话一律不返回。
     */
    public static Optional<ClientConnectionSession> current() {
        synchronized (LOCK) {
            if (!connected || current == null || !current.isOpen()) return Optional.empty();
            return Optional.of(current);
        }
    }

    /** Session bound to the original connection; empty unless it is the current open one. */
    public static Optional<ClientConnectionSession> current(Object connection) {
        synchronized (LOCK) {
            if (!connected || connection == null || activeConnection != connection
                    || current == null || !current.isOpen()) {
                return Optional.empty();
            }
            return Optional.of(current);
        }
    }

    /**
     * 窄 dispatcher：仅当捕获的原始 handler 仍是当前开放会话的连接身份时执行业务。
     * 接收方先在排队前捕获 handler，此处复核；执行期间不再通过静态 current 切换目标，
     * 动作拿到的会话自身的 {@code isOpen} 是最后一道闸。
     */
    public static boolean dispatch(Object connection, Consumer<ClientConnectionSession> action) {
        Objects.requireNonNull(action, "action");
        ClientConnectionSession session;
        synchronized (LOCK) {
            if (!connected || connection == null || activeConnection != connection
                    || current == null || !current.isOpen()) {
                return false;
            }
            session = current;
        }
        action.accept(session);
        return true;
    }

    public static long connectionToken() {
        return GENERATION.get();
    }

    /**
     * 当前会话的取货状态；无可用会话时返回一次性空对象——写入不激活、查询恒为
     * 空/false。不再共享可变 DISCONNECTED 实例（shared Pickup 纯算法不变）。
     */
    public static net.syncmaterial.syncmaterial.client.PickupModeState pickup() {
        ClientConnectionSession session = current().orElse(null);
        return session != null
                ? session.pickup()
                : new net.syncmaterial.syncmaterial.client.PickupModeState();
    }

    public static Object connectionIdentity() {
        synchronized (LOCK) {
            return connected ? activeConnection : null;
        }
    }

    public static boolean isConnected() {
        synchronized (LOCK) {
            return connected;
        }
    }

    public static boolean isCurrent(Object connection, long token) {
        synchronized (LOCK) {
            return connected && activeConnection == connection && current != null
                    && current.token() == token && current.isOpen();
        }
    }

    /**
     * Compat primitive: runs only when the source connection is still the current
     * open session. Production receivers must use {@link #dispatch} instead.
     */
    public static boolean ifCurrent(Object connection, long token, Runnable action) {
        synchronized (LOCK) {
            if (!isCurrent(connection, token)) return false;
        }
        action.run();
        return true;
    }

    public static long invalidateConnection() {
        synchronized (LOCK) {
            return invalidateLocked();
        }
    }

    public static long invalidateConnection(Object connection) {
        synchronized (LOCK) {
            if (connection == null || activeConnection != connection) return -1;
            return invalidateLocked();
        }
    }

    private static long invalidateLocked() {
        if (!connected) return PENDING_DISCONNECT.get();
        connected = false;
        long token = GENERATION.incrementAndGet();
        PENDING_DISCONNECT.set(token);
        return token;
    }

    public static boolean finishPendingDisconnect() {
        return finishDisconnect(PENDING_DISCONNECT.get());
    }

    public static boolean finishDisconnect(long token) {
        ClientConnectionSession closing;
        synchronized (LOCK) {
            if (token < 0 || token != GENERATION.get() || connected
                    || !PENDING_DISCONNECT.compareAndSet(token, -1)) return false;
            closing = current;
            current = null;
            activeConnection = null;
        }
        if (closing != null) closing.close();
        return true;
    }

    /** Immediate compatibility close for the old single active client owner. */
    public static void discard() {
        ClientConnectionSession previous;
        synchronized (LOCK) {
            previous = current;
            current = null;
            activeConnection = null;
            connected = false;
            PENDING_DISCONNECT.set(-1);
        }
        if (previous != null) previous.close();
    }

    /** Test-only process reset; production must use the real connection callbacks. */
    public static void resetForTesting() {
        ClientConnectionSession previous;
        synchronized (LOCK) {
            previous = current;
            current = null;
            activeConnection = null;
            connected = false;
            GENERATION.set(0);
            PENDING_DISCONNECT.set(-1);
        }
        if (previous != null) previous.close();
    }
}
