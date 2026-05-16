package net.syncmaterial.syncmaterial.client.gui;

import java.util.Comparator;

public class MaterialListSorter implements Comparator<MaterialListEntry> {
    public enum SortCriteria {
        COUNT_TOTAL,
        COUNT_MISSING,
        COUNT_AVAILABLE,
        NAME
    }

    private SortCriteria sortCriteria = SortCriteria.COUNT_TOTAL;
    private boolean reverse = false;

    public void setSortCriteria(SortCriteria criteria) {
        this.sortCriteria = criteria;
    }

    public SortCriteria getSortCriteria() {
        return this.sortCriteria;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
    }

    public boolean getReverse() {
        return this.reverse;
    }

    @Override
    public int compare(MaterialListEntry entry1, MaterialListEntry entry2) {
        int nameCompare = entry1.getStack().getName().getString().compareTo(entry2.getStack().getName().getString());

        if (sortCriteria == SortCriteria.COUNT_TOTAL) {
            return entry1.getCountTotal() == entry2.getCountTotal() ? nameCompare : ((entry1.getCountTotal() > entry2.getCountTotal()) != reverse ? -1 : 1);
        } else if (sortCriteria == SortCriteria.COUNT_MISSING) {
            return entry1.getCountMissing() == entry2.getCountMissing() ? nameCompare : ((entry1.getCountMissing() > entry2.getCountMissing()) != reverse ? -1 : 1);
        } else if (sortCriteria == SortCriteria.COUNT_AVAILABLE) {
            return entry1.getCountAvailable() == entry2.getCountAvailable() ? nameCompare : ((entry1.getCountAvailable() > entry2.getCountAvailable()) != reverse ? -1 : 1);
        }

        return reverse == false ? nameCompare * -1 : nameCompare;
    }
}
