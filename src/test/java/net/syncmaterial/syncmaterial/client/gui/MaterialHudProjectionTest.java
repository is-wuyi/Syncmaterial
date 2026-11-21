package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.syncmaterial.syncmaterial.api.ProgressFormulas;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for the pure HUD projection.
 *
 * <p>They lock the WP6/WP7 HUD requirements without Minecraft: both modes switch
 * immediately from the same input, team rows use the core view's teamMissing,
 * personal pickup uses the shared same-item quota against the live inventory
 * sample, unknown preparation never turns into "ready", and every empty state
 * stays distinguishable.</p>
 */
class MaterialHudProjectionTest {

    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @Test
    void collectModeUsesTeamMissingAndPickupModeUsesPersonalPickupFromTheSameViews() {
        // 团队缺口 = 100 - 20 - 30 - 0 = 50；个人可取 = min(100 - 20 - 0, 30) = 30。
        ProjectProgressMaterialView stone = view(1, STONE, 100, 20, 30, true, 0, 0, false, false, true);

        MaterialHudProjection.Model collect = MaterialHudProjection.compute(
                List.of(stone), false, null, true);
        assertEquals(1, collect.rows().size());
        assertEquals(50L, collect.rows().get(0).count());

        MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                List.of(stone), true, Map.of(), true);
        assertEquals(1, pickup.rows().size());
        assertEquals(30L, pickup.rows().get(0).count());
    }

    @Test
    void modeSwitchesAndLiveInventoryChangesTakeEffectWithoutAnyCache() {
        ProjectProgressMaterialView stone = view(1, STONE, 100, 20, 30, true, 0, 0, false, false, true);

        MaterialHudProjection.Model collect = MaterialHudProjection.compute(
                List.of(stone), false, null, true);
        assertEquals(50L, collect.rows().get(0).count());

        MaterialHudProjection.Model pickupSome = MaterialHudProjection.compute(
                List.of(stone), true, Map.of(STONE, 40L), true);
        assertEquals(30L, pickupSome.rows().get(0).count());

        // 同一份视图、新的即时样本：立刻变成"无需再取"，不等待 2 秒缓存。
        MaterialHudProjection.Model pickupSatisfied = MaterialHudProjection.compute(
                List.of(stone), true, Map.of(STONE, 80L), true);
        assertTrue(pickupSatisfied.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PICKUP_NONE_NEEDED,
                pickupSatisfied.emptyState());

        // 切回收集模式仍使用团队缺口，而不是沿用刚才的取货结果。
        MaterialHudProjection.Model collectAgain = MaterialHudProjection.compute(
                List.of(stone), false, null, true);
        assertEquals(50L, collectAgain.rows().get(0).count());
    }

    @Test
    void sameItemQuotaIsAllocatedOnceAcrossDuplicateEntries() {
        ProjectProgressMaterialView first = view(1, STONE, 64, 0, 64, true, 0, 0, false, false, true);
        ProjectProgressMaterialView second = view(2, STONE, 64, 0, 64, true, 0, 0, false, false, true);

        // 背包实测 64 只够第一条目；第二条目仍需取 64。若给两条都减全额，两条都会错误地消失。
        MaterialHudProjection.Model model = MaterialHudProjection.compute(
                List.of(first, second), true, Map.of(STONE, 64L), true);
        assertEquals(1, model.rows().size());
        assertEquals(1, model.rows().get(0).index());
        assertEquals(2, model.rows().get(0).view().materialId(), "输入顺序第一条已吃满额度");
        assertEquals(64L, model.rows().get(0).count());

        // 反转输入顺序后，额度归另一条目：需求仍挂在输入顺序的第二条上。
        MaterialHudProjection.Model reversed = MaterialHudProjection.compute(
                List.of(second, first), true, Map.of(STONE, 64L), true);
        assertEquals(1, reversed.rows().size());
        assertEquals(1, reversed.rows().get(0).index());
        assertEquals(1, reversed.rows().get(0).view().materialId(),
                "额度分配必须跟随输入顺序，而不是固定某个 materialId");
    }

    @Test
    void positiveRowsStillShowWarningWhenPreparationIsUnknown() {
        ProjectProgressMaterialView stone = view(1, STONE, 64, 0, 10, true, 0, 0, false, false, false);

        MaterialHudProjection.Model collect = MaterialHudProjection.compute(
                List.of(stone), false, null, false);
        assertEquals(1, collect.rows().size());
        assertEquals(54L, collect.rows().get(0).count(), "正数行仍按最后已知数据展示");
        assertTrue(collect.warningVisible(), "未知/过期即使有正数行也要显示警告");
        assertEquals(MaterialHudProjection.EmptyState.NONE, collect.emptyState());

        MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                List.of(stone), true, Map.of(), false);
        assertEquals(1, pickup.rows().size());
        assertEquals(10L, pickup.rows().get(0).count());
        assertTrue(pickup.warningVisible());
    }

    @Test
    void unknownPreparationNeverBecomesStagingReadyOrCollected() {
        ProjectProgressMaterialView staged = view(1, STONE, 64, 64, 0, true, 0, 0, false, false, false);

        MaterialHudProjection.Model collect = MaterialHudProjection.compute(
                List.of(staged), false, null, false);
        assertTrue(collect.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PREPARATION_UNKNOWN, collect.emptyState());
        assertTrue(collect.warningVisible());

        MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                List.of(staged), true, Map.of(), false);
        assertTrue(pickup.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PREPARATION_UNKNOWN, pickup.emptyState());
    }

    @Test
    void warehouseEmptyIsDistinctFromNothingLeftForMe() {
        ProjectProgressMaterialView noWarehouse = view(1, STONE, 64, 0, 0, true, 0, 0, false, false, true);
        MaterialHudProjection.Model empty = MaterialHudProjection.compute(
                List.of(noWarehouse), true, Map.of(), true);
        assertTrue(empty.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.WAREHOUSE_EMPTY, empty.emptyState());

        ProjectProgressMaterialView coveredByLive = view(2, STONE, 64, 0, 64, true, 0, 0, false, false, true);
        MaterialHudProjection.Model noneNeeded = MaterialHudProjection.compute(
                List.of(coveredByLive), true, Map.of(STONE, 64L), true);
        assertTrue(noneNeeded.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PICKUP_NONE_NEEDED, noneNeeded.emptyState());

        ProjectProgressMaterialView coveredByStaging = view(3, STONE, 64, 64, 0, true, 0, 0, false, false, true);
        MaterialHudProjection.Model staged = MaterialHudProjection.compute(
                List.of(coveredByStaging), true, Map.of(), true);
        assertTrue(staged.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PICKUP_NONE_NEEDED, staged.emptyState());
    }

    @Test
    void emptyStatesDistinguishParticipationDemandAndCompletion() {
        MaterialHudProjection.Model emptyCatalog = MaterialHudProjection.compute(
                List.of(), false, null, true);
        assertEquals(MaterialHudProjection.EmptyState.NO_DEMAND, emptyCatalog.emptyState());

        ProjectProgressMaterialView unclaimed = view(1, STONE, 64, 0, 0, false, 0, 0, false, false, false);
        MaterialHudProjection.Model noParticipation = MaterialHudProjection.compute(
                List.of(unclaimed), false, null, false);
        assertEquals(MaterialHudProjection.EmptyState.NO_PARTICIPATION, noParticipation.emptyState());
        assertFalse(noParticipation.warningVisible(), "尚未参与时不显示会误导的新鲜度警告");

        ProjectProgressMaterialView zeroDemand = view(2, STONE, 0, 0, 5, true, 0, 0, false, false, false);
        MaterialHudProjection.Model zero = MaterialHudProjection.compute(
                List.of(zeroDemand), false, null, false);
        assertEquals(MaterialHudProjection.EmptyState.NO_DEMAND, zero.emptyState(),
                "零需求优先于未知区域状态");
        assertFalse(zero.warningVisible());

        ProjectProgressMaterialView staged = view(3, STONE, 64, 64, 0, true, 0, 0, true, true, true);
        MaterialHudProjection.Model stagingReady = MaterialHudProjection.compute(
                List.of(staged), false, null, true);
        assertEquals(MaterialHudProjection.EmptyState.STAGING_READY, stagingReady.emptyState());

        ProjectProgressMaterialView inBackpacks = view(4, STONE, 64, 0, 0, true, 0, 64, false, true, true);
        MaterialHudProjection.Model collected = MaterialHudProjection.compute(
                List.of(inBackpacks), false, null, true);
        assertEquals(MaterialHudProjection.EmptyState.TEAM_COLLECTED_AWAITING_STAGING,
                collected.emptyState());
    }

    @Test
    void longMaxValuesNeverWrapOrGetSummed() {
        ProjectProgressMaterialView collectHuge = view(1, STONE, Long.MAX_VALUE, 0, 0,
                true, 0, 0, false, false, true);
        MaterialHudProjection.Model collect = MaterialHudProjection.compute(
                List.of(collectHuge), false, null, true);
        assertEquals(1, collect.rows().size());
        assertEquals(Long.MAX_VALUE, collect.rows().get(0).count());

        ProjectProgressMaterialView pickupHuge = view(2, STONE, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                true, 0, 0, false, false, true);
        MaterialHudProjection.Model pickup = MaterialHudProjection.compute(
                List.of(pickupHuge), true, Map.of(), true);
        assertEquals(1, pickup.rows().size());
        assertEquals(Long.MAX_VALUE, pickup.rows().get(0).count());

        MaterialHudProjection.Model satisfied = MaterialHudProjection.compute(
                List.of(pickupHuge), true, Map.of(STONE, Long.MAX_VALUE), true);
        assertTrue(satisfied.rows().isEmpty());
        assertEquals(MaterialHudProjection.EmptyState.PICKUP_NONE_NEEDED, satisfied.emptyState());
    }

    @Test
    void confirmedZeroLiveSampleDoesNotFallBackToObservedCount() {
        ProjectProgressMaterialView stone = view(1, STONE, 64, 0, 64, true, 30, 0, false, false, true);

        MaterialHudProjection.Model missingConfirmed = MaterialHudProjection.compute(
                List.of(stone), true, Map.of(DIRT, 10L), true);
        assertEquals(64L, missingConfirmed.rows().get(0).count(),
                "非空即时样本缺少该物品就是本机确认 0");

        MaterialHudProjection.Model noSample = MaterialHudProjection.compute(
                List.of(stone), true, null, true);
        assertEquals(34L, noSample.rows().get(0).count(),
                "只有 null 样本才回退视图里的服务端观测");
    }

    private static ProjectProgressMaterialView view(int materialId,
                                                    String itemId,
                                                    long required,
                                                    long staging,
                                                    long warehouse,
                                                    boolean claimed,
                                                    long mine,
                                                    long others,
                                                    boolean stagingReady,
                                                    boolean teamCollected,
                                                    boolean preparationKnown) {
        long held = ProgressFormulas.saturatingAdd(mine, others);
        long teamMissing = ProgressFormulas.collectedMissing(required, staging, warehouse, held);
        long pickupMissing = ProgressFormulas.pickupMissing(required, staging, mine, warehouse);
        if (!preparationKnown) {
            // 记录本身禁止未知状态下确认完成；这里让测试夹具保持同一约束。
            stagingReady = false;
            teamCollected = false;
        }
        return new ProjectProgressMaterialView(materialId, itemId, required, staging, warehouse,
                claimed, mine, others, teamMissing, pickupMissing,
                stagingReady, teamCollected, preparationKnown);
    }
}
