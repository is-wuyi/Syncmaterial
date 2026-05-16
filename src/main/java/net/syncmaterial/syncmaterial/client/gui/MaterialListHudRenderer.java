package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.util.StringUtils;

public class MaterialListHudRenderer {
    protected final MaterialListBase materialList;
    protected boolean shouldRender;

    public MaterialListHudRenderer(MaterialListBase materialList) {
        this.materialList = materialList;
    }

    public void toggleShouldRender() {
        this.shouldRender = !this.shouldRender;
    }

    public boolean getShouldRender() {
        return this.shouldRender;
    }

    public int render(DrawContext drawContext, int xOffset, int yOffset, HudAlignment alignment) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !shouldRender) {
            return 0;
        }

        List<String> lines = new ArrayList<>();
        lines.add("§e" + this.materialList.getTitle());

        for (MaterialListEntry entry : this.materialList.getMaterialsFiltered(true)) {
            if (entry.getCountMissing() > 0) {
                int total = entry.getCountTotal();
                int available = entry.getCountAvailable();
                int missing = entry.getCountMissing();
                String name = entry.getStack().getName().getString();
                lines.add(String.format("§c- %s: %d/%d (%d)", name, available, total, missing));
            }
        }

        if (lines.size() <= 1) {
            return 0;
        }

        int x = xOffset;
        int y = yOffset;
        int lineHeight = 12;

        if (alignment == HudAlignment.TOP_RIGHT || alignment == HudAlignment.BOTTOM_RIGHT) {
            int maxWidth = 0;
            for (String line : lines) {
                maxWidth = Math.max(maxWidth, mc.textRenderer.getWidth(line));
            }
            x = mc.getWindow().getScaledWidth() - maxWidth - 10;
        }

        if (alignment == HudAlignment.BOTTOM_LEFT || alignment == HudAlignment.BOTTOM_RIGHT) {
            y = mc.getWindow().getScaledHeight() - (lines.size() * lineHeight) - 10;
        }

        int bgWidth = 0;
        for (String line : lines) {
            bgWidth = Math.max(bgWidth, mc.textRenderer.getWidth(line));
        }

        drawContext.fill(x - 2, y - 2, x + bgWidth + 2, y + lines.size() * lineHeight + 2, 0xC0000000);

        for (int i = 0; i < lines.size(); i++) {
            drawContext.drawTextWithShadow(mc.textRenderer, lines.get(i), x, y + i * lineHeight, 0xFFFFFFFF);
        }

        return lines.size() * lineHeight;
    }
}
