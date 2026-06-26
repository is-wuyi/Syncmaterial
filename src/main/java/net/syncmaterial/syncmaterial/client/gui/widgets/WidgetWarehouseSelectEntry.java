package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.client.gui.WarehouseEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 仓库选择列表条目：显示仓库信息 + 选择按钮
 */
public class WidgetWarehouseSelectEntry extends WidgetListEntryBase<WarehouseEntry>
{
    private final WidgetListWarehouseSelect parent;
    private final WarehouseEntry entryData;
    private final boolean isOdd;

    public WidgetWarehouseSelectEntry(int x, int y, int width, int height, boolean isOdd,
            WarehouseEntry entry, int listIndex, WidgetListWarehouseSelect parent)
    {
        super(x, y, width, height, entry, listIndex);
        this.entryData = entry;
        this.isOdd = isOdd;
        this.parent = parent;

        // 右侧：选择按钮
        int posX = x + width - 2;
        int posY = y + 1;
        String selectLabel = GuiBase.TXT_GREEN + StringUtils.translate("syncmaterial.gui.button.select");
        ButtonGeneric selectBtn = new ButtonGeneric(posX, posY, -1, true, selectLabel);
        this.addButton(selectBtn, new ButtonListener(this));
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

        // 显示仓库名称 + 坐标
        String display = String.format("%s  [%d,%d,%d]~[%d,%d,%d]",
                this.entryData.name(),
                this.entryData.x1(), this.entryData.y1(), this.entryData.z1(),
                this.entryData.x2(), this.entryData.y2(), this.entryData.z2());
        this.drawString(drawContext, this.x + 2, this.y + 7, 0xFFFFFFFF, display);

        super.render(drawContext, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        List<String> text = new java.util.ArrayList<>();
        text.add(String.format("§l%s", this.entryData.name()));
        text.add(String.format("§7[%d,%d,%d] ~ [%d,%d,%d]",
                this.entryData.x1(), this.entryData.y1(), this.entryData.z1(),
                this.entryData.x2(), this.entryData.y2(), this.entryData.z2()));

        int sizeX = Math.abs(this.entryData.x2() - this.entryData.x1()) + 1;
        int sizeY = Math.abs(this.entryData.y2() - this.entryData.y1()) + 1;
        int sizeZ = Math.abs(this.entryData.z2() - this.entryData.z1()) + 1;
        text.add("§7" + sizeX + " x " + sizeY + " x " + sizeZ);

        RenderUtils.drawHoverText(drawContext, mouseX, mouseY, text);
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final WidgetWarehouseSelectEntry widget;

        public ButtonListener(WidgetWarehouseSelectEntry widget)
        {
            this.widget = widget;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            // 发送 ADD_WAREHOUSE_REF 请求
            String schematicId = this.widget.parent.getWarehouseSelectGui().getSchematicId();
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    schematicId, "ADD_WAREHOUSE_REF", this.widget.entryData.warehouseId(),
                    Optional.empty()));

            // 返回编辑器
            this.widget.parent.getWarehouseSelectGui().onWarehouseRefAdded();
        }
    }
}
