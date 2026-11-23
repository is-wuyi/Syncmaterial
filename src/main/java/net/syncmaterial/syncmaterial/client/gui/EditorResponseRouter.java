package net.syncmaterial.syncmaterial.client.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;

/** Session-owned request-id router for autosave state; it never replays editor writes. */
public final class EditorResponseRouter {
    private static final int MAX_PENDING = 256;
    static final int RESPONSE_TIMEOUT_TICKS = 200;

    /**
     * 无会话回退实例：只读——不存 pending、不完成、不超时回调。
     * 会话实例（随 ClientConnectionSession 创建/关闭）行为不变。
     */
    private static final EditorResponseRouter DISCONNECTED = new EditorResponseRouter(true);

    private final Object lock = new Object();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final boolean noSessionFallback;

    public EditorResponseRouter() {
        this.noSessionFallback = false;
    }

    private EditorResponseRouter(boolean noSessionFallback) {
        this.noSessionFallback = noSessionFallback;
    }

    private static EditorResponseRouter current() {
        return ClientSessionManager.current().map(session -> session.editorResponses())
                .orElse(DISCONNECTED);
    }

    public static boolean register(long requestId, EditorSaveTracker tracker,
            ProjectViewClient.ViewToken target) {
        return current().registerInstance(requestId, tracker, target);
    }

    public boolean registerInstance(long requestId, EditorSaveTracker tracker,
            ProjectViewClient.ViewToken target) {
        if (noSessionFallback) return false;
        synchronized (lock) {
            if (requestId <= 0 || tracker == null || target == null
                    || pending.size() >= MAX_PENDING) return false;
            return pending.putIfAbsent(requestId,
                    new Pending(tracker, target, RESPONSE_TIMEOUT_TICKS)) == null;
        }
    }

    public static boolean complete(ProjectViewS2CPacket packet) {
        return current().completeInstance(packet);
    }

    public boolean completeInstance(ProjectViewS2CPacket packet) {
        if (noSessionFallback) return false;
        synchronized (lock) {
            if (packet.requestId() <= 0) return false;
            if (packet.result() != ProjectViewS2CPacket.Result.OPERATION
                    && packet.result() != ProjectViewS2CPacket.Result.CONFIG
                    && packet.result() != ProjectViewS2CPacket.Result.ERROR) return false;
            if (packet.action() != ProjectViewAction.AREA_CONFIG) return false;
            Pending entry = pending.get(packet.requestId());
            if (entry == null
                    || !entry.target.matches(packet.scope(), packet.viewId(), packet.generation())) {
                return false;
            }
            pending.remove(packet.requestId());
            return entry.tracker.complete(packet.requestId(), packet.success());
        }
    }

    public static boolean timeout(long requestId) {
        return current().timeoutInstance(requestId);
    }

    public boolean timeoutInstance(long requestId) {
        if (noSessionFallback) return false;
        synchronized (lock) {
            Pending entry = pending.remove(requestId);
            return entry != null && entry.tracker.timeout(requestId);
        }
    }

    public static void tick() {
        current().tickInstance();
    }

    public void tickInstance() {
        if (noSessionFallback) return;
        synchronized (lock) {
            Iterator<Map.Entry<Long, Pending>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Pending> entry = iterator.next();
                if (--entry.getValue().ticksRemaining > 0) continue;
                iterator.remove();
                entry.getValue().tracker.timeout(entry.getKey());
            }
        }
    }

    public static void clear() {
        current().clearInstance();
    }

    public void clearInstance() {
        if (noSessionFallback) return;
        synchronized (lock) {
            pending.values().forEach(entry -> entry.tracker.invalidate());
            pending.clear();
        }
    }

    public static int pendingCount() {
        return current().pendingCountInstance();
    }

    public int pendingCountInstance() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private static final class Pending {
        private final EditorSaveTracker tracker;
        private final ProjectViewClient.ViewToken target;
        private int ticksRemaining;

        private Pending(EditorSaveTracker tracker, ProjectViewClient.ViewToken target,
                        int ticksRemaining) {
            this.tracker = tracker;
            this.target = target;
            this.ticksRemaining = ticksRemaining;
        }
    }
}
