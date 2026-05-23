package net.syncmaterial.syncmaterial.client.gui;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import net.syncmaterial.syncmaterial.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class GuiStagingAreaEditorSimple extends GuiStagingAreaEditorNormal
{
    protected GuiTextFieldGeneric textFieldBoxName;

    public GuiStagingAreaEditorSimple(AreaSelection selection, @Nullable String selectionId)
    {
        super(selection, selectionId);

        this.title = StringUtils.translate("litematica.gui.title.area_editor_simple");
    }

    @Override
    protected String getSelectionModeName()
    {
        return SelectionMode.SIMPLE.getDisplayName();
    }

    @Override
    protected int addSubRegionFields(int x, int y)
    {
        x = 12;
        String label = StringUtils.translate("litematica.gui.label.area_editor.box_name");
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, label);
        y += 13;

        boolean currentlyOn = this.selection.getExplicitOrigin() != null;
        this.createButtonOnOff(this.xOrigin, 24, -1, currentlyOn, ButtonListener.Type.TOGGLE_ORIGIN_ENABLED);

        int width = 202;
        this.textFieldBoxName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.textRenderer);
        this.textFieldBoxName.setTextWrapper(this.getBox().getName());
        this.addTextField(this.textFieldBoxName, new TextFieldListenerDummy());
        this.createButton(x + width + 4, y, -1, ButtonListener.Type.SET_BOX_NAME);
        y += 20;

        x = 12;
        width = 68;

        int nextY = 0;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_1);
        x += width + 42;
        nextY = this.createCoordinateInputs(x, y, width, Corner.CORNER_2);
        x += width + 42;

        if (this.selection.getExplicitOrigin() != null)
        {
            this.createCoordinateInputs(x, y, width, Corner.NONE);
        }

        x = 22;

        return y;
    }

    @Override
    @Nullable
    protected Box getBox()
    {
        return this.selection.getSelectedSubRegionBox();
    }

    @Override
    protected void renameSubRegion()
    {
        String oldName = this.selection.getCurrentSubRegionBoxName();
        String newName = this.textFieldBoxName.getTextWrapper();
        this.selection.renameSubRegionBox(oldName, newName);
    }

    @Override
    protected void renameSelection(String newName)
    {
        this.selection.setName(newName);
    }

    @Override
    protected void createOrigin()
    {
        if (this.getBox() != null)
        {
            BlockPos pos1 = this.getBox().getPos1();
            BlockPos pos2 = this.getBox().getPos2();
            BlockPos origin = PositionUtils.getMinCorner(pos1, pos2);
            this.selection.setExplicitOrigin(origin);
        }
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
}
