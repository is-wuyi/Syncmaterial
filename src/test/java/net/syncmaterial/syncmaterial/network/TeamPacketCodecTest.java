package net.syncmaterial.syncmaterial.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.core.TeamResponse;
import net.syncmaterial.syncmaterial.core.TeamState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamPacketCodecTest {
    private static final PlayerIdentity ALICE = new PlayerIdentity(UUID.fromString("12345678-1234-1234-1234-123456789abc"), "Alice");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void assignmentRoundtripKeepsProjectRequestCatalogAndUuid() {
        var original = new TeamCommandC2SPacket(new TeamCommand("project", 99, TeamAction.ASSIGN, 10,
                List.of(3, 4, -1, 3), List.of(ALICE, new PlayerIdentity(null, "Offline"))));
        var buffer = buffer();
        try {
            TeamCommandC2SPacket.CODEC.encode(buffer, original);
            assertEquals(original, TeamCommandC2SPacket.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void zeroRequestStateRoundtripCannotBeConfusedWithOwnResult() {
        var original = new TeamResponseS2CPacket(new TeamResponse("p", 0, TeamAction.TEAM_STATE, 50,
                true, "", new TeamState("p", ALICE, List.of(new PlayerIdentity(null, "Deputy")), false),
                List.of(), 0, 0));
        assertEquals(original, responseRoundtrip(original));
    }

    @Test
    void directoryResponseKeepsOfflineAndUuidIdentity() {
        var original = new TeamResponseS2CPacket(new TeamResponse("p", 5, TeamAction.PLAYERS, 7,
                true, "", null, List.of(new PlayerDirectoryEntry(ALICE, true),
                new PlayerDirectoryEntry(new PlayerIdentity(null, "Offline"), false)), 0, 0));
        assertEquals(original, responseRoundtrip(original));
    }

    @Test
    void failureWithNoKnownTeamDoesNotInventEmptyOwner() {
        var original = new TeamResponseS2CPacket(new TeamResponse("p", 2, TeamAction.TRANSFER, 8,
                false, "Denied", null, List.of(), 0, 0));
        assertNull(responseRoundtrip(original).response().team());
    }

    @Test
    void negativeAndOversizedListLengthRejectedBeforeAllocation() {
        var buffer = commandHeader();
        try {
            buffer.writeVarInt(-1);
            var negative = buffer;
            assertThrows(IllegalArgumentException.class, () -> TeamCommandC2SPacket.CODEC.decode(negative));
        } finally {
            buffer.release();
        }
        buffer = commandHeader();
        try {
            buffer.writeVarInt(TeamLimits.MATERIALS + 1);
            var oversized = buffer;
            assertThrows(IllegalArgumentException.class, () -> TeamCommandC2SPacket.CODEC.decode(oversized));
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedActionCannotReachApplication() {
        var buffer = buffer();
        try {
            buffer.writeUtf("p");
            buffer.writeVarLong(1);
            buffer.writeUtf("DELETE_EVERYTHING");
            assertThrows(IllegalArgumentException.class, () -> TeamCommandC2SPacket.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void pairLimitRejectsValidSizedListsWithExcessiveWork() {
        assertThrows(IllegalArgumentException.class, () -> new TeamCommandC2SPacket(new TeamCommand("p", 1,
                TeamAction.ASSIGN, 1, java.util.Collections.nCopies(1024, 3),
                java.util.Collections.nCopies(128, ALICE))));
    }

    @Test
    void responseListsAreBoundedBeforeDecodingEntryObjects() {
        var buffer = buffer();
        try {
            buffer.writeUtf("p");
            buffer.writeVarLong(1);
            buffer.writeUtf("PLAYERS");
            buffer.writeVarLong(1);
            buffer.writeBoolean(true);
            buffer.writeUtf("");
            buffer.writeBoolean(false);
            buffer.writeVarInt(TeamLimits.DIRECTORY + 1);
            assertThrows(IllegalArgumentException.class, () -> TeamResponseS2CPacket.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void publishedChannelNamesAreAdditive() {
        assertEquals("syncmaterial:team_command", ModPackets.TEAM_COMMAND.toString());
        assertEquals("syncmaterial:team_response", ModPackets.TEAM_RESPONSE.toString());
        assertEquals("syncmaterial:owner_action", ModPackets.OWNER_ACTION.toString());
        assertEquals("syncmaterial:hello_c2s", ModPackets.HELLO_C2S.toString());
    }

    private static TeamResponseS2CPacket responseRoundtrip(TeamResponseS2CPacket original) {
        var buffer = buffer();
        try {
            TeamResponseS2CPacket.CODEC.encode(buffer, original);
            var decoded = TeamResponseS2CPacket.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf commandHeader() {
        var buffer = buffer();
        buffer.writeUtf("p");
        buffer.writeVarLong(1);
        buffer.writeUtf("ASSIGN");
        buffer.writeVarLong(1);
        return buffer;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
