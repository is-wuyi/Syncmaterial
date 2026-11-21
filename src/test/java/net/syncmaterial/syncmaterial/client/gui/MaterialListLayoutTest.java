package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure layout tests: column geometry, draw/clip/hit identity, density, overflow,
 * surrogate-safe clipping and bounded source segments.
 */
class MaterialListLayoutTest {

    private static final ToIntFunction<String> CODEPOINT_WIDTH =
            text -> text.codePointCount(0, text.length());

    @Test
    void wideAndNarrowLayouts_keepAllEightColumns() {
        MaterialListLayout wide = layoutForScreen(960);
        MaterialListLayout medium = layoutForScreen(720);
        MaterialListLayout narrow = layoutForScreen(640);

        assertEquals(MaterialListLayout.Mode.WIDE_SIDEBAR,
                MaterialListLayout.modeForScreenWidth(960));
        assertEquals(MaterialListLayout.Mode.NARROW_MODAL,
                MaterialListLayout.modeForScreenWidth(720));
        assertEquals(MaterialListLayout.Mode.NARROW_MODAL,
                MaterialListLayout.modeForScreenWidth(640));
        assertEquals(250, MaterialListLayout.TEAM_PANEL_WIDTH);

        assertEquals(960 - 2 * MaterialListLayout.SCREEN_SIDE_MARGIN
                        - MaterialListLayout.TEAM_PANEL_WIDTH
                        - MaterialListLayout.TEAM_PANEL_GAP,
                MaterialListLayout.rowWidthForScreenWidth(960));
        assertEquals(720 - 2 * MaterialListLayout.SCREEN_SIDE_MARGIN,
                MaterialListLayout.rowWidthForScreenWidth(720));
        assertEquals(640 - 2 * MaterialListLayout.SCREEN_SIDE_MARGIN,
                MaterialListLayout.rowWidthForScreenWidth(640));

        for (MaterialListLayout layout : List.of(wide, medium, narrow)) {
            assertFalse(layout.hasHorizontalOverflow());
            assertEquals(8, layout.columns().size());
            assertEquals(List.of(
                            MaterialListLayout.ColumnKey.ITEM,
                            MaterialListLayout.ColumnKey.TOTAL,
                            MaterialListLayout.ColumnKey.MISSING,
                            MaterialListLayout.ColumnKey.MINE,
                            MaterialListLayout.ColumnKey.OTHERS,
                            MaterialListLayout.ColumnKey.STAGING,
                            MaterialListLayout.ColumnKey.WAREHOUSE,
                            MaterialListLayout.ColumnKey.CLAIM),
                    keys(layout));
            for (MaterialListLayout.Column column : layout.columns()) {
                assertTrue(column.width() > 0, "column must stay visible: " + column.key());
            }
            assertTrue(layout.action().width() > 0);
            assertTrue(layout.sourceBar().width() > 0);
            assertTrue(layout.sourceBar().right() <= layout.rowWidth());
        }

        assertTrue(wide.column(MaterialListLayout.ColumnKey.ITEM).width()
                > narrow.column(MaterialListLayout.ColumnKey.ITEM).width());
    }

    @Test
    void screenDensity_hitsTwelveTenSevenRows() {
        assertEquals(12, MaterialListLayout.rowsForScreenHeight(540));
        assertEquals(10, MaterialListLayout.rowsForScreenHeight(450));
        assertEquals(7, MaterialListLayout.rowsForScreenHeight(360));

        int viewport = 540 - MaterialListLayout.SCREEN_VERTICAL_CHROME;
        assertEquals(12, MaterialListLayout.rowsForViewportHeight(viewport));
        assertEquals(11, MaterialListLayout.rowsForViewportHeight(
                MaterialListLayout.HEADER_HEIGHT + 12 * MaterialListLayout.ROW_HEIGHT - 1));
        assertEquals(12, MaterialListLayout.rowsForViewportHeight(
                MaterialListLayout.HEADER_HEIGHT + 12 * MaterialListLayout.ROW_HEIGHT));

        assertEquals(0, MaterialListLayout.rowsForScreenHeight(0));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialListLayout.rowsForScreenHeight(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialListLayout.rowsForViewportHeight(-1));
    }

