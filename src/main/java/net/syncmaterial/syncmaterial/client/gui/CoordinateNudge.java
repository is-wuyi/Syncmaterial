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
     * 输出 MaLiLib 的修饰键判断、GLFW 原始按键状态与物理鼠标键，用于排查步长异常。
     *
     * 已借此定位到一个平台行为：macOS 上原版 Mouse.onMouseButton 会把
     * Control + 左键改写成右键（GLFW_MOD_CONTROL 命中时 button 置 1），
     * 这是 macOS 的 Control+click 约定，Litematica 同样如此，无法在我们这层规避。
     * 因此当"物理左键按下但 mouseButton 报 1"时会额外打一行结论，免去逐字段比对。
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
            boolean physicalLeft = isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            boolean physicalRight = isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

            SyncMaterial.LOGGER.info(
                "[坐标步长] mouseButton={} 结果={} | MaLiLib: shift={} alt={} ctrl={} | "
                    + "GLFW 原始: leftCtrl={} rightCtrl={} leftAlt={} rightAlt={} "
                    + "leftShift={} rightShift={} leftSuper={} rightSuper={} "
                    + "鼠标左键={} 鼠标右键={}",
                mouseButton, result, shiftDown, altDown, GuiBase.isCtrlDown(),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT),
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER),
                isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER),
                physicalLeft, physicalRight);

            if (mouseButton == 1 && physicalLeft && !physicalRight)
            {
                SyncMaterial.LOGGER.info(
                    "[坐标步长] 物理左键被当成右键：这是 macOS 上 Control+左键=右键的系统约定，"
                        + "由原版 Mouse.onMouseButton 改写，Litematica 表现相同。"
                        + "请改用 Option(Alt) 或 Shift 作为倍率键。");
            }

            // 明确指出这次点击到底按的是哪个修饰键，避免事后回忆混淆
            boolean ctrlPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
            boolean altPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
            SyncMaterial.LOGGER.info(
                "[坐标步长] 本次按键判定 → Control(物理)={} Option/Alt(物理)={} "
                    + "Shift(物理)={} 最终步长={}",
                ctrlPhysical, altPhysical,
                isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT),
                result);
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
