package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import com.google.common.collect.ImmutableList;
import net.minecraft.util.Mth;

public abstract class MaterialListBase
{
    protected final MaterialListHudRenderer hudRenderer = new MaterialListHudRenderer(this);
    protected final List<MaterialListEntry> materialListPreFiltered = new ArrayList<>();
    protected final List<MaterialListEntry> materialListFiltered = new ArrayList<>();
    protected ImmutableList<MaterialListEntry> materialListAll = ImmutableList.of();
    protected SortCriteria sortCriteria = SortCriteria.COUNT_TOTAL;
    protected boolean reverse = false;
    protected int multiplier = 1;
    protected long countTotal;
    protected long countMissing;
    protected long countMismatched;

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

    public void recreateFilteredList()
    {
        this.materialListFiltered.clear();

        for (MaterialListEntry entry : this.materialListPreFiltered)
        {
            if (entry.getCountMissing() > 0)
            {
                this.materialListFiltered.add(entry);
            }
        }
    }

    public void setMaterialListEntries(List<MaterialListEntry> list)
    {
        this.materialListAll = ImmutableList.copyOf(list);
        this.refreshPreFilteredList();
        this.updateCounts();
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

    public void setMultiplier(int multiplier)
    {
        this.multiplier = net.minecraft.util.Mth.clamp(multiplier, 1, Integer.MAX_VALUE);
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
        COUNT_AVAILABLE,
        COUNT_OTHER,
        COUNT_STAGING,
        COUNT_WAREHOUSE,
        COUNT_CLAIM;

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
