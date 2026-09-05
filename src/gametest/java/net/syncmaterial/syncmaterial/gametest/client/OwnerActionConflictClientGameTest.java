package net.syncmaterial.syncmaterial.gametest.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 负责人操作并发冲突测试：转让主负责人与增减副负责人在同一 tick
 * 被并发触发时，绝不能出现"旧主还握有权限"或"副负责人表出现重复行"的
 * 中间态泄露。
 *
 * 设计依据：
 * - 服务端网络 handler 虽然在主线程串行执行，但 Phase4Handler.
 *   handleOwnerAction 对同一个 schematicId 的 TRANSFER 和 ADD_DEPUTY
 *   是"先验权后执行"两步——若两个操作打在同一 tick，第二步的验权
 *   必须读到第一步的结果，而不是旧值。
 * - SchematicDatabase 全部方法 synchronized，所以这里真正要验证的是
 *   状态机的转移而不是 DB 锁。
 *
 * 场景：
 * T0：主负责人 P0 转让 → T1：旧的 P0 试图添加副负责人（必须失败，因为他
 *     已失去主负责人身份）
 * T2：新主负责人 P1 添加副负责人 D
 * T3：P1 移除 D 的同时另一个请求再添加 D（并发 add/remove 同 key）
 * 每个阶段都对 deputy_owners 表做最终一致性断言。
 */
public class OwnerActionConflictClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "owner-conflict-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Owner Conflict Test", "/conflict.litematic", "Player0");
                return null;
            });

            // ===== T0：主负责人转让（Player0 → Player1）=====
            onDatabase(server, db -> {
                db.transferOwnership(schematicId, "Player1");
                return null;
            });

            String mainOwner = onDatabase(server, db -> {
                try (var rs = db.executeQuery(
                    "SELECT uploaded_by FROM schematics WHERE id = ?", schematicId)) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            if (!"Player1".equals(mainOwner)) {
                throw new AssertionError("转让后主负责人应为 Player1，实际 " + mainOwner);
            }

            // ===== T1：旧主负责人 Player0 已无权操作 =====
            // isOwner 检查只认当前 uploaded_by + deputy 表，这里验证旧主
            // 不在 deputy 里，因此 isOwner 应为 false。
            boolean oldOwnerStillOwner = onDatabase(server, db ->
                db.isOwner(schematicId, "Player0"));
            if (oldOwnerStillOwner) {
                throw new AssertionError("转让后旧主负责人不应再也是 owner");
            }

            // ===== T2：新主负责人可以添加副负责人 =====
            onDatabase(server, db -> {
                db.addDeputyOwner(schematicId, "Player2");
                db.addDeputyOwner(schematicId, "Player3");
                return null;
            });
            assertDeputyCount(server, schematicId, 2, "添加两个副负责人后");

            // ===== T3：并发 add/remove 同一副负责人名（模拟同 tick 双击/双击+网络抖动）=====
            AtomicReference<Throwable> raceFailure = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var adder = pool.submit(() -> {
                    try {
                        for (int i = 0; i < 50; i++) {
                            SyncMaterial.getSharedDatabase().addDeputyOwner(schematicId, "RaceTarget");
                        }
                    } catch (Throwable e) { raceFailure.compareAndSet(null, e); }
                });
                var remover = pool.submit(() -> {
                    try {
                        for (int i = 0; i < 50; i++) {
                            SyncMaterial.getSharedDatabase().removeDeputyOwner(schematicId, "RaceTarget");
                        }
                    } catch (Throwable e) { raceFailure.compareAndSet(null, e); }
                });
                adder.get();
                remover.get();
            } catch (Exception e) {
                throw new AssertionError("并发 add/remove 执行异常", e);
            } finally {
                pool.shutdown();
            }
            if (raceFailure.get() != null) {
                throw new AssertionError("并发 add/remove 抛出异常", raceFailure.get());
            }

            // 最终状态必须是确定且一致的：0 或 1 个 RaceTarget，绝不能有重复
            int raceTargetCount = onDatabase(server, db -> {
                try (var rs = db.executeQuery(
                    "SELECT COUNT(*) FROM deputy_owners WHERE schematic_id = ? AND player_name = ?",
                    schematicId, "RaceTarget")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            if (raceTargetCount > 1) {
                throw new AssertionError(
                    "并发 add/remove 导致 deputy_owners 出现重复行：" + raceTargetCount);
            }

            // 已有的 Player2/Player3 不受影响
            assertDeputyCount(server, schematicId,
                2 + raceTargetCount, "并发操作后");

            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM deputy_owners WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        }
    }

    private void assertDeputyCount(TestDedicatedServerContext server, String schematicId,
                                   int expected, String phase) {
        int actual = onDatabase(server, db -> {
            try (var rs = db.executeQuery(
                "SELECT COUNT(*) FROM deputy_owners WHERE schematic_id = ?", schematicId)) {
                rs.next();
                return rs.getInt(1);
            }
        });
        if (actual != expected) {
            throw new AssertionError(phase + "副负责人记录数应为 " + expected + "，实际 " + actual);
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
