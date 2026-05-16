package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import net.minecraft.util.math.MathHelper;

public abstract class MaterialListBase
{
    protected final MaterialListHudRenderer hudRenderer = new MaterialListHudRenderer(this);
    protected final Set<MaterialListEntry> ignored = new HashSet<>();
    protected final List<MaterialListEntry> materialListPreFiltered = new ArrayList<>();
    protected final List<MaterialListEntry> materialListFiltered = new ArrayList<>();
    protected ImmutableList<MaterialListEntry> materialListAll = ImmutableList.of();
    protected ICompletionListener completionListener;
    protected SortCriteria sortCriteria = SortCriteria.COUNT_TOTAL;
    protected boolean reverse = false;
    protected boolean hideAvailable;
    protected int multiplier = 1;
    protected long countTotal;
    protected long countMissing;
    protected long countMismatched;

    public abstract String getName();

    public abstract String getTitle();

    public boolean supportsRenderLayers()
    {
        return false;
    }

    public MaterialListHudRenderer getHudRenderer()
    {
        return this.hudRenderer;
    }

    public ImmutableList<MaterialListEntry> getMaterialsAll()
    {
        return this.materialListAll;
    }

    public List<MaterialListEntry> getMaterialsFiltered(boolean refresh)
    {
        if (this.hideAvailable)
        {
            return this.getMaterialsMissingOnly(refresh);
        }

        return this.materialListPreFiltered;
    }

    public List<MaterialListEntry> getMaterialsMissingOnly(boolean refresh)
    {
        if (refresh)
        {
            this.recreateFilteredList();
        }

        return this.materialListFiltered;
    }

    public void setCompletionListener(ICompletionListener listener)
    {
        this.completionListener = listener;
    }

    public void recreateFilteredList()
    {
        this.materialListFiltered.clear();

        for (int i = 0; i < this.materialListPreFiltered.size(); ++i)
        {
            MaterialListEntry entry = this.materialListPreFiltered.get(i);
            int countMissing = this.multiplier == 1 ? entry.getCountMissing() : this.multiplier * entry.getCountTotal();

            if (entry.getCountAvailable() < countMissing)
            {
                this.materialListFiltered.add(entry);
            }
            // Remove entries that have been seen as available at least at one point
            // (for example when gathering resources to a staging area)
            else if (this.hideAvailable)
            {
                this.materialListPreFiltered.remove(i);
                --i;
            }
        }
    }

    public void ignoreEntry(MaterialListEntry entry)
    {
        this.ignored.add(entry);
        this.materialListPreFiltered.remove(entry);
        this.recreateFilteredList();
    }

    public void clearIgnored()
    {
        this.ignored.clear();
        this.refreshPreFilteredList();
        this.recreateFilteredList();
    }

    /**
     * Re-creates the all-materials list from the schematic or placement or area
     * by starting a new task, if applicable.
     */
    public abstract void reCreateMaterialList();

    public void setMaterialListEntries(List<MaterialListEntry> list)
    {
        this.materialListAll = ImmutableList.copyOf(list);
        this.refreshPreFilteredList();
        this.updateCounts();

        if (this.completionListener != null)
        {
            this.completionListener.onTaskCompleted();
        }
    }


    /**
     * Resets the pre-filtered materials list to the all materials list
     */
    public void refreshPreFilteredList()
    {
        this.materialListPreFiltered.clear();
        this.materialListPreFiltered.addAll(this.materialListAll);
        this.materialListPreFiltered.removeAll(this.ignored);
    }

    public SortCriteria getSortCriteria()
    {
        return this.sortCriteria;
    }

    public boolean getSortInReverse()
    {
        return this.reverse;
    }

    public boolean getHideAvailable()
    {
        return this.hideAvailable;
    }

    public int getMultiplier()
    {
        return this.multiplier;
    }

    public void setSortCriteria(SortCriteria criteria)
    {
        if (this.sortCriteria == criteria)
        {
            this.reverse = ! this.reverse;
        }
        else
        {
            this.sortCriteria = criteria;
            this.reverse = false;
        }
    }

    public void setSortInReverse(boolean reverse) {
        this.reverse = reverse;
    }

    public void setHideAvailable(boolean hideAvailable)
    {
        this.hideAvailable = hideAvailable;
    }

    public void setMultiplier(int multiplier)
    {
        this.multiplier = MathHelper.clamp(multiplier, 1, Integer.MAX_VALUE);
    }

    public void updateCounts()
    {
        this.countTotal = 0;
        this.countMissing = 0;
        this.countMismatched = 0;

        for (MaterialListEntry entry : this.materialListAll)
        {
            this.countTotal += entry.getCountTotal();
            this.countMissing += entry.getCountMissing();
            this.countMismatched += entry.getCountMismatched();
        }
    }

    public long getCountTotal()
    {
        return this.countTotal;
    }

    public long getCountMissing()
    {
        return this.countMissing;
    }

    public long getCountMismatched()
    {
        return this.countMismatched;
    }




    public enum SortCriteria
    {
        NAME,
        COUNT_TOTAL,
        COUNT_MISSING,
        COUNT_AVAILABLE;

        public static SortCriteria fromStringStatic(String name)
        {
            for (SortCriteria mode : SortCriteria.values())
            {
                if (mode.name().equalsIgnoreCase(name))
                {
                    return mode;
                }
            }

            return SortCriteria.COUNT_TOTAL;
        }
    }
}
