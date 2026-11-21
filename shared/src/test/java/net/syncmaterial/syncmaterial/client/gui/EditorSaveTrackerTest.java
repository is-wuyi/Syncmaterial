package net.syncmaterial.syncmaterial.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSaveTrackerTest {
    @Test
    void olderReplyCannotSaveNewerInput() {
        EditorSaveTracker tracker = new EditorSaveTracker();
        long first = tracker.edit();
        assertTrue(tracker.sent(first, 10));
        long second = tracker.edit();
        assertTrue(tracker.sent(second, 11));

        assertFalse(tracker.complete(10, true));
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());
        assertTrue(tracker.complete(11, true));
        assertEquals(EditorSaveTracker.State.SAVED, tracker.state());
        assertFalse(tracker.complete(11, true));
    }

    @Test
    void oldProtocolStaysUnconfirmedAndFailureIsNotSuccess() {
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        assertTrue(tracker.unconfirmed(edit));
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state());

        long next = tracker.edit();
        assertTrue(tracker.sent(next, 12));
        assertTrue(tracker.complete(12, false));
        assertEquals(EditorSaveTracker.State.FAILED, tracker.state());
    }

    @Test
    void disconnectInvalidatesWithoutReplayingOrMarkingSaved() {
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        tracker.sent(edit, 13);
        tracker.invalidate();
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state());
        assertFalse(tracker.complete(13, true));
    }

    @Test
    void timeoutOnlyUnconfirmsTheExactSavingRequestAndLateAckCannotSaveIt() {
        EditorSaveTracker tracker = new EditorSaveTracker();
        long edit = tracker.edit();
        assertTrue(tracker.sent(edit, 14));

        assertFalse(tracker.timeout(15), "a different request id must not expire this write");
        assertEquals(EditorSaveTracker.State.SAVING, tracker.state());

        assertTrue(tracker.timeout(14));
        assertEquals(EditorSaveTracker.State.UNCONFIRMED, tracker.state());
        assertFalse(tracker.complete(14, true), "a late ack must not turn a timeout into success");
        assertFalse(tracker.timeout(14), "a second timeout must not change state");
    }
}
