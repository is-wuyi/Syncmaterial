package net.syncmaterial.syncmaterial.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.syncmaterial.syncmaterial.client.session.ClientConnectionSession;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.network.InventoryUpdateC2SPacket;

import java.util.*;
import org.slf4j.Logger;
import net.syncmaterial.syncmaterial.SyncMaterial;

public class InventoryWatcher {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 单个客户端连接的背包观测状态。
     *
     * 每次连接各持有一个实例，由 {@code ClientConnectionSession} 拥有；
     * 静态入口只是"当前会话"的兼容包装，找不到会话时一律为空、不发包。
     */
    public static final class Context {
        private String currentSchematicId;
        private final Map<String, Integer> itemIdToMaterialId = new HashMap<>();
        private final Map<Integer, Integer> lastKnownCounts = new HashMap<>();
        private int tickCounter;
        private boolean periodicReports;

        public Context() {}

        public String currentSchematicId() {
            return currentSchematicId;
        }

        public boolean isWatching() {
            return currentSchematicId != null;
        }

        public int tickCounter() {
            return tickCounter;
        }

        public boolean hasPeriodicReports() {
            return periodicReports;
        }

        /**
         * 设置原理图与物品白名单。
         *
         * 相同上下文重复设置是幂等的：不能清空 diff 基线和 tick 计数，
         * 否则 GUI 每次重开都会把整份背包重新上报一遍。
         */
        public void setContext(String schematicId, Map<String, Integer> itemIdToMaterialIdMap) {
            Objects.requireNonNull(itemIdToMaterialIdMap, "itemIdToMaterialIdMap");
            if (Objects.equals(currentSchematicId, schematicId)
                    && itemIdToMaterialId.equals(itemIdToMaterialIdMap)) {
                return;
            }
            currentSchematicId = schematicId;
            itemIdToMaterialId.clear();
            itemIdToMaterialId.putAll(itemIdToMaterialIdMap);
            lastKnownCounts.clear();
            tickCounter = 0;
            periodicReports = false;
        }

        public void enablePeriodicReports() {
            periodicReports = true;
        }

        public void clear() {
            currentSchematicId = null;
            itemIdToMaterialId.clear();
            lastKnownCounts.clear();
            tickCounter = 0;
            periodicReports = false;
        }

        /** 按本 Context 的白名单统计背包；结果 map 每次新建，不与内部白名单共享。 */
        public Map<Integer, Integer> getCounts(Inventory inventory) {
            return InventoryWatcher.getCounts(inventory, itemIdToMaterialId);
        }

        /**
         * 记录一次观测并返回相对本 Context 上次观测的 diff。
         *
         * 只更新本地基线、不发包，便于纯逻辑测试与 forceUpdate/周期 tick 复用。
         */
        public List<ContainerDiff> observeCounts(Map<Integer, Integer> currentCounts) {
            List<ContainerDiff> diffs = computeDiffs(currentCounts, lastKnownCounts);
            for (ContainerDiff diff : diffs) {
                lastKnownCounts.put(diff.materialId(), diff.newCount());
            }
            return diffs;
        }

        private void tick(Inventory inventory) {
            if (currentSchematicId == null) return;

            tickCounter++;
            if (tickCounter % 20 != 0) return;

            checkContainerChanges(inventory);
            if (tickCounter % 100 == 0 && periodicReports) {
                for (var entry : getCounts(inventory).entrySet()) {
                    ClientPlayNetworking.send(new InventoryUpdateC2SPacket(
                            currentSchematicId, entry.getKey(), entry.getValue()));
                }
            }
        }

        private void checkContainerChanges(Inventory inventory) {
            // 用传入的 inventory 调纯函数，而非绕回 getCurrentCounts() 重取全局单例，
            // 保持 getCounts(Inventory, Map) 的可注入设计不被调用链绕过
            List<ContainerDiff> diffs = observeCounts(getCounts(inventory));

            for (ContainerDiff diff : diffs) {
                ClientPlayNetworking.send(new InventoryUpdateC2SPacket(
                        currentSchematicId, diff.materialId(), diff.newCount()));
            }
            if (!diffs.isEmpty()) {
                long removedCount = diffs.stream().filter(d -> d.newCount() == 0).count();
                if (removedCount > 0) {
                    LOGGER.info("本次共 {} 种材料从背包消失", removedCount);
                }
            }
        }
    }

    /** 从当前客户端会话取背包状态；没有会话、已断开或会话已关闭时返回 null，绝不回退到全局状态。 */
    private static Context currentContext() {
        if (!ClientSessionManager.isConnected()) return null;
        return ClientSessionManager.current()
                .filter(ClientConnectionSession::isOpen)
                .map(ClientConnectionSession::inventory)
                .orElse(null);
    }

