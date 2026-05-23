package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class GuiStagingAreaEditorSubRegion extends GuiBase
{
    private final String schematicId;
    private final StagingAreaEntry areaEntry;

    private GuiTextFieldGeneric textFieldName;
    private GuiTextFieldInteger textFieldX1;
    private GuiTextFieldInteger textFieldY1;
    private GuiTextFieldInteger textFieldZ1;
    private GuiTextFieldInteger textFieldX2;
    private GuiTextFieldInteger textFieldY2;
    private GuiTextFieldInteger textFieldZ2;

    public GuiStagingAreaEditorSubRegion(String schematicId, StagingAreaEntry areaEntry)
    {
        this.schematicId = schematicId;
        this.areaEntry = areaEntry;
        this.title = "备货区编辑（子区域）";
        this.useTitleHierarchy = false;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        int x = 12;
        int y = 24;

        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, "备货区名称");
        y += 13;

        int nameWidth = 202;
        this.textFieldName = new GuiTextFieldGeneric(x, y + 2, nameWidth, 16, this.textRenderer);
        this.textFieldName.setTextWrapper(this.areaEntry.name());
        this.addTextField(this.textFieldName, new TextFieldListenerDummy());
        x += nameWidth + 4;
        this.createButton(x, y, -1, ButtonListener.Type.SET_NAME);
        y += 22;

        x = 12;
        int coordWidth = 68;

        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, "角点 1");
        y += 14;

        this.createCoordinateInput(x, y, coordWidth, "X:", String.valueOf(this.areaEntry.x1()), ButtonListener.Type.NUDGE_X1);
        y += 20;
        this.createCoordinateInput(x, y, coordWidth, "Y:", String.valueOf(this.areaEntry.y1()), ButtonListener.Type.NUDGE_Y1);
        y += 20;
        this.createCoordinateInput(x, y, coordWidth, "Z:", String.valueOf(this.areaEntry.z1()), ButtonListener.Type.NUDGE_Z1);
        y += 26;

        x = 12;
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, "角点 2");
        y += 14;

        this.createCoordinateInput(x, y, coordWidth, "X:", String.valueOf(this.areaEntry.x2()), ButtonListener.Type.NUDGE_X2);
        y += 20;
        this.createCoordinateInput(x, y, coordWidth, "Y:", String.valueOf(this.areaEntry.y2()), ButtonListener.Type.NUDGE_Y2);
        y += 20;
        this.createCoordinateInput(x, y, coordWidth, "Z:", String.valueOf(this.areaEntry.z2()), ButtonListener.Type.NUDGE_Z2);
        y += 26;

        x = 12;
        this.createButton(x, y, -1, ButtonListener.Type.MOVE_TO_PLAYER);

        int buttonY = this.getScreenHeight() - 26;
        this.createButton(12, buttonY, -1, ButtonListener.Type.SAVE);

        String closeLabel = GuiBase.TXT_RED + "关闭";
        int closeWidth = this.getStringWidth(closeLabel) + 10;
        int closeX = this.getScreenWidth() - closeWidth - 10;
        this.addButton(new ButtonGeneric(closeX, buttonY, closeWidth, 20, closeLabel),
                new ButtonListener(ButtonListener.Type.CLOSE, this));
    }

    private void createCoordinateInput(int x, int y, int width, String label, String value, ButtonListener.Type nudgeType)
    {
        this.addLabel(x, y + 4, 20, 16, 0xFFFFFFFF, label);
        int offset = 22;

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y + 2, width, 16, this.textRenderer);
        textField.setTextWrapper(value);
        this.addTextField(textField, new TextFieldListenerDummy());

        switch (nudgeType)
        {
            case NUDGE_X1 -> this.textFieldX1 = textField;
            case NUDGE_Y1 -> this.textFieldY1 = textField;
            case NUDGE_Z1 -> this.textFieldZ1 = textField;
            case NUDGE_X2 -> this.textFieldX2 = textField;
            case NUDGE_Y2 -> this.textFieldY2 = textField;
            case NUDGE_Z2 -> this.textFieldZ2 = textField;
        }

        ButtonGeneric button = new ButtonGeneric(x + offset + width + 4, y, MaLiLibIcons.BTN_PLUSMINUS_16, "点击：±1，Shift+点击：±10");
        this.addButton(button, new ButtonListener(nudgeType, this));
    }

    private int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        String label = type.getDisplayName();

        if (width == -1)
        {
            width = this.getStringWidth(label) + 10;
        }

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        this.addButton(button, new ButtonListener(type, this));
        return button.getWidth();
    }

    public void onServerResponse(StagingAreaConfigResponseS2CPacket packet)
    {
        if (!packet.success())
        {
            this.addMessage(MessageType.ERROR, 3000, packet.message());
            return;
        }

        this.addMessage(MessageType.SUCCESS, 2000, packet.message());
    }

    private int getTextFieldInt(GuiTextFieldInteger textField)
    {
        try
        {
            return Integer.parseInt(textField.getTextWrapper());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private void nudgeCoordinate(GuiTextFieldInteger textField, int amount)
    {
        int current = getTextFieldInt(textField);
        textField.setTextWrapper(String.valueOf(current + amount));
    }

    private static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            return false;
        }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final Type type;
        private final GuiStagingAreaEditorSubRegion gui;

        public ButtonListener(Type type, GuiStagingAreaEditorSubRegion gui)
        {
            this.type = type;
            this.gui = gui;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            boolean shift = GuiBase.isShiftDown();
            int amount = shift ? 10 : 1;

            switch (this.type)
            {
                case SET_NAME ->
                {
                    String name = this.gui.textFieldName.getTextWrapper().trim();
                    if (!name.isEmpty())
                    {
                        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                                this.gui.schematicId, "RENAME", this.gui.areaEntry.areaId(),
                                Optional.of(new AreaData(name,
                                        this.gui.getTextFieldInt(this.gui.textFieldX1),
                                        this.gui.getTextFieldInt(this.gui.textFieldY1),
                                        this.gui.getTextFieldInt(this.gui.textFieldZ1),
                                        this.gui.getTextFieldInt(this.gui.textFieldX2),
                                        this.gui.getTextFieldInt(this.gui.textFieldY2),
                                        this.gui.getTextFieldInt(this.gui.textFieldZ2),
                                        Optional.empty()))));
                    }
                }
                case NUDGE_X1 -> this.gui.nudgeCoordinate(this.gui.textFieldX1, mouseButton == 1 ? -amount : amount);
                case NUDGE_Y1 -> this.gui.nudgeCoordinate(this.gui.textFieldY1, mouseButton == 1 ? -amount : amount);
                case NUDGE_Z1 -> this.gui.nudgeCoordinate(this.gui.textFieldZ1, mouseButton == 1 ? -amount : amount);
                case NUDGE_X2 -> this.gui.nudgeCoordinate(this.gui.textFieldX2, mouseButton == 1 ? -amount : amount);
                case NUDGE_Y2 -> this.gui.nudgeCoordinate(this.gui.textFieldY2, mouseButton == 1 ? -amount : amount);
                case NUDGE_Z2 -> this.gui.nudgeCoordinate(this.gui.textFieldZ2, mouseButton == 1 ? -amount : amount);
                case MOVE_TO_PLAYER ->
                {
                    BlockPos pos = MinecraftClient.getInstance().player.getBlockPos();
                    this.gui.textFieldX1.setTextWrapper(String.valueOf(pos.getX()));
                    this.gui.textFieldY1.setTextWrapper(String.valueOf(pos.getY()));
                    this.gui.textFieldZ1.setTextWrapper(String.valueOf(pos.getZ()));
                }
                case SAVE ->
                {
                    String name = this.gui.textFieldName.getTextWrapper().trim();
                    if (name.isEmpty())
                    {
                        name = this.gui.areaEntry.name();
                    }

                    ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                            this.gui.schematicId, "UPDATE", this.gui.areaEntry.areaId(),
                            Optional.of(new AreaData(name,
                                    this.gui.getTextFieldInt(this.gui.textFieldX1),
                                    this.gui.getTextFieldInt(this.gui.textFieldY1),
                                    this.gui.getTextFieldInt(this.gui.textFieldZ1),
                                    this.gui.getTextFieldInt(this.gui.textFieldX2),
                                    this.gui.getTextFieldInt(this.gui.textFieldY2),
                                    this.gui.getTextFieldInt(this.gui.textFieldZ2),
                                    Optional.empty()))));
                }
                case CLOSE ->
                {
                    GuiBase.openGui(this.gui.getParent());
                }
            }
        }

        public enum Type
        {
            SET_NAME        ("设置名称"),
            NUDGE_X1        (""),
            NUDGE_Y1        (""),
            NUDGE_Z1        (""),
            NUDGE_X2        (""),
            NUDGE_Y2        (""),
            NUDGE_Z2        (""),
            MOVE_TO_PLAYER  ("移动到玩家位置"),
            SAVE            (GuiBase.TXT_GREEN + "保存"),
            CLOSE           (GuiBase.TXT_RED + "关闭");

            private final String displayName;

            Type(String displayName)
            {
                this.displayName = displayName;
            }

            public String getDisplayName()
            {
                return this.displayName;
            }
        }
    }
}
