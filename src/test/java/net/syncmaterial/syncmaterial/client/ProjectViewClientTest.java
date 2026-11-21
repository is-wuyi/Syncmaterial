package net.syncmaterial.syncmaterial.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.core.CollaborationOutcome;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.network.ProjectProgressFragmenter;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ProjectViewCommandC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewClientTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");
    private static final ProjectViewScope OTHER = ProjectViewScope.project("other");

    private final Object connection = new Object();
    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private final AtomicLong requestIds = new AtomicLong(1);
    private ProjectViewClient client;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        client = new ProjectViewClient(connection, 1, sent::add, requestIds::getAndIncrement, () -> true);
    }

    @Test
    void onlyMatchingOpenedAckNotifiesTheOriginalOpenRequest() {
        AtomicInteger opened = new AtomicInteger();
        client.setOpenedListener(packet -> opened.incrementAndGet());
        long request = client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket command = lastCommand();
        client.onPacket(new Object(), 1, ProjectViewPackets.opened(PROJECT, request,
                command.viewId(), command.generation(), UUID.randomUUID()));
        assertEquals(0, opened.get());

        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, request,
                command.viewId(), command.generation(), UUID.randomUUID()));
        assertEquals(1, opened.get());
        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, request,
                command.viewId(), command.generation(), UUID.randomUUID()));
        assertEquals(1, opened.get(), "a repeated ack must not reopen the GUI");
    }

    @Test
    void directEditorActivatesProjectWithoutReopeningMaterialList() {
        AtomicInteger opened = new AtomicInteger();
        client.setOpenedListener(packet -> opened.incrementAndGet());
        client.openEditor("project");
        ProjectViewCommandC2SPacket open = sent.stream()
                .map(ProjectViewCommandC2SPacket.class::cast)
                .filter(packet -> packet.action() == ProjectViewAction.OPEN)
                .findFirst().orElseThrow();
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit(),
                client.desiredMask());

        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, open.requestId(),
                open.viewId(), open.generation(), UUID.randomUUID()));
        assertEquals(0, opened.get());
        assertTrue(client.areaConfig(PROJECT, 0, "LIST", Optional.empty()) > 0);

        int before = sent.size();
        client.openEditor("project");
        assertEquals(before, sent.size(), "re-layout must not reopen the project or editor lease");
    }

    @Test
    void completeProgressInstallsAndOldConnectionIsIgnored() {
        long request = client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket command = lastCommand();
        ProjectProgressSnapshot snapshot = snapshot(5);
        var fragments = ProjectProgressFragmenter.encode(snapshot);
        for (var fragment : fragments) {
            ProjectViewS2CPacket packet = ProjectViewPackets.progress(PROJECT, command.viewId(),
                    command.generation(), snapshot, UUID.randomUUID(), fragment);
            client.onPacket(new Object(), 1, packet);
            assertTrue(client.projectStore().orElseThrow().progress().isEmpty());
        }
        UUID snapshotId = UUID.randomUUID();
        for (var fragment : fragments) {
            client.onPacket(connection, 1, ProjectViewPackets.progress(PROJECT, command.viewId(),
                    command.generation(), snapshot, snapshotId, fragment));
        }
        assertEquals(snapshot, client.projectStore().orElseThrow().progress().orElseThrow());
        assertTrue(request > 0);
    }

    @Test
    void closingGuiKeepsBaseMaterialsAndTeamInterests() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        client.openGui("project");
        client.closeGui("project");

        ProjectViewCommandC2SPacket last = lastCommand();
        assertEquals(ProjectViewAction.SET_INTERESTS, last.action());
        assertEquals(ProjectInterestLeases.BASE_MASK, last.interestMask());
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .noneMatch(packet -> packet.action() == ProjectViewAction.CLOSE));
        assertTrue(client.projectStore().isPresent());
    }

    @Test
    void switchingProjectKeepsTheOldScopeUntilTheNewOpenIsAcknowledged() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket oldOpen = lastCommand();
        long request = client.openProject("other", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket newOpen = lastCommand();

        assertEquals(PROJECT, client.projectStore().orElseThrow().scope());
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast).noneMatch(packet ->
                packet.action() == ProjectViewAction.CLOSE && packet.scope().equals(PROJECT)));

        client.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, request,
                newOpen.viewId(), newOpen.generation(), UUID.randomUUID()));

        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast).anyMatch(packet ->
                packet.action() == ProjectViewAction.CLOSE && packet.scope().equals(PROJECT)
                        && packet.viewId() == oldOpen.viewId()
                        && packet.generation() == oldOpen.generation()));
        assertEquals(OTHER, newOpen.scope());
        assertEquals(OTHER, client.projectStore().orElseThrow().scope());
        ProjectProgressSnapshot old = snapshot(8);
        for (var fragment : ProjectProgressFragmenter.encode(old)) {
            client.onPacket(connection, 1, ProjectViewPackets.progress(PROJECT, oldOpen.viewId(),
                    oldOpen.generation(), old, UUID.randomUUID(), fragment));
        }
        assertTrue(client.projectStore().orElseThrow().progress().isEmpty());
    }

    @Test
    void joinAckMustMatchRequestActionAndErrorEndsPending() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();
        AtomicInteger operations = new AtomicInteger();
        client.setOperationListener(packet -> operations.incrementAndGet());
        long request = client.join("project", 1,
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, 32)));
        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, request, open.viewId(),
                open.generation(), outcome(CollaborationOutcome.Action.LEAVE)));
        assertEquals(0, operations.get());
        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, request, open.viewId(),
                open.generation(), outcome(CollaborationOutcome.Action.JOIN)));
        assertEquals(1, operations.get());

        long second = client.leave("project", 1);
        client.onPacket(connection, 1, ProjectViewPackets.error(PROJECT, second, open.viewId(),
                open.generation(), ProjectViewAction.LEAVE, "failed"));
        assertEquals(2, operations.get());
        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, second, open.viewId(),
                open.generation(), outcome(CollaborationOutcome.Action.LEAVE)));
        assertEquals(2, operations.get(), "the late result after ERROR must not complete twice");
    }

    @Test
    void dimensionChangeClosesOldGenerationAndRestoresConsumers() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket old = lastCommand();
        client.openGui("project");
        client.setPickup("project", true);

        client.dimensionChanged("project");

        ProjectViewCommandC2SPacket opened = sent.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .filter(packet -> packet.action() == ProjectViewAction.OPEN)
                .reduce((first, second) -> second).orElseThrow();
        assertTrue(opened.generation() > old.generation());
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast).noneMatch(packet ->
                packet.action() == ProjectViewAction.CLOSE
                        && packet.generation() == old.generation()),
                "a same-scope OPEN replaces the old generation; no redundant CLOSE is sent");
        assertEquals(ProjectInterestLeases.BASE_MASK | InterestKind.AREA_CONFIG.bit()
                | InterestKind.WAREHOUSE_CONTENTS.bit(), client.desiredMask());

        ProjectProgressSnapshot stale = snapshot(9);
        for (var fragment : ProjectProgressFragmenter.encode(stale)) {
            client.onPacket(connection, 1, ProjectViewPackets.progress(PROJECT, old.viewId(),
                    old.generation(), stale, UUID.randomUUID(), fragment));
        }
        assertTrue(client.projectStore().orElseThrow().progress().isEmpty());
    }

    @Test
    void globalWarehouseLifecycleDoesNotCloseTheProjectView() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewClient.GlobalLease global = client.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit());
        assertTrue(global != null);
        assertTrue(client.projectStore().isPresent());
        assertTrue(client.globalStore().isPresent());

        global.close();
        assertTrue(client.projectStore().isPresent());
        assertTrue(client.globalStore().isEmpty());
        assertEquals(ProjectViewScope.globalWarehouse(), lastCommand().scope());
        assertEquals(ProjectViewAction.CLOSE, lastCommand().action());
    }

    @Test
    void correlatedConfigurationCompletesTheEditorAndRejectsStaleRevision() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();
        AtomicInteger operations = new AtomicInteger();
        client.setOperationListener(packet -> operations.incrementAndGet());
        long request = client.areaConfig(PROJECT, 0, "LIST", Optional.empty());
        var config = new ProjectViewS2CPacket.AreaConfigData("LIST", List.of());
        client.onPacket(connection, 1, ProjectViewPackets.configuration(PROJECT, request,
                open.viewId(), open.generation(), 4, ProjectViewAction.AREA_CONFIG, true, "", config));

        assertEquals(1, operations.get());
        assertTrue(client.projectStore().orElseThrow().configuration("LIST").isPresent());
        client.onPacket(connection, 1, ProjectViewPackets.configuration(PROJECT, 0,
                open.viewId(), open.generation(), 3, ProjectViewAction.NONE, true, "", config));
        assertEquals(4, client.projectStore().orElseThrow().configurationSequence("LIST"));
        assertEquals(1, operations.get());
    }

    @Test
    void pendingOperationCapacityFailsWithoutSendingAnotherWrite() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        for (int index = 0; index < 64; index++) {
            assertTrue(client.join("project", 1,
                    List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, index))) > 0);
        }
        int before = sent.size();
        assertEquals(-1, client.join("project", 1,
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, 65))));
        assertEquals(before, sent.size());
    }

    @Test
    void unansweredOperationTimesOutWithoutResendAndLateAckCannotClearNewPending() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();
        List<ProjectViewClient.OperationTimeout> timeouts = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();
        client.setOperationListener(packet -> operations.incrementAndGet());
        client.setOperationTimeoutListener(timeouts::add);

        long stale = client.join("project", 1, List.of());
        int sentBefore = sent.size();
        for (int tick = 0; tick < ProjectViewClient.OPERATION_TIMEOUT_TICKS; tick++) {
            client.tick();
        }
        assertEquals(1, timeouts.size());
        assertEquals(stale, timeouts.getFirst().requestId());
        assertEquals(ProjectViewAction.JOIN, timeouts.getFirst().action());
        assertEquals(1, timeouts.getFirst().materialId());
        assertEquals(sentBefore, sent.size(), "timeout must not replay the write");

        long fresh = client.join("project", 1, List.of());
        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, stale,
                open.viewId(), open.generation(), outcome(CollaborationOutcome.Action.JOIN)));
        assertEquals(0, operations.get(), "a late ack must not complete after its timeout");

        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, fresh,
                open.viewId(), open.generation(), outcome(CollaborationOutcome.Action.JOIN)));
        assertEquals(1, operations.get(), "the late ack must not clear the new pending operation");
    }

    @Test
    void supersededViewReportsCanceledOperationButFreshGenerationStaysPending() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket staleOpen = lastCommand();
        List<ProjectViewClient.OperationTimeout> timeouts = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();
        client.setOperationTimeoutListener(timeouts::add);
        client.setOperationListener(packet -> operations.incrementAndGet());

        long stale = client.join("project", 1, List.of());
        long switchRequest = client.openProject("other", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket freshOpen = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, switchRequest,
                freshOpen.viewId(), freshOpen.generation(), UUID.randomUUID()));

        assertEquals(1, timeouts.size());
        assertEquals(stale, timeouts.getFirst().requestId());
        assertEquals(ProjectViewAction.JOIN, timeouts.getFirst().action());

        long fresh = client.join("other", 1, List.of());
        client.onPacket(connection, 1, ProjectViewPackets.operation(PROJECT, stale,
                staleOpen.viewId(), staleOpen.generation(), outcome(CollaborationOutcome.Action.JOIN)));
        assertEquals(0, operations.get());
        client.onPacket(connection, 1, ProjectViewPackets.operation(OTHER, fresh,
                freshOpen.viewId(), freshOpen.generation(), outcome(CollaborationOutcome.Action.JOIN)));
        assertEquals(1, operations.get());
        assertEquals(1, timeouts.size(), "fresh generation pending must not be canceled by old view");
    }

    @Test
    void deletionClearsStateWithoutSendingARedundantClose() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();
        AtomicInteger deleted = new AtomicInteger();
        client.setDeletionListener(scope -> deleted.incrementAndGet());
        int before = sent.size();

        client.onPacket(connection, 1, ProjectViewPackets.deleted(PROJECT,
                open.viewId(), open.generation()));

        assertEquals(1, deleted.get());
        assertTrue(client.projectStore().isEmpty());
        assertEquals(before, sent.size());
    }

    @Test
    void oldServerDoesNotReceiveProtocolFiveCommands() {
        ProjectViewClient old = new ProjectViewClient(connection, 1, sent::add,
                requestIds::getAndIncrement, () -> false);
        assertEquals(-1, old.openProject("project", ProjectInterestLeases.BASE_MASK));
        assertTrue(sent.isEmpty());
    }

    @Test
    void failedSwitchKeepsTheLiveProjectAndIgnoresTheLateOpenedReceipt() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket first = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, first.requestId(),
                first.viewId(), first.generation(), UUID.randomUUID()));
        AtomicInteger errors = new AtomicInteger();
        client.setOperationListener(packet -> errors.incrementAndGet());

        long request = client.openProject("other", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket candidate = lastCommand();
        assertEquals(PROJECT, client.projectStore().orElseThrow().scope());
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .noneMatch(packet -> packet.action() == ProjectViewAction.CLOSE));

        client.onPacket(connection, 1, ProjectViewPackets.error(OTHER, request,
                candidate.viewId(), candidate.generation(), ProjectViewAction.OPEN, "not ready"));

        assertEquals(1, errors.get());
        assertEquals(PROJECT, client.projectStore().orElseThrow().scope());
        assertTrue(client.join("project", 1, List.of()) > 0,
                "the live project must still accept operations after the failed switch");

        client.onPacket(connection, 1, ProjectViewPackets.opened(OTHER, request,
                candidate.viewId(), candidate.generation(), UUID.randomUUID()));
        assertEquals(PROJECT, client.projectStore().orElseThrow().scope(),
                "an OPENED for a failed candidate must not install it");
    }

    @Test
    void localOpenSendFailureDoesNotDropTheLiveProject() {
        AtomicLong ids = new AtomicLong();
        List<CustomPacketPayload> packets = new ArrayList<>();
        ProjectViewClient failing = new ProjectViewClient(connection, 1, packets::add,
                () -> {
                    long next = ids.incrementAndGet();
                    return next == 1 ? next : -1;
                }, () -> true);
        failing.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket first = (ProjectViewCommandC2SPacket) packets.getLast();
        failing.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, first.requestId(),
                first.viewId(), first.generation(), UUID.randomUUID()));

        assertEquals(-1, failing.openProject("other", ProjectInterestLeases.BASE_MASK));
        assertEquals(PROJECT, failing.projectStore().orElseThrow().scope());
        assertTrue(packets.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .noneMatch(packet -> packet.action() == ProjectViewAction.CLOSE));
    }

    @Test
    void failedInitialOpenLeavesNoZombieViewOrLeases() {
        long request = client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.error(PROJECT, request,
                open.viewId(), open.generation(), ProjectViewAction.OPEN, "not ready"));

        assertTrue(client.projectStore().isEmpty());
        assertEquals(0, client.desiredMask());
        assertEquals(-1, client.areaConfig(PROJECT, 0, "LIST", Optional.empty()));
        assertTrue(sent.stream().map(ProjectViewCommandC2SPacket.class::cast)
                .noneMatch(packet -> packet.action() == ProjectViewAction.CLOSE));
    }

    @Test
    void globalViewIsReferenceCountedAndClosesOnlyAfterTheLastConsumer() {
        ProjectViewClient.GlobalLease manager = client.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit());
        assertTrue(manager != null);
        int opened = sent.size();
        ProjectViewClient.GlobalLease editor = client.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit());
        assertTrue(editor != null);
        assertEquals(opened, sent.size(), "a second consumer must reuse the open global view");

        manager.close();
        assertEquals(opened, sent.size(), "releasing a child consumer must not close the global view");
        assertTrue(client.globalStore().isPresent());

        editor.close();
        assertTrue(client.globalStore().isEmpty());
        assertEquals(ProjectViewScope.globalWarehouse(), lastCommand().scope());
        assertEquals(ProjectViewAction.CLOSE, lastCommand().action());
    }

    @Test
    void freshGlobalLeaseOpensBeforeTheListRequest() {
        ProjectViewClient.GlobalLease lease = client.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit());
        assertTrue(lease != null);
        assertTrue(client.areaConfig(ProjectViewScope.globalWarehouse(), 0, "LIST_WAREHOUSES",
                Optional.empty()) > 0);

        List<ProjectViewCommandC2SPacket> commands = sent.stream()
                .map(ProjectViewCommandC2SPacket.class::cast).toList();
        assertEquals(2, commands.size());
        assertEquals(ProjectViewAction.OPEN, commands.get(0).action());
        assertEquals(ProjectViewScope.globalWarehouse(), commands.get(0).scope());
        assertEquals(ProjectViewAction.AREA_CONFIG, commands.get(1).action());
        assertEquals(ProjectViewScope.globalWarehouse(), commands.get(1).scope());
        lease.close();
    }

    @Test
    void failedGlobalOpenKeepsTheLeaseAndRetryReopens() {
        ProjectViewClient.GlobalLease lease = client.acquireGlobalWarehouse(InterestKind.AREA_CONFIG.bit());
        assertTrue(lease != null);
        ProjectViewCommandC2SPacket failed = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.error(ProjectViewScope.globalWarehouse(),
                failed.requestId(), failed.viewId(), failed.generation(),
                ProjectViewAction.OPEN, "warehouse not ready"));

        assertTrue(client.globalStore().isEmpty());
        assertTrue(lease.isHeld());
        assertTrue(lease.retry());

        ProjectViewCommandC2SPacket retried = lastCommand();
        assertEquals(ProjectViewAction.OPEN, retried.action());
        assertEquals(ProjectViewScope.globalWarehouse(), retried.scope());
        assertTrue(retried.generation() > failed.generation());
        client.onPacket(connection, 1, ProjectViewPackets.opened(ProjectViewScope.globalWarehouse(),
                retried.requestId(), retried.viewId(), retried.generation(), UUID.randomUUID()));
        assertTrue(client.globalStore().isPresent());
        lease.close();
    }

    @Test
    void closingTheListSuppressesALateOpenedReceipt() {
        AtomicInteger opened = new AtomicInteger();
        client.setOpenedListener(packet -> opened.incrementAndGet());
        long request = client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket open = lastCommand();

        client.closeGui("project");
        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, request,
                open.viewId(), open.generation(), UUID.randomUUID()));

        assertEquals(0, opened.get(), "a late OPENED must not reopen a dismissed material list");
    }

    @Test
    void refreshProjectNeverReopensTheGui() {
        client.openProject("project", ProjectInterestLeases.BASE_MASK);
        ProjectViewCommandC2SPacket first = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, first.requestId(),
                first.viewId(), first.generation(), UUID.randomUUID()));
        AtomicInteger opened = new AtomicInteger();
        client.setOpenedListener(packet -> opened.incrementAndGet());

        long refresh = client.refreshProject("project");
        assertTrue(refresh > 0);
        ProjectViewCommandC2SPacket command = lastCommand();
        client.onPacket(connection, 1, ProjectViewPackets.opened(PROJECT, refresh,
                command.viewId(), command.generation(), UUID.randomUUID()));

        assertEquals(0, opened.get());
        assertTrue(client.projectStore().isPresent());
    }

    private ProjectViewCommandC2SPacket lastCommand() {
        return (ProjectViewCommandC2SPacket) sent.getLast();
    }

    private static CollaborationOutcome outcome(CollaborationOutcome.Action action) {
        return new CollaborationOutcome(action, CollaborationOutcome.Status.APPLIED, "project", 1);
    }

    private static ProjectProgressSnapshot snapshot(long sequence) {
        return new ProjectProgressSnapshot("project", "Project", 1, 1, sequence,
                true, true, false, List.of(), List.of());
    }
}
