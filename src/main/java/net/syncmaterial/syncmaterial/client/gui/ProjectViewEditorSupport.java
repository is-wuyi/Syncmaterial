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