    @Test
    void longCounts_driveTheirOwnColumnWidths() {
        String huge = Long.toString(Long.MAX_VALUE);
        MaterialListLayout shortLayout = MaterialListLayout.compute(2000, CODEPOINT_WIDTH,
                samples("1", "Item", "Claim"), "Claim", 0);
        MaterialListLayout hugeLayout = MaterialListLayout.compute(2000, CODEPOINT_WIDTH,
                samples(huge, "Item", "Claim"), "Claim", 0);

        int hugeTextWidth = CODEPOINT_WIDTH.applyAsInt(huge);
        for (MaterialListLayout.ColumnKey key : List.of(
                MaterialListLayout.ColumnKey.TOTAL,
                MaterialListLayout.ColumnKey.MISSING,
                MaterialListLayout.ColumnKey.MINE,
                MaterialListLayout.ColumnKey.OTHERS,
                MaterialListLayout.ColumnKey.STAGING,
                MaterialListLayout.ColumnKey.WAREHOUSE)) {
            assertEquals(hugeTextWidth, hugeLayout.column(key).width());
            assertTrue(hugeLayout.column(key).width()
                    > shortLayout.column(key).width());
        }
        assertFalse(hugeLayout.hasHorizontalOverflow());

        Map<MaterialListLayout.ColumnKey, String> mixed = new EnumMap<>(samples(
                "1", "Item", "Claim"));
        mixed.put(MaterialListLayout.ColumnKey.WAREHOUSE, huge);
        MaterialListLayout mixedLayout = MaterialListLayout.compute(2000, CODEPOINT_WIDTH,
                mixed, "Claim", 0);
        assertEquals(hugeTextWidth,
                mixedLayout.column(MaterialListLayout.ColumnKey.WAREHOUSE).width());
        assertEquals(1, mixedLayout.column(MaterialListLayout.ColumnKey.TOTAL).width());
    }

    @Test
    void extremelyNarrowWidths_reportOverflowWithoutNegativeGeometry() {
        MaterialListLayout layout = MaterialListLayout.compute(40, CODEPOINT_WIDTH,
                samples(Long.toString(Long.MAX_VALUE), "Very Long Material Name",
                        "Alice, Bob, Carol"),
                "Leave", 16);

        assertTrue(layout.hasHorizontalOverflow());
        assertEquals(Math.max(0L, layout.naturalWidth() - 40),
                layout.horizontalOverflowWidth());
        assertTrue(layout.horizontalOverflowWidth() > 0L);
        assertEquals(8, layout.columns().size());

        for (MaterialListLayout.Column column : layout.columns()) {
            assertTrue(column.x() >= 0);
            assertTrue(column.width() >= 0);
            assertTrue(column.right() >= column.x());
        }
        assertTrue(layout.action().x() >= 0);
        assertTrue(layout.action().width() >= 0);
        assertTrue(layout.sourceBar().width() >= 0);
        assertTrue(layout.sourceBar().width() <= layout.rowWidth());
        assertTrue(layout.sourceBar().right() <= layout.rowWidth());

        MaterialListLayout zero = MaterialListLayout.compute(0, CODEPOINT_WIDTH,
                samples("1", "Item", "Claim"), "Claim", 0);
        assertTrue(zero.hasHorizontalOverflow());
        assertEquals(0, zero.sourceBar().width());
        assertThrows(IllegalArgumentException.class,
                () -> MaterialListLayout.compute(-1, CODEPOINT_WIDTH,
                        samples("1", "Item", "Claim"), "Claim", 0));
    }

    @Test
    void overflowingTableKeepsTheActionRailVisibleAndOutOfColumnHitTests() {
        MaterialListLayout layout = MaterialListLayout.compute(160, CODEPOINT_WIDTH,
                samples(Long.toString(Long.MAX_VALUE), "Long Material Name", "Long Participant Name"),
                "Join Collaboration", 16);
        assertTrue(layout.hasHorizontalOverflow());
        assertTrue(layout.action().right() <= layout.rowWidth(),
                "horizontal data overflow must not hide the row action");
        assertTrue(layout.sourceBar().right() < layout.action().x());
    }

    @Test
    void drawingClippingAndHitTesting_shareTheSameColumnRecords() {
        MaterialListLayout layout = layoutForScreen(960);
        for (MaterialListLayout.Column column : layout.columns()) {
            assertEquals(column, layout.columnAt(column.x()).orElseThrow());
            assertEquals(column, layout.columnAt(column.right() - 1).orElseThrow());
            assertTrue(column.contains(column.x()));
            assertFalse(column.contains(column.right()));
            assertTrue(layout.columnAt(column.right()).isEmpty());

            String sample = column.key() == MaterialListLayout.ColumnKey.ITEM
                    ? "Oak Planks"
                    : column.key() == MaterialListLayout.ColumnKey.CLAIM
                            ? "Alice, Bob"
                            : "123456";
            MaterialListLayout.ClipResult clipped = column.clip(sample, CODEPOINT_WIDTH);
            assertTrue(clipped.width() <= column.contentWidth());
            int textX = column.textX(clipped.text(), CODEPOINT_WIDTH);
            assertTrue(column.contains(textX));
            assertEquals(column, layout.columnAt(textX).orElseThrow());
        }

        MaterialListLayout.ActionRegion action = layout.action();
        assertTrue(action.contains(action.x()));
        assertTrue(action.contains(action.right() - 1));
        assertFalse(action.contains(action.right()));
        assertTrue(action.x() > layout.column(MaterialListLayout.ColumnKey.CLAIM).right());
    }

