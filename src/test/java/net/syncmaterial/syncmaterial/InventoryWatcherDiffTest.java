package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.InventoryWatcher.ContainerDiff;

/**
 * InventoryWatcher.computeDiffs 纯逻辑测试。
 * 不依赖 Minecraft 运行时。
 */
public class InventoryWatcherDiffTest {

    private static Map<Integer, Integer> mapOf(int... kv) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void noChange_emptyDiff() {
        var current = mapOf(1, 10, 2, 20);
        var last = mapOf(1, 10, 2, 20);

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertTrue(diffs.isEmpty());
    }

    @Test
    void newMaterial_detected() {
        var current = mapOf(1, 10);
        var last = new HashMap<Integer, Integer>();

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(10, diffs.get(0).newCount());
    }

    @Test
    void removedMaterial_detectedWithZero() {
        var current = new HashMap<Integer, Integer>();
        var last = mapOf(1, 10);

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(0, diffs.get(0).newCount());
    }

    @Test
    void countChanged_detected() {
        var current = mapOf(1, 15);
        var last = mapOf(1, 10);

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(15, diffs.get(0).newCount());
    }

    @Test
    void mixedChanges_allDetected() {
        var current = mapOf(1, 15, 3, 30);   // 1 变化, 3 新增
        var last = mapOf(1, 10, 2, 20);       // 2 消失

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(3, diffs.size());

        // 转成 map 方便验证
        Map<Integer, Integer> diffMap = new HashMap<>();
        for (var d : diffs) diffMap.put(d.materialId(), d.newCount());

        assertEquals(15, diffMap.get(1));  // 变化
        assertEquals(0, diffMap.get(2));   // 消失
        assertEquals(30, diffMap.get(3));  // 新增
    }

    @Test
    void repeatedIdenticalContextDoesNotResetObservationHistory() {
        InventoryWatcher.Context context = new InventoryWatcher.Context();
        Map<String, Integer> mapping = Map.of("minecraft:dirt", 1);
        context.setContext("project", mapping);

        assertEquals(1, context.observeCounts(mapOf(1, 4)).size());
        context.enablePeriodicReports();

        // 相同上下文重复设置：diff 基线和周期上报开关都必须保留，
        // 否则 GUI 每次重开都会把整份背包重新上报一遍。
        context.setContext("project", mapping);
        assertTrue(context.observeCounts(mapOf(1, 4)).isEmpty(),
            "相同上下文必须保留 diff 基线");
        assertTrue(context.hasPeriodicReports(), "相同上下文不应重置周期上报");

        // 换上下文：基线清空、周期上报关闭。
        context.setContext("project", Map.of("minecraft:stone", 2));
        assertFalse(context.hasPeriodicReports());
        assertEquals(1, context.observeCounts(mapOf(1, 4)).size(),
            "换上下文必须清空 diff 基线");
        context.clear();
        assertFalse(context.isWatching());
    }

    @Test
    void contexts_isolateWhitelistAndDiffBaseline() {
        InventoryWatcher.Context first = new InventoryWatcher.Context();
        InventoryWatcher.Context second = new InventoryWatcher.Context();
        first.setContext("first", Map.of("minecraft:stone", 1));
        second.setContext("second", Map.of("minecraft:dirt", 2));

        assertEquals(1, first.observeCounts(mapOf(1, 4)).size());
        assertTrue(first.observeCounts(mapOf(1, 4)).isEmpty(), "重复观测不再产生 diff");

        assertEquals(1, second.observeCounts(mapOf(2, 8)).size(),
            "另一个 Context 有独立的 diff 基线");
        assertTrue(second.observeCounts(mapOf(2, 8)).isEmpty());

        first.clear();
        assertFalse(first.isWatching());
        assertTrue(second.isWatching(), "清理一个 Context 不能影响另一个");
    }

    @Test
    void firstTimeSeesMaterial_detectedAsNew() {
        // lastKnownCounts 为空（首次检查）
        var current = mapOf(1, 5, 2, 10);
        var last = new HashMap<Integer, Integer>();

        List<ContainerDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(2, diffs.size());
    }
}
