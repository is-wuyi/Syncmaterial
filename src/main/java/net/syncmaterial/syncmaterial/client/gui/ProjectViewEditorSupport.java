package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;

/** Protocol-5 editor adapter; the old packet remains the explicit unconfirmed fallback. */
public final class ProjectViewEditorSupport {
    private ProjectViewEditorSupport() {}

    public static long write(ProjectViewScope scope, int areaId, String areaAction,
            Optional<StagingAreaConfigC2SPacket.AreaData> data,
            StagingAreaConfigC2SPacket legacyPacket, EditorSaveTracker tracker) {
        long version = tracker.edit();
        return writePrepared(scope, areaId, areaAction, data, legacyPacket, tracker, version);
    }

    /**
     * Sends an already-registered edit intent: {@code version} was produced by
     * {@link EditorSaveTracker#edit()} when the user made the change, and the caller's
     * debounced payload carries the same immutable target/data that was captured then.
     * This method never re-creates a version at send time; a version that has been
     * superseded by a newer edit is refused (defense in depth) so a stale payload can
     * never overwrite newer data on the wire and a stale ACK can never mark newer
     * input as saved.
     */
    public static long writePrepared(ProjectViewScope scope, int areaId, String areaAction,
            Optional<StagingAreaConfigC2SPacket.AreaData> data,
            StagingAreaConfigC2SPacket legacyPacket, EditorSaveTracker tracker, long version) {
        if (tracker.version() != version) return -1;
        if (ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            ProjectViewClient session = ProjectViewClient.current().orElse(null);
            long request = session == null ? -1L
                    : session.areaConfig(scope, areaId, areaAction, data);
            ProjectViewClient.ViewToken target = session == null ? null : session.viewToken(scope);
            if (request > 0 && target != null && tracker.sent(version, request)
                    && EditorResponseRouter.register(request, tracker, target)) return request;
            tracker.unconfirmed(version);
            return -1;
        }
        ClientPlayNetworking.send(legacyPacket);
        tracker.unconfirmed(version);
        return 0;
    }

    /** One view the caller already read, used to avoid re-reading on a mere GUI resize. */
    public static ProjectViewClient.ViewToken captureView(ProjectViewScope scope) {
        if (!ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) return null;
        return ProjectViewClient.current().map(session -> session.viewToken(scope)).orElse(null);
    }

    /**
     * Validity check for a debounced editor intent. It passes only while the captured
     * context (connection + view) is still current and no newer edit was registered.
     * A failed check marks the intent unconfirmed — a dropped payload never poses as
     * saved — while a superseded (older) version leaves the newer intent untouched.
     */
    public static java.util.function.BooleanSupplier intentValidity(
            EditFence fence, long version, EditorSaveTracker tracker) {
        return () -> {
            if (isFenceCurrent(fence) && tracker.version() == version) return true;
            tracker.unconfirmed(version);
            return false;
        };
    }

    /**
     * Fence for one delayed editor intent. The connection token rejects old sessions even
     * when their view ids are reset; the view token rejects project switches and deletions.
     */
    public record EditFence(long connectionToken, ProjectViewClient.ViewToken view) {}

    public static EditFence captureFence(ProjectViewScope scope) {
        long connectionToken = TeamManagementClient.connectionToken();
        if (!ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            return new EditFence(connectionToken, null);
        }
        ProjectViewClient session = ProjectViewClient.current().orElse(null);
        return new EditFence(connectionToken, session == null ? null : session.viewToken(scope));
    }

    public static boolean isFenceCurrent(EditFence fence) {
        if (fence == null) return false;
        if (TeamManagementClient.connectionToken() != fence.connectionToken()) return false;
        boolean scoped = ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS);
        if (fence.view() == null) return !scoped;
        if (!scoped) return false;
        ProjectViewClient session = ProjectViewClient.current().orElse(null);
        return session != null && session.isCurrentView(fence.view());
    }

    public static String saveStatus(EditorSaveTracker.State state) {
        return switch (state) {
            case IDLE -> "";
            case PENDING -> fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.pending");
            case SAVING -> fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.saving");
            case SAVED -> fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.saved");
            case UNCONFIRMED -> fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.unconfirmed");
            case FAILED -> fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.failed");
        };
    }

    public static long read(ProjectViewScope scope, String areaAction,
                            StagingAreaConfigC2SPacket legacyPacket) {
        if (ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            return ProjectViewClient.current()
                    .map(session -> session.areaConfig(scope, 0, areaAction, Optional.empty()))
                    .orElse(-1L);
        }
        ClientPlayNetworking.send(legacyPacket);
        return 0;
    }
}
