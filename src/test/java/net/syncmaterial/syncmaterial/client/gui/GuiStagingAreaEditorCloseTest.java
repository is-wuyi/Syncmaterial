package net.syncmaterial.syncmaterial.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/** The editor's on-screen Back button must use the same close path as Esc. */
class GuiStagingAreaEditorCloseTest {
    @Test
    void closeButtonRoutesThroughCloseGui() {
        GuiStagingAreaEditorNormal editor = mock(GuiStagingAreaEditorNormal.class);
        GuiStagingAreaEditorNormal.ButtonListener listener =
                new GuiStagingAreaEditorNormal.ButtonListener(
                        GuiStagingAreaEditorNormal.ButtonListener.Type.CLOSE, null, null, editor);

        // The listener computes a nudge amount before dispatching; avoid touching the
        // real keyboard/window state while testing the CLOSE dispatch.
        try (MockedStatic<GuiBase> guiBase = mockStatic(GuiBase.class)) {
            guiBase.when(GuiBase::isShiftDown).thenReturn(false);
            guiBase.when(GuiBase::isAltDown).thenReturn(false);
            listener.actionPerformedWithButton(null, 0);
        }

        verify(editor).closeGui(true);
    }
}
