package net.syncmaterial.syncmaterial.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamRecordsTest {
    private static final PlayerIdentity ALICE = new PlayerIdentity(java.util.UUID.randomUUID(), "Alice");
    private static final PlayerIdentity BOB = new PlayerIdentity(java.util.UUID.randomUUID(), "Bob");

    @Test
    void ownerAndDeputyMatchUuidInsteadOfReusedNames() {
        var state = new TeamState("p", ALICE, List.of(BOB), true);
        assertTrue(state.isMainOwner(new PlayerIdentity(ALICE.uuid(), "Renamed")));
        assertTrue(state.isOwner(new PlayerIdentity(BOB.uuid(), "NewBob")));
        assertFalse(state.isOwner(new PlayerIdentity(java.util.UUID.randomUUID(), "Bob")));
        assertFalse(state.isOwner(new PlayerIdentity(null, "Alice")));
    }

    @Test
    void unresolvedRolesKeepCompatibleNameUntilUpgrade() {
        var state = new TeamState("p", new PlayerIdentity(null, "ALICE"), List.of(), true);
        assertTrue(state.isMainOwner(ALICE));
        assertEquals(List.of(), state.deputyNames());
    }

    @Test
    void foundationUnresolvedNamesCannotGrantUuidAuthority() {
        var unresolved = new TeamState("p", new PlayerIdentity(null, "Alice"), List.of(), true, false);
        assertFalse(unresolved.isMainOwner(ALICE));
        assertFalse(unresolved.isOwner(ALICE));
        var upgraded = new TeamState("p", ALICE, List.of(), true, false);
        assertTrue(upgraded.isMainOwner(ALICE));
    }

    @Test
    void validatedShapeDoesNotDiscardMixedInvalidMaterials() {
        var request = new TeamCommand("p", 1, TeamAction.ASSIGN, 4,
                List.of(5, -1, 999, 5), List.of(ALICE, ALICE));
        assertEquals(4, request.materialIds().size());
        assertEquals(2, request.targets().size());
    }

    @Test
    void actionSpecificFieldsCannotBeSmuggledIntoQueries() {
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 1, TeamAction.PLAYERS, -1,
                List.of(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 1, TeamAction.TRANSFER, -1,
                List.of(), List.of(ALICE, BOB)));
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 1, TeamAction.KICK, -1,
                List.of(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 0, TeamAction.TEAM_STATE, -1,
                List.of(), List.of()));
    }

    @Test
    void boundsLimitActualPairWorkBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 1, TeamAction.ASSIGN, 1,
                java.util.Collections.nCopies(TeamLimits.MATERIALS, 1),
                java.util.Collections.nCopies(TeamLimits.TARGETS, ALICE)));
        assertThrows(IllegalArgumentException.class, () -> new TeamCommand("p", 1, TeamAction.ASSIGN, 1,
                java.util.Collections.nCopies(TeamLimits.MATERIALS + 1, 1), List.of(ALICE)));
    }

    @Test
    void unsolicitedReplyCannotBeAnOperationResult() {
        var state = new TeamState("p", ALICE, List.of(), true);
        assertDoesNotThrow(() -> new TeamResponse("p", 0, TeamAction.TEAM_STATE, 3,
                true, "", state, List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeamResponse("p", 0, TeamAction.ASSIGN, 3,
                true, "", state, List.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TeamResponse("other", 1, TeamAction.TRANSFER, 3,
                true, "", state, List.of(), 0, 0));
    }

    @Test
    void allCollectionsAreDefensiveSnapshots() {
        var targets = new java.util.ArrayList<>(List.of(ALICE));
        var request = new TeamCommand("p", 1, TeamAction.TRANSFER, -1, List.of(), targets);
        var state = new TeamState("p", ALICE, targets, true);
        targets.clear();
        assertEquals(List.of(ALICE), request.targets());
        assertEquals(List.of(ALICE), state.deputies());
        assertThrows(UnsupportedOperationException.class, () -> state.deputies().clear());
    }

    @Test
    void offlineNameIsAllowedButOnlineRequiresRealUuid() {
        assertDoesNotThrow(() -> new PlayerDirectoryEntry(new PlayerIdentity(null, "Offline"), false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerDirectoryEntry(new PlayerIdentity(null, "Fake"), true));
    }
}
