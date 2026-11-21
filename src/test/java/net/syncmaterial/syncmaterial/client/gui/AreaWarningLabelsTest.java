package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;
import fi.dy.masa.malilib.util.StringUtils;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class AreaWarningLabelsTest {
    @Test
    void typedStatusesUseLocalizedKeysWhileLegacyMessagesStayReadable() {
        try (MockedStatic<StringUtils> strings = mockStatic(StringUtils.class)) {
            strings.when(() -> StringUtils.translate("syncmaterial.gui.area_status.stale"))
                    .thenReturn("观测已过期");
            strings.when(() -> StringUtils.translate("syncmaterial.gui.area_status.unknown"))
                    .thenReturn("尚未观测");
            strings.when(() -> StringUtils.translate("syncmaterial.gui.area_status.opaque"))
                    .thenReturn("内容无法确认");
            assertEquals("观测已过期", AreaWarningLabels.status("stale"));
            assertEquals("尚未观测", AreaWarningLabels.status("unknown"));
            assertEquals("内容无法确认", AreaWarningLabels.status("opaque"));
            assertEquals("区块尚未加载", AreaWarningLabels.status("区块尚未加载"));
            assertEquals("", AreaWarningLabels.status(null));
        }
    }

    @Test
    void summarySeparatesAreaStatusAndDeduplicatesRepeatedWarnings() {
        var stale = new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging_area", 1, "平台", "stale");
        try (MockedStatic<StringUtils> strings = mockStatic(StringUtils.class)) {
            strings.when(() -> StringUtils.translate("syncmaterial.gui.label.freshness_warning"))
                    .thenReturn("区域库存待确认：");
            strings.when(() -> StringUtils.translate("syncmaterial.gui.area_status.stale"))
                    .thenReturn("观测已过期");
            assertEquals("区域库存待确认： 平台 — 观测已过期", AreaWarningLabels.summary(List.of(stale, stale)));
            assertEquals("区域库存待确认：", AreaWarningLabels.summary(List.of()));
        }
    }
}
