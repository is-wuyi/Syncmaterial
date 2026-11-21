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

    /** 每个测试独立持有实例，防止用例之间通过共享状态互相污染。 */
    private final PickupModeState state = new PickupModeState();

    /** 脱离 MC 客户端的材料快照 */
    private record Snapshot(String itemId, long countTotal, long stagingCount,
                            long myCount, long warehouseCount, boolean claimedByCurrentPlayer)
        implements PickupModeState.MaterialSnapshot {}

    private static Map<String, Long> counts(String itemId, long count) {
        return Map.of(itemId, count);
    }

    @AfterEach
    void tearDown() {
        state.clear();
    }

    @Test
    void inactive_recomputeYieldsEmpty() {
        state.setActive(false);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        assertTrue(state.getNeeds().isEmpty(), "未激活时不应产生需求");
    }

    @Test
    void active_computesPickupMissingCappedByWarehouse() {
        state.setActive(true);
        // 需求 = min(总数 - 备货区 - 我的, 仓库存量) = min(64-10-4, 20) = 20
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 10, 4, 20, true)));
        assertEquals(counts("minecraft:stone", 20), state.getNeeds());
    }

    @Test
    void unclaimedMaterials_excluded() {
        state.setActive(true);
        state.recompute(List.of(
            new Snapshot("minecraft:stone", 64, 0, 0, 64, true),
            new Snapshot("minecraft:dirt", 64, 0, 0, 64, false)));
        assertEquals(counts("minecraft:stone", 64), state.getNeeds(),
            "未认领的材料不该进入需求：格子高亮与仓库线框须同口径");
    }

    @Test
    void sameItemAcrossEntries_summed() {
        state.setActive(true);
        state.recompute(List.of(
            new Snapshot("minecraft:stone", 10, 0, 0, 10, true),
            new Snapshot("minecraft:stone", 5, 0, 0, 5, true)));
        assertEquals(counts("minecraft:stone", 15), state.getNeeds());
    }

    @Test
    void fullyStocked_dropsOutOfNeeds() {
        state.setActive(true);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 64, 64, true)));
        assertTrue(state.getNeeds().isEmpty(), "背包已够时不应再要求取货");
    }

    @Test
    void needsFollowDataChanges_notFrozenAtActivation() {
        state.setActive(true);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        assertEquals(64, state.getNeeds().get("minecraft:stone"));

        // 取走 64 个后再算：需求应消失。重构前需求是激活那一刻的快照，
        // 取满后仓库线框仍亮着
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 64, 64, true)));
        assertTrue(state.getNeeds().isEmpty(), "需求必须跟随数据变化，而非冻结在激活时刻");
    }

    @Test
    void deactivate_clearsNeeds() {
        state.setActive(true);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        state.setActive(false);
        assertTrue(state.getNeeds().isEmpty());
        assertFalse(state.isActive());
    }

    @Test
    void clear_resetsActiveAndNeeds() {
        state.setActive(true);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));
        state.clear();
        assertFalse(state.isActive(), "换服/删除原理图后必须回到未激活");
        assertTrue(state.getNeeds().isEmpty());
    }

    @Test
    void neededItemIds_matchesNeedsKeys() {
        state.setActive(true);
        state.recompute(List.of(
            new Snapshot("minecraft:stone", 10, 0, 0, 10, true),
            new Snapshot("minecraft:dirt", 8, 0, 0, 8, true)));
        assertEquals(state.getNeeds().keySet(), state.getNeededItemIds(),
            "线框过滤用的 ID 集必须与高亮需求同源");
    }

    @Test
    void nullMaterials_tolerated() {
        state.setActive(true);
        state.recompute(null);
        assertTrue(state.getNeeds().isEmpty(), "HUD 未初始化时清单为 null，不应抛异常");
    }

    // ===== 本地背包实测数覆盖陈旧 myCount =====

    @Test
    void liveCounts_overrideStaleSnapshotMyCount() {
        state.setActive(true);
        // 快照说我手上 0 个（服务端还没收到上报），实测已经拿了 64。
        // 若沿用快照，需求算成 128，贪心选格会多点亮一格 —— 这正是实机
        // 看到的"取快了高亮飘到后面去"
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 128, 0, 0, 128, true)),
            counts("minecraft:stone", 64));
        assertEquals(counts("minecraft:stone", 64), state.getNeeds(),
            "需求必须按本地实测数算，不能等服务端往返");
    }

    @Test
    void liveCounts_satisfiedDemandDropsOut() {
        state.setActive(true);
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)),
            counts("minecraft:stone", 64));
        assertTrue(state.getNeeds().isEmpty(), "实测已够时需求应立刻归零");
    }

    @Test
    void liveCounts_confirmedMissingItemIsZero() {
        state.setActive(true);
        // 非空即时样本没有该物品，表示本机确认其数量为 0，而不是没有样本。
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 30, 64, true)),
            counts("minecraft:dirt", 10));
        assertEquals(counts("minecraft:stone", 64), state.getNeeds(),
            "即时样本确认缺失时必须取 0，不能回退到陈旧的服务端观测");
    }

    @Test
    void liveCounts_splitAcrossEntriesOfSameItem() {
        state.setActive(true);
        // 同一物品对应两个条目（各需 64），实测背包共 64。
        // 额度须按条目顺序分配：第一条吃满 64，第二条拿不到，仍需 64。
        // 若给两条都减 64，需求会被低估成 0，该亮的格子不亮
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true),
                    new Snapshot("minecraft:stone", 64, 0, 0, 64, true)),
            counts("minecraft:stone", 64));
        assertEquals(counts("minecraft:stone", 64), state.getNeeds(),
            "同物品多条目须分配实测额度，不能每条都减全额");
    }

    @Test
    void liveCounts_nullTreatedAsAbsent() {
        state.setActive(true);
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 64, 0, 10, 64, true)), null);
        assertEquals(counts("minecraft:stone", 54), state.getNeeds(),
            "实测数为 null 时退回快照，不应抛异常");
    }

    @Test
    void liveCounts_respectStagingDeduction() {
        state.setActive(true);
        // 需求 = min(总数 - 备货区 - 实测, 仓库) = min(128 - 32 - 64, 128) = 32
        state.recompute(
            List.of(new Snapshot("minecraft:stone", 128, 32, 0, 128, true)),
            counts("minecraft:stone", 64));
        assertEquals(counts("minecraft:stone", 32), state.getNeeds());
    }

    // ===== 逐条目结果：HUD 复用同一套同物品额度分配 =====

    @Test
    void perMaterialNeed_allocatesSameItemQuotaInInputOrder() {
        // 两个条目各需 64，背包共 64。额度按顺序给：第一条吃完，第二条仍需 64。
        PickupModeState.MaterialPickupNeed[] needs = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true),
                    new Snapshot("minecraft:stone", 64, 0, 0, 64, true)),
            counts("minecraft:stone", 64));
        assertEquals(0L, needs[0].remaining());
        assertEquals(0L, needs[0].pickupMissing());
        assertEquals(64L, needs[1].remaining());
        assertEquals(64L, needs[1].pickupMissing());
    }

    @Test
    void perMaterialNeed_nullLiveCountsFallsBackToSnapshot() {
        PickupModeState.MaterialPickupNeed need = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", 64, 0, 10, 64, true)), null)[0];
        assertEquals(54L, need.remaining());
        assertEquals(54L, need.pickupMissing(), "没有即时样本时退回快照的背包数");
    }

    @Test
    void perMaterialNeed_nonNullSampleMissingItemIsConfirmedZero() {
        PickupModeState.MaterialPickupNeed need = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", 64, 0, 30, 64, true)),
            counts("minecraft:dirt", 10))[0];
        assertEquals(64L, need.remaining(), "非空样本缺少该物品 = 本机确认 0，不能回退陈旧观测");
        assertEquals(64L, need.pickupMissing());
    }

    @Test
    void perMaterialNeed_unclaimedEntryIsNone() {
        PickupModeState.MaterialPickupNeed need = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, false)),
            counts("minecraft:stone", 0))[0];
        assertEquals(PickupModeState.MaterialPickupNeed.NONE, need);
    }

    @Test
    void perMaterialNeed_warehouseCapsPickupButNotRemaining() {
        // 还差 64，但仓库只有 10：逐条结果同时保留"差 64"和"可取 10"。
        PickupModeState.MaterialPickupNeed need = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", 64, 0, 0, 10, true)),
            counts("minecraft:stone", 0))[0];
        assertEquals(64L, need.remaining());
        assertEquals(10L, need.pickupMissing());
    }

    @Test
    void perMaterialNeed_longMaxDoesNotOverflow() {
        PickupModeState.MaterialPickupNeed[] needs = PickupModeState.computePerMaterialPickupNeeds(
            List.of(new Snapshot("minecraft:stone", Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, true),
                    new Snapshot("minecraft:stone", Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, true)),
            counts("minecraft:stone", Long.MAX_VALUE));
        assertEquals(0L, needs[0].pickupMissing());
        assertEquals(Long.MAX_VALUE, needs[1].remaining());
        assertEquals(Long.MAX_VALUE, needs[1].pickupMissing(), "第二条目不应因求和回绕");
    }

    @Test
    void perMaterialNeed_nullMaterialsReturnsEmptyArray() {
        assertEquals(0, PickupModeState.computePerMaterialPickupNeeds(null, null).length);
    }

    @Test
    void sameItemAggregateSaturatesAtLongMaxInsteadOfThrowing() {
        state.setActive(true);
        // 无即时样本时两条目各自需要 Long.MAX_VALUE；聚合值必须饱和而非 addExact 抛异常。
        state.recompute(List.of(
            new Snapshot("minecraft:stone", Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, true),
            new Snapshot("minecraft:stone", Long.MAX_VALUE, 0, 0, Long.MAX_VALUE, true)));
        assertEquals(counts("minecraft:stone", Long.MAX_VALUE), state.getNeeds());
    }

    @Test
    void instances_areIsolatedForActiveAndNeeds() {
        PickupModeState second = new PickupModeState();

        state.setActive(true);
        state.recompute(List.of(new Snapshot("minecraft:stone", 64, 0, 0, 64, true)));

        assertFalse(second.isActive(), "另一个会话不应被本会话的开关激活");
        assertTrue(second.getNeeds().isEmpty(), "另一个会话不应看到本会话的需求");

        second.setActive(true);
        second.recompute(List.of(new Snapshot("minecraft:dirt", 8, 0, 0, 8, true)));

        assertEquals(counts("minecraft:stone", 64), state.getNeeds());
        assertEquals(counts("minecraft:dirt", 8), second.getNeeds());

        state.clear();
        assertFalse(state.isActive());
        assertTrue(second.isActive(), "清理一个会话不能关闭另一个会话的取货模式");
        assertEquals(counts("minecraft:dirt", 8), second.getNeeds());
    }
}
