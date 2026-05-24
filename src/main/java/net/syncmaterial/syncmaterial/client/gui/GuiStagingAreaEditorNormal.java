package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.*;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetStagingAreaEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import net.syncmaterial.syncmaterial.selection.SelectionMode;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class GuiStagingAreaEditorNormal extends GuiListBase<StagingAreaEntry, WidgetStagingAreaEntry, WidgetListStagingAreas>
                                          implements ISelectionListener<StagingAreaEntry>, StagingAreaEditorGui
{
    protected final AreaSelection selection;
    protected final String schematicId;
    protected GuiTextFieldGeneric textFieldSelectionName;
    protected WidgetCheckBox checkBoxOrigin;
    protected WidgetCheckBox checkBoxCorner1;
    protected WidgetCheckBox checkBoxCorner2;
    protected int xNext;
    protected int yNext;
    protected int xOrigin;
    protected int xSet;
    @Nullable protected String selectionId;
    protected boolean needsServerLoad = false;
    protected boolean loadingFromServer = false;

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId)
    {
        this(selection, selectionId, "");
    }

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId, String schematicId)
    {
        super(8, 116);

        this.selection = selection;
        this.selectionId = selectionId;
        this.schematicId = schematicId;
        this.needsServerLoad = schematicId != null && !schematicId.isEmpty();
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("litematica.gui.title.area_editor_normal");
    }

    public void setSelectionId(@Nullable String selectionId)
    {
        this.selectionId = selectionId;
    }

    @Override
    public String getSchematicId()
    {
        return this.schematicId;
    }

    @Override
    public void deleteArea(int areaId)
    {
        this.selection.removeSubRegionBox(this.selection.getAllSubRegionNames().stream()
                .skip(areaId).findFirst().orElse(""));
        this.initGui();
    }

    @Override
    public void refreshAreas()
    {
        this.initGui();
    }

    public void onServerResponse(StagingAreaConfigResponseS2CPacket packet)
    {
        if (!packet.success())
        {
            return;
        }

        if (this.loadingFromServer)
        {
            this.loadingFromServer = false;

            this.selection.removeAllSubRegionBoxes();

            for (var area : packet.areas())
            {
                BlockPos pos1 = new BlockPos(area.x1(), area.y1(), area.z1());
                BlockPos pos2 = new BlockPos(area.x2(), area.y2(), area.z2());
                Box box = new Box(pos1, pos2, area.name());
                this.selection.addSubRegionBox(box, true);
            }

            this.initGui();
        }
    }

    @Override
    public void initGui()
    {
        super.initGui();

        if (this.selection != null)
        {
            this.createSelectionEditFields();
            this.addSubRegionFields(this.xOrigin, this.yNext);
            this.updateCheckBoxes();
        }
        else
        {
            this.addLabel(20, 30, 120, 12, 0xFFFFAA00, StringUtils.translate("litematica.error.area_editor.no_selection"));
        }

        // 首次打开时从服务端加载备货区数据
        if (this.needsServerLoad && this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.needsServerLoad = false;
            this.loadingFromServer = true;
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    this.schematicId, "LIST", -1, Optional.empty()));
        }
    }

    protected void createSelectionEditFields()
    {
        int xLeft = 12;
        int x = xLeft - 2;
        int y = 24;

        x += this.createButton(x, y, -1, ButtonListener.Type.CHANGE_SELECTION_MODE) + 4;
        this.xOrigin = x;

        x = xLeft;
        y += 20;

        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, StringUtils.translate("litematica.gui.label.area_editor.selection_name"));
        y += 13;

        int width = 202;
        this.textFieldSelectionName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.textRenderer);
        this.textFieldSelectionName.setTextWrapper(this.selection.getName());
        this.addTextField(this.textFieldSelectionName, new TextFieldListenerDummy());
        x += width + 4;
        x += this.createButton(x, y, -1, ButtonListener.Type.SET_SELECTION_NAME) + 10;
        y += 20;

        this.xSet = x;
        this.yNext = y;
    }

    protected int addSubRegionFields(int x, int y)
    {
        int width = 68;
        int xSave = 10;
        int ySave = y + 4;

        xSave += this.createButton(xSave, ySave, -1, ButtonListener.Type.CREATE_SUB_REGION) + 4;

        boolean currentlyOn = this.selection.getExplicitOrigin() != null;
        xSave += this.createButtonOnOff(xSave, ySave, -1, currentlyOn, ButtonListener.Type.TOGGLE_ORIGIN_ENABLED) + 4;

        if (this.selection.getExplicitOrigin() != null)
        {
            if ((this.xSet - xSave) > 5)
            {
                xSave = this.xSet + 5;
            }

            x = Math.max(xSave, this.xOrigin);
            this.createCoordinateInputs(x, 5, width, Corner.NONE);
        }

        x = 12;
        y = this.getListY() - 12;
        String str = String.valueOf(this.selection.getAllSubRegionNames().size());
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, GuiBase.TXT_BOLD + StringUtils.translate("litematica.gui.label.area_editor.sub_regions", str));

        y = this.getScreenHeight() - 26;

        String saveLabel = "保存备货区";
        int saveWidth = this.getStringWidth(saveLabel) + 10;

        String label = GuiBase.TXT_RED + StringUtils.translate("gui.close");
        int buttonWidth = this.getStringWidth(label) + 10;
        x = this.getScreenWidth() - buttonWidth - 10;
        this.addButton(new ButtonGeneric(x, y, buttonWidth, 20, label), new ButtonListener(ButtonListener.Type.CLOSE, null, null, this));

        x -= saveWidth + 4;
        this.addButton(new ButtonGeneric(x, y, saveWidth, 20, saveLabel), new ButtonListener(ButtonListener.Type.SAVE, null, null, this));

        return y;
    }

    protected void renameSubRegion()
    {
    }

    protected void createOrigin()
    {
        BlockPos origin = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.mc.player);
        this.selection.setExplicitOrigin(origin);
    }

    protected int createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label = "";
        WidgetCheckBox widget = null;

        switch (corner)
        {
            case CORNER_1:
                label = StringUtils.translate("litematica.gui.label.area_editor.corner_1");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner1 = widget;
                break;
            case CORNER_2:
                label = StringUtils.translate("litematica.gui.label.area_editor.corner_2");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner2 = widget;
                break;
            case NONE:
                label = StringUtils.translate("litematica.gui.label.area_editor.origin");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxOrigin = widget;
                break;
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

        this.createButton(x + 10, y, -1, corner, ButtonListener.Type.MOVE_TO_PLAYER);
        y += 22;

        return y;
    }

    protected void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        String label = coordType.name() + ":";
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, label);
        int offset = 12;

        y += 2;
        BlockPos pos = corner == Corner.NONE ? this.selection.getEffectiveOrigin() : this.getBox().getPosition(corner);
        String text = "";
        ButtonListener.Type type = null;

        switch (coordType)
        {
            case X:
                text = String.valueOf(pos.getX());
                type = ButtonListener.Type.NUDGE_COORD_X;
                break;
            case Y:
                text = String.valueOf(pos.getY());
                type = ButtonListener.Type.NUDGE_COORD_Y;
                break;
            case Z:
                text = String.valueOf(pos.getZ());
                type = ButtonListener.Type.NUDGE_COORD_Z;
                break;
        }

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.textRenderer);
        TextFieldListener listener = new TextFieldListener(coordType, corner, this);
        textField.setTextWrapper(text);
        this.addTextField(textField, listener);

        this.createCoordinateButton(x + offset + width + 4, y, corner, coordType, type);
    }

    protected int createButtonOnOff(int x, int y, int width, boolean isCurrentlyOn, ButtonListener.Type type)
    {
        ButtonOnOff button = new ButtonOnOff(x, y, width, false, type.getTranslationKey(), isCurrentlyOn);
        this.addButton(button, new ButtonListener(type, null, null, this));
        return button.getWidth();
    }

    protected int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        return this.createButton(x, y, width, null, type);
    }

    protected int createButton(int x, int y, int width, @Nullable Corner corner, ButtonListener.Type type)
    {
        String label;

        if (type == ButtonListener.Type.CHANGE_SELECTION_MODE)
        {
            label = type.getDisplayName(this.getSelectionModeName());
        }
        else
        {
            label = type.getDisplayName();
        }

        if (width == -1)
        {
            width = this.getStringWidth(label) + 10;
        }

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        ButtonListener listener = new ButtonListener(type, corner, null, this);
        this.addButton(button, listener);

        return width;
    }

    protected String getSelectionModeName()
    {
        return SelectionMode.NORMAL.getDisplayName();
    }

    protected void createCoordinateButton(int x, int y, Corner corner, CoordinateType coordType, ButtonListener.Type type)
    {
        String hover = StringUtils.translate("litematica.gui.button.hover.plus_minus_tip_ctrl_alt_shift");
        ButtonGeneric button = new ButtonGeneric(x, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        ButtonListener listener = new ButtonListener(type, corner, coordType, this);
        this.addButton(button, listener);
    }

    protected void updateCheckBoxes()
    {
        if (this.checkBoxOrigin != null)
        {
            this.checkBoxOrigin.setChecked(this.selection.isOriginSelected(), false);
        }

        if (this.checkBoxCorner1 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_1;
            this.checkBoxCorner1.setChecked(checked, false);
        }

        if (this.checkBoxCorner2 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_2;
            this.checkBoxCorner2.setChecked(checked, false);
        }
    }

    @Nullable
    protected Box getBox()
    {
        return null;
    }

    protected void updatePosition(String numberString, Corner corner, CoordinateType type)
    {
        try
        {
            int value = Integer.parseInt(numberString);
            this.selection.setCoordinate(this.getBox(), corner, type, value);
        }
        catch (NumberFormatException e)
        {
        }
    }

    protected void moveCoordinate(int amount, Corner corner, CoordinateType type)
    {
        int oldValue = 0;

        if (corner == Corner.NONE)
        {
            oldValue = PositionUtils.getCoordinate(this.selection.getEffectiveOrigin(), type);
        }
        else if (this.getBox() != null)
        {
            oldValue = this.getBox().getCoordinate(corner, type);
        }

        this.selection.setCoordinate(this.getBox(), corner, type, oldValue + amount);
    }

    protected void renameSelection()
    {
        String newName = this.textFieldSelectionName.getTextWrapper();
        this.renameSelection(newName);
    }

    protected void renameSelection(String newName)
    {
        this.selection.setName(newName);
    }

    @Override
    protected WidgetListStagingAreas createListWidget(int listX, int listY)
    {
        return new WidgetListStagingAreas(listX, listY,
                this.getBrowserWidth(), this.getBrowserHeight(),
                this.getStagingAreaEntries(), this);
    }

    protected List<StagingAreaEntry> getStagingAreaEntries()
    {
        List<StagingAreaEntry> entries = new ArrayList<>();
        int id = 0;

        for (String name : this.selection.getAllSubRegionNames())
        {
            Box box = this.selection.getSubRegionBox(name);

            if (box != null)
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                entries.add(new StagingAreaEntry(id++, name,
                        pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ(), ""));
            }
        }

        return entries;
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - 146;
    }

    @Override
    protected ISelectionListener<StagingAreaEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    public void onSelectionChange(@Nullable StagingAreaEntry entry)
    {
        if (entry != null)
        {
            this.selection.setSelectedSubRegionBox(entry.name());
        }
    }

    protected static class ButtonListener implements IButtonActionListener
    {
        private final GuiStagingAreaEditorNormal parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        public ButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType, GuiStagingAreaEditorNormal parent)
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

            this.parent.setNextMessageType(MessageType.ERROR);

            switch (this.type)
            {
                case NUDGE_COORD_X:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    break;

                case NUDGE_COORD_Y:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    break;

                case NUDGE_COORD_Z:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    break;

                case CHANGE_SELECTION_MODE:
                    break;

                case CREATE_SUB_REGION:
                {
                    GuiTextInput gui = new GuiTextInput(512, "litematica.gui.title.area_editor.sub_region_name", "", null, new SubRegionCreator(this.parent));
                    gui.setParent(this.parent);
                    GuiBase.openGui(gui);
                    break;
                }

                case SET_SELECTION_NAME:
                {
                    this.parent.renameSelection();
                    break;
                }

                case SET_BOX_NAME:
                {
                    this.parent.renameSubRegion();
                    break;
                }

                case MOVE_TO_PLAYER:
                    if (this.parent.mc.player != null)
                    {
                        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.parent.mc.player);

                        if (this.corner == Corner.NONE)
                        {
                            this.parent.selection.setExplicitOrigin(pos);
                        }
                        else
                        {
                            this.parent.selection.setSelectedSubRegionCornerPos(pos, this.corner);
                        }
                    }
                    break;

                case TOGGLE_ORIGIN_ENABLED:
                    BlockPos origin = this.parent.selection.getExplicitOrigin();

                    if (origin == null)
                    {
                        this.parent.createOrigin();
                    }
                    else
                    {
                        this.parent.selection.setExplicitOrigin(null);
                    }
                    break;

                case SAVE:
                {
                    ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                            this.parent.schematicId, "CLEAR", -1, Optional.empty()));

                    for (String name : this.parent.selection.getAllSubRegionNames())
                    {
                        Box box = this.parent.selection.getSubRegionBox(name);

                        if (box != null)
                        {
                            BlockPos pos1 = box.getPos1();
                            BlockPos pos2 = box.getPos2();
                            int x1 = Math.min(pos1.getX(), pos2.getX());
                            int y1 = Math.min(pos1.getY(), pos2.getY());
                            int z1 = Math.min(pos1.getZ(), pos2.getZ());
                            int x2 = Math.max(pos1.getX(), pos2.getX());
                            int y2 = Math.max(pos1.getY(), pos2.getY());
                            int z2 = Math.max(pos1.getZ(), pos2.getZ());
                            AreaData areaData = new AreaData(box.getName(), x1, y1, z1, x2, y2, z2, Optional.empty());
                            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                                    this.parent.schematicId, "ADD", -1, Optional.of(areaData)));
                        }
                    }
                    break;
                }

                case CLOSE:
                    GuiBase.openGui(this.parent.getParent());
                    return;
            }

            this.parent.initGui();
        }

        public enum Type
        {
            SET_SELECTION_NAME      ("litematica.gui.button.area_editor.set_selection_name"),
            SET_BOX_NAME            ("litematica.gui.button.area_editor.set_box_name"),
            TOGGLE_ORIGIN_ENABLED   ("litematica.gui.button.area_editor.origin_enabled"),
            CREATE_SUB_REGION       ("litematica.gui.button.area_editor.create_sub_region"),
            CHANGE_SELECTION_MODE   ("litematica.gui.button.area_editor.change_selection_mode"),
            MOVE_TO_PLAYER          ("litematica.gui.button.move_to_player"),
            CLOSE                   ("gui.close"),
            SAVE                    ("syncmaterial.gui.button.save"),
            NUDGE_COORD_X           (""),
            NUDGE_COORD_Y           (""),
            NUDGE_COORD_Z           ("");

            private final String translationKey;
            @Nullable private final String hoverText;

            private Type(String translationKey)
            {
                this(translationKey, null);
            }

            private Type(String translationKey, @Nullable String hoverText)
            {
                this.translationKey = translationKey;
                this.hoverText = hoverText;
            }

            public String getTranslationKey()
            {
                return this.translationKey;
            }

            public String getDisplayName(Object... args)
            {
                return StringUtils.translate(this.translationKey, args);
            }
        }
    }

    protected static class TextFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiStagingAreaEditorNormal parent;
        private final CoordinateType type;
        private final Corner corner;

        public TextFieldListener(CoordinateType type, Corner corner, GuiStagingAreaEditorNormal parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.parent.updatePosition(textField.getTextWrapper(), this.corner, this.type);
            return false;
        }
    }

    public static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            return false;
        }
    }

    protected static class SubRegionCreator implements IStringConsumerFeedback
    {
        private final GuiStagingAreaEditorNormal gui;

        private SubRegionCreator(GuiStagingAreaEditorNormal gui)
        {
            this.gui = gui;
        }

        @Override
        public boolean setString(String string)
        {
            BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.gui.mc.player);
            this.gui.selection.createNewSubRegionBox(pos, string);
            this.gui.initGui();
            return true;
        }
    }

    protected static class CheckBoxListener implements ISelectionListener<WidgetCheckBox>
    {
        private final GuiStagingAreaEditorNormal gui;
        private final Corner corner;

        public CheckBoxListener(Corner corner, GuiStagingAreaEditorNormal gui)
        {
            this.gui = gui;
            this.corner = corner;
        }

        @Override
        public void onSelectionChange(WidgetCheckBox entry)
        {
            if (entry.isChecked())
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(true);
                    this.gui.selection.clearCurrentSelectedCorner();
                }
                else
                {
                    this.gui.selection.setOriginSelected(false);
                    this.gui.selection.setCurrentSelectedCorner(this.corner);
                }
            }
            else
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(false);
                }
                else
                {
                    this.gui.selection.clearCurrentSelectedCorner();
                }
            }

            this.gui.updateCheckBoxes();
        }
    }
}
