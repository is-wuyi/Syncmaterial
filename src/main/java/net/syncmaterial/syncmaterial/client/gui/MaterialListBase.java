package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.util.math.MathHelper;

public abstract class MaterialListBase
{
    protected final MaterialListHudRenderer hudRenderer = new MaterialListHudRenderer(this);
    protected final List<MaterialListEntry> materialListPreFiltered = new ArrayList<>();
    protected final List<MaterialListEntry> materialListFiltered = new ArrayList<>();
    protected ImmutableList<MaterialListEntry> materialListAll = ImmutableList.of();
    protected final Map<MaterialListEntry, String> claimStatusMap = new HashMap<>();
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

            if (entry.getCountMissing() > 0)
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

    public String getClaimStatus(MaterialListEntry entry)
    {
        return this.claimStatusMap.getOrDefault(entry, StringUtils.translate("syncmaterial.gui.label.unclaimed"));
    }

    public void setClaimStatus(MaterialListEntry entry, String status)
    {
        this.claimStatusMap.put(entry, status);
    }

    public void claimEntry(MaterialListEntry entry)
    {
    }

    /**
     * 格式化物品数量字符串，显示堆叠数和潜影盒数量。
     * 例：130 = 2 x 64 + 2 = 0.20 [shulker]
     *
     * @param count         物品总数
     * @param maxStackSize  最大堆叠数（若 <= 0 则按 64 处理）
     * @param shulkerBoxAbbr 潜影盒缩写（由 StringUtils.translate 获取）
     */
    public static String getFormattedCountString(int count, int maxStackSize, String shulkerBoxAbbr)
    {
        if (maxStackSize <= 0) maxStackSize = 64;

        int stacks = count / maxStackSize;
        int remainder = count % maxStackSize;
        double boxCount = (double) count / (27D * maxStackSize);
        String strCount;

        if (count > maxStackSize)
        {
            if (maxStackSize > 1)
            {
                if (remainder > 0)
                {
                    strCount = String.format("%d = %d x %d + %d = %.2f %s", count, stacks, maxStackSize, remainder, boxCount, shulkerBoxAbbr);
                }
                else
                {
                    strCount = String.format("%d = %d x %d = %.2f %s", count, stacks, maxStackSize, boxCount, shulkerBoxAbbr);
                }
            }
            else
            {
                strCount = String.format("%d = %.2f %s", count, boxCount, shulkerBoxAbbr);
            }
        }
        else
        {
            strCount = String.format("%d", count);
        }

        return strCount;
    }

    /**
     * HUD 专用格式：简洁的 Litematica 风格
     * 261 (4 x 64 + 5) 或 256 (4 x 64) 或 261 (0.16 潜影盒)
     */
    public static String getFormattedCountStringHud(int count, int maxStackSize, String shulkerBoxAbbr)
    {
        if (maxStackSize <= 0) maxStackSize = 64;

        int stacks = count / maxStackSize;
        int remainder = count % maxStackSize;
        double boxCount = (double) count / (27D * maxStackSize);

        if (count > maxStackSize)
        {
            if (boxCount >= 1.0)
            {
                return String.format("%d (%.2f %s)", count, boxCount, shulkerBoxAbbr);
            }
            else if (remainder > 0)
            {
                return String.format("%d (%d x %d + %d)", count, stacks, maxStackSize, remainder);
            }
            else
            {
                return String.format("%d (%d x %d)", count, stacks, maxStackSize);
            }
        }

        return String.valueOf(count);
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
