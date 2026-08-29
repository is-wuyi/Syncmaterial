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

    // ===== 本地背包实测数覆盖陈旧 myCount =====

    @Test
    void liveCounts_overrideStaleSnapshotMyCount() {
        PickupModeState.setActive(true);
        // 快照说我手上 0 个（服务端还没收到上报），实测已经拿了 64。
        // 若沿用快照，需求算成 128，贪心选格会多点亮一格 —— 这正是实机
        // 看到的"取快了高亮飘到后面去"
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 128, 0, 0, 128, true)),
            Map.of("minecraft:stone", 64));
        assertEquals(Map.of("minecraft:stone", 64), PickupModeState.getNeeds(),
            "需求必须按本地实测数算，不能等服务端往返");
    }

    @Test
    void liveCounts_satisfiedDemandDropsOut() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)),
            Map.of("minecraft:stone", 64));
        assertTrue(PickupModeState.getNeeds().isEmpty(), "实测已够时需求应立刻归零");
    }

    @Test
    void liveCounts_missingItemFallsBackToSnapshot() {
        PickupModeState.setActive(true);
        // 实测里没有这个物品（背包里一个都没有）时，不该误当作"未知"而跳过覆盖：
        // 空额度意味着 myCount=0，需求为全额
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 30, 64, true)),
            Map.of("minecraft:dirt", 10));
        assertEquals(Map.of("minecraft:stone", 34), PickupModeState.getNeeds(),
            "实测中缺失的物品回退到快照值，避免把别的材料算错");
    }

    @Test
    void liveCounts_splitAcrossEntriesOfSameItem() {
        PickupModeState.setActive(true);
        // 同一物品对应两个条目（各需 64），实测背包共 64。
        // 额度须按条目顺序分配：第一条吃满 64，第二条拿不到，仍需 64。
        // 若给两条都减 64，需求会被低估成 0，该亮的格子不亮
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true),
                    new Snapshot("minecraft:stone", 64, 0, 0, 64, true)),
            Map.of("minecraft:stone", 64));
        assertEquals(Map.of("minecraft:stone", 64), PickupModeState.getNeeds(),
            "同物品多条目须分配实测额度，不能每条都减全额");
    }

    @Test
    void liveCounts_nullTreatedAsAbsent() {
        PickupModeState.setActive(true);
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 10, 64, true)), null);
        assertEquals(Map.of("minecraft:stone", 54), PickupModeState.getNeeds(),
            "实测数为 null 时退回快照，不应抛异常");
    }

    @Test
    void liveCounts_respectStagingDeduction() {
        PickupModeState.setActive(true);
        // 需求 = min(总数 - 备货区 - 实测, 仓库) = min(128 - 32 - 64, 128) = 32
        PickupModeState.recompute(
            List.of(new Snapshot("minecraft:stone", 128, 32, 0, 128, true)),
            Map.of("minecraft:stone", 64));
        assertEquals(Map.of("minecraft:stone", 32), PickupModeState.getNeeds());
    }
}
