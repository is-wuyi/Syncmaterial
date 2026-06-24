/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced SelectionManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui.widgets;

import java.util.List;

import net.minecraft.client.gui.DrawContext;

import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEditorGui;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInputFeedback;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.render.RenderUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Optional;

public class WidgetStagingAreaEntry extends WidgetListEntryBase<StagingAreaEntry>
{
    private final WidgetListStagingAreas parent;
    private final StagingAreaEntry entryData;
    private final boolean isOdd;
    private final int buttonsStartX;

    public WidgetStagingAreaEntry(int x, int y, int width, int height, boolean isOdd,
            StagingAreaEntry entry, int listIndex, AreaSelection selection, WidgetListStagingAreas parent)
    {
        super(x, y, width, height, entry, listIndex);

        this.entryData = entry;
        this.isOdd = isOdd;
        this.parent = parent;

        int posX = x + width - 2;
        int posY = y + 1;

        posX = this.createButton(posX, posY, ButtonListener.ButtonType.REMOVE);
        posX = this.createButton(posX, posY, ButtonListener.ButtonType.CONFIGURE);
        posX = this.createButton(posX, posY, ButtonListener.ButtonType.RENAME);

        this.buttonsStartX = posX;
    }

    private int createButton(int x, int y, ButtonListener.ButtonType type)
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
        // Draw a lighter background for the hovered and the selected entry
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

        // Phase 5: 来源标签
        String sourceTag = "[备货区] ";
        int sourceColor = 0xFF55FF55; // 绿色
        int tagWidth = fi.dy.masa.malilib.util.StringUtils.getStringWidth(sourceTag);
        this.drawString(drawContext, this.x + 2, this.y + 7, sourceColor, sourceTag);

        // Display: "备货区名称  [x1,y1,z1]~[x2,y2,z2]"
        String display = fi.dy.masa.malilib.util.StringUtils.translate(
                "syncmaterial.gui.label.area_entry_display",
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
        text.add(fi.dy.masa.malilib.util.StringUtils.translate(
                "syncmaterial.gui.label.area_coords",
                this.entryData.x1(), this.entryData.y1(), this.entryData.z1(),
                this.entryData.x2(), this.entryData.y2(), this.entryData.z2()));

        int sizeX = Math.abs(this.entryData.x2() - this.entryData.x1()) + 1;
        int sizeY = Math.abs(this.entryData.y2() - this.entryData.y1()) + 1;
        int sizeZ = Math.abs(this.entryData.z2() - this.entryData.z1()) + 1;
        text.add("§7" + fi.dy.masa.malilib.util.StringUtils.translate(
                "syncmaterial.gui.label.size", sizeX, sizeY, sizeZ));

        int offset = 12;
        if (GuiBase.isMouseOver(mouseX, mouseY, this.x, this.y, this.buttonsStartX - offset, this.height))
        {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, text);
        }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private static boolean hasShiftDown() {
            return org.lwjgl.glfw.GLFW.glfwGetKey(
                net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle(),
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }

        private final WidgetStagingAreaEntry widget;
        private final ButtonType type;

        public ButtonListener(ButtonType type, WidgetStagingAreaEntry widget)
        {
            this.type = type;
            this.widget = widget;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.RENAME)
            {
                String title = fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.title.rename_area");
                String name = this.widget.entryData.name();
                AreaRenamer renamer = new AreaRenamer(this.widget.entryData, this.widget.parent.getEditorGui());
                GuiBase.openGui(new GuiTextInputFeedback(160, title, name, (net.minecraft.client.gui.screen.Screen) this.widget.parent.getEditorGui(), renamer));
            }
            else if (this.type == ButtonType.CONFIGURE)
            {
                AreaSelection selection = this.widget.parent.getSelection();
                Box box = selection.getSubRegionBox(this.widget.entryData.name());
                if (box != null)
                {
                    selection.setSelectedSubRegionBox(box.getName());
                    GuiStagingAreaEditorSubRegion sub = new GuiStagingAreaEditorSubRegion(
                            selection, box, null, this.widget.parent.getEditorGui().getSchematicId());
                    sub.setParent(net.minecraft.client.MinecraftClient.getInstance().currentScreen);
                    GuiBase.openGui(sub);
                }
            }
            else if (this.type == ButtonType.REMOVE)
            {
                if (hasShiftDown()) {
                    this.widget.parent.getEditorGui().deleteArea(this.widget.entryData.areaId());
                } else {
                    // 显示提示：需要按住 Shift
                    fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                        fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                        fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.hint.hold_shift_delete"));
                }
            }
        }

        public enum ButtonType
        {
            RENAME          (fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.rename")),
            CONFIGURE       (fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.configure")),
            REMOVE          (GuiBase.TXT_RED + fi.dy.masa.malilib.util.StringUtils.translate("syncmaterial.gui.button.shift_delete"));

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

    private static class AreaRenamer implements IStringConsumerFeedback
    {
        private final StagingAreaEntry entry;
        private final StagingAreaEditorGui gui;

        public AreaRenamer(StagingAreaEntry entry, StagingAreaEditorGui gui)
        {
            this.entry = entry;
            this.gui = gui;
        }

        @Override
        public boolean setString(String newName)
        {
            if (newName == null || newName.trim().isEmpty())
            {
                return false;
            }

            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    this.gui.getSchematicId(), "RENAME", this.entry.areaId(),
                    Optional.of(new AreaData(newName.trim(),
                            this.entry.x1(), this.entry.y1(), this.entry.z1(),
                            this.entry.x2(), this.entry.y2(), this.entry.z2(),
                            Optional.ofNullable(this.entry.world())))));
            return true;
        }
    }
}
