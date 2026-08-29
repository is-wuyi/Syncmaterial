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

    /** itemId → 还需从仓库取货的数量；未激活时为空 */
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
        if (!active || materials == null) {
            needs = Map.of();
            return;
        }
        Map<String, Integer> next = new HashMap<>();
        for (MaterialSnapshot entry : materials) {
            if (!entry.claimedByCurrentPlayer()) continue;
            int pickupMissing = (int) ProgressFormulas.pickupMissing(
                entry.countTotal(), entry.stagingCount(), entry.myCount(), entry.warehouseCount());
            if (pickupMissing > 0) {
                next.merge(entry.itemId(), pickupMissing, Integer::sum);
            }
        }
        needs = Collections.unmodifiableMap(next);
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
