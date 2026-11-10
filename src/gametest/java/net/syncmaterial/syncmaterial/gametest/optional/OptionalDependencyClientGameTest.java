package net.syncmaterial.syncmaterial.gametest.optional;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolHandshake;
import net.syncmaterial.syncmaterial.server.runtime.BackendMode;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Client acceptance test for a runtime where MaLiLib, Litematica and Syncmatica
 * are physically absent.
 *
 * <p>Without MaLiLib, {@code SyncMaterialClientBootstrap} deliberately returns
 * before touching any MaLiLib/Litematica type, so no syncmaterial client receiver,
 * HUD, GUI or JOIN handshake is registered. This test verifies that the client
 * still boots to the main menu and can join a dedicated server, that it never
 * sends the handshake, and that the server therefore treats it as a client without
 * the mod and sends no business payloads. Reaching the connected state without a
 * crash is the observable proof that no optional type was class-loaded.</p>
 *
 * <p>No optional mod type and no SyncMaterial GUI type is referenced here. The
 * only client state read is {@link ClientProtocolState}, which is plain Java.</p>
 */
public class OptionalDependencyClientGameTest implements FabricClientGameTest {
    private static final List<String> OPTIONAL_MODS = List.of("malilib", "litematica", "syncmatica");

    @Override
    public void runTest(ClientGameTestContext context) {
        assertOptionalModsMissing();

        boolean mainMenu = context.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) instanceof TitleScreen
                        && client.level == null && client.player == null);
        if (!mainMenu) {
            throw new AssertionError("客户端未处于已启动的主菜单");
        }
        if (ClientProtocolState.getStatus() != ClientProtocolState.Status.PENDING
                || ClientProtocolState.getServerProtocolVersion() != 0) {
            throw new AssertionError("进入服务器前协议状态应为 PENDING/0，实际 "
                    + ClientProtocolState.getStatus() + "/" + ClientProtocolState.getServerProtocolVersion());
        }

        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            server.computeOnServer(instance -> {
                if (BackendMode.isFoundationEnabled()) {
                    ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                    if (runtime == null || !runtime.isOpen()) {
                        throw new AssertionError("foundation 模式必须安装打开的新 runtime");
                    }
                    if (SyncMaterial.getSharedDatabase() != null) {
                        throw new AssertionError("foundation 模式不得打开旧数据库");
                    }
                } else {
                    if (SyncMaterial.getSharedDatabase() == null) {
                        throw new AssertionError("legacy 模式必须初始化旧数据库");
                    }
                    if (ServerRuntimeRegistry.get(instance) != null) {
                        throw new AssertionError("legacy 模式不得安装新 runtime");
                    }
                }
                return null;
            });

            context.waitTicks(40);
            assertConnectedClient(context);
            assertNoHandshake(server, "连接后");

            // 再等一段时间：即使握手逻辑被错误地延迟注册/延迟触发，也必须在
            // 无 MaLiLib 的客户端上保持不发送。
            context.waitTicks(40);
            assertConnectedClient(context);
            assertNoHandshake(server, "稳定后");

            boolean pending = context.computeOnClient(mc ->
                    ClientProtocolState.getStatus() == ClientProtocolState.Status.PENDING
                            && ClientProtocolState.getServerProtocolVersion() == 0
                            && ClientProtocolState.getServerModVersion().isEmpty());
            if (!pending) {
                throw new AssertionError("无 MaLiLib 客户端不应收到服务端握手回应，实际状态 "
                        + context.computeOnClient(mc -> ClientProtocolState.getStatus() + "/"
                        + ClientProtocolState.getServerProtocolVersion() + "/"
                        + ClientProtocolState.getServerModVersion()));
            }
        }
    }

    private static void assertConnectedClient(ClientGameTestContext context) {
        boolean ready = context.computeOnClient(mc -> mc.player != null && mc.level != null);
        if (!ready) {
            throw new AssertionError("客户端未在无 MaLiLib 环境下完成进服（player/level 为空）");
        }
    }

    /**
     * 服务端只应在收到 HelloC2S 后记录握手并推送业务包。无 MaLiLib 客户端从不
     * 注册 JOIN 监听，所以这里必须始终是“未握手”，服务端据此不会发送非法包。
     */
    private static void assertNoHandshake(TestDedicatedServerContext server, String phase) {
        server.computeOnServer(instance -> {
            var players = instance.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                throw new AssertionError(phase + "：测试服务器上找不到客户端玩家");
            }
            var player = players.get(0);
            if (ProtocolHandshake.hasHandshaked(player.getUUID())
                    || ProtocolHandshake.getVersion(player.getUUID()) != 0) {
                throw new AssertionError(phase + "：无 MaLiLib 客户端不应主动握手，服务端却记录了版本 "
                        + ProtocolHandshake.getVersion(player.getUUID()));
            }
            return null;
        });
    }

    private static void assertOptionalModsMissing() {
        for (String modId : OPTIONAL_MODS) {
            if (FabricLoader.getInstance().isModLoaded(modId)) {
                throw new AssertionError("缺可选依赖验收环境不得加载 " + modId);
            }
        }
        if (!FabricLoader.getInstance().isModLoaded("syncmaterial")) {
            throw new AssertionError("验收环境必须加载 syncmaterial 本体");
        }
    }
}
