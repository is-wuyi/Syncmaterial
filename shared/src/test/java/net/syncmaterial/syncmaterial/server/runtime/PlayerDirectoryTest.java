package net.syncmaterial.syncmaterial.server.runtime;

import java.util.List;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDirectoryTest {
    @Test
    void unionContainsStoredOfflineClaimantsAndCachedPlayers() {
        var old = new PlayerIdentity(UUID.randomUUID(), "OldClaimant");
        var cached = new PlayerIdentity(UUID.randomUUID(), "Cached");
        var online = new PlayerIdentity(UUID.randomUUID(), "Online");
        var result = PlayerDirectory.merge(List.of(old), List.of(cached), List.of(online));
        assertEquals(3, result.size());
        assertTrue(result.getFirst().online());
        assertEquals(online, result.getFirst().player());
        assertTrue(result.stream().anyMatch(entry -> entry.player().equals(old) && !entry.online()));
    }

    @Test
    void uuidDeduplicationUsesCurrentOnlineName() {
        UUID id = UUID.randomUUID();
        var result = PlayerDirectory.merge(List.of(new PlayerIdentity(id, "Old")),
                List.of(new PlayerIdentity(id, "Cached")), List.of(new PlayerIdentity(id, "New")));
        assertEquals(1, result.size());
        assertEquals("New", result.getFirst().player().name());
        assertTrue(result.getFirst().online());
    }

    @Test
    void unresolvedOfflineNameIsNotLostWhenNoRealIdentityExists() {
        var unresolved = new PlayerIdentity(null, "Unknown");
        var result = PlayerDirectory.merge(List.of(unresolved), List.of(), List.of());
        assertEquals(unresolved, result.getFirst().player());
        assertFalse(result.getFirst().online());
        assertEquals(unresolved, PlayerDirectory.resolve(unresolved, result));
    }

    @Test
    void nameResolutionRejectsDifferentOfflineUuids() {
        var left = new PlayerIdentity(UUID.randomUUID(), "Same");
        var right = new PlayerIdentity(UUID.randomUUID(), "Same");
        var directory = PlayerDirectory.merge(List.of(left, right), List.of(), List.of());
        assertEquals(2, directory.size());
        assertThrows(IllegalArgumentException.class, () -> PlayerDirectory.resolve(new PlayerIdentity(null, "Same"), directory));
        assertEquals(left, PlayerDirectory.resolve(new PlayerIdentity(left.uuid(), "OldName"), directory));
    }

    @Test
    void uniqueOnlineFactWinsOverOfflineNameCollision() {
        var old = new PlayerIdentity(UUID.randomUUID(), "Same");
        var current = new PlayerIdentity(UUID.randomUUID(), "Same");
        var directory = PlayerDirectory.merge(List.of(old), List.of(), List.of(current));
        assertEquals(current, PlayerDirectory.resolve(new PlayerIdentity(null, "same"), directory));
    }

    @Test
    void unknownUuidDoesNotBecomeTrustedNameIdentity() {
        assertThrows(IllegalArgumentException.class, () -> PlayerDirectory.resolve(
                new PlayerIdentity(UUID.randomUUID(), "Owner"), List.of()));
    }
}
