package net.syncmaterial.syncmaterial.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

/**
 * 客户端 GameTest 烟雾测试：验证客户端能启动、能连上专用服务器、断言机制工作。
 *
 * 这是客户端 GameTest 框架的链路打通测试，不绑定任何具体 bug。
 * 跑通它之后，针对具体客户端逻辑的测试才有意义。
 *
 * 运行方式：./gradlew runClientGameTest（需要显示器；CI 需 xvfb）
 */
public class SmokeTestClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (var server = ctx.worldBuilder().createServer();
             var conn = server.connect()) {

            // 等待客户端世界加载完成、玩家就位
            ctx.waitTicks(40);

            Boolean playerReady = ctx.computeOnClient(mc ->
                mc.player != null && mc.level != null);

            if (playerReady == null || !playerReady) {
                throw new AssertionError("客户端玩家或世界未在 40 tick 内就绪");
            }

            // 能走到这里说明：客户端启动了、连上了服务器、世界加载了、玩家生成了，
            // 断言机制工作正常——链路打通
        }
    }
}
