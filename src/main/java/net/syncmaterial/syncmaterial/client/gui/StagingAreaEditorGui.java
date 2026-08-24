package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;

public interface StagingAreaEditorGui extends ISelectionListener<StagingAreaEntry>
{
    String getSchematicId();

    void deleteArea(int areaId);

    void refreshAreas();

    /** 备货区所属维度；未知时返回空串 */
    String getAreaWorld(String areaName);
}
