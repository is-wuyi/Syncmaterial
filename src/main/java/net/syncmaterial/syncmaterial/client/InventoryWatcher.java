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

            // 潜影盒内容物统计
            if (stack.getItem() instanceof net.minecraft.item.BlockItem blockItem &&
                blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
                if (container != null) {
                    for (var stored : container.streamNonEmpty().toList()) {
                        if (stored.isEmpty()) continue;
                        String storedId = Registries.ITEM.getId(stored.getItem()).toString();
                        Integer storedMaterialId = itemIdToMaterialId.get(storedId);
                        if (storedMaterialId != null) {
                            currentCounts.merge(storedMaterialId, stored.getCount(), Integer::sum);
                        }
                    }
                }
            }
        }
        return currentCounts;
    }

    private static void checkInventoryChanges(PlayerInventory inventory) {
        Map<Integer, Integer> currentCounts = new HashMap<>();

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            Integer materialId = itemIdToMaterialId.get(itemId);
            if (materialId != null) {
                currentCounts.merge(materialId, stack.getCount(), Integer::sum);
            }
        }

        for (Map.Entry<Integer, Integer> entry : currentCounts.entrySet()) {
            int materialId = entry.getKey();
            int currentCount = entry.getValue();
            int lastCount = lastKnownCounts.getOrDefault(materialId, -1);

            if (currentCount != lastCount) {
                lastKnownCounts.put(materialId, currentCount);
                ClientPlayNetworking.send(new InventoryUpdateC2SPacket(currentSchematicId, materialId, currentCount));
            }
        }

        List<Integer> removedMaterials = new ArrayList<>();
        lastKnownCounts.keySet().removeIf(k -> {
            if (!currentCounts.containsKey(k)) {
                removedMaterials.add(k);
                return true;
            }
            return false;
        });
        for (int materialId : removedMaterials) {
            LOGGER.info("检测到材料 {} 已从背包消失，发送 count=0 到服务器", materialId);
            ClientPlayNetworking.send(new InventoryUpdateC2SPacket(currentSchematicId, materialId, 0));
        }
        if (!removedMaterials.isEmpty()) {
            LOGGER.info("本次共 {} 种材料从背包消失", removedMaterials.size());
        }
    }
}
