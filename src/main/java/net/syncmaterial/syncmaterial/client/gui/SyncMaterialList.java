package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiClaimDialog;
import net.syncmaterial.syncmaterial.network.ClaimMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.MaterialStatusS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncMaterialList extends MaterialListBase {
    private final String title;
    private final Map<Integer, MaterialStatusS2CPacket.MaterialStatusEntry> materialStatusMap = new HashMap<>();

    public SyncMaterialList(String title) {
        this.title = title;
    }

    @Override
    public String getName() {
        return this.title;
    }

    @Override
    public String getTitle() {
        return this.title;
    }

    @Override
    public void reCreateMaterialList() {
    }

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
    }

    public void updateMaterialStatus(List<MaterialStatusS2CPacket.MaterialStatusEntry> statuses) {
        materialStatusMap.clear();
        for (var status : statuses) {
            materialStatusMap.put(status.materialId(), status);
        }
        updateEntriesWithStatus();
    }

    public Map<Integer, MaterialStatusS2CPacket.MaterialStatusEntry> getMaterialStatusMap() {
        return materialStatusMap;
    }

    private void updateEntriesWithStatus() {
        List<MaterialListEntry> entries = this.getMaterialsAll();
        for (int i = 0; i < entries.size(); i++) {
            MaterialListEntry entry = entries.get(i);
            MaterialStatusS2CPacket.MaterialStatusEntry status = materialStatusMap.get(i + 1);
            if (status != null) {
                String claimer = status.claimer().isEmpty() ? null : status.claimer();
                if (claimer != null) {
                    String playerName = MinecraftClient.getInstance().player.getGameProfile().getName();
                    this.setClaimStatus(entry, claimer.equals(playerName) ? "我" : claimer);
                } else {
                    this.setClaimStatus(entry, "未认领");
                }
            }
        }
        this.updateCounts();
    }

    @Override
    public void claimEntry(MaterialListEntry entry) {
        int totalNeeded = entry.getCountMissing();
        if (totalNeeded <= 0) return;

        GuiClaimDialog dialog = new GuiClaimDialog(
            MinecraftClient.getInstance().currentScreen,
            entry.getStack().getName().getString(),
            totalNeeded
        );
        MinecraftClient.getInstance().setScreen(dialog);
    }
}
