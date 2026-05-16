package net.syncmaterial.syncmaterial.client.gui;

import net.syncmaterial.syncmaterial.api.MaterialEntry;
import java.util.List;

public class SyncMaterialList extends MaterialListBase {
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

    public void setMaterialEntries(List<MaterialEntry> entries) {
        this.setMaterialListEntries(MaterialListUtils.convertFromMaterialEntries(entries));
    }
}
