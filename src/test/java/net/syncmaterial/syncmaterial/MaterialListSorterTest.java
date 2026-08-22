package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase.SortCriteria;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListSorter;

/**
 * 材料列表排序测试。名字次序依赖语言环境，因此不硬编码 tiebreak 的具体顺序，
 * 只断言主键排序方向、reverse 翻转、tiebreak 非零且对称。
 */
public class MaterialListSorterTest {

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static MaterialListEntry entry(int dbId, ItemStack stack, int total, int missing,
            int available, int staging, int warehouse, int otherPlayers, int participants) {
        MaterialListEntry e = new MaterialListEntry(dbId, stack, total, missing, 0, available);
        e.setStagingCount(staging);
        e.setWarehouseCount(warehouse);
        e.setOtherPlayersCount(otherPlayers);
        e.setParticipants(java.util.List.of());
        if (participants > 0) {
            e.setParticipants(java.util.List.copyOf(
                java.util.Collections.nCopies(participants, new MaterialListEntry.ParticipantData("p", 1))));
        }
        return e;
    }

    private static MaterialListSorter sorterOf(SortCriteria criteria, boolean reverse) {
        MaterialListBase base = mock(MaterialListBase.class);
        when(base.getSortCriteria()).thenReturn(criteria);
        when(base.getSortInReverse()).thenReturn(reverse);
        return new MaterialListSorter(base);
    }

    private static MaterialListEntry stone(int total, int secondary) {
        return entry(1, new ItemStack(Items.STONE), total, secondary, secondary, secondary, secondary, secondary, 1);
    }

    private static MaterialListEntry diamond(int total, int secondary) {
        return entry(2, new ItemStack(Items.DIAMOND), total, secondary, secondary, secondary, secondary, secondary, 1);
    }

    @Test
    void countTotal_descendingAndReverseFlips() {
        var sorter = sorterOf(SortCriteria.COUNT_TOTAL, false);
        var a = stone(100, 0);
        var b = diamond(50, 0);
        assertTrue(sorter.compare(a, b) < 0, "总数大的排前面");
        assertTrue(sorter.compare(b, a) > 0);

        var reversed = sorterOf(SortCriteria.COUNT_TOTAL, true);
        assertTrue(reversed.compare(a, b) > 0, "reverse 后总数小的排前面");
    }

    @Test
    void countMissing_descending() {
        var sorter = sorterOf(SortCriteria.COUNT_MISSING, false);
        assertTrue(sorter.compare(stone(0, 100), diamond(0, 40)) < 0, "缺得多的排前面");
    }

    @Test
    void countAvailable_descending() {
        var sorter = sorterOf(SortCriteria.COUNT_AVAILABLE, false);
        assertTrue(sorter.compare(stone(0, 80), diamond(0, 10)) < 0);
    }

    @Test
    void countStaging_descending() {
        var sorter = sorterOf(SortCriteria.COUNT_STAGING, false);
        assertTrue(sorter.compare(stone(0, 60), diamond(0, 5)) < 0);
    }

    @Test
    void countWarehouse_descending() {
        var sorter = sorterOf(SortCriteria.COUNT_WAREHOUSE, false);
        assertTrue(sorter.compare(stone(0, 64), diamond(0, 0)) < 0);
    }

    @Test
    void countOther_descending() {
        var sorter = sorterOf(SortCriteria.COUNT_OTHER, false);
        assertTrue(sorter.compare(stone(0, 30), diamond(0, 2)) < 0);
    }

    @Test
    void countClaim_byParticipantCount() {
        var sorter = sorterOf(SortCriteria.COUNT_CLAIM, false);
        var one = entry(1, new ItemStack(Items.STONE), 0, 0, 0, 0, 0, 0, 1);
        var three = entry(2, new ItemStack(Items.DIAMOND), 0, 0, 0, 0, 0, 0, 3);
        assertTrue(sorter.compare(three, one) < 0, "参与者多的排前面");
    }

    @Test
    void equalPrimaryValue_fallsBackToName_nonZeroAndSymmetric() {
        var sorter = sorterOf(SortCriteria.COUNT_TOTAL, false);
        var a = stone(100, 0);
        var b = diamond(100, 0);

        int forward = sorter.compare(a, b);
        assertNotEquals(0, forward, "主值相同应按名字次序区分");
        assertEquals(-forward, sorter.compare(b, a), "tiebreak 应反对称");
    }

    @Test
    void unknownCriteria_fallsBackToNameSort() {
        var sorter = sorterOf(null, false);
        var a = stone(0, 0);
        var b = diamond(0, 0);

        int forward = sorter.compare(a, b);
        assertNotEquals(0, forward);
        assertEquals(-forward, sorterOf(null, true).compare(a, b), "reverse 应翻转名字次序");
    }
}
