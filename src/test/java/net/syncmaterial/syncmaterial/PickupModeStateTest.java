package net.syncmaterial.syncmaterial;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.PickupModeState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取货模式状态机测试。
 *
 * 这些用例锁定的是重构前实机踩到的三类问题：需求量随数据变化及时更新
 * （原先挂在 HUD 渲染上，HUD 关掉就永不更新）、退出与清理会真正清空
 * （原先残留导致换服后仍高亮）、线框过滤与格子高亮共用同一份需求。
 */
class PickupModeStateTest {

    /** 脱离 MC 客户端的材料快照 */
    private record Snapshot(String itemId, int countTotal, int stagingCount,
                            int myCount, int warehouseCount, boolean claimedByCurrentPlayer)
        implements PickupModeState.MaterialSnapshot {}

    @AfterEach
    void tearDown() {
        PickupModeState.clear();
    }

    @Test
    void inactive_recomputeYieldsEmpty() {
        PickupModeState.setActive(false);
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        assertTrue(PickupModeState.getNeeds().isEmpty(), "未激活时不应产生需求");
    }

    @Test
    void active_computesPickupMissingCappedByWarehouse() {
        PickupModeState.setActive(true);
        // 需求 = min(总数 - 备货区 - 我的, 仓库存量) = min(64-10-4, 20) = 20
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 10, 4, 20, true)));
        assertEquals(Map.of("minecraft:stone", 20), PickupModeState.getNeeds());
    }

    @Test
    void unclaimedMaterials_excluded() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(
            new Snapshot("minecraft:stone", 64, 0, 0, 64, true),
            new Snapshot("minecraft:dirt", 64, 0, 0, 64, false)));
        assertEquals(Map.of("minecraft:stone", 64), PickupModeState.getNeeds(),
            "未认领的材料不该进入需求：格子高亮与仓库线框须同口径");
    }

    @Test
    void sameItemAcrossEntries_summed() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(
            new Snapshot("minecraft:stone", 10, 0, 0, 10, true),
            new Snapshot("minecraft:stone", 5, 0, 0, 5, true)));
        assertEquals(Map.of("minecraft:stone", 15), PickupModeState.getNeeds());
    }

    @Test
    void fullyStocked_dropsOutOfNeeds() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 64, 64, true)));
        assertTrue(PickupModeState.getNeeds().isEmpty(), "背包已够时不应再要求取货");
    }

    @Test
    void needsFollowDataChanges_notFrozenAtActivation() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        assertEquals(64, PickupModeState.getNeeds().get("minecraft:stone"));

        // 取走 64 个后再算：需求应消失。重构前需求是激活那一刻的快照，
        // 取满后仓库线框仍亮着
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 64, 64, true)));
        assertTrue(PickupModeState.getNeeds().isEmpty(), "需求必须跟随数据变化，而非冻结在激活时刻");
    }

    @Test
    void deactivate_clearsNeeds() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        PickupModeState.setActive(false);
        assertTrue(PickupModeState.getNeeds().isEmpty());
        assertFalse(PickupModeState.isActive());
    }

    @Test
    void clear_resetsActiveAndNeeds() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        PickupModeState.clear();
        assertFalse(PickupModeState.isActive(), "换服/删除原理图后必须回到未激活");
        assertTrue(PickupModeState.getNeeds().isEmpty());
    }

    @Test
    void neededItemIds_matchesNeedsKeys() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(List.of(
            new Snapshot("minecraft:stone", 10, 0, 0, 10, true),
            new Snapshot("minecraft:dirt", 8, 0, 0, 8, true)));
        assertEquals(PickupModeState.getNeeds().keySet(), PickupModeState.getNeededItemIds(),
            "线框过滤用的 ID 集必须与高亮需求同源");
    }

    @Test
    void nullMaterials_tolerated() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(null);
        assertTrue(PickupModeState.getNeeds().isEmpty(), "HUD 未初始化时清单为 null，不应抛异常");
    }
}