    @Test
    void clipText_neverSplitsSurrogatePairsOrReturnsNegativeWidths() {
        String text = "A\uD83D\uDE00B";
        MaterialListLayout.ClipResult exact = MaterialListLayout.clipText(
                text, 3, CODEPOINT_WIDTH);
        assertEquals(text, exact.text());
        assertEquals(3, exact.width());

        MaterialListLayout.ClipResult clipped = MaterialListLayout.clipText(
                text, 2, CODEPOINT_WIDTH);
        assertEquals("A\u2026", clipped.text());
        assertEquals(2, clipped.width());
        assertWellFormedSurrogates(clipped.text());

        MaterialListLayout.ClipResult emojiFirst = MaterialListLayout.clipText(
                "\uD83D\uDE00BC", 2, CODEPOINT_WIDTH);
        assertEquals("\uD83D\uDE00\u2026", emojiFirst.text());
        assertEquals(2, emojiFirst.width());
        assertWellFormedSurrogates(emojiFirst.text());

        MaterialListLayout.ClipResult ellipsisOnly = MaterialListLayout.clipText(
                "abcdef", 1, String::length);
        assertEquals("\u2026", ellipsisOnly.text());
        assertEquals(1, ellipsisOnly.width());

        MaterialListLayout.ClipResult ascii = MaterialListLayout.clipText(
                "abcdef", 4, String::length);
        assertEquals("abc\u2026", ascii.text());
        assertEquals(4, ascii.width());

        assertEquals("", MaterialListLayout.clipText("abcdef", 0, String::length).text());
        assertEquals(0, MaterialListLayout.clipText("abcdef", 0, String::length).width());
        assertEquals("", MaterialListLayout.clipText("abcdef", -5, String::length).text());
        assertEquals(0, MaterialListLayout.clipText("abcdef", -5, String::length).width());

        String longLabel = "AB\uD83D\uDE00CD";
        MaterialListLayout.ClipResult middle = MaterialListLayout.clipText(
                longLabel, 3, CODEPOINT_WIDTH);
        assertEquals("AB\u2026", middle.text());
        assertWellFormedSurrogates(middle.text());
        assertTrue(middle.width() <= 3);
    }

    @Test
    void sourceSegments_tileTheBarAndPreserveCounts() {
        List<MaterialListLayout.SourceSegment> segments = MaterialListLayout.sourceSegments(
                100, 100, 100L, 20L, 30L, List.of(10L), 40L);

        assertEquals(4, segments.size());
        assertEquals(List.of(
                        MaterialListLayout.SourceKind.STAGING,
                        MaterialListLayout.SourceKind.WAREHOUSE,
                        MaterialListLayout.SourceKind.PARTICIPANT,
                        MaterialListLayout.SourceKind.MISSING),
                segments.stream().map(MaterialListLayout.SourceSegment::kind).toList());
        assertEquals(List.of(20L, 30L, 10L, 40L),
                segments.stream().map(MaterialListLayout.SourceSegment::count).toList());
        assertEquals(List.of(20, 30, 10, 40),
                segments.stream().map(MaterialListLayout.SourceSegment::width).toList());
        assertEquals(List.of(100, 120, 150, 160),
                segments.stream().map(MaterialListLayout.SourceSegment::x).toList());
        assertEquals(200, segments.get(3).right());
        assertEquals(0, segments.get(2).participantIndex());
        assertEquals(-1, segments.get(0).participantIndex());
    }

