package net.syncmaterial.syncmaterial.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.OwnerActionResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import net.syncmaterial.syncmaterial.network.TeamCommandC2SPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 团队管理请求的 200 客户端 tick 有界寿命合同：
 * 超时只标未确认并解绑提交等待，不伪造服务端失败、不清材料选择、
 * 不推进剩余目标或自动重发；旧协议 FIFO 过期后由不持 screen 的占位继续
 * 阻断同类 write，直到迟回包被消费或连接重建。
 */
class TeamManagementBoundedRequestTest {
    private static final String PROJECT = "project-a";
    private static final PlayerIdentity ACTOR = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "Actor");
    private static final PlayerIdentity ALICE = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alice");
    private static final PlayerIdentity BOB = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000003"), "Bob");

    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private GuiMaterialList gui;
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
        when(list.getSchematicId()).thenReturn(project);
        when(sync.getCatalogRevision()).thenReturn(7L);
        when(list.getMaterialList()).thenReturn(sync);
        return list;
    }

    private static GuiPlayerSelectDialog mockDialog(GuiMaterialList list, String project) {
        GuiPlayerSelectDialog dialog = mock(GuiPlayerSelectDialog.class);
        when(dialog.getMaterialList()).thenReturn(list);
        when(dialog.getSchematicId()).thenReturn(project);
        return dialog;
    }

    private static void scopedProtocol() {
        // 仅供测试的 direct entry：显式 onJoin 后才有可写会话
        ClientProtocolState.onHandshakeResponse(4, "test", true);
    }

    private static void legacyProtocol() {
        ClientProtocolState.onHandshakeResponse(3, "test", true);
    }

    /** 与生产一致的未确认文案来源（测试环境无语言包时为原始键）。 */
    private static String unconfirmedCopy() {
        return fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.save.unconfirmed");
    }

    private static void clientTick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            TeamManagementClient.tick();
        }
    }

    private static TeamState state(String project) {
        return new TeamState(project, ACTOR, List.of(), true);
    }

    private static TeamResponse response(String project, long requestId, TeamAction action,
                                         boolean success) {
        return new TeamResponse(project, requestId, action, 1, success, "", state(project),
                List.of(), 0, 0);
    }

    private long currentRequestId() {
        TeamCommandC2SPacket packet = (TeamCommandC2SPacket) sent.get(sent.size() - 1);
        return packet.command().requestId();
    }

    @Test
    void scopedPendingExpiresAfter200TicksWithUnconfirmedResultOnly() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        assertEquals(1, sent.size());

        clientTick(199);
        assertEquals(1, TeamManagementClient.pendingCountForTesting(), "199 tick 内不得提前过期");
        verify(gui, never()).onTeamFeedback(anyBoolean(), anyString());

        clientTick(1);
        assertEquals(0, TeamManagementClient.pendingCountForTesting(), "200 tick 无回包必须解绑");
        // 不伪造服务端失败：列表路径只有未确认反馈，没有失败落地
        verify(gui).onTeamFeedback(false, unconfirmedCopy());
        verify(gui, never()).applyBatchAssignResult(anyBoolean(), any());
        verify(gui, never()).applyKickResult(anyBoolean());

        clientTick(200);
        assertEquals(1, sent.size(), "超时后不得自动重发");
    }

    @Test
    void expiredDialogGroupMarksUnconfirmedWithoutFakingServerFailure() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;
        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.KICK,
                List.of(ALICE), List.of(11)));
        long requestId = currentRequestId();

        clientTick(200);
        verify(dialog).onGroupUnconfirmed(TeamAction.KICK);
        verify(dialog, never()).onGroupFailed(anyString());
        verify(dialog, never()).onGroupSucceeded(any());
        verify(gui, never()).applyKickResult(anyBoolean());
        assertEquals(0, TeamManagementClient.pendingCountForTesting());

        // 迟到的真实回包不再消费、不落地任何状态
        clearInvocations(gui, dialog);
        TeamManagementClient.onTeamResponse(response(PROJECT, requestId, TeamAction.KICK, true));
        verify(gui, never()).applyTeamState(any(), any());
        verify(dialog, never()).onGroupSucceeded(any());
        verify(dialog, never()).onGroupUnconfirmed(any());
    }

    @Test
    void expiredGroupStopsWithoutAdvancingRemainingTargets() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog dialog = mockDialog(gui, PROJECT);
        currentScreen = dialog;
        assertNull(TeamManagementClient.submitGroup(gui, dialog, TeamAction.KICK,
                List.of(ALICE, BOB), List.of(11)));
        assertEquals(1, sent.size());
        TeamManagementClient.onTeamResponse(response(PROJECT, currentRequestId(),
                TeamAction.KICK, true));
        assertEquals(2, sent.size(), "第一条成功后正常发送下一位");

        clientTick(200);
        verify(dialog).onGroupUnconfirmed(TeamAction.KICK);
        clientTick(100);
        assertEquals(2, sent.size(), "超时后不得推进剩余目标或重发");
        verify(dialog, never()).onGroupSucceeded(TeamAction.KICK);
    }

    @Test
    void request0AuthorityPushStillAppliesAfterPendingTimeout() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        clientTick(200);
        assertEquals(0, TeamManagementClient.pendingCountForTesting());

        TeamState team = state(PROJECT);
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, 0, TeamAction.TEAM_STATE,
                5, true, "", team, List.of(), 0, 0));
        verify(gui).applyTeamState(team, ACTOR);
        assertTrue(TeamManagementClient.getTeamState(PROJECT) == team,
                "request0 权威推送不能因 pending 超时被一律丢弃");
    }

    @Test
    void legacyExpiredWriteKeepsBlockingPlaceholderUntilLateResponseConsumed() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertEquals(1, sent.size());

        clientTick(200);
        verify(gui).onTeamFeedback(false, unconfirmedCopy());
        clearInvocations(gui);

        // 占位继续阻断同类新 write：迟到旧 ACK 不能误配新操作
        String busy = TeamManagementClient.toggleSelfClaim(gui);
        assertNotNull(busy, "旧回包未消费前不得放行同类新 write");
        assertEquals(1, sent.size());
        verify(gui, never()).onTeamFeedback(anyBoolean(), anyString());

        // 迟到回包消费占位：无 screen 绑定，不得把结果套到界面
        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "late", "Owner", List.of("Owner"), true));
        verify(gui, never()).applyLegacyOwnerState(any(), any(), anyBoolean());
        verify(gui, never()).onTeamFeedback(anyBoolean(), anyString());

        // 占位被消费后放行新 write
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertEquals(2, sent.size());
    }

    @Test
    void legacyDuplicateLateAckCannotCompleteAnotherWrite() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        clientTick(200);
        // 迟到的第一条回包消费占位（队列已空）
        TeamManagementClient.onLegacyOwnerActionResponse(new OwnerActionResponseS2CPacket(
                true, "late", "Owner", List.of("Owner"), true));
        clearInvocations(gui);

        // 占位释放后新 write 才被允许；更早的重复回包只能按 FIFO 关联，
        // 无 requestId 的旧协议无法区分（该局限明确保留）
        assertNull(TeamManagementClient.toggleSelfClaim(gui));
        assertEquals(2, sent.size());
        verify(gui, never()).applyLegacyOwnerState(any(), any(), anyBoolean());
    }

    @Test
    void legacyPlayersRequestExpiresAndUnblocksOnlyAfterLateResponse() {
        legacyProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        GuiPlayerSelectDialog first = mockDialog(gui, PROJECT);
        TeamManagementClient.requestPlayerList(first);
        assertEquals(1, sent.size());

        clientTick(200);
        verify(first).onPlayerListFailed(unconfirmedCopy());

        GuiPlayerSelectDialog second = mockDialog(gui, PROJECT);
        TeamManagementClient.requestPlayerList(second);
        verify(second).onPlayerListFailed(anyString());
        assertEquals(1, sent.size(), "占位仍在时同类请求必须被阻断");

        TeamManagementClient.onLegacyPlayerListResponse(List.of(new PlayerInfo("Alice", true)));
        verify(first, never()).onPlayerListResponse(any());
        verify(second, never()).onPlayerListResponse(any());

        GuiPlayerSelectDialog third = mockDialog(gui, PROJECT);
        TeamManagementClient.requestPlayerList(third);
        assertEquals(2, sent.size(), "占位被迟回包消费后放行新请求");
    }

    @Test
    void expiredRequestsAreDroppedOnSessionCloseWithoutUnconfirmedNoise() {
        scopedProtocol();
        TeamManagementClient.onMaterialListOpened(gui);
        assertNull(TeamManagementClient.submitGroup(gui, null, TeamAction.TOGGLE_SELF_CLAIM,
                List.of(), List.of()));
        clientTick(50);
        assertEquals(1, TeamManagementClient.pendingCountForTesting());

        TeamManagementClient.onDisconnect();
        assertEquals(0, TeamManagementClient.pendingCountForTesting(),
                "会话关闭丢弃全部捕获，不再触发任何未确认回调");
        verify(gui, never()).onTeamFeedback(anyBoolean(), anyString());
    }
}
