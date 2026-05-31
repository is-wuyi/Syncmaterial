package net.syncmaterial.syncmaterial.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.syncmaterial.syncmaterial.SyncMaterial;

@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys")
public class MixinKeyCallbacks
{
    @Inject(method = "onKeyAction", at = @At("HEAD"), cancellable = false)
    private void onKeyActionHead(KeyAction action, IKeybind key, CallbackInfoReturnable<Boolean> cir)
    {
        ToolMode mode = DataManager.getToolMode();
        SyncMaterial.LOGGER.info("[Mixin-KeyCallbacks] onKeyAction: action={}, key={}, mode={}, usesAreaSelection={}",
                action, key.getKeysDisplayString(), mode, mode.getUsesAreaSelection());
    }
}