    @Test
    void sourceSegments_stayBoundedForLongMaxAndOverSupply() {
        long max = Long.MAX_VALUE;
        List<MaterialListLayout.SourceSegment> segments = MaterialListLayout.sourceSegments(
                7, 97, max, max, max, List.of(max, 0L), max);

        assertEquals(4, segments.size());
        assertSegmentBounds(segments, 7, 97);
        assertTrue(segments.stream().allMatch(segment -> segment.count() == max));
        assertEquals(0, segments.get(2).participantIndex());
        assertEquals(7 + 97, segments.get(3).right());

        List<MaterialListLayout.SourceSegment> overSupply =
                MaterialListLayout.sourceSegments(
                        0, 50, 10L, 1_000L, 500L, List.of(), 0L);
        assertEquals(2, overSupply.size());
        assertSegmentBounds(overSupply, 0, 50);
        assertEquals(50, overSupply.get(0).width() + overSupply.get(1).width());
        assertTrue(overSupply.get(0).width() > 0);
        assertTrue(overSupply.get(1).width() > 0);

        List<MaterialListLayout.SourceSegment> longMaxDemand =
                MaterialListLayout.sourceSegments(
                        5, 31, max, 1L, 2L, List.of(3L), max);
        assertSegmentBounds(longMaxDemand, 5, 31);
        assertEquals(max, longMaxDemand.get(3).count());
        assertEquals(5 + 31, longMaxDemand.get(3).right());
    }

    @Test
    void sourceSegments_useMinimumVisiblePixelsAndSkipZeroShares() {
        List<MaterialListLayout.SourceSegment> tiny = MaterialListLayout.sourceSegments(
                0, 10, 1_000L, 1L, 0L, List.of(), 999L);
        assertEquals(2, tiny.size());
        assertEquals(1, tiny.get(0).width());
        assertEquals(9, tiny.get(1).width());
        assertEquals(10, tiny.get(1).right());

        List<MaterialListLayout.SourceSegment> shares = MaterialListLayout.sourceSegments(
                0, 30, 100L, 0L, 0L, List.of(1L, 2L, 3L), 94L);
        assertEquals(4, shares.size());
        assertEquals(List.of(0, 1, 2),
                shares.subList(0, 3).stream()
                        .map(MaterialListLayout.SourceSegment::participantIndex)
                        .toList());
        for (MaterialListLayout.SourceSegment share : shares.subList(0, 3)) {
            assertEquals(1, share.width());
        }
        assertSegmentBounds(shares, 0, 30);
        assertEquals(30, shares.get(3).right());

        List<MaterialListLayout.SourceSegment> noMissing = MaterialListLayout.sourceSegments(
                0, 10, 3L, 1L, 0L, List.of(1L), 0L);
        assertEquals(2, noMissing.size());
        assertTrue(noMissing.get(1).right() <= 10);
        assertTrue(noMissing.stream().mapToInt(
                MaterialListLayout.SourceSegment::width).sum() < 10);
    }

    @Test
    void sourceSegments_handleEmptyAndInvalidInputs() {
        assertTrue(MaterialListLayout.sourceSegments(
                0, 0, 100L, 50L, 0L, List.of(10L), 40L).isEmpty());
        assertTrue(MaterialListLayout.sourceSegments(
                0, 100, 0L, 0L, 0L, List.of(), 0L).isEmpty());
        assertTrue(MaterialListLayout.sourceSegments(
                0, 100, 100L, 0L, 0L, List.of(0L, 0L), 0L).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> MaterialListLayout.sourceSegments(
                -1, 10, 1L, 0L, 0L, List.of(), 0L));
        assertThrows(IllegalArgumentException.class, () -> MaterialListLayout.sourceSegments(
                0, -1, 1L, 0L, 0L, List.of(), 0L));
        assertThrows(IllegalArgumentException.class, () -> MaterialListLayout.sourceSegments(
                0, 10, -1L, 0L, 0L, List.of(), 0L));
        assertThrows(IllegalArgumentException.class, () -> MaterialListLayout.sourceSegments(
                0, 10, 1L, 0L, 0L, List.of(-1L), 0L));
        assertThrows(IllegalArgumentException.class, () -> MaterialListLayout.sourceSegments(
                Integer.MAX_VALUE, 1, 1L, 0L, 0L, List.of(), 0L));
    }

    @Test
    void layoutSourceBar_staysInsideTheRowAndItsSegments() {
        MaterialListLayout layout = layoutForScreen(640);
        List<MaterialListLayout.SourceSegment> segments = layout.sourceSegments(
                100L, 20L, 10L, List.of(10L), 60L);

        assertFalse(segments.isEmpty());
        assertSegmentBounds(segments, layout.sourceBar().x(), layout.sourceBar().width());
        assertTrue(segments.get(segments.size() - 1).right()
                <= layout.sourceBar().right());
        assertTrue(layout.sourceBar().right() <= layout.rowWidth());
    }

