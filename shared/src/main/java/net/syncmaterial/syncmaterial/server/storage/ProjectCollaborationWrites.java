package net.syncmaterial.syncmaterial.server.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;

/** Leave/report admission and persistence inside one caller-owned writer transaction. */
final class ProjectCollaborationWrites {
    private ProjectCollaborationWrites() {}

    static CollaborationOutcome leave(Connection connection, String projectId, int materialId,
                                      PlayerIdentity actor, Predicate<ProjectSnapshot> currentMaterial,
                                      BooleanSupplier admission, BooleanSupplier currentOperation)
            throws SQLException {
        Admission admitted = admittedProject(connection, projectId, materialId,
                currentMaterial, admission, currentOperation);
        if (admitted.rejection() != null) {
            return outcome(CollaborationOutcome.Action.LEAVE,
                    admitted.rejection(), projectId, materialId);
        }
        ProjectWrites.LeaveResult result = ProjectWrites.leaveWithResult(
                connection, projectId, materialId, actor);
        return outcome(CollaborationOutcome.Action.LEAVE,
                result.changed() ? CollaborationOutcome.Status.APPLIED : CollaborationOutcome.Status.UNCHANGED,
                projectId, materialId);
    }

    static CollaborationOutcome report(Connection connection, String projectId, int materialId,
                                       PlayerIdentity actor, long count,
                                       Predicate<ProjectSnapshot> currentMaterial,
                                       BooleanSupplier admission, BooleanSupplier currentOperation)
            throws SQLException {
        Admission admitted = admittedProject(connection, projectId, materialId,
                currentMaterial, admission, currentOperation);
        if (admitted.rejection() != null) {
            return outcome(CollaborationOutcome.Action.REPORT,
                    admitted.rejection(), projectId, materialId);
        }
        Long playerId = PlayersRepository.findPlayerId(connection, actor);
        if (playerId == null || !ProjectWrites.isClaimed(connection, projectId, materialId, playerId)) {
            return outcome(CollaborationOutcome.Action.REPORT,
                    CollaborationOutcome.Status.NOT_CLAIMED, projectId, materialId);
        }
        ProjectWrites.recordInventory(connection, projectId, materialId, actor, count);
        return outcome(CollaborationOutcome.Action.REPORT, CollaborationOutcome.Status.APPLIED,
                projectId, materialId);
    }

    private static Admission admittedProject(Connection connection, String projectId,
                                      int materialId, Predicate<ProjectSnapshot> currentMaterial,
                                      BooleanSupplier admission, BooleanSupplier currentOperation)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(currentMaterial, "currentMaterial");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(currentOperation, "currentOperation");
        if (!currentOperation.getAsBoolean()) {
            return new Admission(CollaborationOutcome.Status.SUPERSEDED);
        }
        Optional<ProjectSnapshot> existing = ProjectReads.findProject(connection, projectId);
        if (existing.isEmpty()) {
            return new Admission(CollaborationOutcome.Status.PROJECT_MISSING);
        }
        if (!admission.getAsBoolean()) {
            return new Admission(CollaborationOutcome.Status.DENIED);
        }
        ProjectSnapshot project = existing.get();
        if (!currentMaterial.test(project)) {
            return new Admission(CollaborationOutcome.Status.STALE_MATERIALS);
        }
        for (MaterialSnapshot material : project.materials()) {
            if (material.id() == materialId) return new Admission(null);
        }
        return new Admission(CollaborationOutcome.Status.INVALID_REQUEST);
    }

    private record Admission(CollaborationOutcome.Status rejection) {}

    private static CollaborationOutcome outcome(CollaborationOutcome.Action action,
                                                CollaborationOutcome.Status status,
                                                String projectId, int materialId) {
        return CollaborationOutcome.of(action, status, projectId, materialId);
    }
}
