package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.List;

import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager;
import net.syncmaterial.syncmaterial.client.gui.WarehouseEntry;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;

/**
 * 仓库列表条目 widget（类似 WidgetStagingAreaEntry）
 */
public class WidgetWarehouseEntry extends WidgetListEntryBase<WarehouseEntry>
{
    private final WidgetListWarehouses parent;
    private final WarehouseEntry entryData;
    private final boolean isOdd;
    private final int buttonsStartX;

    public WidgetWarehouseEntry(int x, int y, int width, int height, boolean isOdd,
            WarehouseEntry entry, int listIndex, WidgetListWarehouses parent)
    {
        super(x, y, width, height, entry, listIndex);

        this.entryData = entry;
        this.isOdd = isOdd;
        this.parent = parent;

        int posX = x + width - 2;
        int posY = y + 1;

        posX = this.createButton(posX, posY, ButtonListener.ButtonType.EDIT) - 1;
        posX = this.createButton(posX, posY, ButtonListener.ButtonType.DELETE);

        this.buttonsStartX = posX;
    }

    private int createButton(int x, int y, ButtonListener.ButtonType type)
    {
        return this.addButton(new ButtonGeneric(x, y, -1, true, type.getDisplayName()),
                new ButtonListener(type, this)).getX() - 1;
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

        int offset = 12;
        if (GuiBase.isMouseOver(mouseX, mouseY, this.x, this.y, this.buttonsStartX - offset, this.height))
        {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, text);
        }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final WidgetWarehouseEntry widget;
        private final ButtonType type;

        public ButtonListener(ButtonType type, WidgetWarehouseEntry widget)
        {
            this.type = type;
            this.widget = widget;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.DELETE)
            {
                if (hasShiftDown()) {
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                        new net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket(
                            "", "DELETE_WAREHOUSE", this.widget.entryData.warehouseId(),
                            java.util.Optional.empty()));
                    // 刷新列表
                    this.widget.parent.getWarehouseGui().requestRefresh();
                } else {
                    fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                        fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.hold_shift_delete"));
                }
            }
            else if (this.type == ButtonType.EDIT)
            {
                // TODO: 打开编辑界面
                fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                    fi.dy.masa.malilib.gui.Message.MessageType.INFO, "编辑功能开发中");
            }
        }

        private static boolean hasShiftDown() {
            return org.lwjgl.glfw.GLFW.glfwGetKey(
                net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle(),
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }

        public enum ButtonType
        {
            EDIT    (GuiBase.TXT_AQUA + fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.edit")),
            DELETE  (GuiBase.TXT_RED + fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.shift_delete"));

            private final String labelKey;

            ButtonType(String labelKey)
            {
                this.labelKey = labelKey;
            }

            public String getDisplayName()
            {
                return this.labelKey;
            }
        }
    }
}
