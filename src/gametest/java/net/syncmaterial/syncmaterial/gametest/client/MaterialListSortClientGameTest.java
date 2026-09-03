package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.InventoryWatcher;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 表头排序点击端到端测试：点击不同列表头后列表必须按对应标准重排，
 * 同一列表头再点一次必须翻转方向。
 *
 * 点击路径（WidgetMaterialListEntry 的列命中 → criteriaForColumn 映射 →
 * setSortCriteria → refreshEntries）用 handleHeaderClick 模拟——操作同一
 * listWidget / materialList 的同一方法。列 → 标准映射本身由单测
 * MaterialListHeaderColumnTest 锁定。
 *
 * 排序器语义：reverse=false 时降序（大的在前），同 criteria 再点翻转。
 * 数据走服务端权威语义（GUI 打开后 requestCollaborationStatus 的状态包
 * 会覆盖构造器里按本地背包算的瞬间值）：
 * - dirt: total 30，Player0 经 claims + 背包上报链路收集 25 → missing 5
 * - stone: total 10，无人认领 → missing 10
 * - iron: total 20，无人认领 → missing 20
 * 总量降序 dirt, iron, stone；缺失降序 iron, stone, dirt。
 */
public class MaterialListSortClientGameTest implements FabricClientGameTest {

    private int dirtId;
    private int stoneId;
    private int ironId;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "sort-click-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Sort Click Test", "/sort-click.litematic", "Player0");
                return null;
            });

            // 先给背包 25 个泥土：开 GUI 后 setContext 清空 diff 快照，
            // InventoryWatcher 会检测到 +25 并走真实上报 → 聚合 → 广播链路
            server.runCommand("give @a minecraft:dirt 25");
            ctx.waitTicks(20);

            List<Integer> ids = onDatabase(server, db -> {
                insertMaterial(db, schematicId, "minecraft:dirt", 30);
                insertMaterial(db, schematicId, "minecraft:stone", 10);
                insertMaterial(db, schematicId, "minecraft:iron_ingot", 20);
                try (var rs = db.executeQuery("SELECT id, item_id FROM material_entries " +
                        "WHERE schematic_id = ? ORDER BY id", schematicId)) {
                    var result = new java.util.ArrayList<Integer>();
                    while (rs.next()) {
                        result.add(rs.getInt("id"));
                    }
                    return result;
                }
            });
            // 按插入顺序取回 rowid 对应关系
            dirtId = ids.get(0);
            stoneId = ids.get(1);
            ironId = ids.get(2);

            // 让 Player0 成为 dirt 的参与者：背包上报只有 isCollaborating
            // 通过才会被聚合进 participants 并广播
            onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO claims (schematic_id, material_id, player_name) VALUES (?, ?, ?)",
                    schematicId, dirtId, "Player0");
                return null;
            });

            ctx.runOnClient(client -> SyncMaterialClient.openMaterialListScreen(
                schematicId, "Sort Click Test",
                List.of(
                    new MaterialEntry(dirtId, new ItemStack(Items.DIRT), 30),
                    new MaterialEntry(stoneId, new ItemStack(Items.STONE), 10),
                    new MaterialEntry(ironId, new ItemStack(Items.IRON_INGOT), 20)),
                true, true, "Player0", List.of(), true));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitTicks(10);

            // ===== 等待稳定态：背包上报 → 服务端聚合 → 广播链路完成 =====
            waitForStableState(ctx);

            // ===== 初始：默认总量列，降序 dirt(30), iron(20), stone(10) =====
            assertOrder(ctx, "初始总量降序", List.of(dirtId, ironId, stoneId));

            // ===== 点缺失列：缺失降序 iron(20), stone(10), dirt(5) =====
            clickColumn(ctx, 2);
            assertOrder(ctx, "缺失降序", List.of(ironId, stoneId, dirtId));

            // ===== 同列再点：方向翻转（升序）=====
            clickColumn(ctx, 2);
            assertOrder(ctx, "缺失升序（翻转）", List.of(dirtId, stoneId, ironId));

            // ===== 换总量列：新标准方向重置，回到总量降序 =====
            clickColumn(ctx, 1);
            assertOrder(ctx, "切回总量降序", List.of(dirtId, ironId, stoneId));

            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        } finally {
            ctx.runOnClient(client -> {
                client.setScreen(null);
                InventoryWatcher.clearContext();
            });
        }
    }

    private void insertMaterial(SchematicDatabase db, String schematicId, String itemId, int count)
            throws java.sql.SQLException {
        db.executeUpdate(
            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
            schematicId, itemId, count);
    }

    private void clickColumn(ClientGameTestContext ctx, int column) {
        ctx.runOnClient(client -> {
            if (client.currentScreen instanceof GuiMaterialList gui) {
                gui.handleHeaderClick(column);
            } else {
                throw new AssertionError("材料列表未打开");
            }
        });
        ctx.waitTicks(2);
    }

    /**
     * 等待背包上报 → 服务端聚合 → 广播链路完成：dirt 的 available 达到 25。
     * GUI 打开瞬间的构造器值是本地背包快算，服务端状态包到达后会被覆盖。
     */
    private void waitForStableState(ClientGameTestContext ctx) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            int dirtAvail = ctx.computeOnClient(client -> {
                if (!(client.currentScreen instanceof GuiMaterialList gui)) {
                    throw new AssertionError("材料列表未打开");
                }
                for (MaterialListEntry e : gui.getMaterialList().getMaterialsAll()) {
                    if (e.getDatabaseId() == dirtId) return e.getCountAvailable();
                }
                return -1;
            });
            if (dirtAvail == 25) return;
            ctx.waitTicks(10);
        }
        throw new AssertionError("等待 dirt available=25 超时（背包上报→聚合→广播链路未完成）");
    }

    private void assertOrder(ClientGameTestContext ctx, String phase, List<Integer> expectedIds) {
        List<Integer> actual = ctx.computeOnClient(client -> {
            if (!(client.currentScreen instanceof GuiMaterialList gui)) {
                throw new AssertionError("材料列表未打开");
            }
            return gui.getCurrentEntryIds();
        });
        if (!expectedIds.equals(actual)) {
            throw new AssertionError(phase + ": 期望顺序 " + expectedIds + "，实际 " + actual);
        }
    }

    private <T> T onDatabase(TestDedicatedServerContext server,
                             SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (Exception e) {
                throw new RuntimeException("测试数据库操作失败", e);
            }
        });
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws Exception;
    }
}
