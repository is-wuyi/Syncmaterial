package net.syncmaterial.syncmaterial.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Pure, deterministic layout for the production material list.
 *
 * <p>This class deliberately has no Minecraft, MaLiLib or Litematica dependency. The
 * production screen supplies the actual row width, the real font measurement function
 * and representative strings; drawing, clipping and hit testing then consume the same
 * {@link Column} records. Column coordinates are relative to the left edge of a row;
 * callers add the row/list origin once.</p>
 *
 * <p>The eight data columns are always present. If the measured content does not fit,
 * the layout keeps the measured widths and reports an explicit
 * {@link #horizontalOverflowWidth()}. It never shrinks fonts, drops quantity columns or
 * emits negative widths. Callers that cannot show the overflow should surface it
 * (for example by refusing to draw the right-most action area or by showing a
 * horizontal-overflow hint) rather than silently changing the data model.</p>
 *
 * <p>Vertical constants follow the approved production list: 32&nbsp;px rows,
 * 20&nbsp;px header and 36&nbsp;px toolbar. The source bar occupies the bottom of a
 * row. The convenience {@link #rowsForScreenHeight(int)} is calibrated for the approved
 * 12/10/7 row targets at 960x540, 720x450 and 640x360.</p>
 */
public final class MaterialListLayout {

    /** Standard material row height. */
    public static final int ROW_HEIGHT = 32;
    /** Column header height. */
    public static final int HEADER_HEIGHT = 20;
    /** Toolbar height above the list. */
    public static final int TOOLBAR_HEIGHT = 36;
    /** Height of the bounded source-distribution bar painted inside the row bottom. */
    public static final int SOURCE_BAR_HEIGHT = 4;
    /** Bottom inset of the source bar inside the row. */
    public static final int SOURCE_BAR_BOTTOM_INSET = 1;
    /** Docked team panel width. */
    public static final int TEAM_PANEL_WIDTH = 250;
    /** Gap between the material table and the docked team panel. */
    public static final int TEAM_PANEL_GAP = 8;
    /** Horizontal screen margin used by the production list. */
    public static final int SCREEN_SIDE_MARGIN = 12;
    /**
     * Minimum logical screen width for the docked 250&nbsp;px team panel. Narrower
     * screens use the team modal instead, which keeps the quantity columns intact.
     */
    public static final int WIDE_SIDEBAR_MIN_SCREEN_WIDTH = 900;
    /**
     * Chrome above and below the list viewport (title, toolbar, footer and spacing).
     * Used only by the screen-height convenience; callers with an already measured
     * viewport should use {@link #rowsForViewportHeight(int)}.
     */
    public static final int SCREEN_VERTICAL_CHROME = 108;

    private static final int ROW_LEFT_PADDING = 4;
    private static final int ROW_RIGHT_PADDING = 4;
    private static final int CHECKBOX_GAP = 4;
    private static final int ITEM_ICON_WIDTH = 20;
    private static final int COLUMN_GAP = 6;
    private static final int ACTION_GAP = 8;
    private static final int ACTION_PADDING = 8;
    private static final String ELLIPSIS = "\u2026";

    private static final List<ColumnKey> DATA_COLUMNS = List.of(
            ColumnKey.ITEM,
            ColumnKey.TOTAL,
            ColumnKey.MISSING,
            ColumnKey.MINE,
            ColumnKey.OTHERS,
            ColumnKey.STAGING,
            ColumnKey.WAREHOUSE,
            ColumnKey.CLAIM);

    private static final List<ColumnKey> NUMERIC_COLUMNS = List.of(
            ColumnKey.TOTAL,
            ColumnKey.MISSING,
            ColumnKey.MINE,
            ColumnKey.OTHERS,
            ColumnKey.STAGING,
            ColumnKey.WAREHOUSE);

    private final int rowWidth;
    private final long naturalWidth;
    private final long horizontalOverflowWidth;
    private final List<Column> columns;
    private final Map<ColumnKey, Column> columnsByKey;
    private final ActionRegion action;
    private final boolean hasCheckbox;
    private final int checkboxX;
    private final int checkboxWidth;
    private final SourceBarBounds sourceBar;

    private MaterialListLayout(int rowWidth,
                               long naturalWidth,
                               List<Column> columns,
                               ActionRegion action,
                               int checkboxWidth,
                               SourceBarBounds sourceBar) {
        this.rowWidth = rowWidth;
        this.naturalWidth = naturalWidth;
        this.horizontalOverflowWidth = Math.max(0L, naturalWidth - rowWidth);
        this.columns = List.copyOf(columns);
        EnumMap<ColumnKey, Column> byKey = new EnumMap<>(ColumnKey.class);
        for (Column column : columns) {
            byKey.put(column.key(), column);
        }
        this.columnsByKey = Map.copyOf(byKey);
        this.action = action;
        this.hasCheckbox = checkboxWidth > 0;
        this.checkboxX = this.hasCheckbox ? ROW_LEFT_PADDING : -1;
        this.checkboxWidth = checkboxWidth;
        this.sourceBar = sourceBar;
    }

    /** The logical row width supplied by the caller. */
    public int rowWidth() {
        return this.rowWidth;
    }

    /**
     * Width the measured content needs before any flexible item-column expansion.
     * When this is greater than {@link #rowWidth()} the layout is in horizontal
     * overflow and all columns retain their measured widths.
     */
    public long naturalWidth() {
        return this.naturalWidth;
    }

    /** Non-negative overflow in pixels; {@code 0} when the measured content fits. */
    public long horizontalOverflowWidth() {
        return this.horizontalOverflowWidth;
    }

    public boolean hasHorizontalOverflow() {
        return this.horizontalOverflowWidth > 0L;
    }

    /** All eight data columns in stable draw/sort order. */
    public List<Column> columns() {
        return this.columns;
    }

    /** Looks up a column by its stable enum key. */
    public Column column(ColumnKey key) {
        Objects.requireNonNull(key, "key");
        Column column = this.columnsByKey.get(key);
        if (column == null) {
            throw new IllegalArgumentException("unknown column: " + key);
        }
        return column;
    }

    /** Row-local hit test over the same records used for drawing and clipping. */
    public Optional<Column> columnAt(int localX) {
        for (Column column : this.columns) {
            if (column.contains(localX)) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /** Pinned row action region; only the eight data columns scroll horizontally. */
    public ActionRegion action() {
        return this.action;
    }

    public int dataViewportRight() {
        return Math.max(0, this.action.x() - ACTION_GAP);
    }

    public boolean hasCheckbox() {
        return this.hasCheckbox;
    }

    /** Left edge of the owner checkbox, or {@code -1} when no checkbox was reserved. */
    public int checkboxX() {
        return this.checkboxX;
    }

    /** Reserved checkbox width, or {@code 0} when no checkbox was reserved. */
    public int checkboxWidth() {
        return this.checkboxWidth;
    }

    /** Bounded row-local horizontal span of the row-bottom source bar. */
    public SourceBarBounds sourceBar() {
        return this.sourceBar;
    }

    /**
     * Computes source-bar segments using this layout's bounded source-bar span.
     * Segment x coordinates are row-local, like {@link #sourceBar()}.
     *
     * @see #sourceSegments(int, int, long, long, long, List, long)
     */
    public List<SourceSegment> sourceSegments(long required,
                                              long staging,
                                              long warehouse,
                                              List<Long> participantCounts,
                                              long missing) {
        return sourceSegments(this.sourceBar.x(),
                this.sourceBar.width(),
                required,
                staging,
                warehouse,
                participantCounts,
                missing);
    }

    public static MaterialListLayout compute(Input input) {
        Objects.requireNonNull(input, "input");

        ToIntFunction<String> textWidth = input.textWidth();
        Map<ColumnKey, String> samples = input.columnSamples();
        EnumMap<ColumnKey, Long> widths = new EnumMap<>(ColumnKey.class);

        long itemTextWidth = measure(textWidth, samples.get(ColumnKey.ITEM));
        widths.put(ColumnKey.ITEM, ITEM_ICON_WIDTH + itemTextWidth);
        for (ColumnKey key : NUMERIC_COLUMNS) {
            // Each numeric column keeps its own measured width, so a long count in
            // one column does not silently widen every other column.
            widths.put(key, (long) measure(textWidth, samples.get(key)));
        }
        widths.put(ColumnKey.CLAIM, (long) measure(textWidth, samples.get(ColumnKey.CLAIM)));

        long checkboxReserve = input.checkboxWidth() > 0
                ? (long) input.checkboxWidth() + CHECKBOX_GAP
                : 0L;
        long actionWidth = ACTION_PADDING + (long) measure(textWidth, input.actionLabel());

        long naturalWidth = ROW_LEFT_PADDING
                + checkboxReserve
                + widths.get(ColumnKey.ITEM);
        for (int i = 1; i < DATA_COLUMNS.size(); i++) {
            naturalWidth += COLUMN_GAP;
            naturalWidth += widths.get(DATA_COLUMNS.get(i));
        }
        naturalWidth += ACTION_GAP;
        naturalWidth += actionWidth;
        naturalWidth += ROW_RIGHT_PADDING;

        long itemWidth = widths.get(ColumnKey.ITEM);
        if (naturalWidth <= input.rowWidth()) {
            itemWidth += input.rowWidth() - naturalWidth;
        }

        List<Column> columns = new ArrayList<>(DATA_COLUMNS.size());
        long cursor = ROW_LEFT_PADDING + checkboxReserve;
        for (int i = 0; i < DATA_COLUMNS.size(); i++) {
            ColumnKey key = DATA_COLUMNS.get(i);
            int inset = key == ColumnKey.ITEM ? ITEM_ICON_WIDTH : 0;
            long width = key == ColumnKey.ITEM ? itemWidth : widths.get(key);
            cursor = addColumn(columns, key, alignmentOf(key), cursor, width, inset);
            if (i < DATA_COLUMNS.size() - 1) {
                cursor += COLUMN_GAP;
            }
        }

        long pinnedWidth = Math.min(actionWidth, Math.max(0L, (long) input.rowWidth() - ROW_RIGHT_PADDING));
        long actionX = Math.max(0L, (long) input.rowWidth() - ROW_RIGHT_PADDING - pinnedWidth);
        ActionRegion action = actionAt(actionX, pinnedWidth);
        SourceBarBounds sourceBar = sourceBarFor(Math.max(0, action.x() - ACTION_GAP), columns);
        return new MaterialListLayout(input.rowWidth(),
                naturalWidth,
                columns,
                action,
                input.checkboxWidth(),
                sourceBar);
    }

    public static MaterialListLayout compute(int rowWidth,
                                             ToIntFunction<String> textWidth,
                                             Map<ColumnKey, String> columnSamples,
                                             String actionLabel,
                                             int checkboxWidth) {
        return compute(new Input(rowWidth, textWidth, columnSamples, actionLabel, checkboxWidth));
    }

    /**
     * Returns the docked team-panel mode for a logical screen width. The threshold is
     * intentionally explicit: at 900 logical pixels and above the 250&nbsp;px panel
     * is docked; below that the team panel is a modal over the full-width table.
     */
    public static Mode modeForScreenWidth(int screenWidth) {
        if (screenWidth < 0) {
            throw new IllegalArgumentException("screenWidth must be >= 0");
        }
        return screenWidth >= WIDE_SIDEBAR_MIN_SCREEN_WIDTH
                ? Mode.WIDE_SIDEBAR
                : Mode.NARROW_MODAL;
    }

    /**
     * Row width left for the material table after screen margins and, in wide mode,
     * the docked 250&nbsp;px team panel. Never negative.
     */
    public static int rowWidthForScreenWidth(int screenWidth) {
        Mode mode = modeForScreenWidth(screenWidth);
        long width = (long) screenWidth - 2L * SCREEN_SIDE_MARGIN;
        if (mode == Mode.WIDE_SIDEBAR) {
            width -= TEAM_PANEL_WIDTH + TEAM_PANEL_GAP;
        }
        if (width <= 0L) {
            return 0;
        }
        return (int) Math.min(width, Integer.MAX_VALUE);
    }

    /**
     * Visible material rows for an already measured list viewport. The header row and
     * 32&nbsp;px rows are both inside the viewport.
     */
    public static int rowsForViewportHeight(int viewportHeight) {
        if (viewportHeight < 0) {
            throw new IllegalArgumentException("viewportHeight must be >= 0");
        }
        return Math.max(0, (viewportHeight - HEADER_HEIGHT) / ROW_HEIGHT);
    }

    /**
     * Visible material rows for the whole screen when the standard chrome is present.
     * The production target is 12/10/7 rows at 960x540, 720x450 and 640x360.
     */
    public static int rowsForScreenHeight(int screenHeight) {
        if (screenHeight < 0) {
            throw new IllegalArgumentException("screenHeight must be >= 0");
        }
        return rowsForViewportHeight(Math.max(0, screenHeight - SCREEN_VERTICAL_CHROME));
    }

    /** Wraps native controls without shrinking their measured labels. */
    public static ControlLayout packControls(int screenWidth, List<Integer> widths, boolean rightAligned) {
        if (screenWidth < 0) throw new IllegalArgumentException("screenWidth must be >= 0");
        int left = SCREEN_SIDE_MARGIN;
        int right = Math.max(left, screenWidth - SCREEN_SIDE_MARGIN);
        int cursor = rightAligned ? right : left;
        int row = 0;
        List<ControlPosition> positions = new ArrayList<>(widths.size());
        for (int width : widths) {
            if (width < 0) throw new IllegalArgumentException("control width must be >= 0");
            boolean occupied = cursor != (rightAligned ? right : left);
            if (occupied && (rightAligned ? cursor - width < left : cursor + width > right)) {
                row++;
                cursor = rightAligned ? right : left;
            }
            int x = rightAligned ? Math.max(left, cursor - width) : cursor;
            positions.add(new ControlPosition(x, row * 22));
            cursor = rightAligned ? x - 2 : x + width + 2;
        }
        return new ControlLayout(positions, widths.isEmpty() ? 0 : row + 1);
    }

    public record ControlPosition(int x, int y) {}

    public record ControlLayout(List<ControlPosition> positions, int rows) {
        public ControlLayout {
            positions = List.copyOf(positions);
        }
    }

    /** Row-local y of the source bar for a row starting at {@code rowY}. */
    public static int sourceBarY(int rowY) {
        return rowY + ROW_HEIGHT - SOURCE_BAR_BOTTOM_INSET - SOURCE_BAR_HEIGHT;
    }

    /**
     * Clips {@code text} to {@code maxWidth} using the real font measurement function.
     * Code points are copied whole, so valid UTF-16 surrogate pairs are never split.
     * A non-positive or too-small width yields an empty string, never a negative width.
     */
    public static ClipResult clipText(String text,
                                      int maxWidth,
                                      ToIntFunction<String> textWidth) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(textWidth, "textWidth");
        if (maxWidth <= 0 || text.isEmpty()) {
            return new ClipResult("", 0);
        }

        int fullWidth = measure(textWidth, text);
        if (fullWidth <= maxWidth) {
            return new ClipResult(text, fullWidth);
        }

        int ellipsisWidth = measure(textWidth, ELLIPSIS);
        if (ellipsisWidth > maxWidth) {
            return new ClipResult("", 0);
        }

        int budget = maxWidth - ellipsisWidth;
        StringBuilder prefix = new StringBuilder();
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            String candidate = text.substring(0, next);
            int candidateWidth = measure(textWidth, candidate);
            if (candidateWidth > budget) {
                break;
            }
            prefix.appendCodePoint(codePoint);
            width = candidateWidth;
            index = next;
        }

        String clipped = prefix + ELLIPSIS;
        int clippedWidth = measure(textWidth, clipped);
        // Weird, non-monotonic font implementations must still not exceed the bound.
        while (clippedWidth > maxWidth && prefix.length() > 0) {
            int last = prefix.codePointBefore(prefix.length());
            prefix.delete(prefix.length() - Character.charCount(last), prefix.length());
            clipped = prefix + ELLIPSIS;
            clippedWidth = measure(textWidth, clipped);
        }
        if (clippedWidth > maxWidth) {
            return new ClipResult("", 0);
        }
        return new ClipResult(clipped, Math.max(0, clippedWidth));
    }

    /**
     * Computes deterministic source-bar segments for staging, warehouse, any number of
     * participant shares and the remaining missing amount.
     *
     * <p>Counts remain {@code long}; they are converted to pixels only through an exact
     * integer ratio (floor), then capped so the sum of widths never exceeds
     * {@code barWidth}. Positive categories receive a one-pixel minimum when the bar
     * has at least one pixel per category; otherwise they receive zero. The missing
     * segment, when present, always consumes the remaining pixels, so a bar with
     * missing demand ends exactly at its right edge.</p>
     */
    public static List<SourceSegment> sourceSegments(int barX,
                                                     int barWidth,
                                                     long required,
                                                     long staging,
                                                     long warehouse,
                                                     List<Long> participantCounts,
                                                     long missing) {
        if (barX < 0) {
            throw new IllegalArgumentException("barX must be >= 0");
        }
        if (barWidth < 0) {
            throw new IllegalArgumentException("barWidth must be >= 0");
        }
        if (barWidth > Integer.MAX_VALUE - barX) {
            throw new IllegalArgumentException("bar span exceeds integer range");
        }
        requireNonNegative(required, "required");
        requireNonNegative(staging, "staging");
        requireNonNegative(warehouse, "warehouse");
        requireNonNegative(missing, "missing");
        Objects.requireNonNull(participantCounts, "participantCounts");
        for (Long count : participantCounts) {
            if (count == null || count < 0L) {
                throw new IllegalArgumentException("participant counts must be >= 0");
            }
        }
        if (barWidth == 0 || required == 0L) {
            return List.of();
        }

        List<PendingSegment> pending = new ArrayList<>();
        if (staging > 0L) {
            pending.add(new PendingSegment(SourceKind.STAGING, staging, -1));
        }
        if (warehouse > 0L) {
            pending.add(new PendingSegment(SourceKind.WAREHOUSE, warehouse, -1));
        }
        for (int index = 0; index < participantCounts.size(); index++) {
            long count = participantCounts.get(index);
            if (count > 0L) {
                pending.add(new PendingSegment(SourceKind.PARTICIPANT, count, index));
            }
        }
        boolean hasMissing = missing > 0L;
        int categoryCount = pending.size() + (hasMissing ? 1 : 0);
        if (categoryCount == 0) {
            return List.of();
        }

        int minimum = barWidth >= categoryCount ? 1 : 0;
        int missingReserve = hasMissing ? minimum : 0;
        int remaining = barWidth;
        int cursor = barX;
        List<SourceSegment> segments = new ArrayList<>(categoryCount);

        for (int index = 0; index < pending.size(); index++) {
            PendingSegment segment = pending.get(index);
            int laterNonMissing = pending.size() - 1 - index;
            long reserve = (long) minimum * laterNonMissing + missingReserve;
            int maxForThis = (int) Math.max(0L, (long) remaining - reserve);
            int exact = exactPixels(segment.count(), required, barWidth);
            int width = Math.min(maxForThis, Math.max(minimum, exact));
            width = Math.max(0, width);

            segments.add(new SourceSegment(segment.kind(),
                    segment.count(),
                    cursor,
                    width,
                    segment.participantIndex()));
            cursor += width;
            remaining -= width;
        }

        if (hasMissing) {
            segments.add(new SourceSegment(SourceKind.MISSING, missing, cursor, remaining, -1));
        }
        return List.copyOf(segments);
    }

    private static int exactPixels(long count, long required, int barWidth) {
        if (count <= 0L || required <= 0L || barWidth <= 0) {
            return 0;
        }
        BigInteger pixels = BigInteger.valueOf(count)
                .multiply(BigInteger.valueOf(barWidth))
                .divide(BigInteger.valueOf(required));
        return pixels.min(BigInteger.valueOf(barWidth)).intValue();
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
    }

    private static long addColumn(List<Column> columns,
                                  ColumnKey key,
                                  Alignment alignment,
                                  long x,
                                  long width,
                                  int textInset) {
        int columnX = clampToInt(x);
        int columnWidth = clampWidth(width, columnX);
        int inset = Math.min(Math.max(0, textInset), columnWidth);
        columns.add(new Column(key, alignment, columnX, columnWidth, inset));
        return x + width;
    }

    private static Alignment alignmentOf(ColumnKey key) {
        return key == ColumnKey.ITEM || key == ColumnKey.CLAIM
                ? Alignment.LEFT
                : Alignment.RIGHT;
    }

    private static ActionRegion actionAt(long x, long width) {
        int actionX = clampToInt(x);
        return new ActionRegion(actionX, clampWidth(width, actionX));
    }

    private static SourceBarBounds sourceBarFor(int rowWidth, List<Column> columns) {
        Column item = columns.get(0);
        Column claim = columns.get(DATA_COLUMNS.size() - 1);
        int safeRight = clampToInt(Math.max(0L, (long) rowWidth - ROW_RIGHT_PADDING));
        int start = Math.min(item.x(), safeRight);
        int end = Math.min(claim.right(), safeRight);
        return new SourceBarBounds(start, Math.max(0, end - start));
    }

    private static int measure(ToIntFunction<String> textWidth, String text) {
        return Math.max(0, textWidth.applyAsInt(text));
    }

    private static int clampToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static int clampWidth(long width, int x) {
        if (width <= 0L) {
            return 0;
        }
        long max = (long) Integer.MAX_VALUE - x;
        return (int) Math.max(0L, Math.min(width, max));
    }

    private record PendingSegment(SourceKind kind, long count, int participantIndex) {
    }

    /** Layout mode chosen by {@link #modeForScreenWidth(int)}. */
    public enum Mode {
        /** Docked 250px team panel on the right. */
        WIDE_SIDEBAR,
        /** Team panel is opened as a modal over the full-width table. */
        NARROW_MODAL
    }

    /** Stable keys for the eight data columns. */
    public enum ColumnKey {
        ITEM,
        TOTAL,
        MISSING,
        MINE,
        OTHERS,
        STAGING,
        WAREHOUSE,
        CLAIM
    }

    /** Horizontal alignment used by drawing and clipping. */
    public enum Alignment {
        LEFT,
        RIGHT
    }

    /** Source-bar segment kinds in stable draw order. */
    public enum SourceKind {
        STAGING,
        WAREHOUSE,
        PARTICIPANT,
        MISSING
    }

    /**
     * One data column. {@code width} is the exact draw/clip/hit width; {@code textInset}
     * reserves a leading icon (only the item column uses it).
     */
    public record Column(ColumnKey key,
                         Alignment alignment,
                         int x,
                         int width,
                         int textInset) {
        public Column {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(alignment, "alignment");
            if (x < 0) {
                throw new IllegalArgumentException("x must be >= 0");
            }
            if (width < 0 || width > Integer.MAX_VALUE - x) {
                throw new IllegalArgumentException("width is out of range");
            }
            if (textInset < 0 || textInset > width) {
                throw new IllegalArgumentException("textInset must be in [0, width]");
            }
        }

        public int right() {
            return this.x + this.width;
        }

        public int contentX() {
            return this.x + this.textInset;
        }

        public int contentWidth() {
            return this.width - this.textInset;
        }

        public boolean contains(int localX) {
            return localX >= this.x && localX < right();
        }

        /** Draw x for already-clipped text, clamped to the column content bounds. */
        public int textX(String text, ToIntFunction<String> textWidth) {
            int measured = Math.min(contentWidth(), measure(textWidth, text));
            return this.alignment == Alignment.RIGHT
                    ? contentX() + contentWidth() - measured
                    : contentX();
        }

        /** Clips text to this column's content width. */
        public ClipResult clip(String text, ToIntFunction<String> textWidth) {
            return clipText(text, contentWidth(), textWidth);
        }
    }

    /** The pinned row action button region outside horizontally scrolled data. */
    public record ActionRegion(int x, int width) {
        public ActionRegion {
            if (x < 0) {
                throw new IllegalArgumentException("x must be >= 0");
            }
            if (width < 0 || width > Integer.MAX_VALUE - x) {
                throw new IllegalArgumentException("width is out of range");
            }
        }

        public int right() {
            return this.x + this.width;
        }

        public boolean contains(int localX) {
            return localX >= this.x && localX < right();
        }
    }

    /** Bounded span of the row-bottom source bar. */
    public record SourceBarBounds(int x, int width) {
        public SourceBarBounds {
            if (x < 0) {
                throw new IllegalArgumentException("x must be >= 0");
            }
            if (width < 0 || width > Integer.MAX_VALUE - x) {
                throw new IllegalArgumentException("width is out of range");
            }
        }

        public int right() {
            return this.x + this.width;
        }
    }

    /**
     * One painted source-bar segment. {@code count} stays {@code long}; widths are
     * pixels only. Participant segments carry the caller's participant index.
     */
    public record SourceSegment(SourceKind kind,
                                long count,
                                int x,
                                int width,
                                int participantIndex) {
        public SourceSegment {
            Objects.requireNonNull(kind, "kind");
            if (count < 0L) {
                throw new IllegalArgumentException("count must be >= 0");
            }
            if (x < 0) {
                throw new IllegalArgumentException("x must be >= 0");
            }
            if (width < 0 || width > Integer.MAX_VALUE - x) {
                throw new IllegalArgumentException("width is out of range");
            }
            if (kind == SourceKind.PARTICIPANT) {
                if (participantIndex < 0) {
                    throw new IllegalArgumentException("participant index is required");
                }
            } else if (participantIndex != -1) {
                throw new IllegalArgumentException("non-participant index must be -1");
            }
        }

        public int right() {
            return this.x + this.width;
        }

        public boolean contains(int screenX) {
            return screenX >= this.x && screenX < right();
        }
    }

    /** Result of {@link #clipText(String, int, ToIntFunction)}. */
    public record ClipResult(String text, int width) {
        public ClipResult {
            Objects.requireNonNull(text, "text");
            if (width < 0) {
                throw new IllegalArgumentException("width must be >= 0");
            }
        }
    }

    /**
     * Inputs for {@link #compute(Input)}. All eight column sample keys are required.
     * Numeric samples should be the widest strings that can actually be displayed in
     * that column, for example {@code Long.toString(maxObservedCount)}. Item and claim
     * samples are representative labels and should include a wider column header when
     * applicable; per-row names are clipped with {@link Column#clip}. Samples are
     * measured with the real font function, never with a static cross-GUI width.
     */
    public record Input(int rowWidth,
                        ToIntFunction<String> textWidth,
                        Map<ColumnKey, String> columnSamples,
                        String actionLabel,
                        int checkboxWidth) {
        public Input {
            if (rowWidth < 0) {
                throw new IllegalArgumentException("rowWidth must be >= 0");
            }
            Objects.requireNonNull(textWidth, "textWidth");
            Objects.requireNonNull(columnSamples, "columnSamples");
            Objects.requireNonNull(actionLabel, "actionLabel");
            if (checkboxWidth < 0) {
                throw new IllegalArgumentException("checkboxWidth must be >= 0");
            }
            if (actionLabel.isEmpty()) {
                throw new IllegalArgumentException("actionLabel must not be empty");
            }

            EnumMap<ColumnKey, String> copy = new EnumMap<>(ColumnKey.class);
            for (Map.Entry<ColumnKey, String> entry : columnSamples.entrySet()) {
                ColumnKey key = Objects.requireNonNull(entry.getKey(), "column key");
                String sample = Objects.requireNonNull(entry.getValue(), "sample for " + key);
                if (sample.isEmpty()) {
                    throw new IllegalArgumentException("sample for " + key + " must not be empty");
                }
                copy.put(key, sample);
            }
            for (ColumnKey key : DATA_COLUMNS) {
                if (!copy.containsKey(key)) {
                    throw new IllegalArgumentException("missing sample for " + key);
                }
            }
            columnSamples = Map.copyOf(copy);
        }
    }
}
