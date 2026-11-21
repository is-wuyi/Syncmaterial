package net.syncmaterial.syncmaterial.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog;
import net.syncmaterial.syncmaterial.client.gui.MaterialSelection;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.TeamCommandC2SPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 批量分配组回执与选择意图版本的端到端关联：迟到的成功回包只看提交时捕获的
 * 快照，不得清空用户提交后新增或重选的勾选，也不得影响另一个界面实例。
 */
class TeamManagementBatchSelectionTest {
    private static final String PROJECT = "project-a";
    private static final PlayerIdentity ACTOR = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "Actor");
    private static final PlayerIdentity ALICE = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alice");

    private final List<CustomPacketPayload> sent = new ArrayList<>();
    private GuiMaterialList gui;
    private GuiPlayerSelectDialog dialog;
    private Screen currentScreen;

    @BeforeEach
    void setUp() {
        TeamManagementClient.resetForTesting();
        ClientProtocolState.reset();
        TeamManagementClient.setTransportForTesting(sent::add);
        TeamManagementClient.setIdentityProviderForTesting(() -> ACTOR);
        TeamManagementClient.setScreenProviderForTesting(() -> currentScreen);
        TeamManagementClient.onJoin();
        ClientProtocolState.onHandshakeResponse(4, "test", true);

        gui = newGui();
        dialog = mockDialog(gui);
        currentScreen = dialog;
        TeamManagementClient.onMaterialListOpened(gui);
    }

    @AfterEach
    void tearDown() {
        TeamManagementClient.resetForTesting();
        ClientProtocolState.reset();
    }

    private static GuiMaterialList newGui() {
        GuiMaterialList list = mock(GuiMaterialList.class, CALLS_REAL_METHODS);
        SyncMaterialList sync = mock(SyncMaterialList.class);
        when(sync.getSchematicId()).thenReturn(PROJECT);
        when(sync.getCatalogRevision()).thenReturn(7L);
        setField(list, "materialList", sync);
        setField(list, "selectedMaterials", new MaterialSelection());
        return list;
    }

    private static GuiPlayerSelectDialog mockDialog(GuiMaterialList list) {
        GuiPlayerSelectDialog result = mock(GuiPlayerSelectDialog.class);
        when(result.getMaterialList()).thenReturn(list);
        when(result.getSchematicId()).thenReturn(PROJECT);
        return result;
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("missing field " + name);
    }

    private void select(GuiMaterialList list, Integer... ids) {
        for (Integer id : ids) {
            list.getSelectedMaterialIds().add(id);
        }
    }

    private long submitAssign(GuiMaterialList list, GuiPlayerSelectDialog parent, Integer... ids) {
        assertNull(TeamManagementClient.submitGroup(list, parent, TeamAction.ASSIGN,
                List.of(ALICE), List.of(ids)));
        CustomPacketPayload payload = sent.get(sent.size() - 1);
        TeamCommandC2SPacket packet = (TeamCommandC2SPacket) payload;
        return packet.command().requestId();
    }

    private void respond(long requestId, boolean success) {
        currentScreen = dialog;
        TeamManagementClient.onTeamResponse(new TeamResponse(PROJECT, requestId, TeamAction.ASSIGN,
                1, success, "", null, List.of(), 0, 0));
    }

    @Test
    void lateAssignCompletionKeepsMaterialSelectedAfterSubmit() {
        select(gui, 11);
        long requestId = submitAssign(gui, dialog, 11);

        select(gui, 13);
        respond(requestId, true);

        assertEquals(List.of(13), gui.getSelectedMaterialIds());
        verify(gui.getMaterialList()).requestCollaborationStatus();
    }

    @Test
    void sameIdReselectedAfterSubmitSurvivesOldCompletion() {
        select(gui, 11);
        long requestId = submitAssign(gui, dialog, 11);

        gui.getSelectedMaterialIds().remove(Integer.valueOf(11));
        select(gui, 11);
        respond(requestId, true);

        assertEquals(List.of(11), gui.getSelectedMaterialIds(),
                "取消后重选属于新意图，旧组回执不得清除");
    }

    @Test
    void failedGroupDoesNotClearSelection() {
        select(gui, 11);
        long requestId = submitAssign(gui, dialog, 11);

        select(gui, 13);
        respond(requestId, false);

        assertEquals(List.of(11, 13), gui.getSelectedMaterialIds());
    }

    @Test
    void duplicateOldResponseIsIgnoredAfterGroupFinished() {
        select(gui, 11);
        long requestId = submitAssign(gui, dialog, 11);
        respond(requestId, true);
        assertEquals(List.of(), gui.getSelectedMaterialIds());

        select(gui, 12);
        respond(requestId, true);

        assertEquals(List.of(12), gui.getSelectedMaterialIds());
    }

    @Test
    void oldGroupOnlyClearsItsCapturedGuiInstance() {
        GuiMaterialList other = newGui();
        select(other, 21);
        select(gui, 11);
        long requestId = submitAssign(gui, dialog, 11);

        select(other, 22);
        respond(requestId, true);

        assertEquals(List.of(), gui.getSelectedMaterialIds());
        assertEquals(List.of(21, 22), other.getSelectedMaterialIds(),
                "另一个界面实例的选择不得被旧组回执清除");
    }
}
