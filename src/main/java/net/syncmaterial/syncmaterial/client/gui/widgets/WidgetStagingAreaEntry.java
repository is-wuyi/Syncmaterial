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
        String label = type.getDisplayName();
        if (type == ButtonListener.ButtonType.REMOVE && ButtonListener.isPendingConfirm(this.entryData.areaId())) {
            label = GuiBase.TXT_RED + "确认?";
        }
        return this.addButton(new ButtonGeneric(x, y, -1, true, label), new ButtonListener(type, this)).getX() - 1;
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

        // Display: "备货区名称  [x1,y1,z1]~[x2,y2,z2]"
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
        text.add(String.format("§7尺寸: %d x %d x %d", sizeX, sizeY, sizeZ));

        int offset = 12;
        if (GuiBase.isMouseOver(mouseX, mouseY, this.x, this.y, this.buttonsStartX - offset, this.height))
        {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, text);
        }
    }

    private static class ButtonListener implements IButtonActionListener
    {
        private static int lastDeleteAreaId = -1;
        private static long lastDeleteTime = 0;

        static boolean isPendingConfirm(int areaId) {
            return areaId == lastDeleteAreaId && System.currentTimeMillis() - lastDeleteTime < 3000;
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
                String title = "重命名备货区";
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
                long now = System.currentTimeMillis();
                if (this.widget.entryData.areaId() == lastDeleteAreaId && now - lastDeleteTime < 3000) {
                    this.widget.parent.getEditorGui().deleteArea(this.widget.entryData.areaId());
                    lastDeleteAreaId = -1;
                    lastDeleteTime = 0;
                } else {
                    lastDeleteAreaId = this.widget.entryData.areaId();
                    lastDeleteTime = now;
                }
            }
        }

        public enum ButtonType
        {
            RENAME          ("重命名"),
            CONFIGURE       ("配置"),
            REMOVE          (GuiBase.TXT_RED + "-");

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
