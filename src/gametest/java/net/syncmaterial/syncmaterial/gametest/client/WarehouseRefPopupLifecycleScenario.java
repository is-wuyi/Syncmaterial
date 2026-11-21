package net.syncmaterial.syncmaterial.gametest.client;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.EditorSaveTracker;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager;
import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;

/**
 * Shared narrow scenario for the real popup -&gt; project warehouse reference loop.
 *
 * <p>It uses the production popup/editor/manager widgets on the client thread, real mouse
 * input through {@link ClientGameTestContext#getInput()}, and revision-5 sessions.
 * Request ids and pending session entries are observed only by reflection; no production
 * behavior or test hook is added. Legacy and Foundation entries only supply their own real
 * server fixture and authoritative assertions.</p>
 */
public final class WarehouseRefPopupLifecycleScenario {
    private static final String PROJECTS_CONFIG = "LIST";
    private static final String GLOBAL_CONFIG = "LIST_WAREHOUSES";
    private static final String REFS_CONFIG = "LIST_WAREHOUSE_REFS";

    private WarehouseRefPopupLifecycleScenario() {}

    /** Backend-specific fixture creation and authoritative assertions. */
    public interface Backend {
        int prepare(TestDedicatedServerContext server, String projectId);

        boolean warehouseExists(TestDedicatedServerContext server, int warehouseId);

        boolean projectReferences(TestDedicatedServerContext server, String projectId, int warehouseId);

        void cleanup(TestDedicatedServerContext server, String projectId, int warehouseId);
    }

