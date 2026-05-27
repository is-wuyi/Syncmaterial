package net.syncmaterial.syncmaterial.client.render;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;

public class StagingAreaRenderer implements IRenderer
{
    private static final StagingAreaRenderer INSTANCE = new StagingAreaRenderer();

    private final Color4f colorArea = new Color4f(0.0f, 1.0f, 0.0f, 1.0f);
    private final Color4f colorSelected = new Color4f(1.0f, 0.8f, 0.0f, 1.0f);
    private final Color4f colorSide = new Color4f(0.0f, 1.0f, 0.0f, 0.18f);
    private final Color4f colorSelectedSide = new Color4f(1.0f, 0.8f, 0.0f, 0.18f);

    @Nullable
    private AreaSelection currentSelection;

    private StagingAreaRenderer() {}

    public static StagingAreaRenderer getInstance()
    {
        return INSTANCE;
    }

    public void setCurrentSelection(@Nullable AreaSelection selection)
    {
        this.currentSelection = selection;
    }

    @Nullable
    public AreaSelection getCurrentSelection()
    {
        return this.currentSelection;
    }

    @Override
    public void onRenderWorldLastAdvanced(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix,
            Frustum frustum, Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
        if (this.currentSelection == null)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null)
        {
            return;
        }

        profiler.push("syncmaterial_staging_areas");

        Box selectedBox = this.currentSelection.getSelectedSubRegionBox();

        for (Box box : this.currentSelection.getAllSubRegionBoxes())
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            boolean isSelected = box == selectedBox;
            Color4f lineColor = isSelected ? this.colorSelected : this.colorArea;
            Color4f sideColor = isSelected ? this.colorSelectedSide : this.colorSide;

            RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, lineColor, lineColor, lineColor);
            RenderUtils.renderAreaSides(pos1, pos2, sideColor, posMatrix);
        }

        profiler.pop();
    }
}
