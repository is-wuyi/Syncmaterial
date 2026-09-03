package net.syncmaterial.syncmaterial.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.syncmaterial.syncmaterial.client.config.Configs;

/**
 * 配置持久化端到端测试：以新配置项 containerHighlightColor 为样本，
 * 验证「改值 → 保存到磁盘 → 内存篡改 → 从磁盘重载 → 恢复」的往返。
 *
 * 此前 Configs 的 save/load 整条链路没有任何测试——颜色配置保存后
 * 重启丢失这类问题只能靠玩家上报。用真实文件（gametest run 目录下的
 * config/syncmaterial.json）走完整 JSON 序列化，不 mock。
 *
 * finally 恢复原值并保存：配置文件是 run 目录里的共享状态，
 * 不能让本测试污染其他测试或后续运行。
 */
public class ConfigPersistenceClientGameTest implements FabricClientGameTest {

    private static final String SAVED = "#FF112233";
    private static final String TAMPERED = "#FF445566";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // saveToFile/loadFromFile 内部经 malilib 读取 MinecraftClient 单例，
        // 必须在客户端线程执行（gametest 线程直接调用会被 API 线程检查拦截）
        ctx.computeOnClient(client -> {
            runPersistenceAssertions();
            return true;
        });
    }

    private void runPersistenceAssertions() {
        var config = Configs.Render.CONTAINER_HIGHLIGHT_COLOR;
        String original = config.getStringValue();

        try {
            // ===== 改值并保存到磁盘 =====
            config.setValueFromString(SAVED);
            Configs.saveToFile();
            assertEquals(SAVED, config.getStringValue(), "改值后内存值应生效");

            // ===== 篡改内存值（模拟重启后尚未读盘的中间态）=====
            config.setValueFromString(TAMPERED);
            assertNotEquals(SAVED, config.getStringValue(), "篡改后内存值应与保存值不同");

            // ===== 从磁盘重载：必须恢复为保存值 =====
            Configs.loadFromFile();
            assertEquals(SAVED, config.getStringValue(), "重载后应恢复为保存的值");

            // ===== 渲染读取的颜色与配置一致（锁"渲染用配置而非硬编码"）=====
            String afterReload = config.getStringValue();
            var rendererColor = net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer
                .getInstance().getContainerHighlightColor();
            assertEquals(afterReload, toArgbString(rendererColor),
                "渲染器读取的颜色应与配置值一致");
        } finally {
            config.setValueFromString(original);
            Configs.saveToFile();
        }
    }

    /** Color4f → #AARRGGBB（malilib 自带格式化，与 ConfigColor 的字符串格式对齐） */
    private static String toArgbString(fi.dy.masa.malilib.util.data.Color4f color) {
        return color.toHexString();
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (java.util.Objects.equals(unexpected, actual)) {
            throw new AssertionError(message + "：不应为 " + unexpected);
        }
    }
}
