//? if >=26 {
package net.syncmaterial.syncmaterial.client.gui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;

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
     * 步长诊断，debug 级别。
     *
     * 平时不输出，遇到"按了修饰键但步长不对"时把日志级别开到 debug 即可定位。
     * 同时打印 MaLiLib 的判断值与 GLFW 原始按键状态，两者不一致就说明
     * 中间有一层在改写，例如下面这两个 macOS 平台行为：
     *
     * 1. Screen.hasControlDown 在 macOS 上检测的是 Command(⌘) 而非 Control(⌃)，
     *    所以按住 Control 时 isCtrlDown() 仍是 false。
     * 2. Mouse.onMouseButton 会把 Control + 左键改写成右键
     *    （mods 命中 GLFW_MOD_CONTROL 时 button 置 1），这是 Control+click 的系统约定。
     *
     * 两者叠加的结果：Control + 左键等于一个不带倍率的右键，恒为 -1。
     * Litematica 跑在同一套原版逻辑上，表现完全相同，无法在我们这层规避。
     */
    private static void logDiagnostics(int mouseButton, boolean shiftDown, boolean altDown, int result)
    {
        if (!SyncMaterial.LOGGER.isDebugEnabled())
        {
            return;
        }

        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null)
            {
                return;
            }

            long window = mc.getWindow().getHandle();
            boolean physicalLeft = isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            boolean physicalRight = isMouseDown(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            boolean ctrlPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
            boolean altPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
            boolean shiftPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
            boolean superPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER);

            SyncMaterial.LOGGER.debug(
                "[坐标步长] 步长={} mouseButton={} | 物理按键: Ctrl={} Alt={} Shift={} Super={} "
                    + "鼠标左={} 鼠标右={} | MaLiLib 判断: ctrl={} alt={} shift={}",
                result, mouseButton,
                ctrlPhysical, altPhysical, shiftPhysical, superPhysical,
                physicalLeft, physicalRight,
                GuiBase.isCtrlDown(), altDown, shiftDown);

            if (mouseButton == 1 && physicalLeft && !physicalRight)
            {
                SyncMaterial.LOGGER.debug(
                    "[坐标步长] 物理左键被当成右键：macOS 上 Control+左键=右键的系统约定，"
                        + "由原版 Mouse.onMouseButton 改写。倍率键请用 Shift 或 Option(Alt)。");
            }
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
//?} else {
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
     * 步长诊断，debug 级别。
     *
     * 平时不输出，遇到"按了修饰键但步长不对"时把日志级别开到 debug 即可定位。
     * 同时打印 MaLiLib 的判断值与 GLFW 原始按键状态，两者不一致就说明
     * 中间有一层在改写，例如下面这两个 macOS 平台行为：
     *
     * 1. Screen.hasControlDown 在 macOS 上检测的是 Command(⌘) 而非 Control(⌃)，
     *    所以按住 Control 时 isCtrlDown() 仍是 false。
     * 2. Mouse.onMouseButton 会把 Control + 左键改写成右键
     *    （mods 命中 GLFW_MOD_CONTROL 时 button 置 1），这是 Control+click 的系统约定。
     *
     * 两者叠加的结果：Control + 左键等于一个不带倍率的右键，恒为 -1。
     * Litematica 跑在同一套原版逻辑上，表现完全相同，无法在我们这层规避。
     */
    private static void logDiagnostics(int mouseButton, boolean shiftDown, boolean altDown, int result)
    {
        if (!SyncMaterial.LOGGER.isDebugEnabled())
        {
            return;
        }

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
            boolean ctrlPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
            boolean altPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
            boolean shiftPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
            boolean superPhysical = isKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER)
                    || isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER);

            SyncMaterial.LOGGER.debug(
                "[坐标步长] 步长={} mouseButton={} | 物理按键: Ctrl={} Alt={} Shift={} Super={} "
                    + "鼠标左={} 鼠标右={} | MaLiLib 判断: ctrl={} alt={} shift={}",
                result, mouseButton,
                ctrlPhysical, altPhysical, shiftPhysical, superPhysical,
                physicalLeft, physicalRight,
                GuiBase.isCtrlDown(), altDown, shiftDown);

            if (mouseButton == 1 && physicalLeft && !physicalRight)
            {
                SyncMaterial.LOGGER.debug(
                    "[坐标步长] 物理左键被当成右键：macOS 上 Control+左键=右键的系统约定，"
                        + "由原版 Mouse.onMouseButton 改写。倍率键请用 Shift 或 Option(Alt)。");
            }
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
//?}
