package net.syncmaterial.syncmaterial.gametest.client;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListBase;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.MaterialListLayout;
import net.syncmaterial.syncmaterial.client.gui.MaterialRowStatus;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListMaterialList;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetMaterialListEntry;
import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.network.AreaOverlapStatus;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

/**
 * Real dedicated-client GameTest for the production material list on all supported nodes.
 *
 * <p>This test deliberately does not use the earlier fixed diagnostic screen. It opens
 * the production {@link GuiMaterialList}, feeds synthetic
 * {@link ProjectProgressSnapshot} data through the same
 * {@link SyncMaterialList#applyProjectProgress(ProjectProgressSnapshot)} entry point used
 * by the client project-view session, and then drives the real MaLiLib widgets with real
 * mouse and keyboard input.</p>
 *
 * <p>Every screenshot is a real frame of the production GUI at the requested logical
 * size (physical window multiplied by the given GUI scale). The test records row and
 * column geometry, all input results, and every discovered production defect; it fails at
 * the end with the complete defect list instead of stopping before later evidence is
 * collected.</p>
 */
public final class ProductionUiClientGameTest implements FabricClientGameTest {
    private static final Path SCREENSHOT_DIR = Path.of("screenshots", "production-ui");
    private static final String PROJECT_ID = "production-ui-fixture";
    private static final String PROJECT_NAME = "主仓库扩建 · 当前版本界面";
    private static final String OTHER_NAME = "云杉";
    private static final UUID OTHER_UUID = UUID.nameUUIDFromBytes(
            "syncmaterial-production-ui-other".getBytes(StandardCharsets.UTF_8));

    private static final Item[] ITEMS = {
            Items.CHEST, Items.STONE, Items.DIRT, Items.GLASS,
            Items.IRON_INGOT, Items.OAK_LOG, Items.TORCH, Items.COBBLESTONE,
            Items.SLIME_BALL, Items.REPEATER, Items.SAND,
            //? if >=26.2 {
            Items.WOOL.white()
            //?} else {
            /*Items.WHITE_WOOL
            *///?}
    };
    private static final String[] ITEM_IDS = {
            "minecraft:chest", "minecraft:stone", "minecraft:dirt", "minecraft:glass",
            "minecraft:iron_ingot", "minecraft:oak_log", "minecraft:torch", "minecraft:cobblestone",
            "minecraft:slime_ball", "minecraft:repeater", "minecraft:sand", "minecraft:white_wool"
    };
    private static final long[] REQUIRED = {64, 1024, 256, 128, 384, 0, 96, 512, 32, 24, 64, 48};
    private static final long[] STAGING = {16, 256, 0, 128, 0, 0, 32, 0, 32, 0, 16, 0};
    private static final long[] WAREHOUSE = {0, 512, 0, 0, 0, 0, 0, 384, 0, 0, 48, 0};
    private static final long[] SELF = {0, 64, 0, 0, 128, 0, 16, 0, 0, 0, 0, 0};
    private static final long[] OTHER = {0, 64, 256, 0, 64, 0, 8, 64, 16, 0, 0, 0};
    private static final int REPEATER_INDEX = 9;

    private static final int CHEST_MATERIAL_ID = 1;
    private static final int STONE_MATERIAL_ID = 2;
    private static final int IRON_MATERIAL_ID = 5;

