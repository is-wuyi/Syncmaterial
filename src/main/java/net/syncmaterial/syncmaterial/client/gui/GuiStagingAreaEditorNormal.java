package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.util.StringUtils;

import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetStagingAreaEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class GuiStagingAreaEditorNormal extends GuiListBase<StagingAreaEntry, WidgetStagingAreaEntry, WidgetListStagingAreas>
        implements ISelectionListener<StagingAreaEntry>, StagingAreaEditorGui
{
    private final String schematicId;
    private final List<StagingAreaEntry> areas = new ArrayList<>();

    public GuiStagingAreaEditorNormal(String schematicId)
    {
        super(8, 116);

        this.schematicId = schematicId;
        this.title = "备货区配置（标准模式）";
        this.useTitleHierarchy = false;
    }

    @Override
    public String getSchematicId()
    {
        return this.schematicId;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "LIST", -1, Optional.empty()));

        // Create the bottom buttons
        int x = 12;
        int y = this.getScreenHeight() - 26;

        this.createButton(x, y, -1, ButtonListener.Type.ADD_AREA);

        x += this.getStringWidth(ButtonListener.Type.ADD_AREA.getDisplayName()) + 14;
        this.createButton(x, y, -1, ButtonListener.Type.REFRESH);

        // Close button on the right side
        String label = ButtonListener.Type.CLOSE.getDisplayName();
        int buttonWidth = this.getStringWidth(label) + 10;
        x = this.getScreenWidth() - buttonWidth - 10;
        this.addButton(new ButtonGeneric(x, y, buttonWidth, 20, label), new ButtonListener(ButtonListener.Type.CLOSE, this));
    }

    @Override
    public void deleteArea(int areaId)
    {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "DELETE", areaId, Optional.empty()));
    }

    @Override
    public void refreshAreas()
    {
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(this.schematicId, "LIST", -1, Optional.empty()));
    }

    public void onServerResponse(StagingAreaConfigResponseS2CPacket packet)
    {
        if (packet.success() == false)
        {
            this.addMessage(MessageType.ERROR, 3000, packet.message());
            return;
        }

        this.areas.clear();
        for (StagingAreaConfigResponseS2CPacket.AreaInfo info : packet.areas())
        {
            this.areas.add(new StagingAreaEntry(info.areaId(), info.name(),
                    info.x1(), info.y1(), info.z1(),
                    info.x2(), info.y2(), info.z2(), info.world()));
        }

        if (this.getListWidget() != null)
        {
            this.getListWidget().refreshEntries();
        }

        this.addMessage(MessageType.SUCCESS, 2000,
                "已更新备货区列表 (" + this.areas.size() + " 个区域)");
    }

    @Override
    protected WidgetListStagingAreas createListWidget(int listX, int listY)
    {
        return new WidgetListStagingAreas(listX, listY,
                this.getBrowserWidth(), this.getBrowserHeight(),
                this.areas, this);
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
    public void onSelectionChange(StagingAreaEntry entry)
    {
        // Selection handling is not needed for now
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

    private static class ButtonListener implements IButtonActionListener
    {
        private final Type type;
        private final GuiStagingAreaEditorNormal gui;

        public ButtonListener(Type type, GuiStagingAreaEditorNormal gui)
        {
            this.type = type;
            this.gui = gui;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case ADD_AREA:
                {
                    String defaultName = "area_" + (this.gui.areas.size() + 1);
                    IStringConsumerFeedback consumer = string ->
                    {
                        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                                this.gui.schematicId, "ADD", -1,
                                Optional.of(new AreaData(string.trim(), 0, 0, 0, 0, 0, 0, Optional.empty()))));
                        return true;
                    };
                    GuiBase.openGui(new GuiTextInput(512, "新建备货区", defaultName, this.gui, consumer));
                    break;
                }

                case REFRESH:
                {
                    this.gui.refreshAreas();
                    break;
                }

                case CLOSE:
                {
                    GuiBase.openGui(this.gui.getParent());
                    break;
                }
            }
        }

        public enum Type
        {
            ADD_AREA    ("新建子区域"),
            REFRESH     ("刷新列表"),
            CLOSE       (GuiBase.TXT_RED + "关闭");

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
