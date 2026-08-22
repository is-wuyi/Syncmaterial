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
}