    private final List<String> defects = new ArrayList<>();
    private int screenshotsCaptured;

    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            Files.createDirectories(SCREENSHOT_DIR);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法创建截图目录 " + SCREENSHOT_DIR, failure);
        }

        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            java.util.Objects.requireNonNull(connection, "client connection");
            context.waitTicks(30);
            selectChineseAndReload(context);
            String actor = context.computeOnClient(client -> client.player.getName().getString());
            UUID actorId = context.computeOnClient(client -> client.player.getUUID());
            SyncMaterial.LOGGER.info("UI_PRODUCTION_TEST START project={} actor={} screenshots={}",
                    PROJECT_ID, actor, SCREENSHOT_DIR);

            ProjectProgressSnapshot normal = snapshot(actorId, actor, 3, true);
            ProjectProgressSnapshot warning = snapshot(actorId, actor, 4, false);

            // 1) Owner wide: 960x540 logical, 12/12 rows visible.
            open(context, actor, true, true, 960, 540, 2, normal);
            verifyRows(context, "owner-wide", 960, 540);
            verifyColumns(context, "owner-wide", 960, 540);
            verifyWideManagementPanel(context);
            capture(context, "production-owner-wide-960x540", 960, 540, 2);
            verifySelectionAndClear(context, "production-owner-wide-selected-960x540", 960, 540, 2);

            // 2) Owner medium: 720x450 logical, 10/10 rows visible, team modal + inputs.
            open(context, actor, true, true, 720, 450, 2, normal);
            verifyRows(context, "owner-medium", 720, 450);
            verifyColumns(context, "owner-medium", 720, 450);
            capture(context, "production-owner-medium-720x450", 720, 450, 2);
            verifyInputs(context);

            // 3) Member medium: same table without the owner checkbox/management column.
            open(context, actor, false, false, 720, 450, 2, normal);
            verifyRows(context, "member-medium", 720, 450);
            verifyColumns(context, "member-medium", 720, 450);
            verifyMemberView(context);
            capture(context, "production-member-medium-720x450", 720, 450, 2);

            // 4) Owner at large GUI scale: 640x360 logical, 7 rows visible.
            open(context, actor, true, true, 640, 360, 3, normal);
            verifyRows(context, "owner-large-scale", 640, 360);
            verifyColumns(context, "owner-large-scale", 640, 360);
            capture(context, "production-owner-large-scale-640x360", 640, 360, 3);
            verifyCompactScroll(context, "owner-large-scale");
            verifyViewportPreservation(context);

            // 5) Unknown/stale area plus overlap at 960x540 logical.
            open(context, actor, true, true, 960, 540, 2, warning);
            verifyRows(context, "warning", 960, 540);
            verifyColumns(context, "warning", 960, 540);
            verifyWarningState(context);
            capture(context, "production-warning-960x540", 960, 540, 2);
            verifyLongQuantitiesAndHorizontalOverflow(context, actor, actorId);
            verifyLanguages(context, actor, normal);
        } finally {
            context.runOnClient(client -> {
                ClientScreenAccess.showScreen(client, null);
                InventoryWatcher.clearContext();
                PickupModeState.clear();
            });
        }

        if (!this.defects.isEmpty()) {
            SyncMaterial.LOGGER.error("UI_PRODUCTION_TEST FAIL defects={}", this.defects);
            throw new AssertionError("生产 UI 缺陷: " + String.join(" | ", this.defects));
        }
        SyncMaterial.LOGGER.info(
                "UI_PRODUCTION_TEST PASS rows=12/10/7 screenshots={} languages=8 inputs=filter/selection-outside-filter/catalog-delete-under-dialog/header-sort/toggles/selection/team-outside/team-close/escape/scroll/modal-keyboard/warning-viewport/long-horizontal",
                this.screenshotsCaptured);
    }

    // =================================================================================
    // Fixtures / screen setup
    // =================================================================================

    private static ProjectProgressSnapshot snapshot(UUID actorId, String actor, long sequence,
                                                    boolean known) {
        List<ProjectProgressMaterial> materials = new ArrayList<>(ITEMS.length);
        for (int index = 0; index < ITEMS.length; index++) {
            materials.add(new ProjectProgressMaterial(index + 1, ITEM_IDS[index],
                    REQUIRED[index], STAGING[index], WAREHOUSE[index],
                    participants(index, actorId, actor)));
        }
        if (known) {
            return new ProjectProgressSnapshot(PROJECT_ID, PROJECT_NAME, 7, 11, sequence,
                    true, true, false, List.of(), materials);
        }
        return new ProjectProgressSnapshot(PROJECT_ID, PROJECT_NAME, 7, 12, sequence,
                false, false, true,
                List.of(
                        new AreaInventoryWarning(AreaKind.STAGING_AREA, 1, "卸货平台",
                                AreaInventoryWarning.STATUS_STALE),
                        new AreaInventoryWarning(AreaKind.WAREHOUSE, 2, "北仓",
                                AreaInventoryWarning.STATUS_UNKNOWN)),
                materials);
    }

    private static List<ProgressParticipant> participants(int index, UUID actorId, String actor) {
        List<ProgressParticipant> participants = new ArrayList<>(2);
        if (SELF[index] > 0 || index == REPEATER_INDEX) {
            participants.add(new ProgressParticipant(new PlayerIdentity(actorId, actor), SELF[index]));
        }
        if (OTHER[index] > 0) {
            participants.add(new ProgressParticipant(new PlayerIdentity(OTHER_UUID, OTHER_NAME), OTHER[index]));
        }
        return participants;
    }

    private static void selectChineseAndReload(ClientGameTestContext context) {
        selectLanguageAndReload(context, "zh_cn");
    }

    private static void selectLanguageAndReload(ClientGameTestContext context, String language) {
        var reload = context.computeOnClient(client -> {
            client.options.languageCode = language;
            client.getLanguageManager().setSelected(language);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> {
            //? if >=26.2 {
            return reload.isDone() && client.gui.overlay() == null;
            //?} else {
            /*return reload.isDone() && client.getOverlay() == null;
            *///?}
        }, 400);
        reload.join();
        context.runOnClient(ProductionUiClientGameTest::clearToasts);
        context.waitTicks(10);
    }

    private static GuiMaterialList open(ClientGameTestContext context, String actor, boolean owner,
                                        boolean mainOwner, int logicalWidth, int logicalHeight,
                                        int scale, ProjectProgressSnapshot snapshot) {
        resize(context, logicalWidth, logicalHeight, scale);
        context.runOnClient(client -> ClientScreenAccess.showScreen(client, null));
        context.waitTicks(1);
        context.runOnClient(client -> {
            GuiMaterialList gui = new GuiMaterialList(PROJECT_ID, PROJECT_NAME, List.of(),
                    owner, mainOwner, actor, List.of("云杉", "远山"), true);
            ClientScreenAccess.showScreen(client, gui);
        });
        context.waitForScreen(GuiMaterialList.class);
        context.waitTicks(5);

        boolean applied = context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            return gui.getMaterialList().applyProjectProgress(snapshot);
        });
        if (!applied) {
            throw new AssertionError("applyProjectProgress 拒绝了合成快照 seq=" + snapshot.publicationSequence());
        }
        context.runOnClient(client -> {
            currentGuiUnchecked(client).onMaterialCatalogUpdated();
            clearToasts(client);
        });
        context.waitTicks(8);
        context.runOnClient(ProductionUiClientGameTest::clearToasts);
        context.waitTicks(2);

        GuiMaterialList gui = currentGui(context);
        SyncMaterial.LOGGER.info(
                "UI_PRODUCTION snapshot seq={} known={} screen={}x{} scale={} materials={} title={}",
                snapshot.publicationSequence(), snapshot.preparationKnown(),
                gui.width, gui.height, scale, gui.getMaterialList().getMaterialsAll().size(),
                gui.getMaterialList().getTitle());
        return gui;
    }

    private static void clearToasts(net.minecraft.client.Minecraft client) {
        //? if >=26.2 {
        client.gui.toastManager().clear();
        //?} else {
        /*client.getToastManager().clear();
        *///?}
    }

    private static void resize(ClientGameTestContext context, int logicalWidth, int logicalHeight,
                               int scale) {
        context.getInput().resizeWindow(logicalWidth * scale, logicalHeight * scale);
        context.runOnClient(client -> {
            client.options.guiScale().set(scale);
            //? if >=26.2 {
            client.resizeGui();
            //?} else {
            /*client.resizeDisplay();
            *///?}
        });
        context.waitTicks(5);
        context.waitFor(client -> client.getWindow().getGuiScaledWidth() == logicalWidth
                && client.getWindow().getGuiScaledHeight() == logicalHeight, 200);
        context.waitTicks(3);
    }

    private static GuiMaterialList currentGui(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) {
                throw new AssertionError("生产材料清单未打开，当前界面="
                        + ClientScreenAccess.currentScreen(client));
            }
            return gui;
        });
    }

    private static GuiMaterialList currentGuiUnchecked(net.minecraft.client.Minecraft client) {
        return ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui ? gui : null;
    }

    // =================================================================================
    // Row / column measurements
    // =================================================================================

    private void verifyRows(ClientGameTestContext context, String view, int logicalWidth,
                            int logicalHeight) {
        verifyRows(context, view, logicalWidth, logicalHeight,
                MaterialListLayout.rowsForScreenHeight(logicalHeight));
    }

    private void verifyRows(ClientGameTestContext context, String view, int logicalWidth,
                            int logicalHeight, int target) {
        ViewMetrics metrics = metrics(context);
        int expected = Math.min(ITEMS.length, target);
        long visible = metrics.rows.stream().filter(row -> row.id > 0).count();
        long headers = metrics.rows.stream().filter(row -> row.id < 0).count();
        int expectedScrollMax = Math.max(0, ITEMS.length - expected);

        StringBuilder message = new StringBuilder();
        message.append("view=").append(view)
                .append(" logical=").append(logicalWidth).append('x').append(logicalHeight)
                .append(" target=").append(target)
                .append(" expected=").append(expected)
                .append(" visible=").append(visible)
                .append(" capacity=").append(metrics.capacity)
                .append(" header=").append(headers)
                .append(" scroll=").append(metrics.scrollValue).append('/').append(metrics.scrollMax)
                .append(" search=").append(metrics.searchPresent ? "present" : "absent");
        SyncMaterial.LOGGER.info("UI_ROWS {}", message);

        if (visible != expected) {
            defect("row_count_mismatch[" + view + "]",
                    "expected=" + expected + " visible=" + visible + " metrics=" + message);
        }
        if (metrics.capacity != expected) {
            defect("row_capacity_mismatch[" + view + "]",
                    "expected=" + expected + " capacity=" + metrics.capacity + " metrics=" + message);
        }
        if (headers != 1) {
            defect("row_header_count[" + view + "]", "expected=1 header widgets=" + headers);
        }
        if (metrics.scrollMax != expectedScrollMax) {
            defect("row_scroll_max[" + view + "]",
                    "expected=" + expectedScrollMax + " actual=" + metrics.scrollMax);
        }
        if (metrics.scrollValue != 0) {
            defect("row_initial_scroll[" + view + "]", "expected=0 actual=" + metrics.scrollValue);
        }
    }

    private void verifyColumns(ClientGameTestContext context, String view, int logicalWidth,
                               int logicalHeight) {
        ViewMetrics metrics = metrics(context);
        boolean overflow = false;
        boolean outsideRow = false;
        boolean offscreen = false;
        long worstNatural = 0;
        int worstButtonRight = Integer.MIN_VALUE;
        int worstRowRight = Integer.MIN_VALUE;

        for (RowMetrics row : metrics.rows) {
            String tag = row.id > 0 ? ("row" + row.id) : "header";
            if (row.naturalWidth > row.width && metrics.horizontalOverflow <= 0) {
                overflow = true;
                defect("column_overflow[" + view + "/" + tag + "]",
                        "natural=" + row.naturalWidth + " rowWidth=" + row.width
                                + " but no horizontal-scroll capacity");
            }
            if (row.claimRight > row.width && metrics.horizontalOverflow <= 0) {
                defect("claim_column_overflow[" + view + "/" + tag + "]",
                        "claimRight=" + row.claimRight + " rowWidth=" + row.width);
            }
            if (row.actionRight > row.width) {
                defect("action_region_overflow[" + view + "/" + tag + "]",
                        "action=[" + row.actionX + ',' + row.actionRight + "] rowWidth=" + row.width);
            }
            if (row.x < 0 || row.x + row.width > metrics.screenWidth) {
                offscreen = true;
                defect("row_horizontal_offscreen[" + view + "/" + tag + "]",
                        "row=[" + row.x + ',' + (row.x + row.width) + "] screenWidth="
                                + metrics.screenWidth);
            }
            if (row.y < 0 || row.y + row.height > metrics.screenHeight) {
                offscreen = true;
                defect("row_vertical_offscreen[" + view + "/" + tag + "]",
                        "rowY=[" + row.y + ',' + (row.y + row.height) + "] screenHeight="
                                + metrics.screenHeight);
            }
            if (row.buttonWidth > 0) {
                String expectedAction = context.computeOnClient(client -> {
                    GuiMaterialList gui = currentGuiUnchecked(client);
                    MaterialListEntry entry = gui.getMaterialList().getMaterialsAll().stream()
                            .filter(candidate -> candidate.getDatabaseId() == row.id).findFirst().orElseThrow();
                    return fi.dy.masa.malilib.util.StringUtils.translate(gui.getMaterialList().isCollaborating(entry)
                            ? "syncmaterial.gui.button.leave_collaboration" : "syncmaterial.gui.button.join_collaboration");
                });
                String actualAction = context.computeOnClient(client -> rowWidgets(currentGuiUnchecked(client)).stream()
                        .filter(widget -> widget.getEntry().getDatabaseId() == row.id)
                        .flatMap(widget -> subWidgets(widget).stream()).filter(ButtonBase.class::isInstance)
                        .map(ButtonBase.class::cast).map(ProductionUiClientGameTest::buttonLabel).findFirst().orElse(""));
                if (!expectedAction.equals(actualAction)) {
                    defect("row_action_semantics[" + view + "/" + tag + "]", expectedAction + " != " + actualAction);
                }
                int buttonRight = row.buttonX + row.buttonWidth;
                worstButtonRight = Math.max(worstButtonRight, buttonRight);
                worstRowRight = Math.max(worstRowRight, row.x + row.width);
                if (row.buttonX < row.x || buttonRight > row.x + row.width) {
                    outsideRow = true;
                    defect("row_button_outside_row[" + view + "/" + tag + "]",
                            "button=[" + row.buttonX + ',' + buttonRight + "] row=["
                                    + row.x + ',' + (row.x + row.width) + "]");
                }
                if (buttonRight > metrics.screenWidth) {
                    offscreen = true;
                    defect("row_button_offscreen[" + view + "/" + tag + "]",
                            "buttonRight=" + buttonRight + " screenWidth=" + metrics.screenWidth);
                }
                if (row.buttonY < row.y || row.buttonY + row.buttonHeight > row.y + row.height) {
                    defect("row_button_outside_row_vertical[" + view + "/" + tag + "]",
                            "buttonY=[" + row.buttonY + ',' + (row.buttonY + row.buttonHeight)
                                    + "] rowY=[" + row.y + ',' + (row.y + row.height) + "]");
                }
            }
            worstNatural = Math.max(worstNatural, row.naturalWidth);
        }

        SyncMaterial.LOGGER.info(
                "UI_COLUMNS view={} logical={}x{} rowWidth={} natural={} claimRight={} action=[{},{}] "
                        + "buttonRight={} rowRight={} overflow={} buttonOutsideRow={} offscreen={}",
                view, logicalWidth, logicalHeight, metrics.rowWidth(), worstNatural,
                metrics.claimRight(), metrics.actionX(), metrics.actionRight(),
                worstButtonRight, worstRowRight, overflow, outsideRow, offscreen);
    }

    private static ViewMetrics metrics(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                throw new AssertionError("生产材料清单未打开");
            }
            WidgetListMaterialList list = listWidget(gui);
            List<?> rawWidgets = (List<?>) field(WidgetListBase.class, list, "listWidgets");
            List<RowMetrics> rows = new ArrayList<>();
            int rowWidth = -1;
            long naturalWidth = -1;
            int claimRight = -1;
            int actionX = -1;
            int actionRight = -1;

            for (Object value : rawWidgets) {
                if (!(value instanceof WidgetMaterialListEntry widget)) {
                    continue;
                }
                MaterialListLayout layout = layout(widget);
                int id = widget.getEntry() == null ? -1 : widget.getEntry().getDatabaseId();
                int buttonX = -1;
                int buttonY = -1;
                int buttonWidth = 0;
                int buttonHeight = 0;
                if (id > 0) {
                    for (Object sub : subWidgets(widget)) {
                        if (sub instanceof ButtonBase button) {
                            buttonX = button.getX();
                            buttonY = button.getY();
                            buttonWidth = button.getWidth();
                            buttonHeight = button.getHeight();
                            break;
                        }
                    }
                }
                rows.add(new RowMetrics(id, widget.getX(), widget.getY(), widget.getWidth(),
                        widget.getHeight(), layout.hasCheckbox(), layout.naturalWidth(),
                        layout.column(MaterialListLayout.ColumnKey.CLAIM).right(),
                        layout.action().x(), layout.action().right(),
                        buttonX, buttonY, buttonWidth, buttonHeight));
                rowWidth = Math.max(rowWidth, widget.getWidth());
                naturalWidth = Math.max(naturalWidth, layout.naturalWidth());
                claimRight = Math.max(claimRight,
                        layout.column(MaterialListLayout.ColumnKey.CLAIM).right());
                actionX = layout.action().x();
                actionRight = layout.action().right();
            }

            int capacity = (Integer) field(WidgetListBase.class, list, "maxVisibleBrowserEntries");
            GuiScrollBar scrollBar = (GuiScrollBar) field(WidgetListBase.class, list, "scrollBar");
            return new ViewMetrics(client.getWindow().getGuiScaledWidth(),
                    client.getWindow().getGuiScaledHeight(), capacity,
                    scrollBar.getValue(), scrollBar.getMaxValue(),
                    list.getSearchBarWidget() != null, list.maxHorizontalOverflow(),
                    rowWidth, naturalWidth, claimRight,
                    actionX, actionRight, rows);
        });
    }

    private static WidgetListMaterialList listWidget(GuiMaterialList gui) {
        return (WidgetListMaterialList) field(GuiListBase.class, gui, "widget");
    }

    private static MaterialListLayout layout(WidgetMaterialListEntry widget) {
        return (MaterialListLayout) field(WidgetMaterialListEntry.class, widget, "layout");
    }

    private static List<?> subWidgets(WidgetMaterialListEntry widget) {
        return (List<?>) field(fi.dy.masa.malilib.gui.widgets.WidgetContainer.class, widget, "subWidgets");
    }

    @SuppressWarnings("unchecked")
    private static List<WidgetMaterialListEntry> rowWidgets(GuiMaterialList gui) {
        List<?> raw = (List<?>) field(WidgetListBase.class, listWidget(gui), "listWidgets");
        List<WidgetMaterialListEntry> rows = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof WidgetMaterialListEntry widget && widget.getEntry() != null) {
                rows.add(widget);
            }
        }
        return rows;
    }

    // =================================================================================
    // Screenshots
    // =================================================================================

    private void capture(ClientGameTestContext context, String name, int logicalWidth,
                                int logicalHeight, int scale) {
        context.runOnClient(ProductionUiClientGameTest::clearToasts);
        moveCursorLogical(context, 2, logicalHeight - 2);
        context.waitTicks(2);
        Path path = context.takeScreenshot(TestScreenshotOptions.of(name)
                .withDestinationDir(SCREENSHOT_DIR.toAbsolutePath())
                .disableCounterPrefix());
        long size = Files.isRegularFile(path) ? path.toFile().length() : -1;
        SyncMaterial.LOGGER.info("UI_SHOT {} path={} bytes={} physical={}x{} logical={}x{} scale={}",
                name, path, size, logicalWidth * scale, logicalHeight * scale,
                logicalWidth, logicalHeight, scale);
        if (size <= 0) {
            throw new AssertionError("截图未生成: " + path);
        }
        this.screenshotsCaptured++;
    }

    // =================================================================================
    // Input checks
    // =================================================================================

    private void verifySelectionAndClear(ClientGameTestContext context, String shotName,
                                         int logicalWidth, int logicalHeight, int scale) {
        GuiMaterialList gui = currentGui(context);
        List<Integer> ids = gui.getCurrentEntryIds();
        if (ids.size() < 2) {
            defect("selection_fixture", "expected at least 2 rows, got " + ids);
            return;
        }
        int first = ids.get(0);
        int second = ids.get(1);
        int rowsBefore = ids.size();

        clickCheckboxForMaterial(context, first);
        int selectedFirst = selectedIds(context).size();
        List<Integer> afterFirst = currentGui(context).getCurrentEntryIds();
        if (selectedFirst != 1) {
            defect("selection_first_click", "expected 1 selected, got " + selectedFirst);
        }
        if (afterFirst.size() != rowsBefore) {
            defect("selection_hides_rows", "rows before=" + rowsBefore + " after=" + afterFirst.size());
        }

        clickCheckboxForMaterial(context, second);
        int selectedSecond = selectedIds(context).size();
        List<Integer> afterSecond = currentGui(context).getCurrentEntryIds();
        if (selectedSecond != 2) {
            defect("selection_second_click", "expected 2 selected, got " + selectedSecond);
        }
        if (afterSecond.size() != rowsBefore) {
            defect("selection_second_hides_rows",
                    "rows before=" + rowsBefore + " after=" + afterSecond.size());
        }
        SyncMaterial.LOGGER.info("UI_INPUT selection ids={} selected={} rowsBefore={} rowsAfter={}",
                selectedIds(context), selectedSecond, rowsBefore, afterSecond.size());

        capture(context, shotName, logicalWidth, logicalHeight, scale);

        clickCheckboxForMaterial(context, first);
        clickCheckboxForMaterial(context, second);
        int cleared = selectedIds(context).size();
        if (cleared != 0) {
            defect("selection_clear_via_checkbox", "expected 0 selected, got " + cleared);
        }
        SyncMaterial.LOGGER.info("UI_INPUT selection_clear via_checkbox selected={} rows={}",
                cleared, currentGui(context).getCurrentEntryIds().size());
    }

    private void verifyWideManagementPanel(ClientGameTestContext context) {
        GuiMaterialList gui = currentGui(context);
        if (!gui.isOwner() || !gui.isMainOwner()) {
            defect("wide_owner_role", "expected owner/main-owner screen");
        }
        if (findButton(gui, "负责人管理") != null) {
            defect("wide_team_button_present", "960 wide owner view must dock the panel, not show a team button");
        }
        ButtonBase assign = findButton(gui, "分配给");
        ButtonBase kick = findButton(gui, "踢出");
        if (assign == null || kick == null) {
            defect("wide_management_actions",
                    "expected docked assign/kick buttons, assign=" + (assign != null)
                            + " kick=" + (kick != null));
        } else {
            SyncMaterial.LOGGER.info("UI_INPUT wide_panel assign={} kick={}",
                    buttonLabel(assign), buttonLabel(kick));
        }
    }

    private void verifyMemberView(ClientGameTestContext context) {
        GuiMaterialList gui = currentGui(context);
        if (gui.isOwner() || gui.isMainOwner()) {
            defect("member_role", "member screen unexpectedly has owner role");
        }
        if (findButton(gui, "负责人管理") == null) {
            defect("member_team_button", "member view is missing the team button");
        }
        ViewMetrics metrics = metrics(context);
        boolean anyCheckbox = metrics.rows.stream().anyMatch(RowMetrics::checkbox);
        if (anyCheckbox) {
            defect("member_checkbox", "member rows must not reserve the owner checkbox");
        }
        SyncMaterial.LOGGER.info("UI_INPUT member owner={} mainOwner={} checkbox={} teamButton={}",
                gui.isOwner(), gui.isMainOwner(), anyCheckbox,
                findButton(gui, "负责人管理") != null);
    }

    private void verifyInputs(ClientGameTestContext context) {
        normalizeInputs(context);

        // Header sort: missing column descending, same column reverses, total resets direction.
        clickHeaderColumn(context, 2);
        MaterialListBase.SortCriteria criteria = currentGui(context).getMaterialList().getSortCriteria();
        boolean reverse = currentGui(context).getMaterialList().getSortInReverse();
        List<Integer> missingOrder = currentGui(context).getCurrentEntryIds();
        if (criteria != MaterialListBase.SortCriteria.COUNT_MISSING || reverse) {
            defect("header_sort_missing", "criteria=" + criteria + " reverse=" + reverse);
        }
        if (missingOrder.isEmpty() || missingOrder.get(0) != IRON_MATERIAL_ID) {
            defect("header_sort_missing_order", "expected iron first, got " + missingOrder);
        }
        SyncMaterial.LOGGER.info("UI_INPUT header_sort missing criteria={} reverse={} order={}",
                criteria, reverse, missingOrder);

        clickHeaderColumn(context, 2);
        criteria = currentGui(context).getMaterialList().getSortCriteria();
        reverse = currentGui(context).getMaterialList().getSortInReverse();
        List<Integer> reversed = currentGui(context).getCurrentEntryIds();
        if (criteria != MaterialListBase.SortCriteria.COUNT_MISSING || !reverse) {
            defect("header_sort_reverse", "criteria=" + criteria + " reverse=" + reverse);
        }
        if (reversed.isEmpty() || reversed.get(reversed.size() - 1) != IRON_MATERIAL_ID) {
            defect("header_sort_reverse_order", "expected iron last, got " + reversed);
        }
        SyncMaterial.LOGGER.info("UI_INPUT header_sort reverse criteria={} reverse={} order={}",
                criteria, reverse, reversed);

        clickHeaderColumn(context, 1);
        criteria = currentGui(context).getMaterialList().getSortCriteria();
        reverse = currentGui(context).getMaterialList().getSortInReverse();
        List<Integer> totalOrder = currentGui(context).getCurrentEntryIds();
        if (criteria != MaterialListBase.SortCriteria.COUNT_TOTAL || reverse) {
            defect("header_sort_total", "criteria=" + criteria + " reverse=" + reverse);
        }
        if (totalOrder.isEmpty() || totalOrder.get(0) != STONE_MATERIAL_ID) {
            defect("header_sort_total_order", "expected stone first, got " + totalOrder);
        }
        SyncMaterial.LOGGER.info("UI_INPUT header_sort total criteria={} reverse={} order={}",
                criteria, reverse, totalOrder);

        // Pickup and HUD switches: real bottom-bar button clicks.
        boolean pickupBefore = PickupModeState.isActive();
        clickButton(context, "取货指示器：");
        boolean pickupAfter = PickupModeState.isActive();
        if (pickupAfter == pickupBefore) {
            defect("pickup_toggle", "state did not change; before=" + pickupBefore);
        }
        clickButton(context, "取货指示器：");
        if (PickupModeState.isActive() != pickupBefore) {
            defect("pickup_toggle_restore",
                    "expected " + pickupBefore + " after second click, got " + PickupModeState.isActive());
        }
        SyncMaterial.LOGGER.info("UI_INPUT pickup before={} afterFirst={} afterSecond={}",
                pickupBefore, pickupAfter, PickupModeState.isActive());

        boolean hudBefore = currentGui(context).getMaterialList().getHudRenderer().getShouldRender();
        clickButton(context, "HUD信息显示：");
        boolean hudAfter = currentGui(context).getMaterialList().getHudRenderer().getShouldRender();
        if (hudAfter == hudBefore) {
            defect("hud_toggle", "state did not change; before=" + hudBefore);
        }
        clickButton(context, "HUD信息显示：");
        if (currentGui(context).getMaterialList().getHudRenderer().getShouldRender() != hudBefore) {
            defect("hud_toggle_restore", "HUD switch did not restore");
        }
        SyncMaterial.LOGGER.info("UI_INPUT hud before={} afterFirst={} afterSecond={}",
                hudBefore, hudAfter, currentGui(context).getMaterialList().getHudRenderer().getShouldRender());

        // Select an unclaimed row before filtering it out; the hidden selection must remain explicit.
        clickCheckboxForMaterial(context, CHEST_MATERIAL_ID);
        if (!selectedIds(context).equals(List.of(CHEST_MATERIAL_ID))) {
            defect("filter_selection_fixture", "expected chest selected, got " + selectedIds(context));
        }

        // Filter with the real button: only claimed/participating materials may remain.
        long claimed = currentGui(context).getMaterialList().getMaterialsAll().stream()
                .filter(MaterialListEntry::isCurrentPlayerClaimed).count();
        clickButton(context, "仅显示我加入的");
        boolean filterOn = currentGui(context).isFilterMyMaterials();
        List<Integer> filteredIds = currentGui(context).getCurrentEntryIds();
        if (!filterOn) {
            defect("filter_toggle", "filter flag did not turn on");
        }
        if (filteredIds.size() != claimed) {
            defect("filter_count", "expected claimed=" + claimed + " rows=" + filteredIds.size());
        }
        if (filteredIds.stream().anyMatch(id -> currentGui(context).getMaterialList().getMaterialsAll()
                .stream().filter(entry -> entry.getDatabaseId() == id)
                .noneMatch(MaterialListEntry::isCurrentPlayerClaimed))) {
            defect("filter_leak", "filtered rows contain a non-claimed material: " + filteredIds);
        }
        int outside = currentGui(context).getSelectedOutsideFilterCount();
        if (outside != 1) {
            defect("filter_selected_outside", "expected one hidden selection, got " + outside
                    + " selected=" + selectedIds(context) + " visible=" + filteredIds);
        }
        SyncMaterial.LOGGER.info("UI_INPUT filter on={} expectedClaimed={} ids={} selectedOutside={}",
                filterOn, claimed, filteredIds, outside);

        verifySearchAndModalKeyboard(context, filteredIds);

        clickButton(context, "显示全部");
        if (currentGui(context).isFilterMyMaterials()) {
            defect("filter_restore", "filter flag stayed on after 显示全部");
        }
        if (currentGui(context).getCurrentEntryIds().size() != ITEMS.length) {
            defect("filter_restore_count", "expected " + ITEMS.length + " rows, got "
                    + currentGui(context).getCurrentEntryIds());
        }
        SyncMaterial.LOGGER.info("UI_INPUT filter_restore on={} rows={}",
                currentGui(context).isFilterMyMaterials(), currentGui(context).getCurrentEntryIds().size());
        clickCheckboxForMaterial(context, CHEST_MATERIAL_ID);
        if (!selectedIds(context).isEmpty()) {
            defect("filter_selection_restore", "chest selection did not clear: " + selectedIds(context));
        }

        // Team popover: open/close button, outside dismissal must not click through, Esc.
        clickButton(context, "负责人管理");
        if (!teamPopupOpen(context)) {
            defect("team_open", "team popup did not open from the team button");
        }
        capture(context, "production-owner-medium-team-720x450", 720, 450, 2);

        int[] checkbox = firstCheckboxPoint(context);
        clickLogical(context, checkbox[0], checkbox[1]);
        boolean stillOpen = teamPopupOpen(context);
        int selectedAfterOutside = selectedIds(context).size();
        if (stillOpen) {
            defect("team_outside_close", "outside click did not dismiss the team popup");
        }
        if (selectedAfterOutside != 0) {
            defect("team_outside_passthrough",
                    "outside dismissal clicked through into selection: selected=" + selectedAfterOutside);
        }
        SyncMaterial.LOGGER.info("UI_INPUT team_outside open={} selected={}",
                stillOpen, selectedAfterOutside);

        clickButton(context, "负责人管理");
        ButtonBase popupClose = popupButton(currentGui(context), "关闭");
        if (popupClose == null) {
            defect("team_close_button", "popup close button not found");
        } else {
            clickLogical(context, popupClose.getX() + popupClose.getWidth() / 2,
                    popupClose.getY() + popupClose.getHeight() / 2);
            if (teamPopupOpen(context)) {
                defect("team_close_button_action", "popup close button did not dismiss the popup");
            }
            SyncMaterial.LOGGER.info("UI_INPUT team_close_button open={}", teamPopupOpen(context));
        }

        clickButton(context, "负责人管理");
        context.getInput().pressKey(256);
        if (teamPopupOpen(context)) {
            defect("team_escape", "Esc did not dismiss the team popup");
        }
        SyncMaterial.LOGGER.info("UI_INPUT team_escape open={}", teamPopupOpen(context));

        // Scroll and restore to the top with real wheel input.
        List<Integer> beforeScroll = visibleEntryIds(context);
        moveCursorLogical(context, 360, 300);
        context.getInput().scroll(-1);
        context.waitTicks(2);
        int scrollAfter = metrics(context).scrollValue;
        List<Integer> afterScroll = visibleEntryIds(context);
        if (scrollAfter <= 0) {
            defect("scroll_value", "scroll did not advance; value=" + scrollAfter);
        }
        if (beforeScroll.equals(afterScroll)) {
            defect("scroll_visible_rows", "visible rows did not change: " + beforeScroll);
        }
        SyncMaterial.LOGGER.info("UI_INPUT scroll down value={} before={} after={}",
                scrollAfter, beforeScroll, afterScroll);
        for (int i = 0; i < 6; i++) {
            context.getInput().scroll(1);
            context.waitTicks(1);
        }
        int scrollBack = metrics(context).scrollValue;
        if (scrollBack != 0) {
            defect("scroll_restore", "expected 0 after scrolling up, got " + scrollBack);
        }
        SyncMaterial.LOGGER.info("UI_INPUT scroll up value={} rows={}",
                scrollBack, visibleEntryIds(context));
        verifyCatalogDeletionUnderDialog(context);
    }

    private void verifyCatalogDeletionUnderDialog(ClientGameTestContext context) {
        GuiMaterialList[] parent = {currentGui(context)};
        context.runOnClient(client -> {
            parent[0].getSelectedMaterialIds().add(CHEST_MATERIAL_ID);
            parent[0].openAssignDialog();
            if (!(ClientScreenAccess.currentScreen(client)
                    instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog)) {
                throw new AssertionError("目录删除夹具未能打开玩家选择弹窗");
            }
            List<net.syncmaterial.syncmaterial.api.MaterialEntry> remaining =
                    parent[0].getMaterialList().getMaterialsAll().stream()
                            .filter(entry -> entry.getDatabaseId() != CHEST_MATERIAL_ID)
                            .map(entry -> new net.syncmaterial.syncmaterial.api.MaterialEntry(
                                    entry.getDatabaseId(), entry.getStack(), entry.getCountTotal()))
                            .toList();
            parent[0].getMaterialList().setMaterialEntries(remaining, 8);
        });
        List<Integer> selected = context.computeOnClient(client ->
                List.copyOf(parent[0].getSelectedMaterialIds()));
        boolean dialogVisible = context.computeOnClient(client -> ClientScreenAccess.currentScreen(client)
                instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog);
        if (!dialogVisible || !selected.isEmpty()) {
            defect("catalog_delete_under_dialog", "dialogVisible=" + dialogVisible
                    + " selected=" + selected);
        }
        SyncMaterial.LOGGER.info("UI_INPUT catalog_delete_under_dialog visible={} selected={}",
                dialogVisible, selected);
        context.runOnClient(client -> ClientScreenAccess.showScreen(client, parent[0]));
    }

    private void verifySearchAndModalKeyboard(ClientGameTestContext context, List<Integer> filteredIds) {
        ViewMetrics before = metrics(context);
        if (!before.searchPresent) {
            defect("search_widget_absent",
                    "production material list has no WidgetSearchBar; approved production UI requires search");
            List<Integer> idsBeforeTyping = currentGui(context).getCurrentEntryIds();
            moveCursorLogical(context, 300, 260);
            context.getInput().typeChars("石头");
            context.waitTicks(2);
            if (metrics(context).searchPresent) {
                defect("search_unexpected_widget", "typing created a search widget after measurement");
            }
            List<Integer> idsAfterTyping = currentGui(context).getCurrentEntryIds();
            if (!idsBeforeTyping.equals(idsAfterTyping)) {
                defect("typing_without_search_changed_rows",
                        "before=" + idsBeforeTyping + " after=" + idsAfterTyping);
            }
            SyncMaterial.LOGGER.info(
                    "UI_INPUT search ABSENT typing_no_effect rows={} filterOn={}",
                    idsAfterTyping, currentGui(context).isFilterMyMaterials());
        } else {
            int[] icon = searchIconPoint(context);
            if (icon == null) {
                defect("search_icon", "search bar exists but has no icon coordinates");
            } else {
                clickLogical(context, icon[0], icon[1]);
                context.getInput().typeChars("石头");
                context.waitTicks(3);
                String filter = searchFilter(context);
                List<Integer> searchIds = currentGui(context).getCurrentEntryIds();
                if (!"石头".equals(filter) || searchIds.size() != 1) {
                    defect("search_input", "filter=" + filter + " rows=" + searchIds);
                }
                SyncMaterial.LOGGER.info("UI_INPUT search focus filter={} rows={}", filter, searchIds);

                clickButton(context, "负责人管理");
                context.getInput().typeChars("箱");
                context.getInput().pressKey(259);
                String modalFilter = searchFilter(context);
                List<Integer> modalIds = currentGui(context).getCurrentEntryIds();
                if (!"石头".equals(modalFilter) || !modalIds.equals(searchIds)) {
                    defect("search_modal_keyboard",
                            "modal typing/backspace changed underlying search: " + modalFilter
                                    + " rows=" + modalIds);
                }
                context.getInput().pressKey(256);
                if (teamPopupOpen(context) || !"石头".equals(searchFilter(context))
                        || !currentGui(context).getCurrentEntryIds().equals(searchIds)) {
                    defect("search_escape_retain", "Esc lost search state or left popup open");
                }
                SyncMaterial.LOGGER.info("UI_INPUT search modal_keyboard filter={} rows={} popup={}",
                        searchFilter(context), currentGui(context).getCurrentEntryIds(), teamPopupOpen(context));
                context.getInput().pressKey(259);
                context.getInput().pressKey(259);
                if (!searchFilter(context).isEmpty()) {
                    defect("search_clear", "backspace did not clear search: " + searchFilter(context));
                }
                SyncMaterial.LOGGER.info("UI_INPUT search cleared rows={}",
                        currentGui(context).getCurrentEntryIds().size());
            }
        }

        // Modal typing/backspace must never mutate the underlying list/filter state even
        // when the production build has no search widget.
        List<Integer> idsBeforeModal = currentGui(context).getCurrentEntryIds();
        boolean filterBeforeModal = currentGui(context).isFilterMyMaterials();
        clickButton(context, "负责人管理");
        context.getInput().typeChars("箱");
        context.getInput().pressKey(259);
        boolean modalOpen = teamPopupOpen(context);
        List<Integer> idsAfterModal = currentGui(context).getCurrentEntryIds();
        boolean filterAfterModal = currentGui(context).isFilterMyMaterials();
        if (!modalOpen) {
            defect("modal_keyboard_open", "team popup was not open for keyboard isolation check");
        }
        if (!idsBeforeModal.equals(idsAfterModal) || filterBeforeModal != filterAfterModal) {
            defect("modal_keyboard_state",
                    "underlying list changed: before=" + idsBeforeModal + "/" + filterBeforeModal
                            + " after=" + idsAfterModal + "/" + filterAfterModal);
        }
        context.getInput().pressKey(256);
        if (teamPopupOpen(context)) {
            defect("modal_keyboard_escape", "Esc did not close the team popup");
        }
        SyncMaterial.LOGGER.info(
                "UI_INPUT modal_keyboard search={} filterOn={} untiledIds={} popupAfterEsc={}",
                metrics(context).searchPresent ? "present" : "absent", filterAfterModal,
                idsAfterModal, teamPopupOpen(context));
    }

    private void verifyCompactScroll(ClientGameTestContext context, String view) {
        List<Integer> before = visibleEntryIds(context);
        moveCursorLogical(context, 320, 240);
        context.getInput().scroll(-1);
        context.waitTicks(2);
        ViewMetrics metrics = metrics(context);
        List<Integer> after = visibleEntryIds(context);
        if (metrics.scrollValue <= 0 || before.equals(after)) {
            defect("compact_scroll[" + view + "]",
                    "value=" + metrics.scrollValue + " before=" + before + " after=" + after);
        }
        SyncMaterial.LOGGER.info("UI_INPUT compact_scroll view={} value={}/{} before={} after={}",
                view, metrics.scrollValue, metrics.scrollMax, before, after);
        for (int i = 0; i < 8; i++) {
            context.getInput().scroll(1);
            context.waitTicks(1);
        }
        if (metrics(context).scrollValue != 0) {
            defect("compact_scroll_restore[" + view + "]",
                    "value=" + metrics(context).scrollValue);
        }
    }

    private void verifyLanguages(ClientGameTestContext context, String actor,
                                 ProjectProgressSnapshot normal) {
        for (String language : List.of("en_us", "de_de", "es_es", "fr_fr", "ja_jp", "ko_kr", "ru_ru", "zh_cn")) {
            selectLanguageAndReload(context, language);
            open(context, actor, true, true, 720, 450, 2, normal);
            verifyColumns(context, "language-" + language, 720, 450);
            capture(context, "production-language-" + language + "-720x450", 720, 450, 2);
            context.runOnClient(client -> currentGuiUnchecked(client).getSelectedMaterialIds().add(STONE_MATERIAL_ID));
            context.runOnClient(client -> currentGuiUnchecked(client).initGui());
            var controls = context.computeOnClient(client -> screenButtons(currentGuiUnchecked(client)));
            for (int first = 0; first < controls.size(); first++) {
                ButtonBase button = controls.get(first);
                if (button.getX() < 0 || button.getY() < 0
                        || button.getX() + button.getWidth() > 720
                        || button.getY() + button.getHeight() > 450) {
                    defect("language_control_offscreen[" + language + "]", buttonLabel(button));
                }
                for (int second = first + 1; second < controls.size(); second++) {
                    ButtonBase other = controls.get(second);
                    if (button.getX() < other.getX() + other.getWidth()
                            && other.getX() < button.getX() + button.getWidth()
                            && button.getY() < other.getY() + other.getHeight()
                            && other.getY() < button.getY() + button.getHeight()) {
                        defect("language_control_overlap[" + language + "]",
                                buttonLabel(button) + " / " + buttonLabel(other));
                    }
                }
            }
            capture(context, "production-language-" + language + "-selected-720x450", 720, 450, 2);
        }
    }

    private void verifyLongQuantitiesAndHorizontalOverflow(ClientGameTestContext context,
                                                           String actor, UUID actorId) {
        long maximum = Long.MAX_VALUE;
        ProjectProgressSnapshot huge = new ProjectProgressSnapshot(PROJECT_ID, PROJECT_NAME,
                8, 12, 7, true, true, false, List.of(), List.of(
                        new ProjectProgressMaterial(1, "minecraft:stone", maximum, 0, 0, List.of()),
                        new ProjectProgressMaterial(2, "minecraft:dirt", maximum, maximum, maximum,
                                List.of(new ProgressParticipant(new PlayerIdentity(actorId, actor), maximum)))));
        open(context, actor, true, true, 640, 360, 3, huge);
        if (metrics(context).horizontalOverflow <= 0) {
            defect("long_quantity_overflow_hint", "19 digit quantity columns must retain measured widths");
        }
        verifyColumns(context, "long-left", 640, 360);
        context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (!gui.getMaterialList().getExactCountTotal().equals(java.math.BigInteger.valueOf(maximum).multiply(java.math.BigInteger.TWO))) {
                throw new AssertionError("Long.MAX material list lost its exact aggregate");
            }
            return null;
        });
        capture(context, "production-long-quantities-left-640x360", 640, 360, 3);
        context.getInput().holdShift();
        moveCursorLogical(context, 320, 230);
        context.getInput().scroll(-20);
        context.getInput().releaseShift();
        context.waitTicks(2);
        int offset = context.computeOnClient(client -> listWidget(currentGuiUnchecked(client)).horizontalOffset());
        if (offset <= 0) defect("long_horizontal_scroll", "Shift+scroll did not move the viewport");
        verifyColumns(context, "long-right", 640, 360);
        capture(context, "production-long-quantities-right-640x360", 640, 360, 3);
        open(context, actor, true, true, 320, 240, 3, huge);
        verifyColumns(context, "extreme-320x240", 320, 240);
        capture(context, "production-extreme-320x240", 320, 240, 3);
    }

    private void verifyViewportPreservation(ClientGameTestContext context) {
        moveCursorLogical(context, 320, 240);
        context.getInput().scroll(-1);
        context.waitTicks(2);
        List<Integer> before = visibleEntryIds(context);
        int beforeScroll = metrics(context).scrollValue;
        context.runOnClient(client -> currentGuiUnchecked(client)
                .updateOverlapWarning(AreaOverlapStatus.MESSAGE_KEY));
        context.waitTicks(2);
        if (metrics(context).scrollValue != beforeScroll
                || visibleEntryIds(context).isEmpty()
                || !visibleEntryIds(context).getFirst().equals(before.getFirst())) {
            defect("warning_scroll_anchor", "before=" + before + " after=" + visibleEntryIds(context));
        }
        int[] search = searchIconPoint(context);
        clickLogical(context, search[0], search[1]);
        context.getInput().typeChars("石头");
        context.waitTicks(2);
        context.runOnClient(client -> currentGuiUnchecked(client).updateFreshnessWarnings(List.of(
                new CollaborationStatusS2CPacket.AreaFreshnessInfo("warehouse", 1, "北仓", "stale"))));
        context.waitTicks(2);
        if (!"石头".equals(searchFilter(context))
                || !currentGui(context).getCurrentEntryIds().equals(List.of(STONE_MATERIAL_ID))) {
            defect("warning_search_retain", "filter=" + searchFilter(context)
                    + " rows=" + currentGui(context).getCurrentEntryIds());
        }
        context.runOnClient(client -> {
            currentGuiUnchecked(client).updateOverlapWarning(null);
            currentGuiUnchecked(client).updateFreshnessWarnings(List.of());
        });
        context.waitTicks(2);
        if (!"石头".equals(searchFilter(context))) {
            defect("warning_clear_search_retain", "filter=" + searchFilter(context));
        }
        SyncMaterial.LOGGER.info("UI_INPUT viewport_warning_preserved scroll={} search={}",
                beforeScroll, searchFilter(context));
    }

    private void verifyWarningState(ClientGameTestContext context) {
        GuiMaterialList gui = currentGui(context);
        SyncMaterialList list = gui.getMaterialList();
        if (list.isPreparationKnown()) {
            defect("warning_preparation_known", "warning snapshot must not report preparation known");
        }
        if (list.getAreaFreshness().size() != 2) {
            defect("warning_freshness_data", "expected 2 area warnings, got "
                    + list.getAreaFreshness().size());
        }
        List<MaterialRowStatus> statuses = new ArrayList<>();
        for (MaterialListEntry entry : list.getMaterialsAll()) {
            statuses.add(MaterialRowStatus.of(entry.progressView(list)));
        }
        boolean unknownRows = statuses.stream().filter(status ->
                status == MaterialRowStatus.UNKNOWN).count() == 11;
        boolean noDemand = statuses.stream().filter(status ->
                status == MaterialRowStatus.NO_DEMAND).count() == 1;
        if (!unknownRows || !noDemand) {
            defect("warning_row_status", "expected 11 UNKNOWN + 1 NO_DEMAND, got " + statuses);
        }
        SyncMaterial.LOGGER.info("UI_INPUT warning preparationKnown={} warnings={} statuses={}",
                list.isPreparationKnown(), list.getAreaFreshness(), statuses);

        if (gui.getFreshnessWarnings().size() != 2) {
            defect("progress_warnings_not_propagated",
                    "catalog update did not bind both authoritative warnings: "
                            + gui.getFreshnessWarnings());
        }
        context.runOnClient(client -> currentGuiUnchecked(client)
                .updateOverlapWarning(AreaOverlapStatus.MESSAGE_KEY));
        context.waitTicks(2);
        if (gui.getFreshnessWarnings().size() != 2 || !AreaOverlapStatus.MESSAGE_KEY
                .equals(gui.getOverlapWarning())) {
            defect("warning_banner_bind", "freshness=" + gui.getFreshnessWarnings()
                    + " overlap=" + gui.getOverlapWarning());
        }
        ViewMetrics layout = metrics(context);
        RowMetrics header = layout.rows.stream().filter(row -> row.id < 0).findFirst().orElse(null);
        if (header == null || header.y != 48 + 2 * 18 + 4) {
            defect("warning_layout_overlap", "expected headerY=88 after two warning lines, actual="
                    + (header == null ? "absent" : header.y));
        }
        SyncMaterial.LOGGER.info("UI_INPUT warning_banner freshness={} overlap={} warningY=48 contentTop=84",
                gui.getFreshnessWarnings(), gui.getOverlapWarning());
    }

    private void normalizeInputs(ClientGameTestContext context) {
        GuiMaterialList gui = currentGui(context);
        if (PickupModeState.isActive()) {
            clickButton(context, "取货指示器：");
        }
        if (!currentGui(context).getMaterialList().getHudRenderer().getShouldRender()) {
            clickButton(context, "HUD信息显示：");
        }
        if (currentGui(context).isFilterMyMaterials()) {
            clickButton(context, "显示全部");
        }
        for (int i = 0; i < 8; i++) {
            moveCursorLogical(context, 360, 300);
            context.getInput().scroll(1);
            context.waitTicks(1);
        }
        SyncMaterial.LOGGER.info("UI_INPUT normalize pickup={} hud={} filter={} scroll={}",
                PickupModeState.isActive(),
                currentGui(context).getMaterialList().getHudRenderer().getShouldRender(),
                currentGui(context).isFilterMyMaterials(), metrics(context).scrollValue);
    }

    // =================================================================================
    // Real input helpers
    // =================================================================================

    private static void clickHeaderColumn(ClientGameTestContext context, int columnIndex) {
        int[] point = context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return null;
            }
            WidgetListMaterialList list = listWidget(gui);
            List<?> raw = (List<?>) field(WidgetListBase.class, list, "listWidgets");
            for (Object value : raw) {
                if (value instanceof WidgetMaterialListEntry widget && widget.getEntry() == null) {
                    MaterialListLayout layout = layout(widget);
                    MaterialListLayout.ColumnKey key =
                            MaterialListLayout.ColumnKey.values()[columnIndex];
                    MaterialListLayout.Column column = layout.column(key);
                    return new int[]{
                            widget.getX() + column.x() + Math.max(1, column.width() / 2),
                            widget.getY() + widget.getHeight() / 2
                    };
                }
            }
            return null;
        });
        if (point == null) {
            throw new AssertionError("找不到材料列表表头");
        }
        clickLogical(context, point[0], point[1]);
        context.waitTicks(2);
    }

    private static void clickButton(ClientGameTestContext context, String prefix) {
        GuiMaterialList gui = currentGui(context);
        ButtonBase button = findButton(gui, prefix);
        if (button == null) {
            SyncMaterial.LOGGER.error("UI_DEFECT button_missing prefix={} labels={}",
                    prefix, screenButtonLabels(gui));
            throw new AssertionError("找不到生产按钮: " + prefix);
        }
        SyncMaterial.LOGGER.info("UI_INPUT click_button label={} bounds=[{},{},{},{}]",
                strip(buttonLabel(button)), button.getX(), button.getY(),
                button.getWidth(), button.getHeight());
        clickLogical(context, button.getX() + button.getWidth() / 2,
                button.getY() + button.getHeight() / 2);
        context.waitTicks(2);
    }

    private static void clickCheckboxForMaterial(ClientGameTestContext context, int materialId) {
        int[] point = context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return null;
            }
            for (WidgetMaterialListEntry widget : rowWidgets(gui)) {
                if (widget.getEntry().getDatabaseId() == materialId) {
                    return new int[]{widget.getX() + 8, widget.getY() + 13};
                }
            }
            return null;
        });
        if (point == null) {
            throw new AssertionError("勾选行不在可见区域: materialId=" + materialId);
        }
        clickLogical(context, point[0], point[1]);
        context.waitTicks(2);
    }

    private static int[] firstCheckboxPoint(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return null;
            }
            for (WidgetMaterialListEntry widget : rowWidgets(gui)) {
                return new int[]{widget.getX() + 8, widget.getY() + 13};
            }
            return null;
        });
    }

    private static void clickLogical(ClientGameTestContext context, int logicalX, int logicalY) {
        moveCursorLogical(context, logicalX, logicalY);
        context.getInput().pressMouse(0);
        context.waitTicks(1);
    }

    private static void moveCursorLogical(ClientGameTestContext context, int logicalX, int logicalY) {
        double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(logicalX * scale, logicalY * scale);
    }

    private static List<Integer> selectedIds(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            return gui == null ? List.of() : List.copyOf(gui.getSelectedMaterialIds());
        });
    }

    private static List<Integer> visibleEntryIds(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return List.of();
            }
            List<Integer> ids = new ArrayList<>();
            for (WidgetMaterialListEntry widget : rowWidgets(gui)) {
                ids.add(widget.getEntry().getDatabaseId());
            }
            return ids;
        });
    }

    private static boolean teamPopupOpen(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            return gui != null && (Boolean) field(GuiMaterialList.class, gui, "teamPopup");
        });
    }

    private static String searchFilter(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return "";
            }
            var bar = listWidget(gui).getSearchBarWidget();
            return bar == null ? "" : bar.getFilter();
        });
    }

    private static int[] searchIconPoint(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            GuiMaterialList gui = currentGuiUnchecked(client);
            if (gui == null) {
                return null;
            }
            var bar = listWidget(gui).getSearchBarWidget();
            if (bar == null) {
                return null;
            }
            WidgetBase icon = (WidgetBase) field(fi.dy.masa.malilib.gui.widgets.WidgetSearchBar.class,
                    bar, "iconSearch");
            return new int[]{icon.getX() + icon.getWidth() / 2, icon.getY() + icon.getHeight() / 2};
        });
    }

    private static ButtonBase findButton(GuiMaterialList gui, String prefix) {
        for (ButtonBase button : screenButtons(gui)) {
            if (strip(buttonLabel(button)).startsWith(prefix)) {
                return button;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ButtonBase> screenButtons(GuiMaterialList gui) {
        List<?> raw = (List<?>) field(GuiBase.class, gui, "buttons");
        List<ButtonBase> buttons = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof ButtonBase button) {
                buttons.add(button);
            }
        }
        return buttons;
    }

    private static ButtonBase popupButton(GuiMaterialList gui, String label) {
        List<?> raw = (List<?>) field(GuiMaterialList.class, gui, "popupButtons");
        for (Object value : raw) {
            if (value instanceof ButtonBase button && strip(buttonLabel(button)).equals(label)) {
                return button;
            }
        }
        return null;
    }

    private static String buttonLabel(ButtonBase button) {
        return String.valueOf(field(ButtonBase.class, button, "displayString"));
    }

    private static String strip(String value) {
        return value == null ? "" : value.replaceAll("§[0-9a-fk-or]", "");
    }

    private static List<String> screenButtonLabels(GuiMaterialList gui) {
        List<String> labels = new ArrayList<>();
        for (ButtonBase button : screenButtons(gui)) {
            labels.add(strip(buttonLabel(button)));
        }
        return labels;
    }

    // =================================================================================
    // Reflection helpers
    // =================================================================================

    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取 " + owner.getName() + "." + name, failure);
        }
    }

    private void defect(String code, String detail) {
        String message = code + " :: " + detail;
        if (!this.defects.contains(message)) {
            this.defects.add(message);
            SyncMaterial.LOGGER.warn("UI_DEFECT {}", message);
        }
    }

    // =================================================================================
    // Metrics records
    // =================================================================================

    private record RowMetrics(int id, int x, int y, int width, int height,
                              boolean checkbox, long naturalWidth, int claimRight,
                              int actionX, int actionRight,
                              int buttonX, int buttonY, int buttonWidth, int buttonHeight) {
    }

    private record ViewMetrics(int screenWidth, int screenHeight, int capacity,
                               int scrollValue, int scrollMax, boolean searchPresent, int horizontalOverflow,
                               int rowWidth, long naturalWidth, int claimRight,
                               int actionX, int actionRight, List<RowMetrics> rows) {
    }
}
