//? if >=26 {
package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MaterialListUtils {
    public static void updateAvailableCounts(List<MaterialListEntry> list, Player player) {
        if (player == null) return;

        java.util.Map<String, Integer> playerCounts = new java.util.HashMap<>();
        for (int i = 0; i < player.getContainer().size(); i++) {
            ItemStack stack = player.getContainer().getStack(i);
            if (stack.isEmpty()) continue;

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            playerCounts.merge(itemId, stack.getCount(), Integer::sum);

            for (var stored : net.syncmaterial.syncmaterial.client.InventoryWatcher.getShulkerContents(stack)) {
                String storedId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stored.getItem()).toString();
                playerCounts.merge(storedId, stored.getCount(), Integer::sum);
            }
        }

        for (MaterialListEntry entry : list) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString();
            int playerCount = playerCounts.getOrDefault(itemId, 0);
            entry.setCountAvailable(playerCount);
            entry.setCountMissing((int) net.syncmaterial.syncmaterial.api.ProgressFormulas.inventoryOnlyMissing(
                entry.getCountTotal(), playerCount));
        }
    }

    public static List<MaterialListEntry> convertFromMaterialEntries(List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
        List<MaterialListEntry> result = new ArrayList<>();
        for (net.syncmaterial.syncmaterial.api.MaterialEntry entry : entries) {
            result.add(new MaterialListEntry(
                    entry.getDatabaseId(),
                    entry.getStack(),
                    (int) entry.getCountTotal(),
                    (int) entry.getCountMissing(),
                    (int) entry.getCountMismatched(),
                    (int) entry.getCountAvailable()
            ));
        }
        return result;
    }
}
//?} else {
package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MaterialListUtils {
    public static void updateAvailableCounts(List<MaterialListEntry> list, PlayerEntity player) {
        if (player == null) return;

        java.util.Map<String, Integer> playerCounts = new java.util.HashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            String itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            playerCounts.merge(itemId, stack.getCount(), Integer::sum);

            for (var stored : net.syncmaterial.syncmaterial.client.InventoryWatcher.getShulkerContents(stack)) {
                String storedId = net.minecraft.registry.Registries.ITEM.getId(stored.getItem()).toString();
                playerCounts.merge(storedId, stored.getCount(), Integer::sum);
            }
        }

        for (MaterialListEntry entry : list) {
            String itemId = net.minecraft.registry.Registries.ITEM.getId(entry.getStack().getItem()).toString();
            int playerCount = playerCounts.getOrDefault(itemId, 0);
            entry.setCountAvailable(playerCount);
            entry.setCountMissing((int) net.syncmaterial.syncmaterial.api.ProgressFormulas.inventoryOnlyMissing(
                entry.getCountTotal(), playerCount));
        }
    }

    public static List<MaterialListEntry> convertFromMaterialEntries(List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
        List<MaterialListEntry> result = new ArrayList<>();
        for (net.syncmaterial.syncmaterial.api.MaterialEntry entry : entries) {
            result.add(new MaterialListEntry(
                    entry.getDatabaseId(),
                    entry.getStack(),
                    (int) entry.getCountTotal(),
                    (int) entry.getCountMissing(),
                    (int) entry.getCountMismatched(),
                    (int) entry.getCountAvailable()
            ));
        }
        return result;
    }
}
//?}
