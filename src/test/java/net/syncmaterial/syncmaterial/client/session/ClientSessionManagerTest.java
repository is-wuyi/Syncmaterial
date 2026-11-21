package net.syncmaterial.syncmaterial.client.session;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

class ClientSessionManagerTest {
    @AfterEach
    void tearDown() {
        ClientSessionManager.resetForTesting();
        ClientProtocolState.reset();
    }

    @Test
    void oldHelloCannotChangeTheNewConnectionCapabilities() {
        Object first = new Object();
        ClientConnectionSession a = ClientSessionManager.join(first);
        long tokenA = a.token();
        assertTrue(ClientProtocolState.onHandshakeResponse(first, tokenA,
                ProtocolVersion.CURRENT, "a", true));
        assertTrue(ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));

        Object second = new Object();
        ClientConnectionSession b = ClientSessionManager.join(second);
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus());
        assertFalse(ClientProtocolState.onHandshakeResponse(first, tokenA,
                ProtocolVersion.CURRENT, "stale", true));
        assertFalse(ClientProtocolState.isUsable());
        assertTrue(ClientProtocolState.onHandshakeResponse(second, b.token(), 4, "b", true));
        assertFalse(ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
        assertEquals(4, ClientProtocolState.getServerProtocolVersion());
    }

    @Test
    void connectionUsesIdentityEvenWhenEqualsAndHashCodeCollide() {
        Object first = new CollidingIdentity();
        Object second = new CollidingIdentity();
        ClientSessionManager.join(first);
        long token = ClientSessionManager.connectionToken();
        ClientSessionManager.join(second);
        assertFalse(ClientSessionManager.isCurrent(first, token));
        assertTrue(ClientSessionManager.isCurrent(second, ClientSessionManager.connectionToken()));
    }

    @Test
    void repeatedOldCloseCannotClearTheReplacementSession() {
        ClientConnectionSession first = ClientSessionManager.join(new Object());
        ClientConnectionSession second = ClientSessionManager.join(new Object());
        first.close();
        first.close();
        assertTrue(second.isOpen());
        assertSame(second, ClientSessionManager.current().orElseThrow());
    }

    @Test
    void pendingDisconnectFromOldConnectionCannotFinishTheNewConnection() {
        ClientConnectionSession first = ClientSessionManager.join(new Object());
        long pending = ClientSessionManager.invalidateConnection(first.connection());
        ClientConnectionSession second = ClientSessionManager.join(new Object());
        assertFalse(ClientSessionManager.finishDisconnect(pending));
        assertSame(second, ClientSessionManager.current().orElseThrow());
        assertTrue(second.isOpen());
    }

    private static final class CollidingIdentity {
        @Override public boolean equals(Object other) { return other instanceof CollidingIdentity; }
        @Override public int hashCode() { return 1; }
    }
}
