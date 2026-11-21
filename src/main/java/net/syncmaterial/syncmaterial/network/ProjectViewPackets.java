package net.syncmaterial.syncmaterial.network;

import java.util.Optional;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/** Strict constructors for the protocol-5 envelope; pushes always have request id zero. */
public final class ProjectViewPackets {
    private ProjectViewPackets() {}

    public static ProjectViewS2CPacket opened(ProjectViewScope scope, long requestId, long viewId,
                                               long generation, UUID snapshotId) {
        return new ProjectViewS2CPacket(scope, requestId, viewId, generation, 0,
                ProjectViewS2CPacket.Result.OPENED, ProjectViewAction.NONE, true, "", 0, false,
                snapshotId.toString(), 0, 0, 0, 0, 0, new byte[0], Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket progress(ProjectViewScope scope, long viewId, long generation,
            ProjectProgressSnapshot snapshot, UUID snapshotId, ProjectProgressFragmenter.Fragment part) {
        return new ProjectViewS2CPacket(scope, 0, viewId, generation,
                snapshot.publicationSequence(), ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, snapshotId.toString(),
                snapshot.catalogRevision(), snapshot.inventoryRevision(), part.partIndex(),
                part.partCount(), part.totalBytes(), part.fragment(), Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket operation(ProjectViewScope scope, long requestId, long viewId,
                                                  long generation, CollaborationOutcome outcome) {
        ProjectViewAction action = switch (outcome.action()) {
            case JOIN -> ProjectViewAction.JOIN;
            case LEAVE -> ProjectViewAction.LEAVE;
            case REPORT -> throw new IllegalArgumentException("Inventory reports use the frozen heartbeat packet");
        };
        return new ProjectViewS2CPacket(scope, requestId, viewId, generation, 0,
                ProjectViewS2CPacket.Result.OPERATION, action, outcome.success(), outcome.status().name(),
                outcome.materialId(), outcome.changed(), "", 0, 0, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket operation(ProjectViewScope scope, long requestId, long viewId,
            long generation, ProjectViewAction action, boolean success, boolean changed, String message) {
        return new ProjectViewS2CPacket(scope, requestId, viewId, generation, 0,
                ProjectViewS2CPacket.Result.OPERATION, action, success, message, 0, changed,
                "", 0, 0, 0, 0, 0, new byte[0], Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket configuration(ProjectViewScope scope, long requestId, long viewId,
            long generation, long sequence, ProjectViewAction action, boolean success, String message,
            ProjectViewS2CPacket.AreaConfigData configuration) {
        return new ProjectViewS2CPacket(scope, requestId, viewId, generation, sequence,
                ProjectViewS2CPacket.Result.CONFIG, action, success, message, 0, false,
                "", 0, 0, 0, 0, 0, new byte[0], Optional.of(configuration), Optional.empty());
    }

    public static ProjectViewS2CPacket warehouse(ProjectViewScope scope, long viewId, long generation,
            long sequence, ContainerObservation observation) {
        return new ProjectViewS2CPacket(scope, 0, viewId, generation, sequence,
                ProjectViewS2CPacket.Result.WAREHOUSE, ProjectViewAction.NONE, true, "",
                0, false, "", 0, 0, 0, 0, 0, new byte[0], Optional.empty(), Optional.of(observation));
    }

    public static ProjectViewS2CPacket warehouseComplete(ProjectViewScope scope, long viewId,
                                                          long generation, long sequence) {
        return new ProjectViewS2CPacket(scope, 0, viewId, generation, sequence,
                ProjectViewS2CPacket.Result.WAREHOUSE, ProjectViewAction.NONE, true, "COMPLETE",
                0, false, "", 0, 0, 0, 0, 0, new byte[0], Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket invalidated(ProjectViewScope scope, long viewId, long generation,
                                                    String message) {
        return untyped(scope, 0, viewId, generation, ProjectViewS2CPacket.Result.INVALIDATED,
                ProjectViewAction.NONE, true, message, Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket deleted(ProjectViewScope scope, long viewId, long generation) {
        return untyped(scope, 0, viewId, generation, ProjectViewS2CPacket.Result.DELETED,
                ProjectViewAction.NONE, true, "", Optional.empty(), Optional.empty());
    }

    public static ProjectViewS2CPacket error(ProjectViewScope scope, long requestId, long viewId,
            long generation, ProjectViewAction action, String message) {
        return untyped(scope, requestId, viewId, generation, ProjectViewS2CPacket.Result.ERROR,
                action, false, message, Optional.empty(), Optional.empty());
    }

    private static ProjectViewS2CPacket untyped(ProjectViewScope scope, long requestId, long viewId,
            long generation, ProjectViewS2CPacket.Result result, ProjectViewAction action,
            boolean success, String message, Optional<ProjectViewS2CPacket.AreaConfigData> configuration,
            Optional<ContainerObservation> warehouse) {
        return new ProjectViewS2CPacket(scope, requestId, viewId, generation, 0, result, action,
                success, message, 0, false, "", 0, 0, 0, 0, 0, new byte[0], configuration, warehouse);
    }
}
