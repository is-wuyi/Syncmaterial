package net.syncmaterial.syncmaterial.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 取货高亮的选格算法与帧间缓存。
 *
 * 从 HandledScreenMixin 中抽出来的原因：Mixin 里的代码无法被单元测试触达
 * （变异实验证实过——把注入目标方法名改错，整套测试全绿逃逸），而这里的
 * 逻辑恰恰是实机 bug 的集中地。搬出来之后 Mixin 只剩"把 MC 的槽位适配成
 * SlotView + 画边框"两件事。
 *
 * 缓存键必须同时覆盖三个维度，缺一个都会产生实机可见的错误：
 *
 * 1. 需求量 —— 取货进度推进后必须重算
 * 2. 容器身份 —— A 箱子算出的槽位序号被拿去画 B 箱子会导致高亮错位
 * 3. 容器内容 —— 缺这一维就是"东西取了、边框还亮着约半秒"的直接原因：
 *    拿走物品的瞬间需求量还没更新（哈希不变）、还是同一个箱子（ID 不变），
 *    于是缓存判定有效，继续按旧槽位序号画在已经空掉的格子上
 */
public final class PickupHighlight {

    private static Set<Integer> cachedSlots = Set.of();
    private static int cachedKey;
    private static boolean hasCache;

    private PickupHighlight() {}

    /** 一份物品堆的只读视图（槽位里的、或潜影盒/收纳袋内的） */
    public interface StackView {
        String itemId();
        int count();
    }

    /** 容器槽位视图。空槽位只需 present() 返回 false，其余方法不会被读取 */
    public interface SlotView extends StackView {
        boolean present();

        /** 潜影盒/收纳袋等嵌套容器的内容物；无嵌套内容返回空列表 */
        List<? extends StackView> contents();
    }

    /**
     * 贪心选格：按槽位顺序累加，直到覆盖需求量。
     *
     * 语义是"从前往后拿，够了就停"，因此一格可能被整格点亮即使只需要其中几个
     * —— 玩家拿的是整格，这是预期行为。
     */
    public static Set<Integer> select(Map<String, Integer> needs, List<? extends SlotView> slots) {
        if (needs == null || needs.isEmpty() || slots == null || slots.isEmpty()) {
            return Set.of();
        }
        Map<String, Integer> remaining = new HashMap<>(needs);
        Set<Integer> selected = new HashSet<>();

        for (int i = 0; i < slots.size(); i++) {
            SlotView slot = slots.get(i);
            if (slot == null || !slot.present()) continue;

            if (consume(slot.itemId(), slot.count(), remaining)) {
                selected.add(i);
                continue;
            }
            // 槽位本身不需要，再看嵌套容器内容物
            for (StackView stored : slot.contents()) {
                if (consume(stored.itemId(), stored.count(), remaining)) {
                    selected.add(i);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    /** 命中需求则扣减并返回 true。扣减按整格计，与"整格拿走"的实际操作一致 */
    private static boolean consume(String itemId, int count, Map<String, Integer> remaining) {
        int rem = remaining.getOrDefault(itemId, 0);
        if (rem <= 0) return false;
        remaining.put(itemId, rem - count);
        return true;
    }

    /**
     * 容器内容签名：内容一变签名就变，用于让缓存失效。
     * 必须覆盖 select() 读到的全部数据，包括嵌套容器内容物
     * （潜影盒里的东西被取走时，它所在槽位的数量仍是 1，只有内容变了）。
     */
    public static int contentSignature(List<? extends SlotView> slots) {
        if (slots == null) return 0;
        int hash = 1;
        for (int i = 0; i < slots.size(); i++) {
            SlotView slot = slots.get(i);
            hash = 31 * hash + i;
            if (slot == null || !slot.present()) {
                hash = 31 * hash;
                continue;
            }
            hash = 31 * hash + slot.itemId().hashCode();
            hash = 31 * hash + slot.count();
            for (StackView stored : slot.contents()) {
                hash = 31 * hash + stored.itemId().hashCode();
                hash = 31 * hash + stored.count();
            }
        }
        return hash;
    }

    /** 缓存键：需求量 + 容器身份 + 容器内容三者任一变化都要重算 */
    public static int cacheKey(Map<String, Integer> needs, int containerId, List<? extends SlotView> slots) {
        int hash = needs == null ? 0 : needs.hashCode();
        hash = 31 * hash + containerId;
        hash = 31 * hash + contentSignature(slots);
        return hash;
    }

    /**
     * 取得应高亮的槽位序号集合，命中缓存时不重算。
     * 每帧都会被渲染调用，因此缓存的意义是避免逐帧展开潜影盒内容物。
     */
    public static Set<Integer> highlightedSlots(Map<String, Integer> needs, int containerId,
            List<? extends SlotView> slots) {
        int key = cacheKey(needs, containerId, slots);
        if (!hasCache || key != cachedKey) {
            cachedKey = key;
            hasCache = true;
            cachedSlots = select(needs, slots);
        }
        return cachedSlots;
    }

    /** 退出取货模式/关闭容器时调用，避免下次开启先闪一帧旧高亮 */
    public static void invalidate() {
        hasCache = false;
        cachedKey = 0;
        cachedSlots = Set.of();
    }
}
