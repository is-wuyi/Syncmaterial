package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to enable the Material Gathering button in Syncmatica server placement list.
 *
 * 注入目标：
 * 1. 启用构造方法中的按钮
 * 2. 替换点击后的空实现
 */
@Mixin(targets = "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry", remap = false)
public abstract class WidgetSyncmaticaServerPlacementEntryMixin {

    /**
     * 强制启用材料按钮
     * 修改传递给 setEnabled 方法的参数，从 false 改为 true
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
        // 强制返回 true，启用按钮
        return true;
    }
}

/**
 * Mixin for ButtonListener to handle material button clicks
 */
@Mixin(targets = "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry$ButtonListener", remap = false)
class ButtonListenerMixin {

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
            // 获取类型字段
            Object type = this.getClass().getDeclaredField("type").get(this);
            SyncMaterial.LOGGER.info("ButtonListener actionPerformedWithButton called, type: {}, equals: {}", type.toString(), type.toString().equals("MATERIAL_GATHERING"));
            if (type != null && type.toString().equals("MATERIAL_GATHERING")) {
                // 执行我们的请求逻辑
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                try {
                    // 获取 schematic ID
                    Object widget = this.getClass().getDeclaredField("placement").get(this);
                    Object serverPlacement = widget.getClass().getMethod("getEntry").invoke(widget);
                    String schematicId = serverPlacement.getClass().getMethod("getId").invoke(serverPlacement).toString();

                        // 发送请求
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new MaterialStatsRequestC2SPacket(schematicId)
                        );

                        SyncMaterial.LOGGER.info("Sent material request for schematic: {}", schematicId);

                    } catch (Exception e) {
                        SyncMaterial.LOGGER.error("Failed to get schematic ID for material request", e);
                    }
                }

                // 取消原来的执行
                ci.cancel();
            }
        } catch (Exception e) {
            // 如果反射失败，继续执行原来的逻辑
            SyncMaterial.LOGGER.error("Failed to check button type", e);
        }
    }
}