package net.syncmaterial.syncmaterial.client.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.HelloS2CPacket;
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

    // ========== 会话发送端口：原连接 PacketSender 绑定 ==========

    @Test
    void sessionSendUsesCapturedTransportAndRejectsAfterClose() {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ClientConnectionSession session = ClientSessionManager.join(new Object(), sent::add);
        CustomPacketPayload payload = new HelloS2CPacket(ProtocolVersion.CURRENT, "test", true);

        assertTrue(session.sendPacket(payload), "开放会话必须通过捕获的发送端口发包");
        assertEquals(List.of(payload), sent);

        session.close();
        assertFalse(session.sendPacket(payload), "关闭后的会话必须先拒发送");
        assertEquals(1, sent.size(), "关闭后不得再发出任何包");
    }

    @Test
    void oldSessionSendCannotReachTheReplacementTransport() {
        List<CustomPacketPayload> sentA = new ArrayList<>();
        List<CustomPacketPayload> sentB = new ArrayList<>();
        ClientConnectionSession a = ClientSessionManager.join(new Object(), sentA::add);
        ClientConnectionSession b = ClientSessionManager.join(new Object(), sentB::add);
        CustomPacketPayload payload = new HelloS2CPacket(ProtocolVersion.CURRENT, "test", true);

        assertFalse(a.sendPacket(payload), "旧会话已随 JOIN 封闭，延迟发送不得再发出");
        assertTrue(sentB.isEmpty(), "旧会话的发送绝不能落到新连接的端口");
        assertTrue(b.sendPacket(payload));
        assertEquals(1, sentB.size());
        assertTrue(sentA.isEmpty());
        assertSame(b, ClientSessionManager.current().orElseThrow());
    }

    @Test
    void joinClosesPreviousBeforePublishingTheReplacement() {
        ClientConnectionSession a = ClientSessionManager.join(new Object());
        List<Optional<ClientConnectionSession>> observedDuringClose = new ArrayList<>();
        SyncMaterialList list = mock(SyncMaterialList.class);
        doAnswer(invocation -> {
            observedDuringClose.add(ClientSessionManager.current());
            return null;
        }).when(list).clearBindings();
        a.materialList(list);

        ClientConnectionSession b = ClientSessionManager.join(new Object());

        assertFalse(observedDuringClose.isEmpty(), "旧会话关闭应触发界面解绑回调");
        assertTrue(observedDuringClose.get(0).isEmpty(),
                "发布新 current 之前必须先封闭旧会话；过渡期不得暴露任何会话");
        assertFalse(a.isOpen());
        assertSame(b, ClientSessionManager.current().orElseThrow());
    }

    // ========== 窄 dispatcher：handler 绑定复核 ==========

    @Test
    void dispatchRequiresTheBoundOpenConnection() {
        Object conn = new Object();
        assertFalse(ClientSessionManager.dispatch(conn, session -> fail("无会话时不得执行业务")),
                "无会话时 dispatch 必须拒绝");
        assertTrue(ClientSessionManager.current(conn).isEmpty());

        ClientConnectionSession session = ClientSessionManager.join(conn);
        List<ClientConnectionSession> received = new ArrayList<>();
        assertTrue(ClientSessionManager.dispatch(conn, received::add));
        assertSame(session, received.get(0));
        assertSame(session, ClientSessionManager.current(conn).orElseThrow());

        Object other = new Object();
        ClientSessionManager.join(other);
        assertFalse(ClientSessionManager.dispatch(conn, s -> fail("旧 handler 不得命中新会话")),
                "换连接后旧 handler 必须被拒绝");

        long pending = ClientSessionManager.invalidateConnection(other);
        assertFalse(ClientSessionManager.dispatch(other, s -> fail("失效待回收会话不得执行业务")),
                "断开待清理阶段同样必须拒绝");
        assertTrue(ClientSessionManager.current(other).isEmpty());
        assertTrue(ClientSessionManager.finishDisconnect(pending));

        ClientConnectionSession rejoined = ClientSessionManager.join(other);
        assertTrue(ClientSessionManager.dispatch(other, received::add));
        assertSame(rejoined, received.get(received.size() - 1));
    }

    @Test
    void currentHidesInvalidatedPendingSessionAndPickupStaysInactive() {
        ClientConnectionSession session = ClientSessionManager.join(new Object());
        long pending = ClientSessionManager.invalidateConnection();

        assertTrue(ClientSessionManager.current().isEmpty(),
                "已失效但仍待回收的会话不得作为 current 暴露");

        // 无会话 pickup：一次性空对象，写入不激活、查询恒为空/false
        var orphan = ClientSessionManager.pickup();
        assertFalse(orphan.isActive());
        assertTrue(orphan.getNeeds().isEmpty());
        orphan.setActive(true);
        orphan.recompute(List.of());
        assertFalse(ClientSessionManager.pickup().isActive(), "无会话写入不得激活任何共享状态");
        assertTrue(ClientSessionManager.pickup().getNeededItemIds().isEmpty());

        assertTrue(ClientSessionManager.finishDisconnect(pending));
        assertFalse(session.isOpen());
    }

    @Test
    void directHandshakeWithoutSessionLeavesNoWritableFallback() {
        // 无会话 direct handshake/reset：不得产生可写 DISCONNECTED fallback
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "orphan", true);
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus());
        assertEquals(0, ClientProtocolState.getServerProtocolVersion());
        assertEquals("", ClientProtocolState.getServerModVersion());
        assertFalse(ClientProtocolState.isUsable());
        assertFalse(ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));

        ClientProtocolState.reset();
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus(),
                "无会话 reset 不得创建可写 fallback");

        // 显式 join 后 direct entry 才生效（仅供测试；网络路径必须绑定原 handler）
        ClientSessionManager.join(new Object());
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "joined", true);
        assertTrue(ClientProtocolState.isUsable());
        assertEquals("joined", ClientProtocolState.getServerModVersion());
    }

    @Test
    void generationCounterContinuesAcrossJoinsWithoutSecondCounter() {
        ClientConnectionSession a = ClientSessionManager.join(new Object());
        long first = a.token();
        ClientConnectionSession b = ClientSessionManager.join(new Object());
        assertEquals(first + 1, b.token(), "generation 必须延续同一计数器");
        assertEquals(b.token(), ClientSessionManager.connectionToken());
    }

    private static final class CollidingIdentity {
        @Override public boolean equals(Object other) { return other instanceof CollidingIdentity; }
        @Override public int hashCode() { return 1; }
    }
}
