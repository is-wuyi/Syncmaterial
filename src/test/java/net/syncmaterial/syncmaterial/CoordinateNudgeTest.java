package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.gui.CoordinateNudge;

/**
 * 坐标加减按钮的步长计算测试。
 *
 * 历史问题：三个编辑界面各写一份倍率逻辑，悬浮提示又是单独维护的文案，
 * 结果提示写着 ±10/±100/±1000，代码实际是另一套，长期不符。
 * 现在倍率收敛到 CoordinateNudge 单一来源，这些用例锁住它的语义。
 *
 * 倍率沿用 Litematica 的 GuiSubRegionConfiguration：Shift ×8、Alt ×4。
 */
public class CoordinateNudgeTest
{
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    @Test
    void noModifier_leftIncreasesByOne_rightDecreasesByOne()
    {
        assertEquals(1, CoordinateNudge.amount(LEFT, false, false));
        assertEquals(-1, CoordinateNudge.amount(RIGHT, false, false));
    }

    @Test
    void shift_multipliesByEight()
    {
        assertEquals(8, CoordinateNudge.amount(LEFT, true, false));
        assertEquals(-8, CoordinateNudge.amount(RIGHT, true, false),
            "右键必须保持减少方向，倍率只影响绝对值");
    }

    @Test
    void alt_multipliesByFour()
    {
        assertEquals(4, CoordinateNudge.amount(LEFT, false, true));
        assertEquals(-4, CoordinateNudge.amount(RIGHT, false, true));
    }

    @Test
    void shiftAndAlt_stack()
    {
        assertEquals(32, CoordinateNudge.amount(LEFT, true, true),
            "两个修饰键应相乘（8 × 4），与 Litematica 的叠加语义一致");
        assertEquals(-32, CoordinateNudge.amount(RIGHT, true, true));
    }

    @Test
    void multiplierConstants_matchLitematica()
    {
        // 文案直接引用这套数字，常量变了必须同步改三个语言文件
        assertEquals(8, CoordinateNudge.SHIFT_MULTIPLIER);
        assertEquals(4, CoordinateNudge.ALT_MULTIPLIER);
    }

    @Test
    void onlyButtonOne_treatedAsDecrease()
    {
        // MaLiLib 的滚轮也会走按钮回调，向下滚传 1、向上滚传 0；
        // 其余按键值（如中键 2）按增加处理，避免出现 0 步长
        assertEquals(1, CoordinateNudge.amount(2, false, false));
        assertEquals(-1, CoordinateNudge.amount(1, false, false));
    }
}
