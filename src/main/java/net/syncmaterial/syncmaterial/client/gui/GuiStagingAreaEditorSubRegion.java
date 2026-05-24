package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class GuiStagingAreaEditorSubRegion extends GuiStagingAreaEditorSimple
{
    protected final Box box;

    public GuiStagingAreaEditorSubRegion(AreaSelection selection, Box box, @Nullable String selectionId)
    {
        super(selection, selectionId);

        this.box = box;
        this.title = StringUtils.translate("litematica.gui.title.area_editor_sub_region");
    }

    @Override
    protected void createSelectionEditFields()
    {
    }

    @Override
    protected int addSubRegionFields(int x, int y)
    {
        x = 12;
        y = 24;
        String label = StringUtils.translate("litematica.gui.label.area_editor.box_name");
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, label);
        y += 13;

        int width = 202;
        this.textFieldBoxName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.textRenderer);
        this.textFieldBoxName.setTextWrapper(this.getBox().getName());
        this.addTextField(this.textFieldBoxName, new TextFieldListenerDummy());
        this.createButton(x + width + 4, y, -1, ButtonListener.Type.SET_BOX_NAME);
        y += 20;

        x = 12;
        width = 68;

        this.createCoordinateInputs(x, y, width, Corner.CORNER_1);
        x += width + 42;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_2);
        x += width + 42;

        y = this.getScreenHeight() - 26;
        String backLabel = "\u2190 返回";
        int backWidth = this.getStringWidth(backLabel) + 10;
        x = 12;
        this.addButton(new ButtonGeneric(x, y, backWidth, 20, backLabel),
                new BackButtonListener(this));

        return y;
    }

    @Override
    @Nullable
    protected Box getBox()
    {
        return this.box;
    }

    @Override
    protected void renameSubRegion()
    {
        String oldName = this.box.getName();
        String newName = this.textFieldBoxName.getTextWrapper();
        this.selection.renameSubRegionBox(oldName, newName);

        if (this.schematicId != null)
        {
            Integer serverId = this.selection.getServerId(oldName);

            if (serverId != null)
            {
                BlockPos pos1 = this.box.getPos1();
                BlockPos pos2 = this.box.getPos2();
                int x1 = Math.min(pos1.getX(), pos2.getX());
                int y1 = Math.min(pos1.getY(), pos2.getY());
                int z1 = Math.min(pos1.getZ(), pos2.getZ());
                int x2 = Math.max(pos1.getX(), pos2.getX());
                int y2 = Math.max(pos1.getY(), pos2.getY());
                int z2 = Math.max(pos1.getZ(), pos2.getZ());
                AreaData areaData = new AreaData(newName, x1, y1, z1, x2, y2, z2, Optional.empty());
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        this.schematicId, "RENAME", serverId, Optional.of(areaData)));

                this.selection.removeServerId(oldName);
                this.selection.setServerId(newName, serverId);
            }
        }
    }

    @Override
    protected void createOrigin()
    {
    }

    @Override
    protected WidgetListStagingAreas getListWidget()
    {
        return null;
    }

    @Override
    protected void reCreateListWidget()
    {
    }

    private static class BackButtonListener implements fi.dy.masa.malilib.gui.button.IButtonActionListener
    {
        private final GuiStagingAreaEditorSubRegion gui;

        BackButtonListener(GuiStagingAreaEditorSubRegion gui)
        {
            this.gui = gui;
        }

        @Override
        public void actionPerformedWithButton(fi.dy.masa.malilib.gui.button.ButtonBase button, int mouseButton)
        {
            GuiStagingAreaEditorNormal editor = new GuiStagingAreaEditorNormal(
                    this.gui.selection, null, this.gui.schematicId);
            editor.setNeedsServerLoad();
            GuiBase.openGui(editor);
        }
    }
}
