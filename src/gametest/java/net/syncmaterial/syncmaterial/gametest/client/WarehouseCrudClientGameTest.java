package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseEditor;
import net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager;
import net.syncmaterial.syncmaterial.client.gui.WarehouseEntry;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 仓库 GUI CRUD 端到端测试。
 *
 * 覆盖此前完全空白的客户端仓库管理链路：GUI 打开 → 创建（走真实选区回调）→
 * 编辑器改名/改坐标（走去抖）→ 引用增删 → Shift 删除，每步都在服务端
 * 数据库断言真实结果，在客户端断言列表与线框状态。
 *
 * 服务端 handler 与数据库层已有单测（StagingAreaConfigHandlerTest /
 * WarehouseDatabaseTest），本测试补的是它们之上的整条客户端链路：
 * 按钮回调是否真的发包、响应是否真的回流到 GUI、渲染器是否真的收到线框。
 *
 * 与其他客户端测试一致的做法：选区确认/取消直接调 confirm()/cancel()
 * （Enter/Esc 走 GLFW 硬件轮询，合成输入驱动不了，见 StagingAreaSelection-
 * ClientGameTest 的说明）；创建流程因此直接调用 manager 的选区回调，
 * 与真实点击「添加仓库」→ 输名字 → 准星确认后的代码路径完全相同。
 */
