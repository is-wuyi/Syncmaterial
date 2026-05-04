package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

import java.util.List;
import java.util.stream.Collectors;

public class LitematicaMaterialListAdapter
{
    public static MaterialListBase createMaterialListBase(String schematicName, List<MaterialEntry> entries)
    {
        List<MaterialListEntry> litematicaEntries = convert(entries);
        SyncMaterialMaterialList materialList = new SyncMaterialMaterialList(schematicName);
        materialList.setMaterialListEntries(litematicaEntries);

        MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), MinecraftClient.getInstance().player);

        updateCountMissing(materialList.getMaterialsAll());

        return materialList;
    }

    private static void updateCountMissing(List<MaterialListEntry> entries)
    {
        for (MaterialListEntry entry : entries)
        {
            int countTotal = entry.getCountTotal();
            int countAvailable = entry.getCountAvailable();
            int countMissing = countTotal - countAvailable;
            if (countMissing < 0) countMissing = 0;

            try
            {
                java.lang.reflect.Field field = MaterialListEntry.class.getDeclaredField("countMissing");
                field.setAccessible(true);
                field.setInt(entry, countMissing);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static List<MaterialListEntry> convert(List<MaterialEntry> entries)
    {
        return entries.stream()
                .map(LitematicaMaterialListAdapter::convertEntry)
                .collect(Collectors.toList());
    }

    private static MaterialListEntry convertEntry(MaterialEntry entry)
    {
        ItemStack stack = entry.getStack();
        int countTotal = safeLongToInt(entry.getCountTotal());
        int countMissing = safeLongToInt(entry.getCountMissing());
        int countMismatched = safeLongToInt(entry.getCountMismatched());
        int countAvailable = safeLongToInt(entry.getCountAvailable());

        return new MaterialListEntry(stack, countTotal, countMissing, countMismatched, countAvailable);
    }

    private static int safeLongToInt(long value)
    {
        if (value > Integer.MAX_VALUE)
        {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static class SyncMaterialMaterialList extends MaterialListBase
    {
        private final String name;

        public SyncMaterialMaterialList(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return this.name;
        }

        @Override
        public String getTitle()
        {
            return "共享材料表 - " + this.name;
        }

        @Override
        public void reCreateMaterialList()
        {
        }
    }
}