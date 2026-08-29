package net.syncmaterial.syncmaterial;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.PickupHighlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取货高亮选格与缓存测试。
 *
 * 这些用例锁定的是实机踩到的两个症状：
 * - "取快了高亮飘到后面去"：需求量偏大导致贪心多吃一格
 * - "东西取了边框还亮约半秒"：缓存键漏了容器内容这一维
 */
class PickupHighlightTest {

    private record Stack(String itemId, int count) implements PickupHighlight.StackView {}

    private record SlotOf(boolean present, String itemId, int count,
                          List<? extends PickupHighlight.StackView> contents)
        implements PickupHighlight.SlotView {}

    private static PickupHighlight.SlotView slot(String itemId, int count) {
        return new SlotOf(true, itemId, count, List.of());
    }

    private static PickupHighlight.SlotView empty() {
        return new SlotOf(false, "", 0, List.of());
    }

    private static PickupHighlight.SlotView shulker(String storedId, int storedCount) {
        return new SlotOf(true, "minecraft:shulker_box", 1, List.of(new Stack(storedId, storedCount)));
    }

    @BeforeEach
    void reset() {
        PickupHighlight.invalidate();
    }

    @Test
    void selectsSlotsUntilDemandCovered() {
        // 需要 128：前两格各 64 正好覆盖，第三格不该亮
        Set<Integer> selected = PickupHighlight.select(
            Map.of("minecraft:stone", 128),
            List.of(slot("minecraft:stone", 64),
                    slot("minecraft:stone", 64),
                    slot("minecraft:stone", 64)));
        assertEquals(Set.of(0, 1), selected);
    }

    @Test
    void partialDemand_stillHighlightsWholeSlot() {
        // 只需 10 个，但玩家拿的是整格，故整格点亮
        assertEquals(Set.of(0), PickupHighlight.select(
            Map.of("minecraft:stone", 10),
            List.of(slot("minecraft:stone", 64), slot("minecraft:stone", 64))));
    }

    @Test
    void staleNeeds_wouldOverSelect_soFreshNeedsMustNotLeak() {
        // 已取走 64（需求应为 64），若用陈旧需求 128 会多点亮一格。
        // 这一条正是"高亮飘到后面去"的机制说明：同一容器、不同需求量，
        // 选出的格子数必须不同
        List<PickupHighlight.SlotView> slots = List.of(
            slot("minecraft:stone", 64), slot("minecraft:stone", 64), slot("minecraft:stone", 64));
        assertEquals(Set.of(0, 1), PickupHighlight.select(Map.of("minecraft:stone", 128), slots));
        assertEquals(Set.of(0), PickupHighlight.select(Map.of("minecraft:stone", 64), slots));
    }

    @Test
    void emptySlots_skippedWithoutConsumingDemand() {
        Set<Integer> selected = PickupHighlight.select(
            Map.of("minecraft:stone", 64),
            List.of(empty(), slot("minecraft:stone", 64), slot("minecraft:stone", 64)));
        assertEquals(Set.of(1), selected, "空格不该占用需求额度，也不该被点亮");
    }

    @Test
    void unrelatedItems_notHighlighted() {
        Set<Integer> selected = PickupHighlight.select(
            Map.of("minecraft:stone", 64),
            List.of(slot("minecraft:dirt", 64), slot("minecraft:stone", 64)));
        assertEquals(Set.of(1), selected);
    }

    @Test
    void shulkerContents_triggerHighlight() {
        Set<Integer> selected = PickupHighlight.select(
            Map.of("minecraft:stone", 64),
            List.of(shulker("minecraft:stone", 64)));
        assertEquals(Set.of(0), selected, "潜影盒内含所需物品时其所在格应点亮");
    }

    @Test
    void multipleItemTypes_trackedIndependently() {
        Set<Integer> selected = PickupHighlight.select(
            Map.of("minecraft:stone", 64, "minecraft:dirt", 32),
            List.of(slot("minecraft:stone", 64),
                    slot("minecraft:stone", 64),
                    slot("minecraft:dirt", 32),
                    slot("minecraft:dirt", 32)));
        assertEquals(Set.of(0, 2), selected, "各物品的需求额度互不干扰");
    }

