package net.syncmaterial.syncmaterial.client.gui;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MaterialListUtils {
    public static void updateAvailableCounts(ImmutableList<MaterialListEntry> list, PlayerEntity player) {
        if (player == null) return;

        for (MaterialListEntry entry : list) {
            Item item = entry.getStack().getItem();
            long available = countItemInInventory(player, item);
            entry.setCountAvailable((int) available);
            entry.setCountMissing(Math.max(0, entry.getCountTotal() - (int) available));
        }
    }

    public static long countItemInInventory(PlayerEntity player, Item item) {
        long count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static List<MaterialListEntry> convertFromMaterialEntries(List<MaterialEntry> entries) {
        List<MaterialListEntry> result = new ArrayList<>();
        for (MaterialEntry entry : entries) {
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
