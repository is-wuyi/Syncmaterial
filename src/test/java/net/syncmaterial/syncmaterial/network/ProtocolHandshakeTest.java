package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProtocolHandshakeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @AfterEach
    void clear() {
        ProtocolHandshake.clearAll();
    }

    @Test
    void currentVersionAccepted() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Player");
        assertTrue(fixture.handshake(player, ProtocolVersion.CURRENT));
        assertTrue(ProtocolHandshake.hasHandshaked(player));
        assertEquals(ProtocolVersion.CURRENT, ProtocolHandshake.getVersion(player));
    }

    @Test
    void namesDoNotChangeRecordedVersion() {
        Fixture fixture = new Fixture();
        ServerPlayer named = fixture.player(UUID.randomUUID(), "Player");
        ServerPlayer blank = fixture.player(UUID.randomUUID(), "  ");
        assertTrue(fixture.handshake(named, ProtocolVersion.CURRENT));
        assertTrue(fixture.handshake(blank, ProtocolVersion.CURRENT));
        assertEquals(ProtocolHandshake.getVersion(named), ProtocolHandshake.getVersion(blank));
    }

    @Test
    void newerClientAccepted() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "New");
        assertTrue(fixture.handshake(player, ProtocolVersion.CURRENT + 3));
        assertEquals(ProtocolVersion.CURRENT + 3, ProtocolHandshake.getVersion(player));
    }

    @Test
    void oldClientRejectedWithoutRecord() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Old");
        assertFalse(fixture.handshake(player, ProtocolVersion.MIN_COMPATIBLE - 1));
        assertFalse(ProtocolHandshake.hasHandshaked(player));
        assertEquals(0, ProtocolHandshake.getVersion(player));
    }

    @Test
    void rejectingRepeatedHandshakeRevokesSameConnection() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Old");
        assertTrue(fixture.handshake(player, ProtocolVersion.CURRENT));
        assertFalse(fixture.handshake(player, ProtocolVersion.MIN_COMPATIBLE - 1));
        assertFalse(ProtocolHandshake.hasHandshaked(player));
    }

    @Test
    void nullUnknownAndDisconnectedPlayersRejected() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Player");
        assertFalse(fixture.handshake(null, ProtocolVersion.CURRENT));
        player.connection = null;
        assertFalse(fixture.handshake(player, ProtocolVersion.CURRENT));
        assertFalse(ProtocolHandshake.hasHandshaked((ServerPlayer) null));
        assertFalse(ProtocolHandshake.hasHandshaked(UUID.randomUUID()));
        assertFalse(ProtocolHandshake.hasHandshaked((UUID) null));
        assertEquals(0, ProtocolHandshake.getVersion((UUID) null));
        assertEquals(0, ProtocolHandshake.getVersion((ServerPlayer) null));
    }

    @Test
    void unregisteredOrStoppedServerCannotCreateRecord() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Player");
        ProtocolHandshake.clear(fixture.server);
        assertFalse(fixture.handshake(player, ProtocolVersion.CURRENT));
        assertFalse(ProtocolHandshake.hasHandshaked(player));
    }

    @Test
    void wrongServerCannotAcceptPlayer() {
        Fixture a = new Fixture();
        Fixture b = new Fixture();
        ServerPlayer player = a.player(UUID.randomUUID(), "Player");
        assertFalse(b.handshake(player, ProtocolVersion.CURRENT));
        assertFalse(ProtocolHandshake.hasHandshaked(player));
    }

    @Test
    void removeIsIdempotentAndNullSafe() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Player");
        assertTrue(fixture.handshake(player, ProtocolVersion.CURRENT));
        ProtocolHandshake.remove(player);
        ProtocolHandshake.remove(player);
        ProtocolHandshake.remove((ServerPlayer) null);
        assertFalse(ProtocolHandshake.hasHandshaked(player));
    }

    @Test
    void playerRecordsRemainIndependent() {
        Fixture fixture = new Fixture();
        ServerPlayer a = fixture.player(UUID.randomUUID(), "A");
        ServerPlayer b = fixture.player(UUID.randomUUID(), "B");
        assertTrue(fixture.handshake(a, ProtocolVersion.CURRENT));
        assertTrue(fixture.handshake(b, ProtocolVersion.CURRENT + 2));
        ProtocolHandshake.remove(a);
        assertFalse(ProtocolHandshake.hasHandshaked(a));
        assertEquals(ProtocolVersion.CURRENT + 2, ProtocolHandshake.getVersion(b));
    }

    @Test
    void sameUuidAcrossServersHasNoSharedCapabilities() {
        Fixture a = new Fixture();
        Fixture b = new Fixture();
        UUID id = UUID.randomUUID();
        ServerPlayer playerA = a.player(id, "Player");
        ServerPlayer playerB = b.player(id, "Player");
        assertTrue(a.handshake(playerA, 1));
        assertTrue(b.handshake(playerB, ProtocolVersion.CURRENT));
        assertFalse(ProtocolHandshake.supports(playerA, ProtocolVersion.AREA_INVENTORY_SNAPSHOTS));
        assertTrue(ProtocolHandshake.supports(playerB, ProtocolVersion.AREA_INVENTORY_SNAPSHOTS));
        assertEquals(0, ProtocolHandshake.getVersion(id), "无 server 的歧义读取必须关闭能力");
        ProtocolHandshake.clear(a.server);
        assertEquals(ProtocolVersion.CURRENT, ProtocolHandshake.getVersion(playerB));
        ProtocolHandshake.remove(playerA);
        assertTrue(ProtocolHandshake.hasHandshaked(playerB));
    }

    @Test
    void oldConnectionCannotClearReconnectedPlayer() {
        Fixture fixture = new Fixture();
        UUID id = UUID.randomUUID();
        ServerPlayer old = fixture.player(id, "Player");
        assertTrue(fixture.handshake(old, 1));
        ServerPlayer current = fixture.player(id, "Player");
        assertFalse(ProtocolHandshake.hasHandshaked(current));
        assertTrue(fixture.handshake(current, ProtocolVersion.CURRENT));
        ProtocolHandshake.remove(old);
        assertFalse(ProtocolHandshake.hasHandshaked(old));
        assertTrue(ProtocolHandshake.hasHandshaked(current));
    }

    @Test
    void dimensionReplacementWithSameConnectionRetainsHandshake() {
        Fixture fixture = new Fixture();
        UUID id = UUID.randomUUID();
        ServerPlayer old = fixture.player(id, "Player");
        assertTrue(fixture.handshake(old, ProtocolVersion.CURRENT));
        ServerPlayer replacement = fixture.player(id, "Player");
        replacement.connection = old.connection;
        assertTrue(ProtocolHandshake.hasHandshaked(replacement));
        assertFalse(ProtocolHandshake.hasHandshaked(old));
    }

    @Test
    void disconnectedPlayerWithClearedConnectionDoesNotLeaveHandshake() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.player(UUID.randomUUID(), "Player");
        assertTrue(fixture.handshake(player, ProtocolVersion.CURRENT));
        player.connection = null;
        fixture.players.remove(player.getUUID());
        ProtocolHandshake.remove(player);
        assertEquals(0, ProtocolHandshake.getVersion(player.getUUID()));
    }

    @Test
    void oldPlayerWithClearedConnectionCannotRemoveNewConnection() {
        Fixture fixture = new Fixture();
        UUID id = UUID.randomUUID();
        ServerPlayer old = fixture.player(id, "Player");
        assertTrue(fixture.handshake(old, 1));
        ServerPlayer current = fixture.player(id, "Player");
        assertTrue(fixture.handshake(current, ProtocolVersion.CURRENT));
        old.connection = null;
        ProtocolHandshake.remove(old);
        assertTrue(ProtocolHandshake.hasHandshaked(current));
    }

    private static final class Fixture {
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel world = mock(ServerLevel.class);
        private final Map<UUID, ServerPlayer> players = new HashMap<>();

        private Fixture() {
            PlayerList list = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(list);
            when(world.getServer()).thenReturn(server);
            when(list.getPlayer(any(UUID.class))).thenAnswer(invocation -> players.get(invocation.getArgument(0)));
            when(list.getPlayers()).thenAnswer(ignored -> new ArrayList<>(players.values()));
            ProtocolHandshake.registerServer(server);
        }

        private ServerPlayer player(UUID id, String name) {
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.getUUID()).thenReturn(id);
            when(player.getName()).thenReturn(Component.literal(name));
            when(player.level()).thenReturn(world);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            players.put(id, player);
            return player;
        }

        private boolean handshake(ServerPlayer player, int version) {
            return ProtocolHandshake.recordHandshake(server, player, version, "test");
        }
    }
}
