package net.syncmaterial.syncmaterial.client.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;

/**
 * Client-thread request-id router for autosave state; it never replays editor writes.
 *
 * <p>Every entry is fenced by the exact view generation that accepted the write. A reply
 * from an old view/generation/request/config cannot complete a newer edit, and a request
 * that never gets a reply is downgraded to UNCONFIRMED by the client tick instead of
 * retrying the write.</p>
 */
public final class EditorResponseRouter {
    private static final int MAX_PENDING = 256;
    static final int RESPONSE_TIMEOUT_TICKS = 200;
    private static final Object LOCK = new Object();
    private static final Map<Long, Pending> PENDING = new HashMap<>();

    private EditorResponseRouter() {}

    public static boolean register(long requestId, EditorSaveTracker tracker,
            ProjectViewClient.ViewToken target) {
        synchronized (LOCK) {
            if (requestId <= 0 || tracker == null || target == null
                    || PENDING.size() >= MAX_PENDING) return false;
            return PENDING.putIfAbsent(requestId,
                    new Pending(tracker, target, RESPONSE_TIMEOUT_TICKS)) == null;
        }
    }

    public static boolean complete(ProjectViewS2CPacket packet) {
        synchronized (LOCK) {
            if (packet.requestId() <= 0) return false;
            if (packet.result() != ProjectViewS2CPacket.Result.OPERATION
                    && packet.result() != ProjectViewS2CPacket.Result.CONFIG
                    && packet.result() != ProjectViewS2CPacket.Result.ERROR) return false;
            if (packet.action() != ProjectViewAction.AREA_CONFIG) return false;
            Pending pending = PENDING.get(packet.requestId());
            if (pending == null
                    || !pending.target.matches(packet.scope(), packet.viewId(), packet.generation())) {
                return false;
            }
            PENDING.remove(packet.requestId());
            return pending.tracker.complete(packet.requestId(), packet.success());
        }
    }

    /** Called from the client connection tick for a request the session already expired. */
    public static boolean timeout(long requestId) {
        synchronized (LOCK) {
            Pending pending = PENDING.remove(requestId);
            return pending != null && pending.tracker.timeout(requestId);
        }
    }

    /** Advances all reply windows by one client tick; expired entries become UNCONFIRMED. */
    public static void tick() {
        synchronized (LOCK) {
            Iterator<Map.Entry<Long, Pending>> iterator = PENDING.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Pending> entry = iterator.next();
                Pending pending = entry.getValue();
                if (--pending.ticksRemaining > 0) continue;
                iterator.remove();
                pending.tracker.timeout(entry.getKey());
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            PENDING.values().forEach(pending -> pending.tracker.invalidate());
            PENDING.clear();
        }
    }

    public static int pendingCount() {
        synchronized (LOCK) {
            return PENDING.size();
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
