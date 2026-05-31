package net.syncmaterial.syncmaterial.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.MinecraftClient;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;

@Mixin(SelectionManager.class)
public class MixinSelectionManager
{
    @Inject(method = "getCurrentSelection", at = @At("HEAD"), cancellable = true)
    private void onGetCurrentSelection(CallbackInfoReturnable<AreaSelection> cir)
    {
        GuiStagingAreaEditorNormal editor = GuiStagingAreaEditorNormal.getCurrentEditor();
        if (editor != null)
        {
            cir.setReturnValue(editor.getLitematicaSelection());
        }
    }

    @Inject(method = "setPositionOfCurrentSelectionToRayTrace", at = @At("RETURN"), cancellable = false)
    private void onSetPositionOfCurrentSelectionToRayTrace(MinecraftClient mc, Corner corner, boolean moveEntireSelection, double maxDistance, CallbackInfo ci)
    {
        GuiStagingAreaEditorNormal editor = GuiStagingAreaEditorNormal.getCurrentEditor();
        if (editor != null && corner != Corner.NONE)
        {
            editor.syncCornerToServer(corner);
        }
    }
}
