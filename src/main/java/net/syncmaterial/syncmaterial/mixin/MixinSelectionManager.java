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
import net.syncmaterial.syncmaterial.SyncMaterial;
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
            try
            {
                cir.setReturnValue(editor.getLitematicaSelection());
            }
            catch (Exception e)
            {
                SyncMaterial.LOGGER.error("[Mixin] getCurrentSelection EXCEPTION!", e);
            }
        }
    }

    @Inject(method = "setPositionOfCurrentSelectionToRayTrace", at = @At("HEAD"), cancellable = false)
    private void onSetPositionOfCurrentSelectionToRayTraceHead(MinecraftClient mc, Corner corner, boolean moveEntireSelection, double maxDistance, CallbackInfo ci)
    {
        if (GuiStagingAreaEditorNormal.getCurrentEditor() != null)
        {
            SyncMaterial.LOGGER.info("[Mixin] setPositionOfCurrentSelectionToRayTrace HEAD: corner={}", corner);
        }
    }

    @Inject(method = "setPositionOfCurrentSelectionToRayTrace", at = @At("RETURN"), cancellable = false)
    private void onSetPositionOfCurrentSelectionToRayTrace(MinecraftClient mc, Corner corner, boolean moveEntireSelection, double maxDistance, CallbackInfo ci)
    {
        try
        {
            GuiStagingAreaEditorNormal editor = GuiStagingAreaEditorNormal.getCurrentEditor();
            if (editor != null && corner != Corner.NONE)
            {
                SyncMaterial.LOGGER.info("[Mixin] setPositionOfCurrentSelectionToRayTrace RETURN: syncing corner {}", corner);
                editor.syncCornerToServer(corner);
            }
        }
        catch (Exception e)
        {
            SyncMaterial.LOGGER.error("[Mixin] setPositionOfCurrentSelectionToRayTrace RETURN EXCEPTION!", e);
        }
    }

    @Inject(method = "handleCuboidModeMouseClick", at = @At("RETURN"), cancellable = false)
    private void onHandleCuboidModeMouseClickReturn(MinecraftClient mc, double maxDistance, boolean isRightClick, boolean moveEntireSelection, CallbackInfo ci)
    {
        try
        {
            GuiStagingAreaEditorNormal editor = GuiStagingAreaEditorNormal.getCurrentEditor();
            if (editor != null)
            {
                editor.syncLitematicaChangesToSelection();
                editor.syncCornerToServer(Corner.CORNER_1);
                editor.syncCornerToServer(Corner.CORNER_2);
            }
        }
        catch (Exception e)
        {
            SyncMaterial.LOGGER.error("[Mixin] handleCuboidModeMouseClick RETURN EXCEPTION!", e);
        }
    }
}
