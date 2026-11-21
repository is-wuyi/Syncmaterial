package net.syncmaterial.syncmaterial.server.runtime;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineInventoryTrackerSnapshotTest {

    private static final String PROJECT = "project";

    @Test
    void snapshotOnlyIncludesFreshConnectedClaimedObservations() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID offline = UUID.randomUUID();

        tracker.connected(alice);
        tracker.connected(bob);
        tracker.update(alice, PROJECT, 1, 64);
        tracker.update(alice, PROJECT, 2, 16);
        tracker.update(alice, "other-project", 1, 99);
        tracker.update(bob, PROJECT, 1, 32);
        tracker.update(bob, PROJECT, 3, 7);
        tracker.update(offline, PROJECT, 2, 48);

        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT, List.of(
                new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")),
                new ClaimSnapshot(1, new PlayerIdentity(bob, "Bob")),
                new ClaimSnapshot(2, new PlayerIdentity(offline, "Offline")),
                new ClaimSnapshot(3, new PlayerIdentity(null, "Legacy"))));

        assertEquals(PROJECT, sample.projectId());
        assertEquals(64, sample.count(1, alice));
        assertEquals(32, sample.count(1, bob));
        assertEquals(0, sample.count(2, offline));
        assertEquals(0, sample.count(3, offline));
        assertEquals(0, sample.count(2, alice));
        assertEquals(tracker.connection(alice), sample.connectionGeneration(alice));
        assertEquals(tracker.connection(bob), sample.connectionGeneration(bob));
        assertEquals(0, sample.connectionGeneration(offline));
        assertEquals(1, sample.counts().size());
        assertEquals(64, tracker.count(alice, PROJECT, 1));
        assertEquals(16, tracker.count(alice, PROJECT, 2));
    }

    @Test
    void unclaimedMaterialAndUnobservedClaimAreNotSampled() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);
        tracker.update(alice, PROJECT, 2, 16);

        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT,
                List.of(new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice"))));

        assertEquals(64, sample.count(1, alice));
        assertEquals(0, sample.count(2, alice));
        assertTrue(sample.counts().get(2) == null);
    }

    @Test
    void exactFreshnessBoundaryExpiresTheObservation() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);
        List<ClaimSnapshot> claims = List.of(new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")));

        clock.set(Duration.ofSeconds(9).toNanos());
        assertEquals(64, tracker.snapshot(PROJECT, claims).count(1, alice));

        clock.set(Duration.ofSeconds(10).toNanos());
        assertEquals(0, tracker.snapshot(PROJECT, claims).count(1, alice));
        assertEquals(0, tracker.count(alice, PROJECT, 1));
    }

    @Test
    void disconnectAndReconnectCannotResurrectOldObservation() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        long oldConnection = tracker.connection(alice);
        tracker.update(alice, PROJECT, 1, 64);
        List<ClaimSnapshot> claims = List.of(new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")));

        tracker.disconnected(alice);
        assertEquals(0, tracker.snapshot(PROJECT, claims).count(1, alice));

        tracker.connected(alice);
        long newConnection = tracker.connection(alice);
        assertTrue(newConnection != oldConnection);
        assertEquals(0, tracker.snapshot(PROJECT, claims).count(1, alice));

        tracker.update(alice, oldConnection, PROJECT, 1, 64);
        assertEquals(0, tracker.snapshot(PROJECT, claims).count(1, alice));

        tracker.update(alice, newConnection, PROJECT, 1, 32);
        assertEquals(32, tracker.snapshot(PROJECT, claims).count(1, alice));
        assertEquals(newConnection, tracker.snapshot(PROJECT, claims).connectionGeneration(alice));
    }

    @Test
    void delayedSampleRequiresFreshClockAndTheSameConnectionGeneration() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);
        List<ClaimSnapshot> claims = List.of(new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")));
        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT, claims);

        assertTrue(tracker.isCurrent(sample));
        clock.set(Duration.ofSeconds(9).toNanos());
        assertTrue(tracker.isCurrent(sample));
        clock.set(Duration.ofSeconds(10).toNanos());
        assertFalse(tracker.isCurrent(sample));

        clock.set(0);
        assertTrue(tracker.isCurrent(sample));
        tracker.disconnected(alice);
        assertFalse(tracker.isCurrent(sample));
    }

    @Test
    void backwardsClockCannotMakeAnObservationFreshAgain() {
        AtomicLong clock = new AtomicLong(Duration.ofSeconds(5).toNanos());
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);
        assertEquals(64, tracker.count(alice, PROJECT, 1));

        clock.set(0);
        assertEquals(0, tracker.count(alice, PROJECT, 1));
        assertTrue(tracker.snapshot(PROJECT,
                List.of(new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")))).counts().isEmpty());
    }

    @Test
    void legacyNameOnlyClaimNeverManufacturesUuid() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);

        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT,
                List.of(new ClaimSnapshot(1, new PlayerIdentity(null, "Alice"))));

        assertTrue(sample.counts().isEmpty());
        assertTrue(sample.connectionGenerations().isEmpty());
        assertEquals(0, sample.count(1, alice));
    }

    @Test
    void nameOnlyClaimLookupReturnsZeroAlongsideOnlineUuidObservation() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 4);
        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT, List.of(
                new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice")),
                new ClaimSnapshot(1, new PlayerIdentity(null, "Offline"))));
        assertEquals(4, sample.count(1, alice));
        assertEquals(0, sample.count(1, null));
        assertEquals(0, sample.connectionGeneration(null));
    }

    @Test
    void duplicateClaimsAreIdempotentAndTheSnapshotIsDeeplyImmutable() {
        AtomicLong clock = new AtomicLong();
        OnlineInventoryTracker tracker = tracker(clock);
        UUID alice = UUID.randomUUID();
        tracker.connected(alice);
        tracker.update(alice, PROJECT, 1, 64);
        ClaimSnapshot claim = new ClaimSnapshot(1, new PlayerIdentity(alice, "Alice"));

        OnlineInventorySnapshot sample = tracker.snapshot(PROJECT, List.of(claim, claim));

        assertEquals(64, sample.count(1, alice));
        assertEquals(1, sample.counts().get(1).size());
        assertThrows(UnsupportedOperationException.class, () -> sample.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> sample.counts().get(1).clear());
        assertThrows(UnsupportedOperationException.class, () -> sample.connectionGenerations().clear());
    }

    @Test
    void snapshotRejectsBlankProjectAndNullClaims() {
        OnlineInventoryTracker tracker = tracker(new AtomicLong());
        assertThrows(IllegalArgumentException.class, () -> tracker.snapshot(" ", List.of()));
        assertThrows(NullPointerException.class, () -> tracker.snapshot(PROJECT, null));
    }

    private static OnlineInventoryTracker tracker(AtomicLong clock) {
        return new OnlineInventoryTracker(clock::get, Duration.ofSeconds(10));
    }
}
