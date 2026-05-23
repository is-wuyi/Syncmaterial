package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket.AreaInfo;

public record StagingAreaEntry(int areaId, String name, int x1, int y1, int z1, int x2, int y2, int z2)
{
    public static List<StagingAreaEntry> fromAreaInfos(List<AreaInfo> infos)
    {
        List<StagingAreaEntry> list = new ArrayList<>();
        for (AreaInfo info : infos)
        {
            list.add(new StagingAreaEntry(info.areaId(), info.name(),
                    info.x1(), info.y1(), info.z1(),
                    info.x2(), info.y2(), info.z2()));
        }
        return list;
    }
}
