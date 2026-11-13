package net.syncmaterial.syncmaterial.server.runtime;

import java.util.UUID;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamRequestTrackerTest {
    @Test
    void duplicateAndOlderRequestsAreRejectedWithinConnection() {
        var tracker = new TeamRequestTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.begin(player, 1, 5));
        tracker.finish(player, 1);
        assertFalse(tracker.begin(player, 1, 5));
        assertFalse(tracker.begin(player, 1, 4));
        assertTrue(tracker.begin(player, 1, 6));
    }

    @Test
    void newConnectionHasIndependentAdmission() {
        var tracker = new TeamRequestTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.begin(player, 1, 10));
        assertTrue(tracker.begin(player, 2, 1));
        assertFalse(tracker.begin(player, 1, 11));
        tracker.finish(player, 1);
        assertFalse(tracker.begin(player, 2, 1));
    }

    @Test
    void inFlightWorkIsBoundedAndCompletedRequestsReleaseSlot() {
        var tracker = new TeamRequestTracker();
        UUID player = UUID.randomUUID();
        for (int request = 1; request <= TeamLimits.IN_FLIGHT; request++) assertTrue(tracker.begin(player, 1, request));
        assertFalse(tracker.begin(player, 1, TeamLimits.IN_FLIGHT + 1));
        tracker.finish(player, 1);
        assertTrue(tracker.begin(player, 1, TeamLimits.IN_FLIGHT + 1));
    }

    @Test
    void disconnectInvalidatesOnlyThatPlayer() {
        var tracker = new TeamRequestTracker();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        tracker.begin(left, 1, 3);
        tracker.begin(right, 1, 3);
        tracker.disconnected(left);
        assertTrue(tracker.begin(left, 2, 1));
        assertFalse(tracker.begin(right, 1, 2));
    }

    @Test
    void sequencesOrderProjectStateWithoutChangingMaterialRevision() {
        var tracker = new TeamRequestTracker();
        long first = tracker.nextSequence("a");
        long other = tracker.nextSequence("b");
        long latest = tracker.nextSequence("a");
        assertTrue(first < other && other < latest);
        assertEquals(other, tracker.currentSequence("b"));
        assertEquals(latest, tracker.currentSequence("a"));
        tracker.clear();
        assertEquals(0, tracker.currentSequence("a"));
        assertTrue(tracker.nextSequence("a") > latest);
    }

    @Test
    void connectionIdentityDoesNotDependOnHashCode() {
        var tracker = new TeamRequestTracker();
        UUID player = UUID.randomUUID();
        Object left = new Object() { @Override public int hashCode() { return 1; } };
        Object right = new Object() { @Override public int hashCode() { return 1; } };
        long first = tracker.connection(player, left);
        assertEquals(first, tracker.connection(player, left));
        assertTrue(tracker.connection(player, right) > first);
        tracker.disconnected(player);
        assertTrue(tracker.connection(player, left) > first);
    }

    @Test
    void malformedAdmissionNeverAllocatesSession() {
        var tracker = new TeamRequestTracker();
        assertFalse(tracker.begin(null, 1, 1));
        assertFalse(tracker.begin(UUID.randomUUID(), 0, 1));
        assertFalse(tracker.begin(UUID.randomUUID(), 1, 0));
    }
}
