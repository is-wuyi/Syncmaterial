package net.syncmaterial.syncmaterial.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 本地背包实测统计。
 *
 * 与 InventoryWatcher 的分工：那个类负责"把变化上报给服务端"（按 materialId
 * 聚合、带白名单、每 20 tick 一次），这里负责"当前这一刻我手上有多少"
 * （按 itemId 聚合、不过滤、随调随取）。
 *
 * 之所以不复用 InventoryWatcher.getCounts：键不同（materialId vs itemId）、
 * 且那条链路的结果要经服务端往返才回到客户端，正是高亮延迟的来源。
 *
 * itemId 一律用 Item.toString()，与 MaterialListEntry.itemId() 及
 * HandledScreenMixin 读槽位时的口径保持一致 —— 三处必须同源，否则
 * 需求 Map 的键与查表的键对不上，高亮会整体失效。
 */
public final class InventoryScanner {

    private InventoryScanner() {}

    /**
     * 统计玩家背包中各物品的实际数量，含潜影盒内容物与光标上正拿着的那一堆。
     *
     * 为什么要算光标持物：从箱子里拖出一堆物品时，它先落在光标上而不在背包
     * 槽位里。漏算会让这一堆物品"凭空消失"，需求量回弹，已经取到手的格子
     * 又被重新点亮。
     *
     * @return itemId → 数量；player 为 null 时返回空 Map
     */
    public static Map<String, Integer> liveCountsByItemId(Player player) {
        if (player == null) return Map.of();

        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            accumulate(counts, inventory.getItem(i));
        }
        if (player.containerMenu != null) {
            accumulate(counts, player.containerMenu.getCarried());
        }
        return Collections.unmodifiableMap(counts);
    }

    private static void accumulate(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        counts.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);
        for (ItemStack stored : InventoryWatcher.getShulkerContents(stack)) {
            if (stored.isEmpty()) continue;
            counts.merge(stored.getItem().toString(), stored.getCount(), Integer::sum);
        }
    }
}
