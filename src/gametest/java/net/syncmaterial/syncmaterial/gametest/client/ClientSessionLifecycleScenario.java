package net.syncmaterial.syncmaterial.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;

/** Real project reopen and fast A-to-B connection lifecycle for both backends. */
public final class ClientSessionLifecycleScenario {
    private ClientSessionLifecycleScenario() {}

    public interface Backend {
        void prepare(TestDedicatedServerContext server, String projectId);
        void cleanup(TestDedicatedServerContext server, String projectId);
    }

    public static void run(ClientGameTestContext ctx, Backend backend) {
        String projectA = "session-a-" + System.nanoTime();
        String projectB = "session-b-" + System.nanoTime();
        ClientConnectionSession old;
        long oldToken;
        Object oldConnection;
        var serverA = ctx.worldBuilder().createServer();
        var connectionA = serverA.connect();
        try (serverA; connectionA) {
            ctx.waitTicks(20);
            backend.prepare(serverA, projectA);
            old = openProjectAndVerifyHud(ctx, projectA);
            oldToken = old.token();
            oldConnection = old.connection();
            backend.cleanup(serverA, projectA);
        } finally {
            closeMaterialScreen(ctx);
        }

        var serverB = ctx.worldBuilder().createServer();
        var connectionB = serverB.connect();
        try (serverB; connectionB) {
            ctx.waitTicks(20);
            ClientConnectionSession current = ClientSessionManager.current().orElseThrow();
            if (current == old || !current.isOpen() || old.isOpen()) {
                throw new AssertionError("快速重连没有唯一封闭旧会话");
            }
            if (ClientSessionManager.isCurrent(oldConnection, oldToken)) {
                throw new AssertionError("旧连接身份仍被当前会话接受");
            }
            if (ClientProtocolState.onHandshakeResponse(oldConnection, oldToken, 4, "stale", true)) {
                throw new AssertionError("旧 Hello 被新连接接受");
            }
            if (ClientProtocolState.getServerProtocolVersion() != ProtocolVersion.CURRENT) {
                throw new AssertionError("旧 Hello 改写了新连接能力");
            }
            if (SyncMaterialClient.getActiveMaterialList() != null
                    || InventoryWatcher.isWatching()
                    || ClientSessionManager.pickup().isActive()) {
                throw new AssertionError("旧项目材料／背包／取货状态泄漏到新连接");
            }

            backend.prepare(serverB, projectB);
            openProjectAndVerifyHud(ctx, projectB);
            ctx.runOnClient(client -> old.close());
            if (!current.isOpen() || ClientSessionManager.current().orElseThrow() != current
                    || SyncMaterialClient.getActiveMaterialList() == null) {
                throw new AssertionError("重复关闭旧会话清除了新会话");
            }
            backend.cleanup(serverB, projectB);
        } finally {
            closeMaterialScreen(ctx);
        }
    }

    private static ClientConnectionSession openProjectAndVerifyHud(ClientGameTestContext ctx, String projectId) {
        ClientConnectionSession session = ClientSessionManager.current().orElseThrow();
        if (!ctx.computeOnClient(client -> SyncMaterialClient.requestMaterialList(projectId))) {
            throw new AssertionError("无法请求真实项目材料清单: " + projectId);
        }
        ctx.waitForScreen(GuiMaterialList.class);
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("项目未收到协议5权威进度: " + projectId);
        }

        GuiMaterialList first = ctx.computeOnClient(client ->
                (GuiMaterialList) ClientScreenAccess.currentScreen(client));
        first.getMaterialList().getHudRenderer().setShouldRender(false);
        ctx.runOnClient(client -> first.closeGui(false));
        ctx.waitFor(client -> ClientScreenAccess.currentScreen(client) == null);
        if (session.materialList() == null || !session.materialList().hasProjectProgress()
                || !InventoryWatcher.isWatching()) {
            throw new AssertionError("正常关闭GUI错误地停止了项目数据流");
        }
        if (bindingPresent(first.getMaterialList(), "onStatusUpdate")
                || bindingPresent(first.getMaterialList(), "onMaterialCatalogChanged")) {
            throw new AssertionError("关闭GUI后仍保留旧界面的数据回调");
        }

        if (!ctx.computeOnClient(client -> SyncMaterialClient.requestMaterialList(projectId))) {
            throw new AssertionError("无法重新打开真实项目: " + projectId);
        }
        ctx.waitForScreen(GuiMaterialList.class);
        GuiMaterialList reopened = ctx.computeOnClient(client ->
                (GuiMaterialList) ClientScreenAccess.currentScreen(client));
        if (reopened.getMaterialList().getHudRenderer().getShouldRender()) {
            throw new AssertionError("协议5重开路径丢失了HUD分闸");
        }
        if (session.hudVisible()) {
            throw new AssertionError("会话没有保存HUD分闸");
        }
        return session;
    }

    private static boolean bindingPresent(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target) != null;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取绑定字段 " + name, failure);
        }
    }

    private static void closeMaterialScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui) gui.closeGui(false);
            else ClientScreenAccess.showScreen(client, null);
        });
    }

    private static boolean waitFor(ClientGameTestContext ctx,
                                   java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 200; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }
}
