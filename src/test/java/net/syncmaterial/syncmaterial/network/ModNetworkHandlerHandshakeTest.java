package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Hello 握手的服务端生命周期：记录只发生在准入时捕获的 server/context 回调内，
 * 旧上下文关闭或连接更换后不得给新上下文记录/发包。
 */
class ModNetworkHandlerHandshakeTest {

    private MockedStatic<ServerPlayNetworking> networkingMock;
    private MockedStatic<LegacyServerRegistry> registryMock;
    private MockedStatic<SyncMaterial> syncMaterialMock;
    private LegacyNetworkTestSupport support;

    @BeforeAll
    static void setup() {
        LegacyNetworkTestSupport.bootstrap();
    }

    @BeforeEach
    void setUp() {
        support = new LegacyNetworkTestSupport("Player1");
        networkingMock = mockStatic(ServerPlayNetworking.class);
        registryMock = mockStatic(LegacyServerRegistry.class);
        syncMaterialMock = mockStatic(SyncMaterial.class);
        syncMaterialMock.when(SyncMaterial::getModVersion).thenReturn("test");
        registryMock.when(() -> LegacyServerRegistry.get(support.server))
                .thenAnswer(inv -> support.installed.get());
    }

    @AfterEach
    void tearDown() {
        syncMaterialMock.close();
        registryMock.close();
        networkingMock.close();
        support.close();
    }

    private ServerPlayNetworking.Context context() {
        var context = mock(ServerPlayNetworking.Context.class);
        when(context.server()).thenReturn(support.server);
        when(context.player()).thenReturn(support.player);
        return context;
    }

    private HelloC2SPacket hello() {
        return new HelloC2SPacket(ProtocolVersion.CURRENT, "test");
    }

    @Test
    void acceptedHello_recordsOnOwningServerAndSendsHello() {
        ModNetworkHandler.handleHello(hello(), context());

        assertTrue(ProtocolHandshake.hasHandshaked(support.player),
            "握手记录必须写在准入捕获的 server 上");
        networkingMock.verify(() -> ServerPlayNetworking.send(
            eq(support.player), any(HelloS2CPacket.class)));
        networkingMock.verify(() -> ServerPlayNetworking.send(
            eq(support.player), any(WarehouseAreaResponseS2CPacket.class)),
            atLeastOnce());
    }

    @Test
    void missingOwner_doesNotRecordOrSend() {
        registryMock.when(() -> LegacyServerRegistry.get(support.server)).thenReturn(null);

        ModNetworkHandler.handleHello(hello(), context());

        assertFalse(ProtocolHandshake.hasHandshaked(support.player));
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void ownerReplacedBeforeCallback_recordsNothingAndDoesNotTouchReplacement() {
        support.deferServerExecution.set(true);
        var replacement = mock(LegacyServerContext.class);

        ModNetworkHandler.handleHello(hello(), context());

        // 回调排队后旧上下文关闭、B 接管同一 server。
        support.closeOwnerAndInstall(replacement);
        registryMock.when(() -> LegacyServerRegistry.get(support.server)).thenReturn(replacement);
        support.flushQueuedServerTasks();

        assertFalse(ProtocolHandshake.hasHandshaked(support.player),
            "旧上下文排队中的握手不得写入新上下文");
        verifyNoInteractions(replacement);
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void originalConnectionReplacedBeforeCallback_recordsNothing() {
        support.deferServerExecution.set(true);

        ModNetworkHandler.handleHello(hello(), context());

        support.player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        support.flushQueuedServerTasks();

        assertFalse(ProtocolHandshake.hasHandshaked(support.player));
        networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
    }

    @Test
    void testRegistrationSeam_canCaptureReceiversRepeatedly() {
        ModNetworkHandler.registerReceiversForTests();
        ModNetworkHandler.registerReceiversForTests();

        networkingMock.verify(() -> ServerPlayNetworking.registerGlobalReceiver(
            eq(HelloC2SPacket.ID), any()), atLeastOnce());
        networkingMock.verify(() -> ServerPlayNetworking.registerGlobalReceiver(
            eq(MaterialStatsRequestC2SPacket.ID), any()), atLeastOnce());
        networkingMock.verify(() -> ServerPlayNetworking.registerGlobalReceiver(
            eq(WarehouseContainerRequestC2SPacket.ID), any()), atLeastOnce());
    }

    @Test
    void foundationRuntimeClosedAtAdmission_rejectsHello() {
        String previous = System.getProperty("syncmaterial.backend");
        System.setProperty("syncmaterial.backend", "foundation");
        ServerRuntime runtime = mock(ServerRuntime.class);
        when(runtime.isOpen()).thenReturn(false);
        try (MockedStatic<ServerRuntimeRegistry> runtimes = mockStatic(ServerRuntimeRegistry.class)) {
            runtimes.when(() -> ServerRuntimeRegistry.get(support.server)).thenReturn(runtime);

            ModNetworkHandler.handleHello(hello(), context());

            assertFalse(ProtocolHandshake.hasHandshaked(support.player));
            verify(runtime, never()).connected(any());
            networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
        } finally {
            restoreBackend(previous);
        }
    }

    @Test
    void foundationRuntimeClosedBeforeCallback_rejectsHello() {
        String previous = System.getProperty("syncmaterial.backend");
        System.setProperty("syncmaterial.backend", "foundation");
        ServerRuntime runtime = mock(ServerRuntime.class);
        when(runtime.isOpen()).thenReturn(true, false);
        try (MockedStatic<ServerRuntimeRegistry> runtimes = mockStatic(ServerRuntimeRegistry.class)) {
            runtimes.when(() -> ServerRuntimeRegistry.get(support.server)).thenReturn(runtime);
            support.deferServerExecution.set(true);

            ModNetworkHandler.handleHello(hello(), context());
            support.flushQueuedServerTasks();

            assertFalse(ProtocolHandshake.hasHandshaked(support.player));
            verify(runtime, never()).connected(any());
            networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
        } finally {
            restoreBackend(previous);
        }
    }

    private static void restoreBackend(String previous) {
        if (previous == null) {
            System.clearProperty("syncmaterial.backend");
        } else {
            System.setProperty("syncmaterial.backend", previous);
        }
    }

    @Test
    void foundationModeWithoutRuntime_doesNotFallBackToLegacyOwner() {
        String previous = System.getProperty("syncmaterial.backend");
        System.setProperty("syncmaterial.backend", "foundation");
        try {
            ModNetworkHandler.handleHello(hello(), context());

            assertFalse(ProtocolHandshake.hasHandshaked(support.player),
                "Foundation 运行时缺失时不得回退 legacy 上下文");
            networkingMock.verify(() -> ServerPlayNetworking.send(any(), any()), never());
        } finally {
            if (previous == null) {
                System.clearProperty("syncmaterial.backend");
            } else {
                System.setProperty("syncmaterial.backend", previous);
            }
        }
    }
}
