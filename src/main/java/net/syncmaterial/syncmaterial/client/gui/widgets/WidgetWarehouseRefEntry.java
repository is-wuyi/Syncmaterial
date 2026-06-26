package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class WidgetWarehouseRefEntry extends WidgetListEntryBase<StagingAreaConfigResponseS2CPacket.AreaInfo>
{
    private final WidgetListWarehouseRefs parent;
    private final StagingAreaConfigResponseS2CPacket.AreaInfo entryData;
    private final boolean isOdd;
    private final int buttonsStartX;

    public WidgetWarehouseRefEntry(int x, int y, int width, int height, boolean isOdd,
            StagingAreaConfigResponseS2CPacket.AreaInfo entry, int listIndex, WidgetListWarehouseRefs parent)
    {
        super(x, y, width, height, entry, listIndex);

        this.entryData = entry;
        this.isOdd = isOdd;
        this.parent = parent;

        int posX = x + width - 2;
        int posY = y + 1;

        posX = this.createButton(posX, posY, ButtonType.REMOVE);

        this.buttonsStartX = posX;
    }

    private int createButton(int x, int y, ButtonType type)
    {
        return this.addButton(new ButtonGeneric(x, y, -1, true, type.getDisplayName()), new ButtonListener(type, this)).getX() - 1;
    }

    @Override
    public boolean canSelectAt(int mouseX, int mouseY, int mouseButton)
    {
        return mouseX < this.buttonsStartX && super.canSelectAt(mouseX, mouseY, mouseButton);
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        if (selected || this.isMouseOver(mouseX, mouseY))
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
        }
        else if (this.isOdd)
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
        }
        else
        {
            RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
        }

        if (selected)
        {
            RenderUtils.drawOutline(drawContext, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        // 来源标签
        String sourceTag = "[仓库] ";
        int sourceColor = 0xFF55AAFF;
        int tagWidth = StringUtils.getStringWidth(sourceTag);
        this.drawString(drawContext, this.x + 2, this.y + 7, sourceColor, sourceTag);

        // 名称 + 坐标
        String display = StringUtils.translate("syncmaterial.gui.label.area_entry_display",
                this.entryData.name(),
                this.entryData.x1(), this.entryData.y1(), this.entryData.z1(),
                this.entryData.x2(), this.entryData.y2(), this.entryData.z2());
        this.drawString(drawContext, this.x + 2 + tagWidth, this.y + 7, 0xFFFFFFFF, display);

        super.render(drawContext, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        List<String> text = new java.util.ArrayList<>();
        text.add(String.format("§l%s", this.entryData.name()));
        text.add(StringUtils.translate("syncmaterial.gui.label.area_coords",
                this.entryData.x1(), this.entryData.y1(), this.entryData.z1(),
                this.entryData.x2(), this.entryData.y2(), this.entryData.z2()));

        int sizeX = Math.abs(this.entryData.x2() - this.entryData.x1()) + 1;
        int sizeY = Math.abs(this.entryData.y2() - this.entryData.y1()) + 1;
        int sizeZ = Math.abs(this.entryData.z2() - this.entryData.z1()) + 1;
        text.add("§7" + StringUtils.translate("syncmaterial.gui.label.size", sizeX, sizeY, sizeZ));

        int offset = 12;
        if (GuiBase.isMouseOver(mouseX, mouseY, this.x, this.y, this.buttonsStartX - offset, this.height))
        {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, text);
        }
    }

    private enum ButtonType
    {
        REMOVE(GuiBase.TXT_RED + StringUtils.translate("syncmaterial.gui.button.remove_warehouse_ref"));

        private final String label;

        ButtonType(String label) { this.label = label; }
        public String getDisplayName() { return this.label; }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final WidgetWarehouseRefEntry widget;
        private final ButtonType type;

        public ButtonListener(ButtonType type, WidgetWarehouseRefEntry widget)
        {
            this.type = type;
            this.widget = widget;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.REMOVE)
            {
                String schematicId = this.widget.parent.getSchematicId();
                int warehouseId = this.widget.entryData.areaId();
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        schematicId, "REMOVE_WAREHOUSE_REF", warehouseId, Optional.empty()));
            }
        }
    }
}
