package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.network.AreaOverlapStatus;

/**
 * Small persistent overlap banner shared by the material list, warehouse manager and
 * warehouse reference popup. The localized label is ours; the server message stays a
 * tooltip detail so the banner size never depends on server wording.
 */
final class AreaOverlapWarningRenderer {
    private static final int HEIGHT = 14;

    private AreaOverlapWarningRenderer() {
    }

    //? if >=26.2 {
    static void render(GuiContext drawContext, int x, int y, String markerKey, int mouseX, int mouseY)
    //?} else {
    /*static void render(GuiGraphics drawContext, int x, int y, String markerKey, int mouseX, int mouseY)
    *///?}
    {
        if (!AreaOverlapStatus.isWarning(markerKey)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String label = StringUtils.translate(markerKey);
        String text = "⚠ " + label;
        int availableWidth = Math.max(0, mc.getWindow().getGuiScaledWidth() - 20);
        x = Math.max(10, Math.min(x, mc.getWindow().getGuiScaledWidth() - 10));
        int textWidth = Math.min(mc.font.width(text), availableWidth);
        x = Math.max(10, Math.min(x, mc.getWindow().getGuiScaledWidth() - textWidth - 18));
        text = mc.font.plainSubstrByWidth(text, textWidth);
        drawContext.fill(x, y, x + textWidth + 8, y + HEIGHT, 0xA0FF8800);
        drawContext.drawString(mc.font, text, x + 4, y + 3, 0xFFFFFF00, false);
        if (mouseX >= x && mouseX <= x + textWidth + 8 && mouseY >= y && mouseY <= y + HEIGHT) {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY,
                    List.of(label, StringUtils.translate("syncmaterial.gui.tooltip.area_overlap_warning")));
        }
    }

    static String normalize(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
