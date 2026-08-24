package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;

/**
 * 准星选区的"编辑上下文"语义测试。
 *
 * 背景：编辑时若正式渲染和选区渲染同时绘制同一区域，画面上会出现两个重叠的框
 * （原色框 + 预览框），实机表现为"原框没变、旁边多出一个绿框"。
 * 修法是选区持有被编辑对象的标识，正式渲染据此跳过它。
 * 这两者必须严格配对：跳过条件为真时选区必须在画，否则那个框会凭空消失。
 */
public class SelectorEditContextTest
{
    @AfterEach
    void tearDown()
    {
        // 单例状态跨用例共享，必须复位
        StagingAreaSelector.getInstance().reset();
    }

    @Test
    void inactiveSelector_skipsNothing()
    {
        var selector = StagingAreaSelector.getInstance();

        assertFalse(selector.isEditingWarehouse(1),
            "未激活时不得让正式渲染跳过任何仓库，否则线框会凭空消失");
        assertFalse(selector.isEditingStagingArea("s1", "备货区A"),
            "未激活时不得让正式渲染跳过任何备货区");
    }

    @Test
    void editingWarehouse_skipsOnlyThatWarehouse()
    {
        var selector = StagingAreaSelector.getInstance();
        selector.start(dummyCallback(), null, "仓库A", null, null,
            StagingAreaSelector.TargetType.WAREHOUSE, null, 7);

        assertTrue(selector.isEditingWarehouse(7), "正在编辑的仓库应交由选区渲染");
        assertFalse(selector.isEditingWarehouse(8), "其他仓库必须照常渲染");
        assertFalse(selector.isEditingStagingArea("s1", "仓库A"),
            "类型是仓库，不应影响备货区渲染");
    }

    @Test
    void creatingWarehouse_skipsNothing()
    {
        var selector = StagingAreaSelector.getInstance();
        // 新建：用仓库配色但没有目标 ID
        selector.start(dummyCallback(), null, null, null, null,
            StagingAreaSelector.TargetType.WAREHOUSE, null, -1);

        assertFalse(selector.isEditingWarehouse(-1),
            "新建没有目标仓库，-1 不得被当成有效 ID 而跳过某个仓库");
        assertFalse(selector.isEditingWarehouse(7));
    }

    @Test
    void editingStagingArea_matchesBySchematicAndBoxName()
    {
        var selector = StagingAreaSelector.getInstance();
        selector.start(dummyCallback(), null, "备货区A", null, null,
            StagingAreaSelector.TargetType.STAGING_AREA, "s1", -1);

        assertTrue(selector.isEditingStagingArea("s1", "备货区A"));
        assertFalse(selector.isEditingStagingArea("s2", "备货区A"),
            "同名但属于其他原理图的备货区必须照常渲染");
        assertFalse(selector.isEditingStagingArea("s1", "备货区B"));
        assertFalse(selector.isEditingWarehouse(1),
            "类型是备货区，不应影响仓库渲染");
    }

    @Test
    void creatingStagingArea_skipsNothing()
    {
        var selector = StagingAreaSelector.getInstance();
        // 新建备货区走默认重载：无编辑目标
        selector.start(dummyCallback(), null, null, null, null);

        assertFalse(selector.isEditingStagingArea("s1", "备货区A"));
        assertFalse(selector.isEditingStagingArea(null, null),
            "上下文为空时不得匹配成功，否则会跳过渲染");
    }

    @Test
    void reset_clearsEditContext()
    {
        var selector = StagingAreaSelector.getInstance();
        selector.start(dummyCallback(), null, "仓库A", null, null,
            StagingAreaSelector.TargetType.WAREHOUSE, null, 7);
        assertTrue(selector.isEditingWarehouse(7));

        selector.reset();

        assertFalse(selector.isActive());
        assertFalse(selector.isEditingWarehouse(7),
            "断连重置后必须停止跳过，否则该仓库线框永久消失");
    }

    @Test
    void restart_replacesPreviousContext()
    {
        var selector = StagingAreaSelector.getInstance();
        selector.start(dummyCallback(), null, "仓库A", null, null,
            StagingAreaSelector.TargetType.WAREHOUSE, null, 7);
        selector.start(dummyCallback(), null, "备货区A", null, null,
            StagingAreaSelector.TargetType.STAGING_AREA, "s1", -1);

        assertFalse(selector.isEditingWarehouse(7),
            "重新启动选区必须覆盖上一次的上下文，不能两个目标同时生效");
        assertTrue(selector.isEditingStagingArea("s1", "备货区A"));
    }

    private static StagingAreaSelector.SelectionCallback dummyCallback()
    {
        return (boxName, pos1, pos2) -> { };
    }
}
