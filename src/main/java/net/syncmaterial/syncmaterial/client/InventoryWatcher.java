package net.syncmaterial.syncmaterial.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.syncmaterial.syncmaterial.network.InventoryUpdateC2SPacket;

import java.util.*;
import org.slf4j.Logger;
import net.minecraft.item.ItemStack;
import net.syncmaterial.syncmaterial.SyncMaterial;

public class InventoryWatcher {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static String currentSchematicId;
    private static final Map<String, Integer> itemIdToMaterialId = new HashMap<>();
    private static final Map<Integer, Integer> lastKnownCounts = new HashMap<>();
    private static int tickCounter = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || currentSchematicId == null) return;

            tickCounter++;
            if (tickCounter % 20 != 0) return;

            checkInventoryChanges(client.player.getInventory());
        });
    }

    public static void setContext(String schematicId, Map<String, Integer> itemIdToMaterialIdMap) {
        currentSchematicId = schematicId;
        itemIdToMaterialId.clear();
        itemIdToMaterialId.putAll(itemIdToMaterialIdMap);
        lastKnownCounts.clear();
    }

    public static void clearContext() {
        currentSchematicId = null;
        itemIdToMaterialId.clear();
        lastKnownCounts.clear();
    }

    public static void forceUpdate() {
        if (currentSchematicId == null || MinecraftClient.getInstance().player == null) return;
        checkInventoryChanges(MinecraftClient.getInstance().player.getInventory());
    }

    public static List<ItemStack> getShulkerContents(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem &&
            blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
            var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
            if (container != null) {
                return container.streamNonEmpty().toList();
            }
        }
        return List.of();
    }

    public static Map<Integer, Integer> getCurrentCounts() {
        if (MinecraftClient.getInstance().player == null) return Map.of();
        Map<Integer, Integer> currentCounts = new HashMap<>();
        PlayerInventory inventory = MinecraftClient.getInstance().player.getInventory();

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            Integer materialId = itemIdToMaterialId.get(itemId);
            if (materialId != null) {
                currentCounts.merge(materialId, stack.getCount(), Integer::sum);
            }

            for (var stored : getShulkerContents(stack)) {
                String storedId = Registries.ITEM.getId(stored.getItem()).toString();
                Integer storedMaterialId = itemIdToMaterialId.get(storedId);
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
    public static List<InventoryDiff> computeDiffs(Map<Integer, Integer> currentCounts, Map<Integer, Integer> lastKnownCounts) {
        List<InventoryDiff> diffs = new ArrayList<>();
        // 变化和新增的
        for (var entry : currentCounts.entrySet()) {
            int last = lastKnownCounts.getOrDefault(entry.getKey(), -1);
            if (entry.getValue() != last) {
                diffs.add(new InventoryDiff(entry.getKey(), entry.getValue()));
            }
        }
        // 消失的
        for (int key : lastKnownCounts.keySet()) {
            if (!currentCounts.containsKey(key)) {
                diffs.add(new InventoryDiff(key, 0));
            }
        }
        return diffs;
    }

    public record InventoryDiff(int materialId, int newCount) {}

    private static void checkInventoryChanges(PlayerInventory inventory) {
        Map<Integer, Integer> currentCounts = getCurrentCounts();
        List<InventoryDiff> diffs = computeDiffs(currentCounts, lastKnownCounts);

        for (InventoryDiff diff : diffs) {
            lastKnownCounts.put(diff.materialId(), diff.newCount());
            ClientPlayNetworking.send(new InventoryUpdateC2SPacket(currentSchematicId, diff.materialId(), diff.newCount()));
        }
        if (!diffs.isEmpty()) {
            long removedCount = diffs.stream().filter(d -> d.newCount() == 0).count();
            if (removedCount > 0) {
                LOGGER.info("本次共 {} 种材料从背包消失", removedCount);
            }
        }
    }
}
