package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class MaterialListHudRenderer {
    protected final MaterialListBase materialList;
    protected final MaterialListSorter sorter;
    protected boolean shouldRender;

    public MaterialListHudRenderer(MaterialListBase materialList) {
        this.materialList = materialList;
        this.sorter = new MaterialListSorter();
    }

    public boolean getShouldRender() {
        return this.shouldRender;
    }

    public void toggleShouldRender() {
        this.shouldRender = !this.shouldRender;
    }

    public int render(DrawContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !this.shouldRender) {
            return 0;
        }

        List<String> lines = new ArrayList<>();
        List<MaterialListEntry> list = this.materialList.getMaterialsFiltered(true);

        for (MaterialListEntry entry : list) {
            if (entry.getCountMissing() > 0) {
                String name = entry.getStack().getName().getString();
                int available = entry.getCountAvailable();
                int total = entry.getCountTotal();
                lines.add(String.format("%s: %d/%d", name, available, total));
            }
        }

        if (lines.isEmpty()) {
            return 0;
        }

        TextRenderer font = mc.textRenderer;
        int lineHeight = 12;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.getWidth(line));
        }

        int height = lines.size() * lineHeight;
        int x = xOffset;
        int y = yOffset;

        if (alignment == HudAlignment.BOTTOM_RIGHT || alignment == HudAlignment.TOP_RIGHT) {
            x -= width + 10;
        }
        if (alignment == HudAlignment.BOTTOM_LEFT || alignment == HudAlignment.BOTTOM_RIGHT) {
            y -= height + 10;
        }

        drawContext.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0xC0000000);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            drawContext.drawTextWithShadow(font, line, x, y + i * lineHeight, 0xFFFFFFFF);
        }

        return height;
    }
}
