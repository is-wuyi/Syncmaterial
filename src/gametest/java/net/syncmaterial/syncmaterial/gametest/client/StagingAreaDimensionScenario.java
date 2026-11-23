package net.syncmaterial.syncmaterial.gametest.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.selection.AreaSelection;

/**
 * 真实维度传送的编辑/选区端到端场景（beta.61 合同 7）。
 *
 * <p>全部走生产路径：真实编辑器与「添加备货区」按钮、真实文本输入与 Enter 提交、
 * 真实左/右键设点（crosshair lookAt + holdMouse）、真实服务端 {@code ServerPlayer#teleportTo}
 * 跨维度。每个阶段都查询各自生产仓储断言：换维度丢弃的旧选区没有落库，
 * 新维度里合法重选的区域确实写入（world=minecraft:the_nether）。不用
 * {@code selector.dimensionChanged()} 之类的直接调用冒充传送。</p>
 *
 * <p>legacy 与 Foundation 后端只提供各自的真实夹具与权威读取，
 * GUI 与网络路径完全共用。</p>
 */
public final class StagingAreaDimensionScenario {
    private static final String FIRST_AREA = "区域甲";
    private static final String SECOND_AREA = "区域乙";

    private StagingAreaDimensionScenario() {}

    /** Backend-specific fixture creation and authoritative reads. */
    public interface Backend {
        void prepare(TestDedicatedServerContext server, String projectId);

        /** Authoritative project staging-area rows, ordered by id. */
        List<AreaRow> projectAreas(TestDedicatedServerContext server, String projectId);

        void cleanup(TestDedicatedServerContext server, String projectId);
    }

