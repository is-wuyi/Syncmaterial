package net.syncmaterial.syncmaterial.client.gui;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

import java.util.List;

public class GuiMaterialList extends GuiListBase<MaterialListEntry, WidgetMaterialListEntry, WidgetListMaterialList> {
    private final MaterialListBase materialList;

    public GuiMaterialList(String schematicName, List<MaterialEntry> entries) {
        super(10, 44);

        SyncMaterialList materialList = new SyncMaterialList(schematicName);
        List<MaterialListEntry> convertedEntries = MaterialListUtils.convertFromMaterialEntries(entries);
        materialList.setMaterialEntries(convertedEntries);
        this.materialList = materialList;
        this.title = schematicName;
        this.useTitleHierarchy = false;

        MaterialListUtils.updateAvailableCounts(this.materialList.getMaterialsAll(), this.mc.player);
        WidgetMaterialListEntry.setMaxNameLength(materialList.getMaterialsAll(), materialList.getMultiplier());
    }

    public MaterialListBase getMaterialList() {
        return this.materialList;
    }

    @Override
    protected int getBrowserWidth() {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight() {
        return this.getScreenHeight() - 88;
    }

    @Override
    protected WidgetListMaterialList createListWidget(int listX, int listY) {
        return new WidgetListMaterialList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    public void initGui() {
        super.initGui();

        int x = 10;
        int y = 26;

        this.createButtonSortBy(x, y, MaterialListSorter.SortCriteria.COUNT_TOTAL);
        x += 50;
        this.createButtonSortBy(x, y, MaterialListSorter.SortCriteria.COUNT_MISSING);
        x += 50;
        this.createButtonSortBy(x, y, MaterialListSorter.SortCriteria.NAME);
        x += 50;

        x = this.getScreenWidth() - 170;
        this.createButtonToggleHud(x, y);
        x += 60;
        this.createButtonClose(x, y);
    }

    private void createButtonSortBy(int x, int y, MaterialListSorter.SortCriteria criteria) {
        String label = switch (criteria) {
            case COUNT_TOTAL -> "总计";
            case COUNT_MISSING -> "缺失";
            case NAME -> "名称";
            default -> "???";
        };

        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, label);
        this.addButton(button, (btn, mouseButton) -> {
            MaterialListSorter sorter = new MaterialListSorter();
            if (this.materialList.getSortCriteria() == criteria) {
                this.materialList.setSortInReverse(!this.materialList.getSortInReverse());
            } else {
                this.materialList.setSortCriteria(criteria);
                this.materialList.setSortInReverse(false);
            }
            this.getListWidget().refreshEntries();
        });
    }

    private void createButtonToggleHud(int x, int y) {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, true, "HUD", this.materialList.getHudRenderer().getShouldRender());
        this.addButton(button, (btn, mouseButton) -> {
            this.materialList.getHudRenderer().toggleShouldRender();
            button.updateDisplayString(this.materialList.getHudRenderer().getShouldRender());
        });
    }

    private void createButtonClose(int x, int y) {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, "关闭");
        this.addButton(button, (btn, mouseButton) -> this.close());
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        super.render(drawContext, mouseX, mouseY, partialTicks);

        int x = 10;
        int y = this.getScreenHeight() - 30;

        String total = "总计: " + this.materialList.getCountTotal();
        String missing = "缺失: " + this.materialList.getCountMissing();
        String mismatched = "不匹配: " + this.materialList.getCountMismatched();

        drawContext.drawTextWithShadow(this.textRenderer, total, x, y, 0xFFFFFFFF);
        x += this.textRenderer.getWidth(total) + 10;
        drawContext.drawTextWithShadow(this.textRenderer, missing, x, y, 0xFFFF5555);
        x += this.textRenderer.getWidth(missing) + 10;
        drawContext.drawTextWithShadow(this.textRenderer, mismatched, x, y, 0xFF5555FF);

        if (this.materialList.getCountTotal() > 0) {
            long countTotal = this.materialList.getCountTotal();
            long countMissing = this.materialList.getCountMissing();
            int progress = (int) ((countTotal - countMissing) * 100 / countTotal);
            String progressText = String.format("完成: %d%%", progress);
            drawContext.drawTextWithShadow(this.textRenderer, progressText, this.getScreenWidth() - 100, y, 0xFF55FF55);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static class SyncMaterialList extends MaterialListBase {
        private final String title;

        public SyncMaterialList(String title) {
            this.title = title;
        }

        @Override
        public String getName() {
            return this.title;
        }

        @Override
        public String getTitle() {
            return this.title;
        }

        @Override
        public void reCreateMaterialList() {
        }

        public void setMaterialEntries(List<MaterialListEntry> entries) {
            this.materialListAll = ImmutableList.copyOf(entries);
            this.materialListPreFiltered.clear();
            this.materialListPreFiltered.addAll(entries);
            this.updateCounts();
        }
    }
}
