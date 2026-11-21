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
 *
 * <p>本类只持有状态、不持有"当前状态"的全局引用：每个客户端连接各有一个
 * 实例（由 ClientConnectionSession 拥有）。shared 模块不能依赖平台会话
 * 管理器，调用方需要自行从当前会话取实例。</p>
 */
public final class PickupModeState {
    private boolean active;
    private Map<String, Long> needs = Map.of();

    public PickupModeState() {}

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
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
    public Map<String, Long> getNeeds() {
        return needs;
    }

    /** 需要取货的物品 ID 集合，供仓库容器线框过滤 */
    public Set<String> getNeededItemIds() {
        return needs.keySet();
    }

    /** 退出世界/换服/原理图被删除时调用：状态不清会带着陈旧需求继续高亮 */
    public void clear() {
        active = false;
        needs = Map.of();
    }

    /**
     * 依据当前材料清单重算需求。未激活时清空。
     * 调用方负责驱动（每若干 tick 一次），不依赖任何渲染回调。
     */
    public void recompute(List<? extends MaterialSnapshot> materials) {
        recompute(materials, null);
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
     * @param liveCounts itemId → 本地实测数量；null 表示没有即时样本，非空样本缺失即为 0
     */
    public void recompute(List<? extends MaterialSnapshot> materials,
            Map<String, Long> liveCounts) {
        if (!active || materials == null) {
            needs = Map.of();
            return;
        }
        needs = computeNeeds(materials, liveCounts);
    }

    /**
     * 单条目取货需求（与 {@link #computeNeeds} 共用同一套同物品额度分配）。
     *
     * <p>{@code remaining} 是本条目扣除备货区与分配给我的背包额度后仍差多少；
     * {@code pickupMissing} 是其中真正能从仓库取到的数量，即
     * {@code min(remaining, warehouse)}。未认领条目两者都是 0。</p>
     */
    public record MaterialPickupNeed(long remaining, long pickupMissing) {
        public static final MaterialPickupNeed NONE = new MaterialPickupNeed(0L, 0L);

        public MaterialPickupNeed {
            if (remaining < 0L || pickupMissing < 0L) {
                throw new IllegalArgumentException("pickup needs must be >= 0");
            }
            if (pickupMissing > remaining) {
                throw new IllegalArgumentException("pickupMissing cannot exceed remaining");
            }
        }
    }

    /**
     * 按输入顺序逐条目计算取货需求，不聚合。
     *
     * <p>HUD 需要逐行显示"我还需取多少"，同时必须与仓库线框的聚合需求量共用
     * 同一套算法：同一 itemId 的多个材料条目共享背包实测数，不能每条都减一遍
     * 全额。把额度分配保留在这里，避免 HUD 另写一份近似逻辑后与高亮漂移。</p>
     *
     * @param liveCounts itemId → 本地含光标实测数量；null 表示没有即时样本，
     *                   此时回退到快照的 {@link MaterialSnapshot#myCount()}；
     *                   非 null 但缺少某物品表示本机确认该物品为 0
     */
    public static MaterialPickupNeed[] computePerMaterialPickupNeeds(
            List<? extends MaterialSnapshot> materials, Map<String, Long> liveCounts) {
        if (materials == null) return new MaterialPickupNeed[0];

        MaterialPickupNeed[] needs = new MaterialPickupNeed[materials.size()];
        Map<String, Long> allowance = liveCounts == null ? null : new HashMap<>(liveCounts);
        for (int index = 0; index < needs.length; index++) {
            MaterialSnapshot entry = materials.get(index);
            if (entry == null || !entry.claimedByCurrentPlayer()) {
                needs[index] = MaterialPickupNeed.NONE;
                continue;
            }
            String itemId = entry.itemId();
            long myCount = entry.myCount();
            if (allowance != null) {
                long left = allowance.getOrDefault(itemId, 0L);
                // 即时样本里缺少该物品就是本机确认 0；只有 null 样本才回退快照。
                long demand = ProgressFormulas.saturatingSubtract(entry.countTotal(), entry.stagingCount());
                myCount = Math.min(left, demand);
                allowance.put(itemId, left - myCount);
            }
            long remaining = ProgressFormulas.saturatingSubtract(
                    ProgressFormulas.saturatingSubtract(entry.countTotal(), entry.stagingCount()), myCount);
            // 复用公开公式，保持与 HUD/GUI 其它调用点完全相同的输入校验与饱和语义。
            long pickupMissing = ProgressFormulas.pickupMissing(
                    entry.countTotal(), entry.stagingCount(), myCount, entry.warehouseCount());
            needs[index] = new MaterialPickupNeed(remaining, pickupMissing);
        }
        return needs;
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
     *
     * <p>聚合值在 {@link Long#MAX_VALUE} 处饱和，不会因多个条目求和回绕或抛异常。</p>
     */
    public static Map<String, Long> computeNeeds(List<? extends MaterialSnapshot> materials,
            Map<String, Long> liveCounts) {
        if (materials == null) return Map.of();

        MaterialPickupNeed[] perMaterial = computePerMaterialPickupNeeds(materials, liveCounts);
        Map<String, Long> next = new HashMap<>();
        for (int index = 0; index < perMaterial.length; index++) {
            long pickupMissing = perMaterial[index].pickupMissing();
            if (pickupMissing > 0) {
                next.merge(materials.get(index).itemId(), pickupMissing, ProgressFormulas::saturatingAdd);
            }
        }
        return Collections.unmodifiableMap(next);
    }

    /**
     * recompute 的输入契约。抽成接口以便脱离 MC 客户端做单测
     * （MaterialListEntry 依赖 Minecraft.getInstance().player 判断认领）。
     */
    public interface MaterialSnapshot {
        String itemId();
        long countTotal();
        long stagingCount();
        long myCount();
        long warehouseCount();
        boolean claimedByCurrentPlayer();
    }
}
