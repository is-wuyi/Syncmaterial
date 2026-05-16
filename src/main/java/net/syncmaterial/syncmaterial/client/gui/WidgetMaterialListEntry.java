package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class WidgetMaterialListEntry extends WidgetListEntrySortable<MaterialListEntry> {
    private static final String[] HEADERS = new String[] {
            "物品",
            "总计",
            "缺失",
            "已有"
    };
    private static int maxNameLength;
    private static int maxCountLength1;
    private static int maxCountLength2;
    private static int maxCountLength3;

    private final MaterialListBase materialList;
    private final WidgetListMaterialList listWidget;
    private final MaterialListEntry entry;
    private final String header1;
    private final String header2;
    private final String header3;
    private final String header4;
    private final boolean isOdd;

    public WidgetMaterialListEntry(int x, int y, int width, int height, boolean isOdd,
            MaterialListBase materialList, MaterialListEntry entry, int listIndex, WidgetListMaterialList listWidget) {
        super(x, y, width, height, entry, listIndex);

        this.columnCount = 4;
        this.entry = entry;
        this.isOdd = isOdd;
        this.listWidget = listWidget;
        this.materialList = materialList;

        if (this.entry != null) {
            this.header1 = null;
            this.header2 = null;
            this.header3 = null;
            this.header4 = null;
        } else {
            this.header1 = HEADERS[0];
            this.header2 = HEADERS[1];
            this.header3 = HEADERS[2];
            this.header4 = HEADERS[3];
        }
    }

    public static void setMaxNameLength(List<MaterialListEntry> list, int multiplier) {
        maxNameLength = 0;
        maxCountLength1 = 0;
        maxCountLength2 = 0;
        maxCountLength3 = 0;

        MinecraftClient mc = MinecraftClient.getInstance();

        for (MaterialListEntry entry : list) {
            maxNameLength = Math.max(maxNameLength, mc.textRenderer.getWidth(entry.getStack().getName().getString()));
            maxCountLength1 = Math.max(maxCountLength1, mc.textRenderer.getWidth(String.valueOf(entry.getCountTotal() * multiplier)));
            maxCountLength2 = Math.max(maxCountLength2, mc.textRenderer.getWidth(String.valueOf(entry.getCountMissing())));
            maxCountLength3 = Math.max(maxCountLength3, mc.textRenderer.getWidth(String.valueOf(entry.getCountAvailable())));
        }

        maxNameLength = Math.max(maxNameLength, mc.textRenderer.getWidth(HEADERS[0]));
        maxCountLength1 = Math.max(maxCountLength1, mc.textRenderer.getWidth(HEADERS[1]));
        maxCountLength2 = Math.max(maxCountLength2, mc.textRenderer.getWidth(HEADERS[2]));
        maxCountLength3 = Math.max(maxCountLength3, mc.textRenderer.getWidth(HEADERS[3]));
    }

    @Override
    protected int getCurrentSortColumn() {
        return this.materialList.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse() {
        return this.materialList.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column) {
        int x1 = this.x + 4;
        int x2 = x1 + maxNameLength + 40;
        int x3 = x2 + maxCountLength1 + 20;
        int x4 = x3 + maxCountLength2 + 20;

        return switch (column) {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x4;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
        if (super.onMouseClickedImpl(mouseX, mouseY, mouseButton)) {
            return true;
        }

        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (this.entry != null) {
            if (this.isOdd) {
                RenderUtils.drawRect(this.x, this.y, this.width, this.height, 0x20FFFFFF);
            } else {
                RenderUtils.drawRect(this.x, this.y, this.width, this.height, 0x30FFFFFF);
            }
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int x4 = this.getColumnPosX(3);
        int y = this.y + 3;

        if (this.entry == null) {
            if (this.header1 != null) {
                drawContext.drawTextWithShadow(mc.textRenderer, this.header1, x1, y, 0xFFFFFFFF);
            }
            if (this.header2 != null) {
                drawContext.drawTextWithShadow(mc.textRenderer, this.header2, x2, y, 0xFFFFFFFF);
            }
            if (this.header3 != null) {
                drawContext.drawTextWithShadow(mc.textRenderer, this.header3, x3, y, 0xFFFFFFFF);
            }
            if (this.header4 != null) {
                drawContext.drawTextWithShadow(mc.textRenderer, this.header4, x4, y, 0xFFFFFFFF);
            }
        } else {
            ItemStack stack = this.entry.getStack();
            drawContext.drawItem(stack, x1, this.y + 2);
            drawContext.drawTextWithShadow(mc.textRenderer, stack.getName().getString(), x1 + 20, y, 0xFFFFFFFF);

            drawContext.drawTextWithShadow(mc.textRenderer, String.valueOf(this.entry.getCountTotal()), x2, y, 0xFFFFFFFF);

            int missing = this.entry.getCountMissing();
            drawContext.drawTextWithShadow(mc.textRenderer, String.valueOf(missing), x3, y, missing > 0 ? 0xFFFF5555 : 0xFFFFFFFF);

            drawContext.drawTextWithShadow(mc.textRenderer, String.valueOf(this.entry.getCountAvailable()), x4, y, 0xFFFFFFFF);
        }
    }
}
