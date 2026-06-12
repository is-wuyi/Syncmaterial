package net.syncmaterial.syncmaterial.client.render;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

import org.joml.Matrix4f;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;

public class StagingAreaRenderer implements IRenderer
{
    private static final StagingAreaRenderer INSTANCE = new StagingAreaRenderer();

    private final Color4f colorArea = new Color4f(0.0f, 1.0f, 0.0f, 1.0f);
    private final Color4f colorSide = new Color4f(0.0f, 1.0f, 0.0f, 0.18f);

    private final Map<String, AreaSelection> selections = new HashMap<>();
    private final Map<String, Boolean> renderEnabled = new HashMap<>();
    private final Map<String, String> schematicNames = new HashMap<>();

    private StagingAreaRenderer() {}

    public static StagingAreaRenderer getInstance()
    {
        return INSTANCE;
    }

    public void updateSelection(String schematicId, AreaSelection selection)
    {
        this.selections.put(schematicId, selection);
    }

    public void removeSelection(String schematicId)
    {
        this.selections.remove(schematicId);
    }

    @Nullable
    public AreaSelection getSelection(String schematicId)
    {
        return this.selections.get(schematicId);
    }

    public void setRenderEnabled(String schematicId, boolean enabled)
    {
        this.renderEnabled.put(schematicId, enabled);
    }

    public boolean isRenderEnabled(String schematicId)
    {
        return this.renderEnabled.getOrDefault(schematicId, true);
    }

    public void removeRenderData(String schematicId)
    {
        this.selections.remove(schematicId);
        this.renderEnabled.remove(schematicId);
        this.schematicNames.remove(schematicId);
    }

    public void setSchematicName(String schematicId, String name)
    {
        this.schematicNames.put(schematicId, name);
    }

    @Nullable
    public String getSchematicName(String schematicId)
    {
        return this.schematicNames.get(schematicId);
    }

    @Override
    public void onRenderWorldLastAdvanced(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix,
            Frustum frustum, Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
        if (this.selections.isEmpty())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null)
        {
            return;
        }

        profiler.push("syncmaterial_staging_areas");

        for (Map.Entry<String, AreaSelection> entry : this.selections.entrySet())
        {
            if (!isRenderEnabled(entry.getKey()))
            {
                continue;
            }
            AreaSelection selection = entry.getValue();
            for (Box box : selection.getAllSubRegionBoxes())
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();

                if (pos1 == null || pos2 == null)
                {
                    continue;
                }

                RenderUtils.renderAreaOutline(pos1, pos2, 2.0f, this.colorArea, this.colorArea, this.colorArea);
                RenderUtils.renderAreaSides(pos1, pos2, this.colorSide, posMatrix);

                // 标注名称：原理图名称 - 备货区名称
                String schematicName = this.schematicNames.getOrDefault(entry.getKey(), "");
                String label = schematicName.isEmpty()
                    ? box.getName()
                    : schematicName + " - " + box.getName();
                double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                RenderUtils.drawTextPlate(Collections.singletonList(label), cx, cy, cz, 0.05f);
            }
        }

        StagingAreaSelector.getInstance().onRenderWorld(this, posMatrix);

        profiler.pop();
    }
}
