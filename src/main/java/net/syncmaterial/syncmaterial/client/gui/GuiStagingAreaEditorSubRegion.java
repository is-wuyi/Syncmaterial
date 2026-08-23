/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced DataManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

/**
 * 子区域编辑器：显示两个角的坐标编辑界面，独立于主列表界面
 */
public class GuiStagingAreaEditorSubRegion extends GuiBase
{
    protected final AreaSelection selection;
    protected final String schematicId;
    protected final Box box;
    protected GuiTextFieldGeneric textFieldBoxName;
    protected WidgetCheckBox checkBoxCorner1;
    protected WidgetCheckBox checkBoxCorner2;

    public GuiStagingAreaEditorSubRegion(AreaSelection selection, Box box, @Nullable String selectionId)
    {
        this(selection, box, selectionId, "");
    }

    public GuiStagingAreaEditorSubRegion(AreaSelection selection, Box box, @Nullable String selectionId, String schematicId)
    {
        this.selection = selection;
        this.schematicId = schematicId;
        this.box = box;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("litematica.gui.title.area_editor_sub_region");
    }

    @Override
    public void initGui()
    {
        super.initGui();

        int x = 12;
        int y = 24;
        int width = 202;

        // 箱子名称
        String label = StringUtils.translate("litematica.gui.label.area_editor.box_name");
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, label);
        y += 13;

