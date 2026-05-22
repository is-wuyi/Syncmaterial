package net.syncmaterial.syncmaterial.client.gui.widgets;

import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaEntry;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditor;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

public class WidgetStagingAreaEntry extends WidgetListEntryBase<StagingAreaEntry> {
    private final StagingAreaEntry entry;
    private final GuiStagingAreaEditor gui;
    private ButtonGeneric deleteButton;

    public WidgetStagingAreaEntry(int x, int y, int width, int height, StagingAreaEntry entry, GuiStagingAreaEditor gui) {
        super(x, y, width, height, entry, 0);
        this.entry = entry;
        this.gui = gui;

        int btnX = x + width - 50;
        int btnY = y + 2;
        this.deleteButton = new ButtonGeneric(btnX, btnY, 46, 18, "删除");
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected) {
        super.render(drawContext, mouseX, mouseY, selected);

        String text = String.format("%s  [%d,%d,%d]~[%d,%d,%d]",
            this.entry.name(),
            this.entry.x1(), this.entry.y1(), this.entry.z1(),
            this.entry.x2(), this.entry.y2(), this.entry.z2());

        this.drawStringWithShadow(drawContext, this.x + 4, this.y + 7, 0xFFFFFF, text);

        this.deleteButton.render(drawContext, mouseX, mouseY, false);
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int button) {
        if (this.deleteButton != null && this.deleteButton.isMouseOver()) {
            this.gui.deleteArea(this.entry.areaId());
            return true;
        }
        return super.onMouseClicked(mouseX, mouseY, button);
    }
}