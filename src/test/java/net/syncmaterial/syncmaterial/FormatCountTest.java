package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.gui.MaterialListBase;

/**
 * MaterialListBase 格式化方法的纯逻辑测试。
 * 不依赖 Minecraft 运行时。
 */
public class FormatCountTest
{
    private static final String SHULKER = "潜影盒";

    // ========== getFormattedCountString（列表详细格式）==========

    @Test
    void detailed_smallCount_returnsPlainNumber()
    {
        assertEquals("50", MaterialListBase.getFormattedCountString(50, 64, SHULKER));
    }

    @Test
    void detailed_exactStack_containsStackInfo()
    {
        String result = MaterialListBase.getFormattedCountString(128, 64, SHULKER);
        assertTrue(result.contains("128"));
        assertTrue(result.contains("2 x 64"));
    }

    @Test
    void detailed_stacksPlusRemainder()
    {
        String result = MaterialListBase.getFormattedCountString(261, 64, SHULKER);
        assertTrue(result.contains("261"));
        assertTrue(result.contains("4 x 64"));
        assertTrue(result.contains("5"));
    }

    @Test
    void detailed_includesShulkerBoxCount()
    {
        String result = MaterialListBase.getFormattedCountString(2000, 64, SHULKER);
        assertTrue(result.contains(SHULKER), "应包含潜影盒标签");
    }

    @Test
    void detailed_nonStandardStackSize()
    {
        // 堆叠 16 的物品（如末影珍珠）
        String result = MaterialListBase.getFormattedCountString(48, 16, SHULKER);
        assertTrue(result.contains("3 x 16"));
    }

    @Test
    void detailed_maxStackSizeZero_defaultsTo64()
    {
        String result = MaterialListBase.getFormattedCountString(128, 0, SHULKER);
        assertTrue(result.contains("2 x 64"));
    }

    @Test
    void detailed_maxStackSizeNegative_defaultsTo64()
    {
        String result = MaterialListBase.getFormattedCountString(128, -1, SHULKER);
        assertTrue(result.contains("2 x 64"));
    }

    // ========== getFormattedCountStringHud（HUD 简洁格式）==========

    @Test
    void hud_smallCount_returnsPlainNumber()
    {
        assertEquals("50", MaterialListBase.getFormattedCountStringHud(50, 64, SHULKER));
    }

    @Test
    void hud_stacksPlusRemainder_usesParentheses()
    {
        String result = MaterialListBase.getFormattedCountStringHud(261, 64, SHULKER);
        assertEquals("261 (4 x 64 + 5)", result);
    }

    @Test
    void hud_exactStack_usesParentheses()
    {
        String result = MaterialListBase.getFormattedCountStringHud(128, 64, SHULKER);
        assertEquals("128 (2 x 64)", result);
    }

    @Test
    void hud_largeCount_showsShulkerBox()
    {
        // 1728 = 1 潜影盒
        String result = MaterialListBase.getFormattedCountStringHud(1728, 64, SHULKER);
        assertTrue(result.contains(SHULKER), "应包含潜影盒标签");
    }

    @Test
    void hud_noEqualsSign()
    {
        String result = MaterialListBase.getFormattedCountStringHud(261, 64, SHULKER);
        assertFalse(result.contains("="), "HUD 格式不应包含 = 号");
    }

    // ========== SortCriteria.fromStringStatic ==========

    @Test
    void sortCriteria_validName_roundTrip()
    {
        assertEquals(MaterialListBase.SortCriteria.COUNT_MISSING,
            MaterialListBase.SortCriteria.fromStringStatic("COUNT_MISSING"));
        assertEquals(MaterialListBase.SortCriteria.COUNT_WAREHOUSE,
            MaterialListBase.SortCriteria.fromStringStatic("COUNT_WAREHOUSE"));
    }

    @Test
    void sortCriteria_caseInsensitive()
    {
        assertEquals(MaterialListBase.SortCriteria.COUNT_AVAILABLE,
            MaterialListBase.SortCriteria.fromStringStatic("count_available"));
    }

    @Test
    void sortCriteria_unknownName_fallsBackToTotal()
    {
        assertEquals(MaterialListBase.SortCriteria.COUNT_TOTAL,
            MaterialListBase.SortCriteria.fromStringStatic("NOT_A_CRITERIA"));
        assertEquals(MaterialListBase.SortCriteria.COUNT_TOTAL,
            MaterialListBase.SortCriteria.fromStringStatic(""));
    }
}
