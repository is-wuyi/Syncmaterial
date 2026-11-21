package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 材料选择的版本语义：每个用户意图独立，迟到的批量分配回执只能清除提交时
 * 仍保持原意图的 id。
 */
class MaterialSelectionTest {

    private static MaterialSelection selection(Integer... ids) {
        MaterialSelection selection = new MaterialSelection();
        for (Integer id : ids) {
            selection.ids().add(id);
        }
        return selection;
    }

    @Test
    void listCompatibilityShellSupportsExistingMutations() {
        MaterialSelection selection = selection(11, 12, 13);

        assertTrue(selection.ids().contains(12));
        assertEquals(3, selection.ids().size());
        assertFalse(selection.ids().isEmpty());

        selection.ids().remove(Integer.valueOf(12));
        assertFalse(selection.ids().contains(12));
        assertEquals(List.of(11, 13), selection.ids());

        assertTrue(selection.ids().removeIf(id -> id == 13));
        assertEquals(List.of(11), selection.ids());

        selection.ids().clear();
        assertTrue(selection.ids().isEmpty());
        assertTrue(selection.ids().add(21));
        assertEquals(List.of(21), selection.ids());
    }

    @Test
    void repeatedSelectionDoesNotAdvanceIntentOrReportStructuralChange() {
        MaterialSelection selection = selection(11);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));

        assertFalse(selection.ids().add(11));
        assertEquals(List.of(11), selection.ids());
        assertEquals(1, selection.clearCaptured(submitted));
    }

    @Test
    void lateCompletionKeepsNewlySelectedIds() {
        MaterialSelection selection = selection(11, 12);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11, 12));

        selection.ids().add(13);

        assertEquals(2, selection.clearCaptured(submitted));
        assertEquals(List.of(13), selection.ids(), "旧组只能清除提交时捕获的 id");
    }

    @Test
    void deselectThenReselectKeepsNewIntentForSameId() {
        MaterialSelection selection = selection(11);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));

        selection.ids().remove(Integer.valueOf(11));
        selection.ids().add(11);

        assertEquals(0, selection.clearCaptured(submitted));
        assertEquals(List.of(11), selection.ids(), "同 id 重选属于新意图，旧回执不得清除");
    }

    @Test
    void repeatedCompletionIsIdempotentAndCannotClearReselectedId() {
        MaterialSelection selection = selection(11);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));

        assertEquals(1, selection.clearCaptured(submitted));
        assertTrue(selection.ids().isEmpty());

        selection.ids().add(11);
        assertEquals(0, selection.clearCaptured(submitted), "重复回包不得清除重新选择的 id");
        assertEquals(List.of(11), selection.ids());
    }

    @Test
    void captureSkipsIdsThatWereAlreadyDeselected() {
        MaterialSelection selection = selection(11);
        selection.ids().remove(Integer.valueOf(11));

        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));
        assertTrue(submitted.isEmpty());

        selection.ids().add(11);
        assertEquals(0, selection.clearCaptured(submitted));
        assertEquals(List.of(11), selection.ids());
    }

    @Test
    void catalogCleanupDropsDeletedIdsOnly() {
        MaterialSelection selection = selection(11, 12);

        assertEquals(1, selection.retainIds(List.of(12)));
        assertEquals(List.of(12), selection.ids());
    }

    @Test
    void deletedThenRediscoveredIdIsANewIntent() {
        MaterialSelection selection = selection(11);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));

        selection.retainIds(List.of());
        selection.ids().add(11);

        assertEquals(0, selection.clearCaptured(submitted));
        assertEquals(List.of(11), selection.ids());
    }

    @Test
    void clearingSelectionDoesNotLetOldSnapshotMatchReaddedId() {
        MaterialSelection selection = selection(11);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(11));

        selection.ids().clear();
        selection.ids().add(11);

        assertEquals(0, selection.clearCaptured(submitted));
        assertEquals(List.of(11), selection.ids());
    }

    @Test
    void snapshotCannotClearAnotherSelectionInstance() {
        MaterialSelection first = selection(11);
        MaterialSelection second = selection(11);
        MaterialSelection.Snapshot submitted = first.capture(List.of(11));

        assertEquals(0, second.clearCaptured(submitted));
        assertEquals(List.of(11), second.ids());
        assertEquals(1, first.clearCaptured(submitted));
    }

    @Test
    void snapshotExposesOnlySubmittedIntentIds() {
        MaterialSelection selection = selection(11, 12);
        MaterialSelection.Snapshot submitted = selection.capture(List.of(12));

        assertEquals(1, submitted.size());
        assertEquals(java.util.Set.of(12), submitted.materialIds());
        assertTrue(selection.capture(List.of()).isEmpty());
        assertTrue(selection.capture(null).isEmpty());
    }
}
