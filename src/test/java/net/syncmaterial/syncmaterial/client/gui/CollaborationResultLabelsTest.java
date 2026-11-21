package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.util.StringUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class CollaborationResultLabelsTest {
    @Test
    void typedResultUsesOnlyKnownTranslationKeys() {
        try (var strings = mockStatic(StringUtils.class)) {
            strings.when(() -> StringUtils.translate("syncmaterial.gui.result.applied"))
                    .thenReturn("操作已生效");
            strings.when(() -> StringUtils.translate("syncmaterial.gui.result.denied"))
                    .thenReturn("操作被拒绝");
            assertEquals("操作已生效", CollaborationResultLabels.message("APPLIED"));
            assertEquals("操作被拒绝", CollaborationResultLabels.message("DENIED"));
        }
    }

    @Test
    void frozenLegacyAndUnknownResultsRemainReadableWithoutInventingKeys() {
        try (var strings = mockStatic(StringUtils.class)) {
            assertEquals("已退出协作", CollaborationResultLabels.message("已退出协作"));
            assertEquals("未参与该材料", CollaborationResultLabels.message("未参与该材料"));
            assertEquals("NEW_FUTURE_STATUS", CollaborationResultLabels.message("NEW_FUTURE_STATUS"));
            assertEquals("", CollaborationResultLabels.message(null));
            strings.verifyNoInteractions();
        }
    }
}
