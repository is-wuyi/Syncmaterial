package net.syncmaterial.syncmaterial.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.network.BatchAssignResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import net.syncmaterial.syncmaterial.network.TeamCommandC2SPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 客户端团队请求关联与角色状态落地的纯逻辑测试。
 * 覆盖协议 4 requestId 关联、项目/连接失效、严格序号、旧服务端 FIFO、
 * 玩家目录身份以及分组操作必须等全部回包。
 */
class TeamManagementClientTest {
    private static final String PROJECT = "project-a";
    private static final PlayerIdentity ACTOR = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "Actor");
    private static final PlayerIdentity ALICE = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alice");
    private static final PlayerIdentity BOB = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000003"), "Bob");

    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private GuiMaterialList gui;
    private SyncMaterialList syncList;
    private Screen currentScreen;

    @BeforeEach
    void setUp() {
        TeamManagementClient.resetForTesting();
        ClientProtocolState.reset();
        TeamManagementClient.setTransportForTesting(sent::add);
        TeamManagementClient.setIdentityProviderForTesting(() -> ACTOR);
        TeamManagementClient.setScreenProviderForTesting(() -> currentScreen);
        TeamManagementClient.onJoin();
        gui = mockGui(PROJECT);
        syncList = gui.getMaterialList();
        currentScreen = gui;
    }

    @AfterEach
    void tearDown() {
        TeamManagementClient.resetForTesting();
        ClientProtocolState.reset();
    }

    private static GuiMaterialList mockGui(String project) {
        GuiMaterialList list = mock(GuiMaterialList.class);
        SyncMaterialList sync = mock(SyncMaterialList.class);
        when(sync.getSchematicId()).thenReturn(project);
        when(sync.getCatalogRevision()).thenReturn(7L);
        when(list.getSchematicId()).thenReturn(project);
        when(list.getMaterialList()).thenReturn(sync);
        when(list.getSelectedMaterialIds()).thenReturn(List.of(11, 12));
        return list;
    }

    private static GuiPlayerSelectDialog mockDialog(GuiMaterialList list, String project) {
        GuiPlayerSelectDialog dialog = mock(GuiPlayerSelectDialog.class);
        when(dialog.getMaterialList()).thenReturn(list);
        when(dialog.getSchematicId()).thenReturn(project);
        return dialog;
    }

    private void scopedProtocol() {
        ClientProtocolState.onHandshakeResponse(4, "test", true);
    }

    private void legacyProtocol() {
        ClientProtocolState.onHandshakeResponse(3, "test", true);
    }

    private TeamCommandC2SPacket lastCommand() {
        assertFalse(sent.isEmpty(), "应至少发送一个数据包");
        CustomPacketPayload payload = sent.get(sent.size() - 1);
        assertTrue(payload instanceof TeamCommandC2SPacket, "应为团队命令包");
        return (TeamCommandC2SPacket) payload;
    }

    private static TeamState state(String project, PlayerIdentity owner, List<PlayerIdentity> deputies,
                                   boolean allowSelfClaim) {
        return new TeamState(project, owner, deputies, allowSelfClaim);
    }

    private static TeamResponse response(String project, long requestId, TeamAction action, long sequence,
                                         boolean success, String message, TeamState team) {
        return new TeamResponse(project, requestId, action, sequence, success, message, team,
                List.of(), 0, 0);
    }

    @Test
    void scopedAssignSendsOneCorrelatedCommandAndUpdatesRoles() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        String error = TeamManagementClient.submitGroup(gui, null, TeamAction.ASSIGN,
                List.of(ALICE, BOB), List.of(11, 12));
        assertNull(error);
        assertEquals(1, sent.size());
        TeamCommandC2SPacket packet = lastCommand();
        assertEquals(PROJECT, packet.command().projectId());
        assertTrue(packet.command().requestId() > 0);
        assertEquals(TeamAction.ASSIGN, packet.command().action());
        assertEquals(7L, packet.command().catalogRevision(), "目录投递序号应来自当前材料列表");
        assertEquals(List.of(11, 12), packet.command().materialIds());
        assertEquals(List.of(ALICE, BOB), packet.command().targets());

        TeamState team = state(PROJECT, ACTOR, List.of(), true);
        long requestId = packet.command().requestId();
        TeamManagementClient.onTeamResponse(response(PROJECT, requestId, TeamAction.ASSIGN,
                5, true, "已分配", team));

        verify(gui).applyTeamState(team, ACTOR);
        verify(gui).applyBatchAssignResult(true);
        verify(syncList).setIsOwner(true);
        verify(syncList).setAllowSelfClaim(true);
        verify(syncList, never()).setMaterialEntries(any(), anyLong());
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void request0PushAppliesNewestStateWithoutTouchingCatalog() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        TeamState first = state(PROJECT, ACTOR, List.of(), true);
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, 0, TeamAction.TEAM_STATE,
                5, true, "", first, List.of(), 0, 0));
        verify(gui).applyTeamState(first, ACTOR);

        TeamState stale = state(PROJECT, BOB, List.of(), false);
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, 0, TeamAction.TEAM_STATE,
                4, true, "", stale, List.of(), 0, 0));
        verify(gui, never()).applyTeamState(stale, ACTOR);
        assertSame(first, TeamManagementClient.getTeamState(PROJECT));
        assertEquals(5, TeamManagementClient.latestSequenceForTesting(PROJECT));

        verify(syncList, never()).setMaterialEntries(any(), anyLong());
        verify(syncList, never()).invalidateAreaInventory();
    }

    @Test
    void pushForAnotherProjectIsIgnored() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        TeamState other = state("project-b", ACTOR, List.of(), true);
        clearInvocations(gui);
        TeamManagementClient.onTeamResponse(new TeamResponse("project-b", 0, TeamAction.TEAM_STATE,
                1, true, "", other, List.of(), 0, 0));

        verifyNoInteractions(gui);
        assertNull(TeamManagementClient.getTeamState("project-b"));
    }

    @Test
    void disconnectDropsPendingAndLateResponseCannotApply() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        TeamCommandC2SPacket packet = lastCommand();

        clearInvocations(gui);
        TeamManagementClient.onDisconnect();
        TeamManagementClient.onTeamResponse(response(PROJECT, packet.command().requestId(),
                TeamAction.TOGGLE_SELF_CLAIM, 1, true, "", state(PROJECT, ACTOR, List.of(), false)));

        verifyNoInteractions(gui);
        assertFalse(TeamManagementClient.isConnectedForTesting());
    }

    @Test
    void misroutedResponseIsIgnoredWithoutConsumingRequestOrTouchingUi() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        TeamCommandC2SPacket packet = lastCommand();

        TeamManagementClient.onTeamResponse(response("project-b", packet.command().requestId(),
                TeamAction.TOGGLE_SELF_CLAIM, 1, true, "",
                state("project-b", ACTOR, List.of(), true)));

        verify(gui, never()).onTeamFeedback(org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString());
        verify(gui, never()).applyTeamState(any(), any());
        assertEquals(1, TeamManagementClient.pendingCountForTesting(),
                "错配包不得消费原请求，真正回包仍应可处理");

        TeamState team = state(PROJECT, ACTOR, List.of(), true);
        TeamManagementClient.onTeamResponse(response(PROJECT, packet.command().requestId(),
                TeamAction.TOGGLE_SELF_CLAIM, 1, true, "ok", team));
        verify(gui).applyTeamState(team, ACTOR);
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void groupKickSendsSequentiallyAndClosesOnlyAfterEverySuccess() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;

        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.KICK,
                List.of(ALICE, BOB), List.of(11)));
        assertEquals(1, sent.size());
        TeamCommandC2SPacket first = lastCommand();
        assertEquals(TeamAction.KICK, first.command().action());
        assertEquals(List.of(ALICE), first.command().targets());

        TeamManagementClient.onTeamResponse(response(PROJECT, first.command().requestId(),
                TeamAction.KICK, 1, true, "ok", state(PROJECT, ACTOR, List.of(), true)));
        assertEquals(2, sent.size(), "第一条成功后应继续发送下一位目标");
        TeamCommandC2SPacket second = lastCommand();
        assertEquals(List.of(BOB), second.command().targets());
        verify(dialog, never()).onGroupSucceeded(TeamAction.KICK);

        TeamManagementClient.onTeamResponse(response(PROJECT, second.command().requestId(),
                TeamAction.KICK, 2, true, "ok", state(PROJECT, ACTOR, List.of(), true)));
        verify(dialog).onGroupSucceeded(TeamAction.KICK);
        verify(dialog, never()).onGroupFailed(any());
        verify(gui).applyKickResult(true);
    }

    @Test
    void groupFailureKeepsDialogOpenAndReappliesOnlyKnownState() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;

        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.REMOVE_DEPUTY,
                List.of(ALICE, BOB), List.of()));
        TeamCommandC2SPacket first = lastCommand();
        TeamManagementClient.onTeamResponse(response(PROJECT, first.command().requestId(),
                TeamAction.REMOVE_DEPUTY, 1, true, "ok", state(PROJECT, ACTOR, List.of(), true)));
        TeamCommandC2SPacket second = lastCommand();

        TeamManagementClient.onTeamResponse(response(PROJECT, second.command().requestId(),
                TeamAction.REMOVE_DEPUTY, 2, false, "denied", null));

        verify(dialog).onGroupFailed("denied");
        verify(dialog, never()).onGroupSucceeded(TeamAction.REMOVE_DEPUTY);
    }

    @Test
    void unsolicitedLegacyOwnerBroadcastNeverTouchesCurrentScreen() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        clearInvocations(gui);
        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "changed", "Mallory", List.of("Mallory"), false));

        verifyNoInteractions(gui);
    }

    @Test
    void legacyOwnerGroupUsesFifoCapturedUiAndIgnoresLateBroadcast() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;

        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.ADD_DEPUTY,
                List.of(ALICE, BOB), List.of()));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof OwnerActionC2SPacket);
        OwnerActionC2SPacket first = (OwnerActionC2SPacket) sent.get(0);
        assertEquals("ADD_DEPUTY", first.action());
        assertEquals("Alice", first.targetPlayerName());

        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "added", "Owner", List.of("Alice"), true));
        verify(gui).applyLegacyOwnerState("Owner", List.of("Alice"), true);
        assertEquals(2, sent.size());
        assertTrue(sent.get(1) instanceof OwnerActionC2SPacket);
        assertEquals("Bob", ((OwnerActionC2SPacket) sent.get(1)).targetPlayerName());

        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "added", "Owner", List.of("Alice", "Bob"), true));
        verify(dialog).onGroupSucceeded(TeamAction.ADD_DEPUTY);

        // 组已经完成；旧服务端可能又广播一条没有对应请求的负责人响应，必须丢弃。
        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "late", "Mallory", List.of(), false));
        verify(gui, never()).applyLegacyOwnerState("Mallory", List.of(), false);
    }

    @Test
    void legacyPlayerListAndAssignUseFrozenPacketsOnly() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = gui;
        TeamManagementClient.requestPlayerList(dialog);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof PlayerListRequestC2SPacket);
        TeamManagementClient.onLegacyPlayerListResponse(List.of(new PlayerInfo("Alice", true)));
        verify(dialog).onPlayerListResponse(List.of(new PlayerInfo("Alice", true)));

        sent.clear();
        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.ASSIGN,
                List.of(ALICE, BOB), List.of(11)));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0) instanceof net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket);
        net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket assign =
                (net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket) sent.get(0);
        assertEquals(List.of("Alice", "Bob"), assign.targetPlayers());

        TeamManagementClient.onLegacyBatchAssignResponse(new BatchAssignResponseS2CPacket(true, "assigned"));
        verify(dialog).onGroupSucceeded(TeamAction.ASSIGN);
    }

    @Test
    void playersReplyRoutesToCapturedDialogBeforeItIsShownAndCarriesIdentities() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = gui;
        TeamManagementClient.requestPlayerList(dialog);
        TeamCommandC2SPacket packet = lastCommand();
        assertEquals(TeamAction.PLAYERS, packet.command().action());

        List<PlayerDirectoryEntry> players = List.of(
                new PlayerDirectoryEntry(ALICE, true),
                new PlayerDirectoryEntry(BOB, false));
        TeamResponse response = new TeamResponse(PROJECT, packet.command().requestId(),
                TeamAction.PLAYERS, 1, true, "目录达到上限，仅显示前 8192 位",
                null, players, 0, 0);
        TeamManagementClient.onTeamResponse(response);

        verify(dialog).onPlayerDirectoryResponse(players);
        verify(dialog).onPlayerDirectoryHint("目录达到上限，仅显示前 8192 位");
        verify(dialog, never()).onPlayerListFailed(any());
    }

    @Test
    void directToggleUsesCapturedListAndReportsServerMessage() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        TeamCommandC2SPacket packet = lastCommand();
        assertEquals(TeamAction.TOGGLE_SELF_CLAIM, packet.command().action());
        assertTrue(packet.command().targets().isEmpty());
        assertTrue(packet.command().materialIds().isEmpty());

        TeamState team = state(PROJECT, ACTOR, List.of(ALICE), false);
        TeamManagementClient.onTeamResponse(response(PROJECT, packet.command().requestId(),
                TeamAction.TOGGLE_SELF_CLAIM, 9, true, "已关闭", team));

        verify(gui).applyTeamState(team, ACTOR);
        verify(gui).onTeamFeedback(true, "已关闭");
    }

    @Test
    void oldServerOwnerFailureDoesNotApplyBlankOwnerFallback() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        assertNull(TeamManagementClient.removeDeputy(gui, "Alice"));
        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                false, "denied", "", List.of(), true));

        verify(gui, never()).applyLegacyOwnerState(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(gui).onTeamFeedback(false, "denied");
    }

    @Test
    void projectSwitchInvalidatesPendingAndKnownStateForOldProject() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        TeamCommandC2SPacket packet = lastCommand();

        GuiMaterialList otherProject = mockGui("project-b");
        currentScreen = otherProject;
        TeamManagementClient.onMaterialListOpened(otherProject);
        assertTrue(TeamManagementClient.pendingRequestIdsForTesting().isEmpty());

        TeamManagementClient.onTeamResponse(response(PROJECT, packet.command().requestId(),
                TeamAction.TOGGLE_SELF_CLAIM, 1, true, "",
                state(PROJECT, ACTOR, List.of(), true)));

        verify(otherProject, never()).applyTeamState(any(), any());
        assertNotNull(TeamManagementClient.generationForTesting());
    }

    @Test
    void responseForCapturedDialogReplacedByOtherScreenIsIgnored() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;

        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.ASSIGN,
                List.of(ALICE), List.of(11)));
        TeamCommandC2SPacket packet = lastCommand();

        currentScreen = mock(Screen.class);
        TeamManagementClient.onTeamResponse(response(PROJECT, packet.command().requestId(),
                TeamAction.ASSIGN, 1, true, "ok", state(PROJECT, ACTOR, List.of(), true)));

        verify(dialog, never()).onGroupSucceeded(any());
        verify(gui, never()).applyTeamState(any(), any());
        assertEquals(1, TeamManagementClient.pendingCountForTesting());

        TeamManagementClient.onDialogRemoved(dialog);
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void requestIdExhaustionFailsClosedWithoutReusingIds() throws Exception {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        java.lang.reflect.Field field = TeamManagementClient.class.getDeclaredField("NEXT_REQUEST_ID");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) field.get(null)).set(Long.MAX_VALUE - 1);

        String error = TeamManagementClient.toggleSelfClaim(gui);

        assertNull(error);
        assertTrue(sent.isEmpty(), "编号耗尽时不得发送请求");
        verify(gui).onTeamFeedback(false, "请求编号已耗尽，请重新连接服务器");
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void legacySameKindIsSerializedSoFifoCannotMisorder() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertEquals(1, sent.size());

        String second = TeamManagementClient.toggleSelfClaim(gui);
        assertNotNull(second);
        assertEquals(1, sent.size(), "同一旧包种类只允许一个未完成请求");
    }

    @Test
    void legacyDuplicatePlayerListIsRejectedWhileFirstPending() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog first = mockDialog(gui, PROJECT);
        GuiPlayerSelectDialog second = mockDialog(gui, PROJECT);

        TeamManagementClient.requestPlayerList(first);
        TeamManagementClient.requestPlayerList(second);

        assertEquals(1, sent.size());
        verify(second).onPlayerListFailed(any());
    }

    @Test
    void oldServerInjectedTeamPayloadIsIgnoredBeforeCapability() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        clearInvocations(gui);
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, 0, TeamAction.TEAM_STATE,
                1, true, "", state(PROJECT, ACTOR, List.of(), true), List.of(), 0, 0));

        verifyNoInteractions(gui);
        assertNull(TeamManagementClient.getTeamState(PROJECT));
    }

    @Test
    void networkDisconnectInvalidationDoesNotTouchClientOwnedMaps() throws Exception {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        var command = lastCommand().command();
        java.util.concurrent.atomic.AtomicLong token = new java.util.concurrent.atomic.AtomicLong();
        Thread network = new Thread(() -> token.set(TeamManagementClient.invalidateConnection()));
        network.start();
        network.join();
        assertFalse(TeamManagementClient.isConnectedForTesting());
        assertEquals(1, TeamManagementClient.pendingCountForTesting());
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, command.requestId(), TeamAction.TOGGLE_SELF_CLAIM,
                1, true, "", state(PROJECT, ACTOR, List.of(), false), List.of(), 1, 1));
        verify(gui, never()).applyTeamState(any(), any());
        TeamManagementClient.finishDisconnect(token.get());
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void queuedOldDisconnectCleanupCannotClearRejoinedProject() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        long old = TeamManagementClient.invalidateConnection();
        TeamManagementClient.onJoin();
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        TeamManagementClient.finishDisconnect(old);
        assertTrue(TeamManagementClient.isConnectedForTesting());
        assertEquals(1, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void cancelledDisconnectTaskIsRecoveredByClientTickExactlyOnce() {
        scopedProtocol();
        Object connection = new Object();
        TeamManagementClient.onJoin(connection);
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        long token = TeamManagementClient.invalidateConnection(connection);
        assertEquals(1, TeamManagementClient.pendingCountForTesting());
        assertTrue(TeamManagementClient.finishPendingDisconnect());
        assertEquals(0, TeamManagementClient.pendingCountForTesting());
        assertFalse(TeamManagementClient.finishPendingDisconnect());
        assertFalse(TeamManagementClient.finishDisconnect(token));
    }

    @Test
    void oldConnectionDisconnectCannotInvalidateNewConnection() {
        scopedProtocol();
        Object oldConnection = new Object();
        Object currentConnection = new Object();
        TeamManagementClient.onJoin(oldConnection);
        TeamManagementClient.onJoin(currentConnection);
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        long token = TeamManagementClient.connectionToken();
        assertEquals(-1, TeamManagementClient.invalidateConnection(oldConnection));
        assertEquals(token, TeamManagementClient.connectionToken());
        assertTrue(TeamManagementClient.isConnectedForTesting());
        assertFalse(TeamManagementClient.finishPendingDisconnect());
        assertEquals(1, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void pendingOldDisconnectIsDiscardedOnRejoinBeforeClientTick() {
        scopedProtocol();
        Object oldConnection = new Object();
        TeamManagementClient.onJoin(oldConnection);
        long old = TeamManagementClient.invalidateConnection(oldConnection);
        TeamManagementClient.onJoin(new Object());
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertFalse(TeamManagementClient.finishPendingDisconnect());
        assertFalse(TeamManagementClient.finishDisconnect(old));
        assertTrue(TeamManagementClient.isConnectedForTesting());
        assertEquals(1, TeamManagementClient.pendingCountForTesting());
    }

    @Test
    void duplicateDisconnectDoesNotCreateAnotherGenerationOrCleanup() {
        Object connection = new Object();
        TeamManagementClient.onJoin(connection);
        long token = TeamManagementClient.invalidateConnection(connection);
        assertEquals(token, TeamManagementClient.invalidateConnection(connection));
        assertEquals(token, TeamManagementClient.connectionToken());
        assertTrue(TeamManagementClient.finishPendingDisconnect());
        assertEquals(-1, TeamManagementClient.invalidateConnection(connection));
    }

    @Test
    void tokenCapturedBeforeDisconnectCannotApplyAfterRejoin() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        long oldToken = TeamManagementClient.connectionToken();

        TeamManagementClient.onDisconnect();
        TeamManagementClient.onJoin();
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);

        clearInvocations(gui);
        TeamManagementClient.onTeamResponse(oldToken, new TeamResponse(PROJECT, 0,
                TeamAction.TEAM_STATE, 1, true, "", state(PROJECT, ACTOR, List.of(), true),
                List.of(), 0, 0));

        verifyNoInteractions(gui);
        assertNull(TeamManagementClient.getTeamState(PROJECT));
    }
}
