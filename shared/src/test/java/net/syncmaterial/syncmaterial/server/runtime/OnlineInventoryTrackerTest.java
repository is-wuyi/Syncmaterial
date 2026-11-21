package net.syncmaterial.syncmaterial.server.runtime;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnlineInventoryTrackerTest {
    @Test
    void offlinePlayerIsExcludedImmediately() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = new OnlineInventoryTracker(clock::get, Duration.ofSeconds(10));
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        tracker.update(player, "project", 1, 64);
        assertEquals(64, tracker.count(player, "project", 1));
        tracker.disconnected(player);
        assertEquals(0, tracker.count(player, "project", 1));
        tracker.connected(player);
        assertEquals(0, tracker.count(player, "project", 1));
    }

    @Test
    void heartbeatRenewsOnlyItsMaterialObservation() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = new OnlineInventoryTracker(clock::get, Duration.ofSeconds(10));
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        tracker.update(player, "project", 1, 64);
        tracker.update(player, "project", 2, 16);
        clock.set(Duration.ofSeconds(5).toNanos());
        tracker.update(player, "project", 1, 64);
        clock.set(Duration.ofSeconds(10).toNanos());
        assertEquals(64, tracker.count(player, "project", 1));
        assertEquals(0, tracker.count(player, "project", 2));
        clock.set(Duration.ofSeconds(15).toNanos());
        assertEquals(0, tracker.count(player, "project", 1));
    }

    @Test
    void projectAndMaterialIdentitiesAreSeparate() {
        OnlineInventoryTracker tracker = new OnlineInventoryTracker();
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        tracker.update(player, "a", 1, 32);
        tracker.update(player, "b", 1, 64);
        tracker.forget(player, "a", 1);
        assertEquals(0, tracker.count(player, "a", 1));
        assertEquals(64, tracker.count(player, "b", 1));
    }

    @Test
    void lateWriteFromAnOldConnectionDoesNotPopulateTheNewConnection() {
        OnlineInventoryTracker tracker = new OnlineInventoryTracker();
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        long oldConnection = tracker.connection(player);
        tracker.disconnected(player);
        tracker.connected(player);
        tracker.update(player, oldConnection, "project", 1, 64);
        assertEquals(0, tracker.count(player, "project", 1));
        tracker.update(player, tracker.connection(player), "project", 1, 32);
        assertEquals(32, tracker.count(player, "project", 1));
    }

    @Test
    void disconnectReturnsOnlyProjectsWithRemovedObservations() {
        OnlineInventoryTracker tracker = new OnlineInventoryTracker();
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        tracker.update(player, "a", 1, 32);
        tracker.update(player, "a", 2, 16);
        tracker.update(player, "b", 1, 64);
        tracker.update(player, "forgotten", 1, 8);
        tracker.forget(player, "forgotten", 1);

        Set<String> affected = tracker.disconnected(player, tracker.connection(player));
        assertEquals(Set.of("a", "b"), affected);
        assertThrows(UnsupportedOperationException.class, () -> affected.add("other"));
        assertEquals(Set.of(), tracker.disconnected(player));
    }

    @Test
    void delayedDisconnectCannotClearTheNewConnection() {
        OnlineInventoryTracker tracker = new OnlineInventoryTracker();
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        long oldConnection = tracker.connection(player);
        tracker.update(player, "old", 1, 64);
        assertEquals(Set.of("old"), tracker.disconnected(player, oldConnection));
        tracker.connected(player);
        long current = tracker.connection(player);
        tracker.update(player, "new", 1, 32);

        assertEquals(Set.of(), tracker.disconnected(player, oldConnection));
        assertEquals(Set.of(), tracker.disconnected(player, 0));
        assertEquals(current, tracker.connection(player));
        assertEquals(32, tracker.count(player, "new", 1));
        assertEquals(Set.of("new"), tracker.disconnected(player, current));
    }

    @Test
    void expiryRemovesEmptyProjectEntriesAndOnlyPublishesOnce() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = new OnlineInventoryTracker(clock::get, Duration.ofSeconds(10));
        UUID player = UUID.randomUUID();
        tracker.connected(player);
        tracker.update(player, "expired", 1, 64);
        clock.set(Duration.ofSeconds(5).toNanos());
        tracker.update(player, "fresh", 2, 16);
        clock.set(Duration.ofSeconds(10).toNanos());

        assertEquals(Set.of("expired"), tracker.expire());
        assertEquals(Set.of(), tracker.expire());
        assertEquals(Set.of("fresh"), tracker.disconnected(player));
    }

    @Test
    void rejectsInvalidCountAndDoesNotResurrectDisconnectedPlayer() {
        OnlineInventoryTracker tracker = new OnlineInventoryTracker();
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> tracker.update(player, "project", 1, -1));
        tracker.update(player, "project", 1, 64);
        assertEquals(0, tracker.count(player, "project", 1));
    }
}
