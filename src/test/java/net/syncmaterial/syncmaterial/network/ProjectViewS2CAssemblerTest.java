package net.syncmaterial.syncmaterial.network;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.client.ProjectViewAssembler;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProjectViewS2CAssemblerTest {
    private static final ProjectViewScope SCOPE = ProjectViewScope.project("project");
    private static final String SNAPSHOT_ID = "00000000-0000-0000-0000-0000000000aa";
    private static final Object CONNECTION = new Object();
    private static final long CONNECTION_GENERATION = 4L;
    private static final long VIEW_ID = 7L;
    private static final long VIEW_GENERATION = 2L;
    private static final long SEQUENCE = 11L;
    private static final long CATALOG_REVISION = 3L;
    private static final long INVENTORY_REVISION = 4L;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void realS2CProgressPacketsAssembleIntoTheCoreSnapshot() {
        ProjectProgressSnapshot snapshot = snapshot("project");
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        assembler.openView(CONNECTION, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        List<ProjectProgressFragmenter.Fragment> parts = ProjectProgressFragmenter.encode(snapshot);
        ProjectViewAssembler.Outcome outcome = null;
        for (ProjectProgressFragmenter.Fragment part : parts) {
            outcome = assembler.accept(CONNECTION, CONNECTION_GENERATION,
                    packet(snapshot.projectId(), part));
        }
        ProjectViewAssembler.Complete complete = assertInstanceOf(
                ProjectViewAssembler.Complete.class, outcome);
        assertEquals(snapshot, complete.view().snapshot());
        assertEquals(SEQUENCE, complete.view().sequence());
        assertEquals(SCOPE, complete.view().scope());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void realS2CPacketWithCrossProjectPayloadIsRejected() {
        ProjectProgressSnapshot foreign = snapshot("other-project");
        ProjectViewAssembler assembler = new ProjectViewAssembler();
        assembler.openView(CONNECTION, CONNECTION_GENERATION, SCOPE, VIEW_ID, VIEW_GENERATION);
        ProjectProgressFragmenter.Fragment part = ProjectProgressFragmenter.encode(foreign).get(0);
        ProjectViewAssembler.Failed failed = assertInstanceOf(ProjectViewAssembler.Failed.class,
                assembler.accept(CONNECTION, CONNECTION_GENERATION,
                        packet("project", part)));
        assertEquals(ProjectViewAssembler.FailureReason.INCONSISTENT, failed.reason());
    }

    private static ProjectViewS2CPacket packet(String envelopeProjectId,
                                               ProjectProgressFragmenter.Fragment part) {
        ProjectViewScope envelopeScope = ProjectViewScope.project(envelopeProjectId);
        return new ProjectViewS2CPacket(envelopeScope, 0, VIEW_ID, VIEW_GENERATION, SEQUENCE,
                ProjectViewS2CPacket.Result.PROGRESS_PART, ProjectViewAction.NONE, true, "",
                0, false, SNAPSHOT_ID, CATALOG_REVISION, INVENTORY_REVISION,
                part.partIndex(), part.partCount(), part.totalBytes(), part.fragment(),
                Optional.empty(), Optional.empty());
    }

    private static ProjectProgressSnapshot snapshot(String projectId) {
        return new ProjectProgressSnapshot(projectId, "Project", CATALOG_REVISION,
                INVENTORY_REVISION, SEQUENCE, false, true, false, List.of(),
                List.of(new ProjectProgressMaterial(1, "minecraft:stone", 10, 10, 0,
                        List.of(new ProgressParticipant(new PlayerIdentity(null, "Alice"), 0)))));
    }
}