    @Test
    void emptyNeeds_selectsNothing() {
        assertTrue(PickupHighlight.select(Map.of(), List.of(slot("minecraft:stone", 64))).isEmpty());
    }

    @Test
    void nullInputs_tolerated() {
        assertTrue(PickupHighlight.select(null, List.of(slot("minecraft:stone", 1))).isEmpty());
        assertTrue(PickupHighlight.select(Map.of("minecraft:stone", 1), null).isEmpty());
    }

    // ===== 缓存键：三个维度缺一不可 =====

    @Test
    void contentChange_invalidatesCache() {
        Map<String, Integer> needs = Map.of("minecraft:stone", 128);
        List<PickupHighlight.SlotView> before = List.of(
            slot("minecraft:stone", 64), slot("minecraft:stone", 64), slot("minecraft:stone", 64));
        // 第 0 格被取空后，需求量还没更新（服务端往返有延迟），容器也没换。
        // 若缓存键不含内容，这里会返回旧的 {0,1}，表现为已空的格子仍亮着
        List<PickupHighlight.SlotView> after = List.of(
            empty(), slot("minecraft:stone", 64), slot("minecraft:stone", 64));

        assertNotEquals(PickupHighlight.cacheKey(needs, 1, before),
            PickupHighlight.cacheKey(needs, 1, after),
            "容器内容变化必须让缓存失效");

        assertEquals(Set.of(0, 1), PickupHighlight.highlightedSlots(needs, 1, before));
        assertEquals(Set.of(1, 2), PickupHighlight.highlightedSlots(needs, 1, after));
    }

    @Test
    void shulkerContentChange_invalidatesCache() {
        Map<String, Integer> needs = Map.of("minecraft:stone", 64);
        // 潜影盒被取走内容后，槽位数量仍是 1，只有内容变了。
        // 签名不递归展开就抓不到这种变化
        assertNotEquals(
            PickupHighlight.cacheKey(needs, 1, List.of(shulker("minecraft:stone", 64))),
            PickupHighlight.cacheKey(needs, 1, List.of(shulker("minecraft:stone", 32))),
            "嵌套容器内容变化也必须让缓存失效");
    }

    @Test
    void containerChange_invalidatesCache() {
        Map<String, Integer> needs = Map.of("minecraft:stone", 64);
        List<PickupHighlight.SlotView> slots = List.of(slot("minecraft:stone", 64));
        assertNotEquals(PickupHighlight.cacheKey(needs, 1, slots),
            PickupHighlight.cacheKey(needs, 2, slots),
            "换容器必须让缓存失效，否则 A 箱的槽位序号会画到 B 箱上");
    }

    @Test
    void needsChange_invalidatesCache() {
        List<PickupHighlight.SlotView> slots = List.of(slot("minecraft:stone", 64));
        assertNotEquals(
            PickupHighlight.cacheKey(Map.of("minecraft:stone", 64), 1, slots),
            PickupHighlight.cacheKey(Map.of("minecraft:stone", 128), 1, slots),
            "需求量变化必须让缓存失效");
    }

    @Test
    void sameInputs_hitCache() {
        Map<String, Integer> needs = Map.of("minecraft:stone", 64);
        List<PickupHighlight.SlotView> slots = List.of(slot("minecraft:stone", 64));
        Set<Integer> first = PickupHighlight.highlightedSlots(needs, 1, slots);
        Set<Integer> second = PickupHighlight.highlightedSlots(needs, 1, slots);
        assertEquals(first, second);
    }

    @Test
    void invalidate_forcesRecompute() {
        Map<String, Integer> needs = Map.of("minecraft:stone", 64);
        assertEquals(Set.of(0),
            PickupHighlight.highlightedSlots(needs, 1, List.of(slot("minecraft:stone", 64))));
        PickupHighlight.invalidate();
        // 退出取货模式后需求为空，缓存必须被丢掉而不是继续画上一次的格子
        assertTrue(PickupHighlight.highlightedSlots(Map.of(), 1,
            List.of(slot("minecraft:stone", 64))).isEmpty());
    }
}
