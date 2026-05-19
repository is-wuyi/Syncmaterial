package net.syncmaterial.syncmaterial.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.syncmaterial.syncmaterial.network.InventoryUpdateC2SPacket;

import java.util.*;

public class InventoryWatcher {
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
            ClientPlayNetworking.send(new InventoryUpdateC2SPacket(currentSchematicId, materialId, 0));
        }
    }
}
