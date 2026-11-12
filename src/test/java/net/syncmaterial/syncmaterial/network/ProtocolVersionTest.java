package net.syncmaterial.syncmaterial.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议版本协商逻辑测试。
 *
 * 只覆盖纯逻辑部分（版本比较、握手记录、清理），不涉及实际收发包 ——
 * 后者需要完整的 Minecraft 网络栈，由实机验证覆盖。
 */
class ProtocolVersionTest
{
    @BeforeEach
    @AfterEach
    void clearHandshakeState()
    {
        ProtocolHandshake.clearAll();
        ClientProtocolState.reset();
    }

    // ==================== 版本比较 ====================

    @Test
    void atLeast_comparesCorrectly()
    {
        assertTrue(ProtocolVersion.atLeast(5, 3));
        assertTrue(ProtocolVersion.atLeast(3, 3), "相等应视为满足");
        assertFalse(ProtocolVersion.atLeast(2, 3));
    }

    @Test
    void unhandshakedPeerFailsAnyFeatureCheck()
    {
        // 未握手的对端版本为 0，任何功能门槛都不该通过
        assertFalse(ProtocolVersion.atLeast(0, 1));
    }

    @Test
    void isClientAcceptable_respectsMinCompatible()
    {
        assertTrue(ProtocolVersion.isClientAcceptable(ProtocolVersion.MIN_COMPATIBLE));
        assertTrue(ProtocolVersion.isClientAcceptable(ProtocolVersion.MIN_COMPATIBLE + 1));
        assertFalse(ProtocolVersion.isClientAcceptable(ProtocolVersion.MIN_COMPATIBLE - 1));
    }

    @Test
    void minCompatibleNeverExceedsCurrent()
    {
        // 若最低要求高于自身版本，服务端会拒绝所有同版本客户端
        assertTrue(ProtocolVersion.MIN_COMPATIBLE <= ProtocolVersion.CURRENT,
                "MIN_COMPATIBLE 不得高于 CURRENT");
    }

    @Test
    void isPeerNewer_onlyWhenStrictlyGreater()
    {
        assertTrue(ProtocolVersion.isPeerNewer(ProtocolVersion.CURRENT + 1));
        assertFalse(ProtocolVersion.isPeerNewer(ProtocolVersion.CURRENT));
        assertFalse(ProtocolVersion.isPeerNewer(ProtocolVersion.CURRENT - 1));
    }

    // 服务端握手的归属／记录／清理回归见 ProtocolHandshakeTest。

    // ==================== 客户端状态 ====================

    @Test
    void clientState_startsPending()
    {
        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus());
        assertFalse(ClientProtocolState.isUsable());
        assertEquals(0, ClientProtocolState.getServerProtocolVersion());
    }

    @Test
    void clientState_acceptedBecomesUsable()
    {
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "0.3.0", true);
        assertEquals(ClientProtocolState.Status.ACCEPTED, ClientProtocolState.getStatus());
        assertTrue(ClientProtocolState.isUsable());
        assertEquals(ProtocolVersion.CURRENT, ClientProtocolState.getServerProtocolVersion());
        assertEquals("0.3.0", ClientProtocolState.getServerModVersion());
    }

    @Test
    void clientState_rejectedIsNotUsable()
    {
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT + 1, "0.4.0", false);
        assertEquals(ClientProtocolState.Status.REJECTED, ClientProtocolState.getStatus());
        assertFalse(ClientProtocolState.isUsable());
        // 版本信息仍需保留，用于给用户显示"服务端是哪个版本"
        assertEquals("0.4.0", ClientProtocolState.getServerModVersion());
    }

    @Test
    void clientState_serverSupports_requiresAcceptedStatus()
    {
        // 仅版本号够高不足以放行，必须先握手成功
        assertFalse(ClientProtocolState.serverSupports(1));

        ClientProtocolState.onHandshakeResponse(5, "x", true);
        assertTrue(ClientProtocolState.serverSupports(5));
        assertTrue(ClientProtocolState.serverSupports(3));
        assertFalse(ClientProtocolState.serverSupports(6));
    }

    @Test
    void clientState_rejectedServerSupportsNothing()
    {
        ClientProtocolState.onHandshakeResponse(99, "x", false);
        assertFalse(ClientProtocolState.serverSupports(1),
                "被拒绝时即使服务端版本很高也不该放行任何功能");
    }

    @Test
    void clientState_isServerNewer()
    {
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT + 1, "x", true);
        assertTrue(ClientProtocolState.isServerNewer());

        ClientProtocolState.reset();
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, "x", true);
        assertFalse(ClientProtocolState.isServerNewer());
    }

    @Test
    void clientState_reset_clearsEverything()
    {
        ClientProtocolState.onHandshakeResponse(7, "0.9.0", true);
        ClientProtocolState.reset();

        assertEquals(ClientProtocolState.Status.PENDING, ClientProtocolState.getStatus());
        assertEquals(0, ClientProtocolState.getServerProtocolVersion());
        assertEquals("", ClientProtocolState.getServerModVersion(),
                "换服时不应残留上一个服务器的版本信息");
    }

    @Test
    void clientState_nullModVersionBecomesEmpty()
    {
        ClientProtocolState.onHandshakeResponse(ProtocolVersion.CURRENT, null, true);
        assertEquals("", ClientProtocolState.getServerModVersion());
    }
}
