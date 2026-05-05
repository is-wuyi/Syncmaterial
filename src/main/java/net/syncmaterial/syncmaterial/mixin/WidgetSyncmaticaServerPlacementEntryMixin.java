package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to enable the Material Gathering button in Syncmatica server placement list.
 */
@Mixin(targets = {
    "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry",
    "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry$ButtonListener"
}, remap = false)
public class WidgetSyncmaticaServerPlacementEntryMixin {

    /**
     * 强制启用材料按钮
     */
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lfi/dy/masa/malilib/gui/button/ButtonGeneric;setEnabled(Z)V",
            ordinal = 0
        ),
        index = 0,
        remap = false
    )
    private boolean forceEnableButton(boolean originalEnabled) {
        return true;
    }

    /**
     * 处理材料按钮点击
     */
    @Inject(
        method = "actionPerformedWithButton",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void handleMaterialButtonClick(fi.dy.masa.malilib.gui.button.ButtonBase button, int arg1, CallbackInfo ci) {
        try {
            // 获取 type 字段
            java.lang.reflect.Field typeField = this.getClass().getDeclaredField("type");
            typeField.setAccessible(true);
            Object type = typeField.get(this);

            if (type != null && type.toString().equals("MATERIAL_GATHERING")) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    try {
                        // 获取 placement 字段
                        java.lang.reflect.Field placementField = this.getClass().getDeclaredField("placement");
                        placementField.setAccessible(true);
                        Object widget = placementField.get(this);

                        // 获取 schematic ID
                        Object serverPlacement = widget.getClass().getMethod("getEntry").invoke(widget);
                        String schematicId = serverPlacement.getClass().getMethod("getId").invoke(serverPlacement).toString();

                        // 发送请求
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new MaterialStatsRequestC2SPacket(schematicId)
                        );

                        SyncMaterial.LOGGER.info("Sent material request for schematic: {}", schematicId);
                    } catch (Exception e) {
                        SyncMaterial.LOGGER.error("Failed to get schematic ID", e);
                    }
                }
                // 取消原生操作
                ci.cancel();
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("Failed to handle button click", e);
        }
    }
}