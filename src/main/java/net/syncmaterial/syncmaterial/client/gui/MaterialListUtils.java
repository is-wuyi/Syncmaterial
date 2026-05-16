package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MaterialListUtils {
    public static void updateAvailableCounts(List<MaterialListEntry> list, PlayerEntity player) {
        if (player == null) return;
        for (MaterialListEntry entry : list) {
            Item item = entry.getStack().getItem();
            int available = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.getItem() == item) {
                    available += stack.getCount();
                }
            }
            entry.setCountAvailable(available);
        }
    }

    public static List<MaterialListEntry> convertFromMaterialEntries(List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
        List<MaterialListEntry> result = new ArrayList<>();
        for (net.syncmaterial.syncmaterial.api.MaterialEntry entry : entries) {
            result.add(new MaterialListEntry(
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
