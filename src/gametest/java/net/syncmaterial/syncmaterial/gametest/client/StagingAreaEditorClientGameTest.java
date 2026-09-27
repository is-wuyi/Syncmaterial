package net.syncmaterial.syncmaterial.gametest.client;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 备货区编辑器回归：
 * 1. 编辑器从服务端加载后，getAreaServerId 返回真实数据库主键——重命名据此发包，
 *    不再把列表序号当 id 发出（此前会改到 id 相同的另一区域或静默失败）。
 * 2. drawContents 连续多帧后按钮数量保持稳定——「添加仓库引用」按钮已移到
 *    initGui 只建一次，不再每帧 new+addButton 无限增长。
 */
public class StagingAreaEditorClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-editor-" + System.nanoTime();
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
            ctx.waitFor(client -> ClientProtocolState.isUsable());

            onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Editor Test", "/editor.litematic", "Player0");
                return null;
            });
            int areaId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO staging_areas (schematic_id, world, name, x1, y1, z1, x2, y2, z2) " +
                    "VALUES (?, 'minecraft:overworld', 'EditorArea', 0, 64, 0, 1, 65, 1)",
                    schematicId);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            ctx.runOnClient(client ->
                fi.dy.masa.malilib.gui.GuiBase.openGui(
                    new GuiStagingAreaEditorNormal(new AreaSelection(), null, schematicId)));
            ctx.waitForScreen(GuiStagingAreaEditorNormal.class);

            // 等 LIST 响应回填服务端真实 ID
            boolean synced = waitForCondition(ctx, () -> ctx.computeOnClient(client -> {
                if (client.gui.screen() instanceof GuiStagingAreaEditorNormal editor) {
                    return Integer.valueOf(areaId).equals(editor.getAreaServerId("EditorArea"));
                }
                return false;
            }));
            if (!synced) {
                throw new AssertionError("编辑器未从服务端同步到真实备货区 ID（重命名会发错 id）");
            }

            // 连续多帧后按钮数量必须稳定（此前 drawContents 每帧新增按钮）
            int before = countButtons(ctx);
            ctx.waitTicks(40);
            int after = countButtons(ctx);
            if (after != before) {
                throw new AssertionError("编辑器渲染多帧后按钮数量从 " + before + " 变为 " + after
                    + "，drawContents 仍在每帧创建按钮");
            }

            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> client.setScreenAndShow(null));
        }
    }

    private int countButtons(ClientGameTestContext ctx) {
        return ctx.computeOnClient(client -> {
            if (!(client.gui.screen() instanceof GuiStagingAreaEditorNormal editor)) {
                throw new AssertionError("编辑器界面未打开");
            }
            try {
                Field f = fi.dy.masa.malilib.gui.GuiBase.class.getDeclaredField("buttons");
                f.setAccessible(true);
                return ((List<?>) f.get(editor)).size();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("无法读取 GuiBase.buttons: " + e.getMessage());
            }
        });
    }

    private boolean waitForCondition(ClientGameTestContext ctx, java.util.function.Supplier<Boolean> cond) {
        for (int i = 0; i < 60; i += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(cond.get())) {
                return true;
            }
        }
        return false;
    }

    private <T> T onDatabase(TestDedicatedServerContext server, SqlFunction<SchematicDatabase, T> action) {
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