    /** Authoritative row projection of one project staging area. */
    public record AreaRow(int id, String name, String world,
                          int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    public static void run(ClientGameTestContext ctx, Backend backend) {
        String projectId = "client-dim-" + System.nanoTime();
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
            try {
                ctx.waitTicks(20);
                backend.prepare(server, projectId);
                if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                        ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS)))) {
                    throw new AssertionError("客户端未获得协议 5 视图能力");
                }

                // ===== 1. 生产编辑器打开，LIST 同步、项目视图建立 =====
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
                                .flatMap(store -> store.configuration("LIST"))
                                .isPresent()))) {
                    throw new AssertionError("编辑器未完成项目视图/LIST 同步");
                }

                // ===== 2. 第一次选区：真实按钮 → 真实文本输入 → 真实左右键 → Enter 落库 =====
                BlockPos[] initialCorners = startSelectionViaAddButton(ctx, editor, FIRST_AREA);
                requireNoInitialCorners(initialCorners);
                BlockPos overworldPos1 = setCornerByRealClick(ctx, server, true);
                BlockPos overworldPos2 = setCornerByRealClick(ctx, server, false);
                ctx.getInput().pressKey(257); // Enter 确认
                if (!waitFor(ctx, () -> hasArea(backend, server, projectId, FIRST_AREA,
                        overworldPos1, overworldPos2, "minecraft:overworld"))) {
                    throw new AssertionError("主世界合法选区确认后未按所选坐标落库: "
                            + areasToString(backend, server, projectId)
                            + " 期望 pos1=" + overworldPos1 + " pos2=" + overworldPos2);
                }

                // ===== 3. 第二次选区进行中真实传送：旧角点必须丢弃且不落库 =====
                startSelectionViaAddButton(ctx, editor, SECOND_AREA);
                setCornerByRealClick(ctx, server, true);
                teleportToNether(server);
                carveChamberAroundPlayer(server);
                if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                        client.player != null
                                && "minecraft:the_nether".equals(McCompat.dimensionId(client.player.level()))
                                && !StagingAreaSelector.getInstance().isActive()))) {
                    throw new AssertionError("真实维度传送后选区未被丢弃（换维度围栏失效）");
                }
                ctx.waitTicks(40);
                if (hasAreaNamed(backend, server, projectId, SECOND_AREA)) {
                    throw new AssertionError("换维度丢弃的旧选区被写入生产仓储（旧坐标落库）: "
                            + areasToString(backend, server, projectId));
                }

                // ===== 4. 新维度主动重选：合法写入 world=minecraft:the_nether =====
                GuiStagingAreaEditorNormal reopened = ctx.computeOnClient(client -> {
                    GuiStagingAreaEditorNormal opened = new GuiStagingAreaEditorNormal(
                            new AreaSelection(), null, projectId);
                    GuiBase.openGui(opened);
                    return opened;
                });
                if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                        ClientScreenAccess.currentScreen(client) == reopened))) {
                    throw new AssertionError("传送后重新打开编辑器失败");
                }
                startSelectionViaAddButton(ctx, reopened, SECOND_AREA);
                BlockPos netherPos1 = setCornerByRealClick(ctx, server, true);
                BlockPos netherPos2 = setCornerByRealClick(ctx, server, false);
                ctx.getInput().pressKey(257);
                if (!waitFor(ctx, () -> hasArea(backend, server, projectId, SECOND_AREA,
                        netherPos1, netherPos2, "minecraft:the_nether"))) {
                    throw new AssertionError("新维度合法重选未按所选坐标落库: "
                            + areasToString(backend, server, projectId)
                            + " 期望 pos1=" + netherPos1 + " pos2=" + netherPos2);
                }
            } finally {
                // GUI/选区状态在客户端线程清理；数据库清理在服务器关闭前执行
                ctx.runOnClient(client -> {
                    ClientScreenAccess.showScreen(client, null);
                    StagingAreaSelector.getInstance().reset();
                });
                backend.cleanup(server, projectId);
            }
        }
    }

    /** 真实点击「添加备货区」按钮 → 文本弹窗输入名称 → Enter → 选区激活。 */
    private static BlockPos[] startSelectionViaAddButton(ClientGameTestContext ctx,
                                                         GuiStagingAreaEditorNormal editor,
                                                         String areaName) {
        double[] buttonPoint = ctx.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != editor) {
                throw new AssertionError("点击添加按钮前编辑器不是当前界面");
            }
            ButtonBase button = findButton(editor,
                    StringUtils.translate("syncmaterial.gui.button.add_staging_area"));
            if (button == null) {
                throw new AssertionError("编辑器上没有「添加备货区」生产按钮");
            }
            return toScreenPoint(client, button.getX() + button.getWidth() / 2.0,
                    button.getY() + button.getHeight() / 2.0);
        });
        clickAt(ctx, buttonPoint);
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                ClientScreenAccess.currentScreen(client) instanceof GuiTextInput))) {
            throw new AssertionError("点击添加按钮后名称弹窗未打开");
        }

        ctx.getInput().typeChars(areaName);
        ctx.getInput().pressKey(257); // Enter 提交名称 → 生产 SubRegionCreator 启动选区
        if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                StagingAreaSelector.getInstance().isActive()
                        && ClientScreenAccess.currentScreen(client) == null))) {
            throw new AssertionError("名称提交后选区未激活（生产新建路径断裂）");
        }

        return ctx.computeOnClient(client -> new BlockPos[] {
                StagingAreaSelector.getInstance().getPos1(),
                StagingAreaSelector.getInstance().getPos2(),
        });
    }

    /**
     * 真实设点：客户端 lookAt 服务端预置的方块，holdMouse 左/右键走真实输入管线。
     *
     * @param leftClick true 为左键（pos1），false 为右键（pos2）
     */
    private static BlockPos setCornerByRealClick(ClientGameTestContext ctx,
                                                 TestDedicatedServerContext server,
                                                 boolean leftClick) {
        BlockPos target = prepareAimingBlock(server, leftClick);
        ctx.computeOnClient(client -> {
            if (client.player == null) {
                throw new AssertionError("玩家不存在，无法进行真实点击");
            }
            // 服务器在玩家前方 2 格预置了石头；视线对准方块中心后由真实鼠标键设点
            client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
            return null;
        });
        ctx.waitTicks(3);
        int mouseButton = leftClick ? 0 : 1;
        ctx.getInput().holdMouse(mouseButton);
        ctx.waitTicks(2);
        ctx.getInput().releaseMouse(mouseButton);
        ctx.waitTicks(2);

        BlockPos corner = ctx.computeOnClient(client -> leftClick
                ? StagingAreaSelector.getInstance().getPos1()
                : StagingAreaSelector.getInstance().getPos2());
        if (!target.equals(corner)) {
            throw new AssertionError((leftClick ? "左键 pos1" : "右键 pos2") + "未命中预置方块: 期望 "
                    + target + " 实际 " + corner + "（真实左右键输入路径失效）");
        }
        return corner;
    }

    /** 在玩家脚下平面预置瞄准方块：leftClick → 前方 2 格，右键 → 右侧 2 格。 */
    private static BlockPos prepareAimingBlock(TestDedicatedServerContext server, boolean forward) {
        return server.computeOnServer(instance -> {
            ServerPlayer player = instance.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level == null) {
                throw new AssertionError("玩家不在 ServerLevel 中");
            }
            BlockPos feet = player.blockPosition();
            BlockPos target = forward
                    ? new BlockPos(feet.getX() + 2, feet.getY(), feet.getZ())
                    : new BlockPos(feet.getX(), feet.getY(), feet.getZ() + 2);
            level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            return target;
        });
    }

    /**
     * 在玩家当前位置挖出确定性气室（石头地板 + 3 格高空气），让真实左右键的
     * crosshair 射线必然命中预置方块，不依赖任何维度的地形生成。
     */
    private static void carveChamberAroundPlayer(TestDedicatedServerContext server) {
        server.runOnServer(instance -> {
            ServerPlayer player = instance.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level == null) {
                throw new AssertionError("玩家不在 ServerLevel 中");
            }
            BlockPos feet = player.blockPosition();
            for (int dx = -1; dx <= 3; dx++) {
                for (int dz = -1; dz <= 3; dz++) {
                    level.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    for (int dy = 0; dy <= 2; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        });
    }

    /** 真实服务端传送：不做任何客户端 selector 直调，维度围栏由生产 tick 观察触发。 */
    private static void teleportToNether(TestDedicatedServerContext server) {
        server.runOnServer(instance -> {
            ServerPlayer player = instance.getPlayerList().getPlayers().get(0);
            ServerLevel nether = instance.getLevel(Level.NETHER);
            if (nether == null) {
                throw new AssertionError("下界维度不存在");
            }
            // 确定性平台：固定高度气室 + 石头地板，不依赖下界地形
            int baseY = 200;
            for (int dx = -1; dx <= 3; dx++) {
                for (int dz = -1; dz <= 3; dz++) {
                    nether.setBlock(new BlockPos(100 + dx, baseY - 1, 100 + dz),
                            Blocks.STONE.defaultBlockState(), 3);
                    for (int dy = 0; dy <= 2; dy++) {
                        nether.setBlock(new BlockPos(100 + dx, baseY + dy, 100 + dz),
                                Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            player.teleportTo(nether, 100.5, baseY, 100.5, java.util.Set.of(), 0f, 0f, true);
        });
    }

    private static void requireNoInitialCorners(BlockPos[] captured) {
        if (captured[0] != null || captured[1] != null) {
            throw new AssertionError("选区启动不得带入初始角点（新建流程 pos1/pos2 应为空）: "
                    + captured[0] + " / " + captured[1]);
        }
    }

    private static boolean hasArea(Backend backend, TestDedicatedServerContext server,
                                   String projectId, String name, BlockPos pos1, BlockPos pos2,
                                   String world) {
        List<AreaRow> rows = backend.projectAreas(server, projectId);
        for (AreaRow row : rows) {
            if (name.equals(row.name()) && world.equals(row.world())
                    && row.x1() == pos1.getX() && row.y1() == pos1.getY() && row.z1() == pos1.getZ()
                    && row.x2() == pos2.getX() && row.y2() == pos2.getY() && row.z2() == pos2.getZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAreaNamed(Backend backend, TestDedicatedServerContext server,
                                        String projectId, String name) {
        return backend.projectAreas(server, projectId).stream().anyMatch(row -> name.equals(row.name()));
    }

    private static String areasToString(Backend backend, TestDedicatedServerContext server, String projectId) {
        return backend.projectAreas(server, projectId).toString();
    }

    // ========== 真实输入辅助（与 WarehouseRefPopupLifecycleScenario 同一模式） ==========

    private static ButtonBase findButton(GuiStagingAreaEditorNormal editor, String label) {
        Object value = field(editor, "buttons");
        if (!(value instanceof List<?> buttons)) {
            throw new AssertionError("无法读取编辑器按钮列表");
        }
        for (Object button : buttons) {
            if (button instanceof ButtonBase base && label.equals(buttonDisplayString(base))) {
                return base;
            }
        }
        return null;
    }

    /** MaLiLib ButtonBase 的显示文本是 protected 字段，测试经反射按标签定位生产按钮。 */
    private static String buttonDisplayString(ButtonBase button) {
        Object value = field(button, "displayString");
        return value instanceof String text ? text : "";
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

    private static boolean waitFor(ClientGameTestContext ctx, Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 300; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError("无法读取字段 " + name, failure);
            }
        }
        throw new AssertionError("缺少字段 " + name + " on " + target.getClass().getName());
    }
}
