package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;

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
        return amount(mouseButton, GuiBase.isShiftDown(), GuiBase.isAltDown());
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
}
