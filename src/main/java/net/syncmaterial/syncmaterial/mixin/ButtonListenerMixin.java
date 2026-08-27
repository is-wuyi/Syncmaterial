package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for ButtonListener to handle material button clicks
 */
@Mixin(targets = "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry$ButtonListener", remap = false)
public class ButtonListenerMixin {

    /**
     * 注入点击处理逻辑
     * 在 actionPerformedWithButton 方法执行前检查是否为 MATERIAL_GATHERING 按钮
     */
    @Inject(
        method = "actionPerformedWithButton",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void handleMaterialButtonClick(fi.dy.masa.malilib.gui.button.ButtonBase button, int arg1, CallbackInfo ci) {
        try {
            java.lang.reflect.Field typeField = ((Object) this).getClass().getDeclaredField("type");
            typeField.setAccessible(true);
            Object type = typeField.get(this);

            if (type != null && type.toString().equals("MATERIAL_GATHERING")) {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    try {
                        java.lang.reflect.Field placementField = ((Object) this).getClass().getDeclaredField("placement");
                        placementField.setAccessible(true);
                        Object widget = placementField.get(this);

                        Object serverPlacement = widget.getClass().getMethod("getEntry").invoke(widget);
                        String schematicId = serverPlacement.getClass().getMethod("getId").invoke(serverPlacement).toString();

                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new MaterialStatsRequestC2SPacket(schematicId)
                        );

                        SyncMaterial.LOGGER.info("Sent material request for schematic: {}", schematicId);
                    } catch (Exception e) {
                        SyncMaterial.LOGGER.error("Failed to get schematic ID", e);
                    }
                }
                ci.cancel();
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("Failed to handle button click", e);
        }
    }
}
