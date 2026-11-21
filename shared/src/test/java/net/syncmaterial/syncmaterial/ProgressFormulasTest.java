package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.api.ProgressFormulas;

/**
 * 材料进度公式测试。
 * 这些公式决定 HUD/GUI 显示的缺失数字，客户端与服务端共用。
 */
public class ProgressFormulasTest {

    // ========== collectedMissing（收集模式） ==========

    @Test
    void collectedMissing_normalCase() {
        assertEquals(30, ProgressFormulas.collectedMissing(100, 40, 20, 10));
    }

    @Test
    void collectedMissing_overCollected_clampsToZero() {
        // 备货区+仓库+背包已超过总数
        assertEquals(0, ProgressFormulas.collectedMissing(100, 60, 50, 10));
        assertEquals(0, ProgressFormulas.collectedMissing(10, 20, 0, 0));
    }

    @Test
    void collectedMissing_exactMatch_returnsZero() {
        assertEquals(0, ProgressFormulas.collectedMissing(100, 50, 30, 20));
    }

    @Test
    void collectedMissing_nothingCollected_returnsTotal() {
        assertEquals(100, ProgressFormulas.collectedMissing(100, 0, 0, 0));
    }

    @Test
    void collectedMissing_onlyStaging() {
        assertEquals(60, ProgressFormulas.collectedMissing(100, 40, 0, 0));
    }

    // ========== pickupMissing（搬运/取货模式） ==========

    @Test
    void pickupMissing_limitedByWarehouse() {
        // 需求 50 但仓库只有 30 → 只需搬 30
        assertEquals(30, ProgressFormulas.pickupMissing(100, 20, 0, 30));
    }

    @Test
    void pickupMissing_limitedByDemand() {
        // 需求 10，仓库有 50 → 只需搬 10
        assertEquals(10, ProgressFormulas.pickupMissing(100, 80, 10, 50));
    }

    @Test
    void pickupMissing_myInventoryReducesDemand() {
        // 总 100 - 备货区 50 - 我的背包 30 = 需求 20，仓库 40 → 20
        assertEquals(20, ProgressFormulas.pickupMissing(100, 50, 30, 40));
    }

    @Test
    void pickupMissing_emptyWarehouse_returnsZero() {
        assertEquals(0, ProgressFormulas.pickupMissing(100, 0, 0, 0));
    }

    @Test
    void pickupMissing_fullyStaged_returnsZero() {
        // 备货区已满足总数
        assertEquals(0, ProgressFormulas.pickupMissing(100, 100, 0, 64));
    }

    @Test
    void pickupMissing_negativeDemandClamped() {
        // 备货区+背包超过总需求
        assertEquals(0, ProgressFormulas.pickupMissing(100, 90, 30, 64));
    }

    // ========== inventoryOnlyMissing（纯背包版） ==========

    @Test
    void inventoryOnlyMissing_normalCase() {
        assertEquals(40, ProgressFormulas.inventoryOnlyMissing(100, 60));
    }

    @Test
    void inventoryOnlyMissing_overCount_clampsToZero() {
        assertEquals(0, ProgressFormulas.inventoryOnlyMissing(10, 30));
    }

    @Test
    void inventoryOnlyMissing_exactMatch() {
        assertEquals(0, ProgressFormulas.inventoryOnlyMissing(64, 64));
    }

    // ========== 边界、溢出与非法输入 ==========

    @Test
    void collectedMissing_saturatesAtLongMaxValue() {
        assertEquals(Long.MAX_VALUE, ProgressFormulas.collectedMissing(Long.MAX_VALUE, 0, 0, 0));
        assertEquals(Long.MAX_VALUE - 1, ProgressFormulas.collectedMissing(Long.MAX_VALUE, 1, 0, 0));
        assertEquals(Long.MAX_VALUE - 3, ProgressFormulas.collectedMissing(Long.MAX_VALUE, 1, 1, 1));
        assertEquals(0, ProgressFormulas.collectedMissing(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0));
        assertEquals(0, ProgressFormulas.collectedMissing(5, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE));
    }

    @Test
    void pickupMissing_saturatesAndNeverExceedsWarehouse() {
        assertEquals(Long.MAX_VALUE, ProgressFormulas.pickupMissing(Long.MAX_VALUE, 0, 0, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, ProgressFormulas.pickupMissing(Long.MAX_VALUE, 1, 0, Long.MAX_VALUE));
        assertEquals(0, ProgressFormulas.pickupMissing(Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(1, ProgressFormulas.pickupMissing(Long.MAX_VALUE, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertEquals(0, ProgressFormulas.pickupMissing(0, 0, 0, Long.MAX_VALUE));
    }

    @Test
    void inventoryOnlyMissing_saturatesAtZero() {
        assertEquals(Long.MAX_VALUE, ProgressFormulas.inventoryOnlyMissing(Long.MAX_VALUE, 0));
        assertEquals(0, ProgressFormulas.inventoryOnlyMissing(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, ProgressFormulas.inventoryOnlyMissing(0, Long.MAX_VALUE));
    }

    @Test
    void saturatingAddDoesNotWrap() {
        assertEquals(0, ProgressFormulas.saturatingAdd(0, 0));
        assertEquals(10, ProgressFormulas.saturatingAdd(4, 6));
        assertEquals(Long.MAX_VALUE - 1, ProgressFormulas.saturatingAdd(Long.MAX_VALUE - 2, 1));
        assertEquals(Long.MAX_VALUE, ProgressFormulas.saturatingAdd(Long.MAX_VALUE - 1, 2));
        assertEquals(Long.MAX_VALUE, ProgressFormulas.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void saturatingSubtractClampsAtZero() {
        assertEquals(Long.MAX_VALUE, ProgressFormulas.saturatingSubtract(Long.MAX_VALUE, 0));
        assertEquals(Long.MAX_VALUE - 1, ProgressFormulas.saturatingSubtract(Long.MAX_VALUE, 1));
        assertEquals(0, ProgressFormulas.saturatingSubtract(0, Long.MAX_VALUE));
        assertEquals(0, ProgressFormulas.saturatingSubtract(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void negativeInputsAreRejectedInsteadOfBeingClamped() {
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.collectedMissing(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.collectedMissing(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.collectedMissing(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.collectedMissing(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.pickupMissing(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.pickupMissing(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.pickupMissing(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.pickupMissing(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.inventoryOnlyMissing(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.inventoryOnlyMissing(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.saturatingAdd(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.saturatingAdd(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.saturatingSubtract(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressFormulas.saturatingSubtract(0, -1));
    }
}