    @Test
    void controlsWrapWithoutOverlappingOrShrinkingLabels() {
        MaterialListLayout.ControlLayout layout = MaterialListLayout.packControls(320,
                List.of(90, 120, 100, 70), false);
        assertEquals(2, layout.rows());
        assertEquals(List.of(12, 104, 12, 114), layout.positions().stream()
                .map(MaterialListLayout.ControlPosition::x).toList());
        assertEquals(List.of(0, 0, 22, 22), layout.positions().stream()
                .map(MaterialListLayout.ControlPosition::y).toList());
        var right = MaterialListLayout.packControls(320, List.of(90, 120, 100, 70), true);
        assertEquals(2, right.rows());
        assertEquals(List.of(218, 96, 208, 136), right.positions().stream()
                .map(MaterialListLayout.ControlPosition::x).toList());
        assertThrows(IllegalArgumentException.class,
                () -> MaterialListLayout.packControls(-1, List.of(10), false));
    }

    @Test
    void rowConstants_matchTheApprovedProductionLayout() {
        assertEquals(32, MaterialListLayout.ROW_HEIGHT);
        assertEquals(20, MaterialListLayout.HEADER_HEIGHT);
        assertEquals(36, MaterialListLayout.TOOLBAR_HEIGHT);
        assertEquals(4, MaterialListLayout.SOURCE_BAR_HEIGHT);
        assertEquals(MaterialListLayout.ROW_HEIGHT
                        - MaterialListLayout.SOURCE_BAR_BOTTOM_INSET
                        - MaterialListLayout.SOURCE_BAR_HEIGHT,
                MaterialListLayout.sourceBarY(0));
        assertEquals(MaterialListLayout.ROW_HEIGHT
                        - MaterialListLayout.SOURCE_BAR_BOTTOM_INSET - 1,
                MaterialListLayout.sourceBarY(0)
                        + MaterialListLayout.SOURCE_BAR_HEIGHT - 1);
        assertTrue(MaterialListLayout.sourceBarY(0)
                + MaterialListLayout.SOURCE_BAR_HEIGHT
                <= MaterialListLayout.ROW_HEIGHT);
    }

    private static MaterialListLayout layoutForScreen(int screenWidth) {
        return MaterialListLayout.compute(
                MaterialListLayout.rowWidthForScreenWidth(screenWidth),
                CODEPOINT_WIDTH,
                samples("123456", "Oak Planks", "Alice, Bob"),
                "Claim",
                16);
    }

    private static Map<MaterialListLayout.ColumnKey, String> samples(
            String count, String item, String claim) {
        EnumMap<MaterialListLayout.ColumnKey, String> samples =
                new EnumMap<>(MaterialListLayout.ColumnKey.class);
        samples.put(MaterialListLayout.ColumnKey.ITEM, item);
        for (MaterialListLayout.ColumnKey key : List.of(
                MaterialListLayout.ColumnKey.TOTAL,
                MaterialListLayout.ColumnKey.MISSING,
                MaterialListLayout.ColumnKey.MINE,
                MaterialListLayout.ColumnKey.OTHERS,
                MaterialListLayout.ColumnKey.STAGING,
                MaterialListLayout.ColumnKey.WAREHOUSE)) {
            samples.put(key, count);
        }
        samples.put(MaterialListLayout.ColumnKey.CLAIM, claim);
        return samples;
    }

    private static List<MaterialListLayout.ColumnKey> keys(MaterialListLayout layout) {
        List<MaterialListLayout.ColumnKey> keys = new ArrayList<>();
        for (MaterialListLayout.Column column : layout.columns()) {
            keys.add(column.key());
        }
        return keys;
    }

    private static void assertSegmentBounds(List<MaterialListLayout.SourceSegment> segments,
                                            int barX,
                                            int barWidth) {
        int cursor = barX;
        for (MaterialListLayout.SourceSegment segment : segments) {
            assertTrue(segment.width() >= 0);
            assertTrue(segment.count() >= 0L);
            assertEquals(cursor, segment.x());
            assertTrue(segment.right() >= segment.x());
            assertTrue(segment.right() <= barX + barWidth);
            cursor = segment.right();
        }
        assertTrue(cursor <= barX + barWidth);
    }

    private static void assertWellFormedSurrogates(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                assertTrue(index + 1 < text.length(), "unpaired high surrogate");
                assertTrue(Character.isLowSurrogate(text.charAt(index + 1)),
                        "unpaired high surrogate");
                index++;
            } else {
                assertFalse(Character.isLowSurrogate(value), "unpaired low surrogate");
            }
        }
    }
}
