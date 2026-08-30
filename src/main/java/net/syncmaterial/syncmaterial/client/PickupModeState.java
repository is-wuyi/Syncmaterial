package net.syncmaterial.syncmaterial.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.syncmaterial.syncmaterial.api.ProgressFormulas;

/**
 * 取货模式的唯一状态源。
 *
 * 重构前这套状态散在三处且各自更新：GuiMaterialList 的 pickupMode /
 * pickupModeNeededItemIds（只在点按钮时算一次）、MaterialListHudRenderer 的
 * pickupHighlightNeedsStatic（只在 HUD 渲染时更新）。由此产生三类问题：
 *
 * 1. HUD 被关掉后需求量永不更新 —— 格子高亮停在陈旧数据甚至完全不亮
 *    （数据更新挂在渲染上，与"关界面断背包监听"是同一类病）
 * 2. 两份需求数据会漂移 —— 仓库线框按点按钮那一刻的快照过滤，取完货后
 *    已满足的材料，其箱子线框仍然亮着
 * 3. 过滤口径不一致 —— 格子高亮只算"我认领的"，仓库线框不判断认领
 *
 * 现在统一为：needs 是唯一数据（itemId → 还需取货数），线框过滤用它的
 * keySet，两者口径天然一致；更新由调用方在 tick 中驱动，与渲染解耦。
 */
public final class PickupModeState {
    private static boolean active;
    private static Map<String, Integer> needs = Map.of();

    private PickupModeState() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
        if (!active) {
            needs = Map.of();
        }
    }

    /**
     * itemId → 还需从仓库取货的数量；未激活时为空。
     *
     * 这是每若干 tick 采样一次的缓存值，供 HUD 与仓库线框使用。
     * 箱子格子高亮不要用它 —— 见 computeNeeds 的说明。
     */
    public static Map<String, Integer> getNeeds() {
        return needs;
    }

    /** 需要取货的物品 ID 集合，供仓库容器线框过滤 */
    public static Set<String> getNeededItemIds() {
        return needs.keySet();
    }

    /** 退出世界/换服/原理图被删除时调用：状态不清会带着陈旧需求继续高亮 */
    public static void clear() {
        active = false;
        needs = Map.of();
    }

    /**
     * 依据当前材料清单重算需求。未激活时清空。
     * 调用方负责驱动（每若干 tick 一次），不依赖任何渲染回调。
     */
    public static void recompute(List<? extends MaterialSnapshot> materials) {
        recompute(materials, Map.of());
    }

    /**
     * 依据当前材料清单重算需求，用本地背包实测数覆盖快照里的 myCount。
     *
     * 为什么需要覆盖：快照的 myCount 来自 MaterialListEntry.countAvailable，
     * 而那个字段是服务端算完回传的，链路是
     * 「背包变化 → InventoryWatcher 每 20 tick 扫一次 → 上报 → 服务端广播
     * → 客户端收包写回」，最坏一秒以上。用陈旧的 myCount 算出的需求量偏大，
     * 贪心选格就会多吃一格，把后面那格不需要的同种物品也点亮
     * —— 这正是实机看到的"取快了高亮飘到后面去"。
     *
     * 本地背包对"我手上有多少"是权威且零延迟的，故优先采用；服务端那份
     * 仍用于展示他人持有量，两者职责分开。
     *
     * @param liveCounts itemId → 本地实测数量；缺失的条目回退到快照值
     */
    public static void recompute(List<? extends MaterialSnapshot> materials,
            Map<String, Integer> liveCounts) {
        if (!active || materials == null) {
            needs = Map.of();
            return;
        }
        needs = computeNeeds(materials, liveCounts);
    }

    /**
     * 需求量计算本体：纯函数，不读写任何状态。
     *
     * 抽成纯函数是因为它有两个采样时机不同的消费者，而"采样时刻"本身就是
     * 正确性的一部分：
     *
     * - HUD 与仓库线框：每若干 tick 采样一次（见 recompute）。它们只关心
     *   "还差不差"这种粗粒度信息，半秒的滞后看不出来。
     * - 箱子格子高亮：必须在画的那一刻现算，与容器内容同一瞬间采样。
     *   因为高亮是"从第一格往后累加直到凑够需求量"，需求量与容器内容
     *   若不同源，差多少就会多点亮几格 —— 取走一个物品时容器少 1、
     *   需求却还是旧值，贪心就会多走两格，表现为后面的物品闪一下再恢复。
     */
    public static Map<String, Integer> computeNeeds(List<? extends MaterialSnapshot> materials,
            Map<String, Integer> liveCounts) {
        if (materials == null) return Map.of();

        Map<String, Integer> live = liveCounts == null ? Map.of() : liveCounts;
        // 同一物品可能对应多个材料条目，而实测数是该物品在背包里的总量。
        // 若给每个条目都减一遍全额，需求会被低估；故按条目顺序分配额度。
        Map<String, Integer> allowance = new HashMap<>(live);
        Map<String, Integer> next = new HashMap<>();
        for (MaterialSnapshot entry : materials) {
            if (!entry.claimedByCurrentPlayer()) continue;
            String itemId = entry.itemId();
            int myCount = entry.myCount();
            if (live.containsKey(itemId)) {
                int left = allowance.getOrDefault(itemId, 0);
                // 本条目最多认领到自己的需要量，余额留给同物品的后续条目
                int demand = Math.max(0, entry.countTotal() - entry.stagingCount());
                myCount = Math.min(left, demand);
                allowance.put(itemId, left - myCount);
            }
            int pickupMissing = (int) ProgressFormulas.pickupMissing(
                entry.countTotal(), entry.stagingCount(), myCount, entry.warehouseCount());
            if (pickupMissing > 0) {
                next.merge(itemId, pickupMissing, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(next);
    }

    /**
     * recompute 的输入契约。抽成接口以便脱离 MC 客户端做单测
     * （MaterialListEntry 依赖 MinecraftClient.getInstance().player 判断认领）。
     */
    public interface MaterialSnapshot {
        String itemId();
        int countTotal();
        int stagingCount();
        int myCount();
        int warehouseCount();
        boolean claimedByCurrentPlayer();
    }
}