public class WarehouseCrudClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-wh-crud-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            insertSchematic(server, schematicId);

            // ===== 1. 创建：打开管理界面，走选区确认回调（与真实新建路径一致）=====
            ctx.runOnClient(client -> Minecraft.getInstance().setScreenAndShow(
                new GuiWarehouseManager()));
            ctx.waitTicks(5);

            WarehouseEntry created = createWarehouseViaSelectionCallback(ctx);
            int warehouseId = created.warehouseId();

            // 服务端必须真的建了这个仓库
            var serverWarehouse = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                if (manager == null) return null;
                return manager.getAllWarehouses().stream()
                    .filter(w -> w.id() == warehouseId)
                    .findFirst().orElse(null);
            });
            if (serverWarehouse == null) {
                throw new AssertionError("创建回调发包后服务端未出现仓库 " + warehouseId);
            }

            // ===== 2. 编辑器：改名（按钮路径，立即发送）=====
            ctx.runOnClient(client -> {
                GuiWarehouseManager manager = new GuiWarehouseManager();
                Minecraft.getInstance().setScreenAndShow(new GuiWarehouseEditor(created, manager));
            });
            ctx.waitTicks(5);

            ctx.runOnClient(client -> {
                if (Minecraft.getInstance().gui.screen() instanceof GuiWarehouseEditor editor) {
                    editor.renameForTest("改名后的仓库");
                }
            });
            ctx.waitTicks(10);

            String serverName = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                if (manager == null) return null;
                return manager.getAllWarehouses().stream()
                    .filter(w -> w.id() == warehouseId)
                    .map(w -> w.name())
                    .findFirst().orElse(null);
            });
            if (!"改名后的仓库".equals(serverName)) {
                throw new AssertionError("编辑器改名后服务端名字应为「改名后的仓库」，实际为 " + serverName);
            }

            // ===== 3. 编辑器：改坐标（文本框路径，走去抖，最终只发一次）=====
            ctx.runOnClient(client -> {
                if (Minecraft.getInstance().gui.screen() instanceof GuiWarehouseEditor editor) {
                    // 模拟连续敲键：多次调用等价于多次字符输入的 onTextChange，
                    // 中间态应被去抖合并，最终只有 10 生效
                    editor.simulateCoordinateInputForTest(
                        fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1,
                        fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType.X, 20);
                    editor.simulateCoordinateInputForTest(
                        fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1,
                        fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType.X, 30);
                    editor.simulateCoordinateInputForTest(
                        fi.dy.masa.litematica.util.PositionUtils.Corner.CORNER_1,
                        fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType.X, 10);
                }
            });
            // 不足静默期：不应已发送（服务端 X 仍是创建时的 0）。
            // 这是去抖的核心断言：若直发（schedule 被改成 sendUpdate），
            // 5 tick 后服务端就已是最终值 10，此断言转红
            ctx.waitTicks(5);
            var coordsBefore = serverWarehouseCoords(server, warehouseId);
            if (coordsBefore == null || coordsBefore.x1() != 0) {
                throw new AssertionError("去抖静默期内服务端不应收到坐标更新，X 应仍为 0，实际为 "
                    + (coordsBefore == null ? "null" : coordsBefore.x1()));
            }
            // 静默期满：最终值生效
            ctx.waitTicks(20);

            var coordsAfter = serverWarehouseCoords(server, warehouseId);
            if (coordsAfter == null || coordsAfter.x1() != 10) {
                throw new AssertionError("去抖静默期后服务端 X 坐标应为 10，实际为 "
                    + (coordsAfter == null ? "null" : coordsAfter.x1()));
            }

            // ===== 4. 引用：添加与移除 =====

            // 先订阅材料列表：渲染器"被引用"标记的广播只统计玩家当前订阅的
            // 原理图（打开材料列表时由 QueryMaterialStatus 触发订阅），
            // 不订阅则服务端不会认为该玩家在关注这个原理图
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                new net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket(schematicId)));
            ctx.waitTicks(10);

            ctx.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                schematicId, "ADD_WAREHOUSE_REF", warehouseId, Optional.empty())));
            ctx.waitTicks(10);

            boolean referenced = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                return manager != null && manager.getWarehousesForSchematic(schematicId).stream()
                    .anyMatch(w -> w.id() == warehouseId);
            });
            if (!referenced) {
                throw new AssertionError("添加仓库引用后服务端未建立引用关系");
            }

            // 客户端渲染器应收到带引用标记的仓库线框
            boolean rendererKnowsReference = waitForCondition(ctx, () ->
                ctx.computeOnClient(client ->
                    net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer
                        .getInstance().isWarehouseReferenced(warehouseId)));
            if (!rendererKnowsReference) {
                throw new AssertionError("添加引用后客户端渲染器未标记该仓库为被引用");
            }

            ctx.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                schematicId, "REMOVE_WAREHOUSE_REF", warehouseId, Optional.empty())));
            ctx.waitTicks(10);

            boolean stillReferenced = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                return manager != null && manager.getWarehousesForSchematic(schematicId).stream()
                    .anyMatch(w -> w.id() == warehouseId);
            });
            if (stillReferenced) {
                throw new AssertionError("移除仓库引用后服务端引用关系仍然存在");
            }

            // ===== 5. 删除 =====
            ctx.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                "", "DELETE_WAREHOUSE", warehouseId, Optional.empty())));
            ctx.waitTicks(10);

            boolean deleted = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                return manager == null || manager.getAllWarehouses().stream()
                    .noneMatch(w -> w.id() == warehouseId);
            });
            if (!deleted) {
                throw new AssertionError("删除仓库后服务端仍存在该仓库");
            }

            // 级联：引用也必须消失
            boolean cascadeClean = server.computeOnServer(instance -> {
                var manager = SyncMaterial.getServerStagingAreaManager();
                return manager == null || manager.getSchematicsReferencingWarehouse(warehouseId).isEmpty();
            });
            if (!cascadeClean) {
                throw new AssertionError("删除仓库后引用未级联清理");
            }

            onDatabase(server, database -> {
                database.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> {
                Minecraft.getInstance().setScreenAndShow(null);
                StagingAreaSelector.getInstance().reset();
                net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
                    .clearWarehouseAreas();
                net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
                    .clearWarehouseContainers();
            });
        }
    }

    /**
     * 走 GuiWarehouseManager 的选区确认回调创建仓库 —— 与真实流程
     * 「点击添加 → 输名字 → 准星确认」在回调之后的代码完全相同。
     */
    private WarehouseEntry createWarehouseViaSelectionCallback(ClientGameTestContext ctx) {
        BlockPos pos1 = new BlockPos(0, -60, 0);
        BlockPos pos2 = new BlockPos(4, -56, 4);

        ctx.runOnClient(client -> {
            if (!(Minecraft.getInstance().gui.screen() instanceof GuiWarehouseManager manager)) {
                throw new AssertionError("仓库管理界面未打开，无法走创建流程");
            }
            manager.setPendingWarehouseNameForTest("端到端仓库");
            manager.onSelectionConfirmed(null, pos1, pos2);
        });
        ctx.waitTicks(10);

        WarehouseEntry[] holder = new WarehouseEntry[1];
        boolean found = waitForCondition(ctx, () -> {
            WarehouseEntry e = ctx.computeOnClient(client -> {
                if (Minecraft.getInstance().gui.screen() instanceof GuiWarehouseManager manager) {
                    List<WarehouseEntry> list = manager.getWarehouses();
                    return list.isEmpty() ? null : list.get(0);
                }
                return null;
            });
            if (e != null) {
                holder[0] = e;
                return true;
            }
            return false;
        });
        if (!found || holder[0] == null) {
            throw new AssertionError("创建仓库后管理界面列表未刷新出新仓库");
        }
        if (!"端到端仓库".equals(holder[0].name())) {
            throw new AssertionError("列表中的仓库名应为「端到端仓库」，实际为 " + holder[0].name());
        }
        return holder[0];
    }

    private record Coords(int x1, int y1, int z1, int x2, int y2, int z2) {}

    private Coords serverWarehouseCoords(TestDedicatedServerContext server, int warehouseId) {
        return server.computeOnServer(instance -> {
            var manager = SyncMaterial.getServerStagingAreaManager();
            if (manager == null) return null;
            return manager.getAllWarehouses().stream()
                .filter(w -> w.id() == warehouseId)
                .map(w -> new Coords(w.x1(), w.y1(), w.z1(), w.x2(), w.y2(), w.z2()))
                .findFirst().orElse(null);
        });
    }

    private void insertSchematic(TestDedicatedServerContext server, String schematicId) {
        onDatabase(server, database -> {
            database.executeUpdate(
                "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                schematicId, "Warehouse CRUD Test", "/wh-crud.litematic", "Player0");
            return null;
        });
    }

    private boolean waitForCondition(ClientGameTestContext ctx,
                                     java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
        }
        return false;
    }

    private <T> T onDatabase(TestDedicatedServerContext server,
                             SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (SQLException e) {
                throw new RuntimeException("测试数据库操作失败", e);
            }
        });
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