    /** 只注册一次；每个 tick 都重新定位当前会话，旧会话的 Context 不会被继续驱动。 */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                Context context = currentContext();
                if (context == null || client.player == null) return;
                context.tick(client.player.getInventory());
            });
        } catch (RuntimeException failure) {
            REGISTERED.set(false);
            throw failure;
        }
    }

    /** 兼容入口：委托当前会话；没有会话时不做任何事。 */
    public static void setContext(String schematicId, Map<String, Integer> itemIdToMaterialIdMap) {
        Context context = currentContext();
        if (context != null) context.setContext(schematicId, itemIdToMaterialIdMap);
    }

    /** 兼容入口：委托当前会话；没有会话时不做任何事。 */
    public static void enablePeriodicReports() {
        Context context = currentContext();
        if (context != null) context.enablePeriodicReports();
    }

    /** 兼容入口：没有会话时视为未启用。 */
    public static boolean hasPeriodicReports() {
        Context context = currentContext();
        return context != null && context.hasPeriodicReports();
    }

    /** 兼容入口：委托当前会话；没有会话时不做任何事。 */
    public static void clearContext() {
        Context context = currentContext();
        if (context != null) context.clear();
    }

    /** 兼容入口：是否正在监听背包（有注册的原理图上下文）。 */
    public static boolean isWatching() {
        Context context = currentContext();
        return context != null && context.isWatching();
    }

    /** 兼容入口：立即检查一次当前会话的背包变化并上报。 */
    public static void forceUpdate() {
        Context context = currentContext();
        if (context == null || !context.isWatching() || Minecraft.getInstance().player == null) return;
        context.checkContainerChanges(Minecraft.getInstance().player.getInventory());
    }

    public static List<ItemStack> getShulkerContents(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem &&
            blockItem.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if (container != null) {
                //? if >=26.2 {
                return container.nonEmptyItemCopyStream().toList();
                //?} else {
                /*return container.nonEmptyStream().toList();
                *///?}
            }
        }
        return List.of();
    }

    /** 兼容入口：没有会话时返回空表。 */
    public static Map<Integer, Integer> getCurrentCounts() {
        Context context = currentContext();
        Minecraft mc = Minecraft.getInstance();
        if (context == null || mc.player == null) return Map.of();
        return context.getCounts(mc.player.getInventory());
    }

    /**
     * 兼容入口：按当前会话的白名单统计背包；没有会话时返回空表。
     *
     * 返回值每次新建，不与 Context 内部的可写白名单共享。
     */
    public static Map<Integer, Integer> getCounts(Inventory inventory) {
        Context context = currentContext();
        if (context == null) return Map.of();
        return context.getCounts(inventory);
    }

    /**
     * 按显式白名单统计背包中各材料的数量（纯函数，可 JUnit 测试）。
     * 只统计白名单内注册过的 itemId，潜影盒内容物递归展开后同样按白名单过滤。
     * @param inventory 玩家背包
     * @param itemIdToMaterialIdMap itemId → materialId 白名单（只读，不会被修改）
     * @return materialId → 数量
     */
    public static Map<Integer, Integer> getCounts(Inventory inventory,
            Map<String, Integer> itemIdToMaterialIdMap) {
        Map<Integer, Integer> currentCounts = new HashMap<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Integer materialId = itemIdToMaterialIdMap.get(itemId);
            if (materialId != null) {
                currentCounts.merge(materialId, stack.getCount(), Integer::sum);
            }

            for (var stored : getShulkerContents(stack)) {
                String storedId = BuiltInRegistries.ITEM.getKey(stored.getItem()).toString();
                Integer storedMaterialId = itemIdToMaterialIdMap.get(storedId);
                if (storedMaterialId != null) {
                    currentCounts.merge(storedMaterialId, stored.getCount(), Integer::sum);
                }
            }
        }
        return currentCounts;
    }

    /**
     * 计算背包变化的 diff 列表（纯函数，可 JUnit 测试）。
     * @param currentCounts 当前背包中各材料的数量
     * @param lastKnownCounts 上次已知的各材料数量
     * @return 发生变化的材料列表（materialId + 新数量，消失的材料数量为 0）
     */
    public static List<ContainerDiff> computeDiffs(Map<Integer, Integer> currentCounts, Map<Integer, Integer> lastKnownCounts) {
        List<ContainerDiff> diffs = new ArrayList<>();
        // 变化和新增的
        for (var entry : currentCounts.entrySet()) {
            int last = lastKnownCounts.getOrDefault(entry.getKey(), -1);
            if (entry.getValue() != last) {
                diffs.add(new ContainerDiff(entry.getKey(), entry.getValue()));
            }
        }
        // 消失的
        for (int key : lastKnownCounts.keySet()) {
            if (!currentCounts.containsKey(key)) {
                diffs.add(new ContainerDiff(key, 0));
            }
        }
        return diffs;
    }

    public record ContainerDiff(int materialId, int newCount) {}
}
