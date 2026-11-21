package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MaterialListUtils {
    /** Local samples include cursor-carried items and never replace team progress with solo progress. */
    public static void updateAvailableCounts(List<MaterialListEntry> list, Player player) {
        if (player == null) return;
        Map<String, Long> playerCounts = net.syncmaterial.syncmaterial.client.InventoryScanner
                .liveCountsByItemId(player);
        for (MaterialListEntry entry : list) {
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getStack().getItem()).toString();
            long playerCount = playerCounts.getOrDefault(itemId, 0L);
            entry.setCountAvailable(playerCount);
            if (entry.hasAuthoritativeTeamCounts()) continue;
            long teamCount = net.syncmaterial.syncmaterial.api.ProgressFormulas.saturatingAdd(
                    playerCount, entry.getOtherPlayersCount());
            entry.setCountMissing(net.syncmaterial.syncmaterial.api.ProgressFormulas.collectedMissing(
                    entry.getCountTotal(), entry.getStagingCount(), entry.getWarehouseCount(), teamCount));
        }
    }

    public static List<MaterialListEntry> convertFromMaterialEntries(
            List<net.syncmaterial.syncmaterial.api.MaterialEntry> entries) {
        List<MaterialListEntry> result = new ArrayList<>();
        for (net.syncmaterial.syncmaterial.api.MaterialEntry entry : entries) {
            MaterialListEntry converted = new MaterialListEntry(
                    entry.getDatabaseId(), entry.getStack(), entry.getCountTotal(),
                    entry.getCountMissing(), entry.getCountMismatched(), entry.getCountAvailable());
            converted.markAuthoritativeTeamCounts();
            result.add(converted);
        }
        return result;
    }
}
