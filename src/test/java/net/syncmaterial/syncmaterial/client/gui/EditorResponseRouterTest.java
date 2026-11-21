package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorResponseRouterTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");

    @AfterEach
    void tearDown() {
        EditorResponseRouter.clear();
    }

    private static ProjectViewClient session() {
        return new ProjectViewClient(new Object(), 1, packet -> {}, () -> 1L, () -> true);
    }

    private static ProjectViewClient.ViewToken token(ProjectViewClient session) {
        return new ProjectViewClient.ViewToken(session, PROJECT, 7, 3);
    }

    @Test
    void duplicateAndUnknownRequestsCannotCompleteTheLatestEdit() {
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        assertTrue(tracker.sent(edit, 10));
        assertTrue(EditorResponseRouter.register(10, tracker, token(session())));
        assertFalse(EditorResponseRouter.register(10, tracker, token(session())));

        assertFalse(EditorResponseRouter.complete(ProjectViewPackets.error(
                PROJECT, 11, 7, 3, ProjectViewAction.AREA_CONFIG, "wrong")));
        assertTrue(EditorResponseRouter.complete(ProjectViewPackets.error(
                PROJECT, 10, 7, 3, ProjectViewAction.AREA_CONFIG, "failed")));
        assertEquals(EditorSaveTracker.State.FAILED, tracker.state());
        assertEquals(0, EditorResponseRouter.pendingCount());
    }

    @Test
    void oldViewGenerationActionOrConfigCannotCompleteTheCurrentEdit() {
        ProjectViewClient session = session();
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        assertTrue(tracker.sent(edit, 20));
        assertTrue(EditorResponseRouter.register(20, tracker, token(session)));

        assertFalse(EditorResponseRouter.complete(ProjectViewPackets.configuration(PROJECT, 20,
                7, 4, 1, ProjectViewAction.AREA_CONFIG, true, "",
                new ProjectViewS2CPacket.AreaConfigData("LIST", List.of()))));
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        assertFalse(EditorResponseRouter.complete(ProjectViewPackets.configuration(PROJECT, 20,
                7, 3, 1, ProjectViewAction.RESCAN, true, "",
                new ProjectViewS2CPacket.AreaConfigData("LIST", List.of()))));
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        assertFalse(EditorResponseRouter.complete(ProjectViewPackets.configuration(
                ProjectViewScope.project("other"), 20, 7, 3, 1, ProjectViewAction.AREA_CONFIG,
                true, "", new ProjectViewS2CPacket.AreaConfigData("LIST", List.of()))));
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        assertTrue(EditorResponseRouter.complete(ProjectViewPackets.configuration(PROJECT, 20,
                7, 3, 1, ProjectViewAction.AREA_CONFIG, true, "",
                new ProjectViewS2CPacket.AreaConfigData("LIST", List.of()))));
        assertEquals(EditorSaveTracker.State.SAVED, tracker.state());
        assertEquals(0, EditorResponseRouter.pendingCount());
    }

    @Test
    void unansweredWriteBecomesUnconfirmedAndLateAckCannotSaveIt() {
        ProjectViewClient session = session();
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        assertTrue(tracker.sent(edit, 30));
        assertTrue(EditorResponseRouter.register(30, tracker, token(session)));

        for (int tick = 0; tick < EditorResponseRouter.RESPONSE_TIMEOUT_TICKS; tick++) {
            EditorResponseRouter.tick();
        }
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state());
        assertEquals(0, EditorResponseRouter.pendingCount());

        assertFalse(EditorResponseRouter.complete(ProjectViewPackets.configuration(PROJECT, 30,
                7, 3, 1, ProjectViewAction.AREA_CONFIG, true, "",
                new ProjectViewS2CPacket.AreaConfigData("LIST", List.of()))));
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state());
    }
}
