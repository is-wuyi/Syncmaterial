package net.syncmaterial.syncmaterial.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表头列索引 → 排序标准映射的测试。
 * 点击表头与测试共用 MaterialListBase.criteriaForColumn，这里锁住
 * 8 个可排序列的映射与非法列的 null 语义（映射错了表头会排错列）。
 */
class MaterialListHeaderColumnTest {

    @Test
    void allEightColumns_mapToExpectedCriteria()
    {
        assertEquals(MaterialListBase.SortCriteria.NAME, MaterialListBase.criteriaForColumn(0));
        assertEquals(MaterialListBase.SortCriteria.COUNT_TOTAL, MaterialListBase.criteriaForColumn(1));
        assertEquals(MaterialListBase.SortCriteria.COUNT_MISSING, MaterialListBase.criteriaForColumn(2));
        assertEquals(MaterialListBase.SortCriteria.COUNT_AVAILABLE, MaterialListBase.criteriaForColumn(3));
        assertEquals(MaterialListBase.SortCriteria.COUNT_OTHER, MaterialListBase.criteriaForColumn(4));
        assertEquals(MaterialListBase.SortCriteria.COUNT_STAGING, MaterialListBase.criteriaForColumn(5));
        assertEquals(MaterialListBase.SortCriteria.COUNT_WAREHOUSE, MaterialListBase.criteriaForColumn(6));
        assertEquals(MaterialListBase.SortCriteria.COUNT_CLAIM, MaterialListBase.criteriaForColumn(7));
    }

    @Test
    void invalidColumns_returnNull()
    {
        assertNull(MaterialListBase.criteriaForColumn(-1));
        assertNull(MaterialListBase.criteriaForColumn(8));
        assertNull(MaterialListBase.criteriaForColumn(100));
    }
}
