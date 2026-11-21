package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * GuiMaterialList 的选择容器与完成方法：界面层必须把提交时捕获的快照
 * 原样交给选择状态，不能在回包时清空整个当前勾选。
 */
class GuiMaterialListBatchSelectionTest {
    private static final String PROJECT = "project-a";

    private GuiMaterialList newGui() {
        GuiMaterialList gui = mock(GuiMaterialList.class, CALLS_REAL_METHODS);
        SyncMaterialList sync = mock(SyncMaterialList.class);
        when(sync.getSchematicId()).thenReturn(PROJECT);
        setField(gui, "materialList", sync);
        setField(gui, "selectedMaterials", new MaterialSelection());
        return gui;
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("missing field " + name);
    }

    @Test
    void successfulCompletionClearsOnlySubmittedIntent() {
        GuiMaterialList gui = newGui();
        gui.getSelectedMaterialIds().add(11);
        gui.getSelectedMaterialIds().add(12);
        MaterialSelection.Snapshot submitted = gui.captureBatchSelection(List.of(11, 12));

        gui.getSelectedMaterialIds().add(13);
        gui.applyBatchAssignResult(true, submitted);

        assertEquals(List.of(13), gui.getSelectedMaterialIds());
        verify(gui.getMaterialList()).requestCollaborationStatus();
    }

    @Test
    void hiddenSelectionIsCountedAgainstTheCurrentFilter() {
        GuiMaterialList gui = newGui();
        gui.getSelectedMaterialIds().add(11);
        gui.getSelectedMaterialIds().add(12);
        org.mockito.Mockito.doReturn(List.of(11)).when(gui).getCurrentEntryIds();

        assertEquals(1, gui.getSelectedOutsideFilterCount());
    }

    @Test
    void failedCompletionLeavesEverySelectionAlone() {
        GuiMaterialList gui = newGui();
        gui.getSelectedMaterialIds().add(11);
        MaterialSelection.Snapshot submitted = gui.captureBatchSelection(List.of(11));

        gui.getSelectedMaterialIds().add(13);
        gui.applyBatchAssignResult(false, submitted);

        assertEquals(List.of(11, 13), gui.getSelectedMaterialIds());
    }

    @Test
    void anonymousLegacyResultNeverClearsSelection() {
        GuiMaterialList gui = newGui();
        gui.getSelectedMaterialIds().add(11);

        gui.applyBatchAssignResult(true);

        assertEquals(List.of(11), gui.getSelectedMaterialIds(),
                "无意图快照的旧匿名回包只能刷新状态");
        verify(gui.getMaterialList()).requestCollaborationStatus();
    }

    @Test
    void oldSnapshotCannotTouchAnotherGuiSelection() {
        GuiMaterialList first = newGui();
        GuiMaterialList second = newGui();
        first.getSelectedMaterialIds().add(11);
        second.getSelectedMaterialIds().add(21);

        MaterialSelection.Snapshot submitted = first.captureBatchSelection(List.of(11));
        second.getSelectedMaterialIds().add(22);

        second.applyBatchAssignResult(true, submitted);
        assertEquals(List.of(21, 22), second.getSelectedMaterialIds(),
                "其他界面不能消费不属于自己的提交快照");

        first.applyBatchAssignResult(true, submitted);

        assertEquals(List.of(21, 22), second.getSelectedMaterialIds(),
                "回收快照必须绑定发起提交的界面");
    }
}
