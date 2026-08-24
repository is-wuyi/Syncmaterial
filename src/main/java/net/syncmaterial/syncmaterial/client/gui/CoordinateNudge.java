package net.syncmaterial.syncmaterial.client.gui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;

import fi.dy.masa.malilib.gui.GuiBase;

import net.syncmaterial.syncmaterial.SyncMaterial;

/**
 * 坐标加减按钮的步长计算。
 *
 * 抽成单一来源，避免三个编辑界面各写一份而逐渐漂移
 * （历史上就出现过悬浮提示写 ±10/±100/±1000、代码实际是另一套的情况）。
 *
 * 倍率沿用 Litematica 的 GuiSubRegionConfiguration / GuiPlacementConfiguration：
 * Shift ×8、Alt ×4，两者可叠加（同时按下为 ×32）。
 */
public final class CoordinateNudge
{
    public static final int SHIFT_MULTIPLIER = 8;
    public static final int ALT_MULTIPLIER = 4;

    private CoordinateNudge() {}

    /**
     * 按当前修饰键计算步长。
     *
     * @param mouseButton 鼠标按键，1 为右键（减少），其余为增加
     */
    public static int amount(int mouseButton)
    {
        boolean shiftDown = GuiBase.isShiftDown();
        boolean altDown = GuiBase.isAltDown();
        int result = amount(mouseButton, shiftDown, altDown);

        logDiagnostics(mouseButton, shiftDown, altDown, result);

        return result;
    }

    /**
     * 纯函数形式，便于在没有客户端实例的环境下测试。
     */
    public static int amount(int mouseButton, boolean shiftDown, boolean altDown)
    {
        int amount = mouseButton == 1 ? -1 : 1;

        if (shiftDown)
        {
            amount *= SHIFT_MULTIPLIER;
        }
        if (altDown)
        {
            amount *= ALT_MULTIPLIER;
        }

        return amount;
    }

    /**
     * 排查 Alt 修饰键失效：同时输出 MaLiLib 的判断结果与 GLFW 原始按键状态。
     *
     * 两者不一致说明 MaLiLib/原版的判断有问题；两者都为 false 则说明
     * 系统层（如 macOS 的 Option 键）没有把按键状态传给窗口。
     * mouseButton 一并打印，用于确认左键是否被改写成了右键。
     */
    private static void logDiagnostics(int mouseButton, boolean shiftDown, boolean altDown, int result)
    {
        try
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getWindow() == null)
            {
                return;
            }

            long window = mc.getWindow().getHandle();

            SyncMaterial.LOGGER.info(
                "[坐标步长] mouseButton={} 结果={} | MaLiLib: shift={} alt={} | "
                    + "GLFW 原始: leftAlt={} rightAlt={} leftShift={} rightShift={} "
                    + "leftSuper={} rightSuper={} 鼠标左键={} 鼠标右键={}",
                mouseButton, result, shiftDown, altDown,
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER),
                isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_LEFT),
                isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        }
        catch (Exception e)
        {
            // 诊断代码不得影响正常功能
            SyncMaterial.LOGGER.warn("[坐标步长] 诊断日志输出失败", e);
        }
    }

    private static boolean isKeyDown(long window, int key)
    {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private static boolean isMouseDown(long window, int button)
    {
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }
}
