package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import com.google.common.collect.ImmutableList;

public abstract class MaterialListBase {
    protected final MaterialListHudRenderer hudRenderer = new MaterialListHudRenderer(this);
    protected final List<MaterialListEntry> materialListPreFiltered = new ArrayList<>();
    protected final List<MaterialListEntry> materialListFiltered = new ArrayList<>();
    protected ImmutableList<MaterialListEntry> materialListAll = ImmutableList.of();
    protected MaterialListSorter.SortCriteria sortCriteria = MaterialListSorter.SortCriteria.COUNT_TOTAL;
    protected boolean reverse = false;
    protected boolean hideAvailable;
    protected int multiplier = 1;
    protected long countTotal;
    protected long countMissing;
    protected long countMismatched;

    public abstract String getName();

    public abstract String getTitle();

    public abstract void reCreateMaterialList();

    public MaterialListHudRenderer getHudRenderer() {
        return this.hudRenderer;
    }

    public ImmutableList<MaterialListEntry> getMaterialsAll() {
        return this.materialListAll;
    }

    public List<MaterialListEntry> getMaterialsFiltered(boolean refresh) {
        if (this.hideAvailable) {
            return this.getMaterialsMissingOnly(refresh);
        }
        return this.materialListPreFiltered;
    }

    public List<MaterialListEntry> getMaterialsMissingOnly(boolean refresh) {
        if (refresh) {
            this.recreateFilteredList();
        }
        return this.materialListFiltered;
    }

    public void recreateFilteredList() {
        this.materialListFiltered.clear();
        for (int i = 0; i < this.materialListPreFiltered.size(); i++) {
            MaterialListEntry entry = this.materialListPreFiltered.get(i);
            if (entry.getCountMissing() > 0) {
                this.materialListFiltered.add(entry);
            }
        }
    }

    public MaterialListSorter.SortCriteria getSortCriteria() {
        return this.sortCriteria;
    }

    public boolean getSortInReverse() {
        return this.reverse;
    }

    public int getMultiplier() {
        return this.multiplier;
    }

    public long getCountTotal() {
        return this.countTotal;
    }

    public long getCountMissing() {
        return this.countMissing;
    }

    public long getCountMismatched() {
        return this.countMismatched;
    }

    public void updateCounts() {
        this.countTotal = 0;
        this.countMissing = 0;
        this.countMismatched = 0;
        for (MaterialListEntry entry : this.materialListAll) {
            this.countTotal += entry.getCountTotal();
            this.countMissing += entry.getCountMissing();
            this.countMismatched += entry.getCountMismatched();
        }
    }

    public void setSortCriteria(MaterialListSorter.SortCriteria criteria) {
        this.sortCriteria = criteria;
    }

    public void setSortInReverse(boolean reverse) {
        this.reverse = reverse;
    }

    public void setHideAvailable(boolean hideAvailable) {
        this.hideAvailable = hideAvailable;
    }

    public boolean getHideAvailable() {
        return this.hideAvailable;
    }
}
