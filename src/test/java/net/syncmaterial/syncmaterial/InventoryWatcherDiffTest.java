package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.InventoryWatcher.InventoryDiff;

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

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertTrue(diffs.isEmpty());
    }

    @Test
    void newMaterial_detected() {
        var current = mapOf(1, 10);
        var last = new HashMap<Integer, Integer>();

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(10, diffs.get(0).newCount());
    }

    @Test
    void removedMaterial_detectedWithZero() {
        var current = new HashMap<Integer, Integer>();
        var last = mapOf(1, 10);

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(0, diffs.get(0).newCount());
    }

    @Test
    void countChanged_detected() {
        var current = mapOf(1, 15);
        var last = mapOf(1, 10);

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(1, diffs.size());
        assertEquals(1, diffs.get(0).materialId());
        assertEquals(15, diffs.get(0).newCount());
    }

    @Test
    void mixedChanges_allDetected() {
        var current = mapOf(1, 15, 3, 30);   // 1 变化, 3 新增
        var last = mapOf(1, 10, 2, 20);       // 2 消失

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(3, diffs.size());

        // 转成 map 方便验证
        Map<Integer, Integer> diffMap = new HashMap<>();
        for (var d : diffs) diffMap.put(d.materialId(), d.newCount());

        assertEquals(15, diffMap.get(1));  // 变化
        assertEquals(0, diffMap.get(2));   // 消失
        assertEquals(30, diffMap.get(3));  // 新增
    }

    @Test
    void firstTimeSeesMaterial_detectedAsNew() {
        // lastKnownCounts 为空（首次检查）
        var current = mapOf(1, 5, 2, 10);
        var last = new HashMap<Integer, Integer>();

        List<InventoryDiff> diffs = InventoryWatcher.computeDiffs(current, last);
        assertEquals(2, diffs.size());
    }
}
