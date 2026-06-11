package net.syncmaterial.syncmaterial.client.render;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;

import com.mojang.blaze3d.systems.RenderSystem;

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
    private final Color4f colorSelected = new Color4f(1.0f, 0.8f, 0.0f, 1.0f);
    private final Color4f colorSide = new Color4f(0.0f, 1.0f, 0.0f, 0.18f);
    private final Color4f colorSelectedSide = new Color4f(1.0f, 0.8f, 0.0f, 0.18f);

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
            Box selectedBox = selection.getSelectedSubRegionBox();

            for (Box box : selection.getAllSubRegionBoxes())
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

                // 标注名称：原理图名称 - 备货区名称
                String schematicName = this.schematicNames.getOrDefault(entry.getKey(), "");
                String label = schematicName.isEmpty()
                    ? box.getName()
                    : schematicName + " - " + box.getName();
                double cx = (pos1.getX() + pos2.getX()) / 2.0 + 0.5;
                double cy = Math.max(pos1.getY(), pos2.getY()) + 0.5;
                double cz = (pos1.getZ() + pos2.getZ()) / 2.0 + 0.5;
                renderLabel(camera, label, cx, cy, cz);
            }
        }

        StagingAreaSelector.getInstance().onRenderWorld(this, posMatrix);

        profiler.pop();
    }

    /**
     * 在世界坐标中渲染小文字标签（无背景板，仅阴影）
     */
    private void renderLabel(Camera camera, String text, double x, double y, double z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer textRenderer = mc.textRenderer;
        Vec3d camPos = camera.getPos();

        float scale = 0.02f; // 文字约 0.18 blocks 高

        Matrix4fStack modelStack = RenderSystem.getModelViewStack();
        modelStack.pushMatrix();
        modelStack.translate((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z));
        modelStack.rotateYXZ(
            -camera.getYaw() * ((float) (Math.PI / 180.0)),
            camera.getPitch() * ((float) (Math.PI / 180.0)),
            0.0F);
        modelStack.scale(-scale, -scale, scale);

        int textWidth = textRenderer.getWidth(text);
        Matrix4f identityMatrix = new Matrix4f().identity();

        var allocator = new net.minecraft.client.util.BufferAllocator(4096);
        var immediate = net.minecraft.client.render.VertexConsumerProvider.immediate(allocator);
        // 阴影层
        textRenderer.draw(text, -textWidth / 2f + 1, 1, 0x40000000, false,
            identityMatrix, immediate, TextRenderer.TextLayerType.NORMAL, 0, 15728880);
        // 正文层
        textRenderer.draw(text, -textWidth / 2f, 0, 0xFFFFFFFF, false,
            identityMatrix, immediate, TextRenderer.TextLayerType.NORMAL, 0, 15728880);
        immediate.draw();
        allocator.close();

        modelStack.popMatrix();
    }
}