    public static void run(ClientGameTestContext ctx, Backend backend) {
        String projectId = "client-popup-ref-" + System.nanoTime();
        int warehouseId = -1;
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                ctx.waitTicks(20);
                warehouseId = backend.prepare(server, projectId);
                if (warehouseId <= 0) {
                    throw new AssertionError("后端未创建真实仓库 fixture");
                }
                if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                        ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)))) {
                    throw new AssertionError("客户端未获得协议 5 视图能力");
                }

                runFixture(ctx, backend, server, projectId, warehouseId);
            } finally {
                ctx.runOnClient(client -> {
                    ClientScreenAccess.showScreen(client, null);
                    GuiStagingAreaEditorNormal.clearCurrentEditor();
                    StagingAreaRenderer.getInstance().clearWarehouseAreas();
                    StagingAreaRenderer.getInstance().clearWarehouseContainers();
                });
                backend.cleanup(server, projectId, warehouseId);
            }
        }
    }

    private static void runFixture(ClientGameTestContext ctx, Backend backend,
                           TestDedicatedServerContext server, String projectId,
                           int warehouseId) {
        // ===== 1. Real staging editor parent (holds the project/editor lease) =====
        GuiStagingAreaEditorNormal editor = ctx.computeOnClient(client -> {
            GuiStagingAreaEditorNormal opened = new GuiStagingAreaEditorNormal(
                    new AreaSelection(), null, projectId);
            GuiBase.openGui(opened);
            return opened;
        });
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == editor
                        && ProjectViewClient.current()
                        .flatMap(ProjectViewClient::projectStore)
                        .flatMap(store -> store.configuration(PROJECTS_CONFIG))
                        .isPresent()))) {
            throw new AssertionError("备货区编辑器未完成项目视图/LIST 同步");
        }
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> noPendingSession()))) {
            throw new AssertionError("打开弹窗前仍有未完成的会话请求");
        }
        int parentMask = ctx.computeOnClient(client ->
                ProjectViewClient.current().orElseThrow().desiredMask());

        // ===== 2. Popup open: global OPEN must precede one LIST; resize must not duplicate =====
        Observation baseline = ctx.computeOnClient(client -> observe());
        requireNoPending(baseline, "弹窗打开前");
        GuiWarehouseRefPopup popup = ctx.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != editor) {
                throw new AssertionError("打开弹窗前父编辑器不是当前界面");
            }
            GuiWarehouseRefPopup opened = new GuiWarehouseRefPopup(projectId);
            GuiBase.openGui(opened);
            return opened;
        });
        Observation popupInitial = ctx.computeOnClient(client -> {
            Observation first = observe();
            popup.initGui();
            popup.initGui();
            popup.initGui();
            Observation resized = observe();
            if (!first.equals(resized)) {
                throw new AssertionError("popup loading resize 重复发请求: "
                        + first + " -> " + resized);
            }
            return first;
        });
        requireOpenBeforeList(baseline, popupInitial, "popup");
        if (!ctx.computeOnClient(client -> ClientScreenAccess.currentScreen(client) == popup
                && ProjectViewClient.current().flatMap(ProjectViewClient::globalStore).isPresent())) {
            throw new AssertionError("弹窗未打开全局仓库视图");
        }

        // ===== 3. Real popup mouse selection -> ADD_WAREHOUSE_REF SAVED + authoritative refs =====
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == popup
                        && popupHasWarehouse(popup, warehouseId)))) {
            throw new AssertionError("弹窗未收到 LIST_WAREHOUSES 或缺少 fixture 仓库");
        }
        clickPopupSelect(ctx, popup, warehouseId);
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                popup.getSaveState() == EditorSaveTracker.State.SAVED))) {
            throw new AssertionError("ADD_WAREHOUSE_REF 未确认保存，state="
                    + ctx.computeOnClient(client -> popup.getSaveState()));
        }
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> clientReferences(warehouseId)))) {
            throw new AssertionError("客户端权威 LIST_WAREHOUSE_REFS 未包含 " + warehouseId);
        }
        if (!waitFor(ctx, () -> backend.projectReferences(server, projectId, warehouseId))) {
            throw new AssertionError("服务端权威仓库关联未包含 " + warehouseId);
        }

        // Popup's own global lease closes, but the parent editor's project/editor lease must remain.
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            ProjectViewClient session = ProjectViewClient.current().orElseThrow();
            return ClientScreenAccess.currentScreen(client) == editor
                    && session.projectStore().isPresent()
                    && session.desiredMask() == parentMask
                    && session.globalStore().isEmpty();
        }))) {
            throw new AssertionError("关闭弹窗释放或改变了 parent editor 的项目 lease");
        }

        // ===== 4. Real remove button: project ref disappears, warehouse remains =====
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == editor
                        && editorHasRef(editor, warehouseId)))) {
            throw new AssertionError("父编辑器仓库引用列表未回填 " + warehouseId);
        }
        clickRefRemove(ctx, editor, warehouseId);
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> !clientReferences(warehouseId)))) {
            throw new AssertionError("移除引用后客户端权威 refs 仍包含 " + warehouseId);
        }
        if (!waitFor(ctx, () -> !backend.projectReferences(server, projectId, warehouseId)
                && backend.warehouseExists(server, warehouseId))) {
            throw new AssertionError("移除引用必须只取消项目关联，不能删除全局仓库");
        }

        // ===== 5. Manager resize: still one LIST for this global view =====
        ctx.runOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) == editor) {
                editor.closeGui(false);
            }
        });
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == null))) {
            throw new AssertionError("关闭父编辑器失败");
        }

        Observation managerBaseline = ctx.computeOnClient(client -> observe());
        requireNoPending(managerBaseline, "仓库管理器打开前");
        GuiWarehouseManager manager = ctx.computeOnClient(client -> {
            GuiWarehouseManager opened = new GuiWarehouseManager();
            GuiBase.openGui(opened);
            return opened;
        });
        Observation managerInitial = ctx.computeOnClient(client -> {
            Observation first = observe();
            manager.initGui();
            manager.initGui();
            manager.initGui();
            Observation resized = observe();
            if (!first.equals(resized)) {
                throw new AssertionError("manager resize 重复发请求: "
                        + first + " -> " + resized);
            }
            return first;
        });
        requireOpenBeforeList(managerBaseline, managerInitial, "manager");
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == manager
                        && ProjectViewClient.current()
                        .flatMap(ProjectViewClient::globalStore)
                        .flatMap(store -> store.configuration(GLOBAL_CONFIG))
                        .map(list -> list.stream().anyMatch(area -> area.id() == warehouseId))
                        .orElse(false)))) {
            throw new AssertionError("仓库管理器的全局目录未保留 fixture 仓库");
        }

        // ===== 6. Popup over manager: closing it must not release the manager's global lease =====
        GuiWarehouseRefPopup overlayPopup = ctx.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != manager) {
                throw new AssertionError("覆盖弹窗前仓库管理器不是当前界面");
            }
            GuiWarehouseRefPopup opened = new GuiWarehouseRefPopup(projectId);
            GuiBase.openGui(opened);
            return opened;
        });
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == overlayPopup
                        && popupHasWarehouse(overlayPopup, warehouseId)))) {
            throw new AssertionError("覆盖弹窗未读到共享全局视图的仓库列表");
        }
        ctx.getInput().pressKey(256);
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == manager
                        && ProjectViewClient.current().flatMap(ProjectViewClient::globalStore).isPresent()))) {
            throw new AssertionError("关闭弹窗释放了其他界面持有的全局 lease");
        }

        ctx.runOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) == manager) {
                manager.closeGui(false);
            }
        });
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) == null
                        && ProjectViewClient.current().flatMap(ProjectViewClient::globalStore).isEmpty()))) {
            throw new AssertionError("仓库管理器关闭后全局 lease 未释放");
        }
    }

    private static boolean clientReferences(int warehouseId) {
        return ProjectViewClient.current()
                .flatMap(ProjectViewClient::projectStore)
                .flatMap(store -> store.configuration(REFS_CONFIG))
                .map(areas -> areas.stream().anyMatch(area -> area.id() == warehouseId))
                .orElse(false);
    }

    private static boolean popupHasWarehouse(GuiWarehouseRefPopup popup, int warehouseId) {
        Object value = field(popup, "warehouses");
        if (!(value instanceof List<?> areas)) return false;
        for (Object area : areas) {
            if (area instanceof StagingAreaConfigResponseS2CPacket.AreaInfo info
                    && info.areaId() == warehouseId) {
                return true;
            }
        }
        return false;
    }

    private static boolean editorHasRef(GuiStagingAreaEditorNormal editor, int warehouseId) {
        Object list = field(editor, "warehouseListWidget");
        if (list == null) return false;
        for (Object widget : listWidgets(list)) {
            if (areaId(widget, "entryData") == warehouseId) return true;
        }
        return false;
    }

    private static void clickPopupSelect(ClientGameTestContext ctx, GuiWarehouseRefPopup popup,
                                         int warehouseId) {
        double[] point = ctx.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != popup) {
                throw new AssertionError("选择仓库前弹窗已关闭");
            }
            Object list = field(popup, "listWidget");
            if (list == null) {
                throw new AssertionError("弹窗仓库列表尚未构建");
            }
            for (Object widget : listWidgets(list)) {
                if (areaId(widget, "entry") == warehouseId) {
                    ButtonBase button = firstButton(widget);
                    if (button == null) {
                        throw new AssertionError("仓库条目没有选择按钮");
                    }
                    return toScreenPoint(client, button.getX() + button.getWidth() / 2.0,
                            button.getY() + button.getHeight() / 2.0);
                }
            }
            throw new AssertionError("未找到真实仓库条目的选择按钮");
        });
        clickAt(ctx, point);
    }

    private static void clickRefRemove(ClientGameTestContext ctx, GuiStagingAreaEditorNormal editor,
                                       int warehouseId) {
        double[] point = ctx.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != editor) {
                throw new AssertionError("移除引用前父编辑器已关闭");
            }
            Object list = field(editor, "warehouseListWidget");
            if (list == null) {
                throw new AssertionError("父编辑器仓库引用列表尚未构建");
            }
            for (Object widget : listWidgets(list)) {
                if (areaId(widget, "entryData") == warehouseId) {
                    ButtonBase button = firstButton(widget);
                    if (button == null) {
                        throw new AssertionError("仓库引用条目没有移除按钮");
                    }
                    return toScreenPoint(client, button.getX() + button.getWidth() / 2.0,
                            button.getY() + button.getHeight() / 2.0);
                }
            }
            throw new AssertionError("未找到真实仓库引用条目的移除按钮");
        });
        clickAt(ctx, point);
    }

    private static void clickAt(ClientGameTestContext ctx, double[] point) {
        ctx.getInput().setCursorPos(point[0], point[1]);
        ctx.getInput().holdMouse(0);
        ctx.waitTicks(2);
        ctx.getInput().releaseMouse(0);
    }

    private static double[] toScreenPoint(Minecraft client, double guiX, double guiY) {
        var window = client.getWindow();
        double screenX = guiX * window.getScreenWidth() / (double) window.getGuiScaledWidth();
        double screenY = guiY * window.getScreenHeight() / (double) window.getGuiScaledHeight();
        if (screenX < 0 || screenY < 0
                || screenX > window.getScreenWidth() || screenY > window.getScreenHeight()) {
            throw new AssertionError("目标控件超出窗口范围: gui=" + guiX + "," + guiY
                    + " screen=" + screenX + "," + screenY);
        }
        return new double[]{screenX, screenY};
    }

    private static int areaId(Object widget, String fieldName) {
        Object value = field(widget, fieldName);
        return value instanceof StagingAreaConfigResponseS2CPacket.AreaInfo info ? info.areaId() : -1;
    }

    private static List<?> listWidgets(Object listWidget) {
        Object value = field(listWidget, "listWidgets");
        return value instanceof List<?> widgets ? widgets : List.of();
    }

    private static ButtonBase firstButton(Object widget) {
        Object value = field(widget, "subWidgets");
        if (!(value instanceof List<?> widgets)) return null;
        for (Object child : widgets) {
            if (child instanceof ButtonBase button) {
                return button;
            }
        }
        return null;
    }

    private static boolean noPendingSession() {
        ProjectViewClient session = ProjectViewClient.current().orElse(null);
        if (session == null) return false;
        return pendingKeys(session, "pendingOpens").isEmpty()
                && pendingKeys(session, "pendingOperations").isEmpty();
    }

    private static Observation observe() {
        ProjectViewClient session = ProjectViewClient.current()
                .orElseThrow(() -> new AssertionError("缺少 ProjectViewClient 会话"));
        return new Observation(nextRequestId(),
                pendingKeys(session, "pendingOpens"),
                pendingKeys(session, "pendingOperations"));
    }

    private static Set<Long> pendingKeys(ProjectViewClient session, String fieldName) {
        Object value = field(session, fieldName);
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError("ProjectViewClient." + fieldName + " 不是 Map");
        }
        Set<Long> keys = new HashSet<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof Long requestId)) {
                throw new AssertionError("ProjectViewClient." + fieldName + " 存在非 long 请求号");
            }
            keys.add(requestId);
        }
        return Set.copyOf(keys);
    }

    private static long nextRequestId() {
        try {
            Field field = TeamManagementClient.class.getDeclaredField("NEXT_REQUEST_ID");
            field.setAccessible(true);
            return ((AtomicLong) field.get(null)).get();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取客户端请求号计数器", failure);
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError("无法读取字段 " + name, failure);
            }
        }
        throw new AssertionError("缺少字段 " + name + " on " + target.getClass().getName());
    }

    private static void requireNoPending(Observation observation, String phase) {
        if (!observation.opens().isEmpty() || !observation.lists().isEmpty()) {
            throw new AssertionError(phase + "仍有未完成请求: " + observation);
        }
    }

    private static void requireOpenBeforeList(Observation baseline, Observation after, String label) {
        if (after.counter() != baseline.counter() + 2) {
            throw new AssertionError(label + " 应只新增 OPEN+LIST 两个请求号，实际 "
                    + baseline.counter() + " -> " + after.counter());
        }
        if (after.opens().size() != 1 || after.lists().size() != 1) {
            throw new AssertionError(label + " 应各有一个 OPEN/LIST，实际 " + after);
        }
        long open = after.opens().iterator().next();
        long list = after.lists().iterator().next();
        if (open >= list) {
            throw new AssertionError(label + " 必须先发 global OPEN 再发 LIST: open=" + open
                    + ", list=" + list);
        }
    }

    private static boolean waitFor(ClientGameTestContext ctx, Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 300; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }

    private record Observation(long counter, Set<Long> opens, Set<Long> lists) {
    }
}
