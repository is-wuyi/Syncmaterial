package net.syncmaterial.syncmaterial.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mixin to enable the Material Gathering button in Syncmatica server placement list.
 */
@Mixin(targets = "ch.endte.syncmatica.litematica.gui.WidgetSyncmaticaServerPlacementEntry", remap = false)
public class WidgetSyncmaticaServerPlacementEntryMixin {

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
        return true;
    }
}