        this.textFieldBoxName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.textRenderer);
        this.textFieldBoxName.setTextWrapper(this.box.getName());
        this.addTextField(this.textFieldBoxName, new TextFieldListenerDummy());
        this.createButton(x + width + 4, y, -1, SubRegionButtonListener.Type.SET_BOX_NAME);
        y += 20;

        // 两个角的坐标
        x = 12;
        width = 68;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_1);
        x += width + 42;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_2);

        // 返回按钮
        y = this.getScreenHeight() - 26;
        String backLabel = StringUtils.translate("gui.back");
        int backWidth = StringUtils.getStringWidth(backLabel) + 10;
        this.addButton(new ButtonGeneric(12, y, backWidth, 20, backLabel),
                new SubRegionButtonListener(SubRegionButtonListener.Type.BACK, null, null, this));
    }

    // ========== 坐标编辑 ==========

    protected int createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label;
        WidgetCheckBox widget = null;

        switch (corner)
        {
            case CORNER_1:
                label = StringUtils.translate("syncmaterial.gui.label.corner_1");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner1 = widget;
                break;
            case CORNER_2:
                label = StringUtils.translate("syncmaterial.gui.label.corner_2");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner2 = widget;
                break;
            default:
                return y;
        }

        if (widget != null)
        {
            widget.setListener(new CheckBoxListener(corner, this));
            this.addWidget(widget);
        }
        y += 14;

        this.createCoordinateInput(x, y, width, CoordinateType.X, corner);
        y += 20;
        this.createCoordinateInput(x, y, width, CoordinateType.Y, corner);
        y += 20;
        this.createCoordinateInput(x, y, width, CoordinateType.Z, corner);
        y += 22;
        this.createButton(x + 10, y, -1, SubRegionButtonListener.Type.MOVE_TO_PLAYER);
        y += 22;

        return y;
    }

    protected void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        String label = coordType.name() + ":";
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, label);
        int offset = 12;

        y += 2;
        BlockPos pos = this.box.getPosition(corner);
        String text = "";
        SubRegionButtonListener.Type type = null;

        switch (coordType)
        {
            case X:
                text = String.valueOf(pos.getX());
                type = SubRegionButtonListener.Type.NUDGE_COORD_X;
                break;
            case Y:
                text = String.valueOf(pos.getY());
                type = SubRegionButtonListener.Type.NUDGE_COORD_Y;
                break;
            case Z:
                text = String.valueOf(pos.getZ());
                type = SubRegionButtonListener.Type.NUDGE_COORD_Z;
                break;
        }

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.textRenderer);
        TextFieldListener listener = new TextFieldListener(coordType, corner, this);
        textField.setTextWrapper(text);
        this.addTextField(textField, listener);

        String hover = StringUtils.translate("syncmaterial.gui.button.hover.plus_minus_tip");
        ButtonGeneric button = new ButtonGeneric(x + offset + width + 4, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        this.addButton(button, new SubRegionButtonListener(type, corner, coordType, this));
    }

    protected int createButton(int x, int y, int width, SubRegionButtonListener.Type type)
    {
        String label = type.getDisplayName();
        if (width == -1)
        {
            width = StringUtils.getStringWidth(label) + 10;
        }
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        this.addButton(button, new SubRegionButtonListener(type, null, null, this));
        return width;
    }

    // ========== 坐标操作 ==========

    protected void updatePosition(String numberString, Corner corner, CoordinateType type)
    {
        try
        {
            int value = Integer.parseInt(numberString);
            this.selection.setCoordinate(this.box, corner, type, value);
        }
        catch (NumberFormatException e)
        {
        }
    }

    protected void moveCoordinate(int amount, Corner corner, CoordinateType type)
    {
        int oldValue = this.box.getCoordinate(corner, type);
        this.selection.setCoordinate(this.box, corner, type, oldValue + amount);
    }

    protected void sendCoordinateUpdate(Corner corner)
    {
        if (this.schematicId == null) return;

        Integer serverId = this.selection.getServerId(this.box.getName());
        if (serverId == null) return;

        BlockPos pos1 = this.box.getPos1();
        BlockPos pos2 = this.box.getPos2();
        AreaData areaData = GuiStagingAreaEditorNormal.toAreaData(this.box.getName(), pos1, pos2);
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                this.schematicId, "UPDATE", serverId, Optional.of(areaData)));
    }

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
                AreaData areaData = GuiStagingAreaEditorNormal.toAreaData(newName, pos1, pos2);
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        this.schematicId, "RENAME", serverId, Optional.of(areaData)));

                this.selection.removeServerId(oldName);
                this.selection.setServerId(newName, serverId);
            }
        }
    }

    protected void updateCheckBoxes()
    {
        if (this.checkBoxCorner1 != null)
        {
            boolean checked = this.box.getSelectedCorner() == Corner.CORNER_1;
            this.checkBoxCorner1.setChecked(checked, false);
        }
        if (this.checkBoxCorner2 != null)
        {
            boolean checked = this.box.getSelectedCorner() == Corner.CORNER_2;
            this.checkBoxCorner2.setChecked(checked, false);
        }
    }

    // ========== 内部类 ==========

    private static class SubRegionButtonListener implements IButtonActionListener
    {
        private final GuiStagingAreaEditorSubRegion parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        public SubRegionButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType, GuiStagingAreaEditorSubRegion parent)
        {
            this.type = type;
            this.corner = corner;
            this.coordinateType = coordinateType;
            this.parent = parent;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            int amount = mouseButton == 1 ? -1 : 1;
            if (GuiBase.isCtrlDown()) { amount *= 100; }
            if (GuiBase.isShiftDown()) { amount *= 10; }
            if (GuiBase.isAltDown()) { amount *= 5; }

            switch (this.type)
            {
                case NUDGE_COORD_X:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;
                case NUDGE_COORD_Y:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;
                case NUDGE_COORD_Z:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;
                case SET_BOX_NAME:
                    this.parent.renameSubRegion();
                    break;
                case MOVE_TO_PLAYER:
                    if (this.parent.mc.player != null)
                    {
                        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.parent.mc.player);
                        this.parent.selection.setSelectedSubRegionCornerPos(pos, this.corner);
                        this.parent.sendCoordinateUpdate(this.corner);
                    }
                    break;
                case BACK:
                    this.parent.closeGui(true);
                    return;
            }
            this.parent.initGui();
        }

        public enum Type
        {
            SET_BOX_NAME("syncmaterial.gui.button.rename_staging_area"),
            MOVE_TO_PLAYER("litematica.gui.button.move_to_player"),
            BACK("gui.back"),
            NUDGE_COORD_X(""),
            NUDGE_COORD_Y(""),
            NUDGE_COORD_Z("");

            private final String translationKey;
            private Type(String translationKey) { this.translationKey = translationKey; }
            public String getDisplayName(Object... args) { return StringUtils.translate(this.translationKey, args); }
        }
    }

    private static class TextFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiStagingAreaEditorSubRegion parent;
        private final CoordinateType type;
        private final Corner corner;

        public TextFieldListener(CoordinateType type, Corner corner, GuiStagingAreaEditorSubRegion parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.parent.updatePosition(textField.getTextWrapper(), this.corner, this.type);
            this.parent.sendCoordinateUpdate(this.corner);
            return false;
        }
    }

    private static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField) { return false; }
    }

    private static class CheckBoxListener implements fi.dy.masa.malilib.gui.interfaces.ISelectionListener<WidgetCheckBox>
    {
        private final GuiStagingAreaEditorSubRegion gui;
        private final Corner corner;

        public CheckBoxListener(Corner corner, GuiStagingAreaEditorSubRegion gui)
        {
            this.gui = gui;
            this.corner = corner;
        }

        @Override
        public void onSelectionChange(WidgetCheckBox entry)
        {
            if (entry.isChecked())
            {
                this.gui.selection.setCurrentSelectedCorner(this.corner);
            }
            else
            {
                this.gui.selection.clearCurrentSelectedCorner();
            }
            this.gui.updateCheckBoxes();
        }
    }
}
