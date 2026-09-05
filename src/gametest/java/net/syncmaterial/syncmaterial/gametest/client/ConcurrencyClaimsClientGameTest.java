package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.network.Phase4Handler;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * 并发认领端到端测试：多个玩家同时认领同一材料时，
 * claims 表绝不能出现重复行、广播绝不能漏人。
 *
 * 设计依据（调研结论）：
 * - joinCollaboration 走 `INSERT OR IGNORE` + claims 表 UNIQUE 索引，
 *   SchematicDatabase 所有方法 synchronized —— 理论幂等，但此前从没有被
 *   真并发压过。这里用 4 个线程交替 join 两个假人、各自 40 次。
 * - 服务端网络 handler 在主线程串行执行，但 joinCollaboration 本身是
 *   public 且被多处调用，并发安全必须在实现层成立，不能只靠调用点收敛。
 *
 * 场景 A（并发 join 幂等）：两个假人各被并发 join 40 次 →
 *   claims 恰好每人 1 条（不出现重复行）。
 * 场景 B（交错 join/leave）：一个线程循环 join、另一个循环 leave →
 *   只断言"无异常 + 不出现重复行"（最终状态依赖线程调度，不锁死）。
 * 场景 C（广播完整性）：放平后触发一次全量广播 →
 *   两个假人都收到且参与者数为 2（不漏人、不重复）。
 */
public class ConcurrencyClaimsClientGameTest implements FabricClientGameTest {

    private static final String BOT_A = "ConcurrentBotA";
    private static final String BOT_B = "ConcurrentBotB";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "conc-claim-" + System.nanoTime();

        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {

            ctx.waitTicks(20);

            int materialId = onDatabase(server, db -> {
                db.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    schematicId, "Concurrency Test", "/conc.litematic", "Player0");
                db.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    schematicId, "minecraft:dirt", 64);
                try (var rs = db.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            MockBot botA = MockBot.spawn(server, BOT_A);
            MockBot botB = MockBot.spawn(server, BOT_B);
            ctx.waitTicks(10);

            // ===== 场景 A：4 线程并发 join，每人 40 次 =====
            AtomicReference<Throwable> joinFailure = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final String name = (t % 2 == 0) ? BOT_A : BOT_B;
                    futures.add(pool.submit(() -> {
                        try {
                            for (int i = 0; i < 40; i++) {
                                SyncMaterial.getSharedCollaborationManager()
                                    .joinCollaboration(schematicId, materialId, name);
                            }
                        } catch (Throwable e) {
                            joinFailure.compareAndSet(null, e);
                        }
                    }));
                }
                for (var f : futures) f.get();
            } catch (Exception e) {
                throw new AssertionError("并发 join 执行器异常", e);
            }
            if (joinFailure.get() != null) {
                throw new AssertionError("并发 join 抛出异常（数据库/状态被写坏）", joinFailure.get());
            }

            assertClaimCount(server, schematicId, BOT_A, 1, "并发 join 后 BotA 应恰好 1 条 claim");
            assertClaimCount(server, schematicId, BOT_B, 1, "并发 join 后 BotB 应恰好 1 条 claim");

            // ===== 场景 B：join/leave 交错并发（只锁无异常 + 无重复行）=====
            AtomicReference<Throwable> mixFailure = new AtomicReference<>();
            try {
                var f1 = pool.submit(() -> {
                    try {
                        for (int i = 0; i < 30; i++) {
                            SyncMaterial.getSharedCollaborationManager()
                                .joinCollaboration(schematicId, materialId, BOT_A);
                        }
                    } catch (Throwable e) { mixFailure.compareAndSet(null, e); }
                });
                var f2 = pool.submit(() -> {
                    try {
                        for (int i = 0; i < 30; i++) {
                            SyncMaterial.getSharedCollaborationManager()
                                .leaveCollaboration(schematicId, materialId, BOT_A);
                        }
                    } catch (Throwable e) { mixFailure.compareAndSet(null, e); }
                });
                f1.get();
                f2.get();
            } catch (Exception e) {
                throw new AssertionError("并发 join/leave 执行器异常", e);
            }
            if (mixFailure.get() != null) {
                throw new AssertionError("并发 join/leave 抛出异常", mixFailure.get());
            }
            int dupCheck = onDatabase(server, db -> {
                try (var rs = db.executeQuery(
                    "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND player_name = ? AND status = 'active'",
                    schematicId, BOT_A)) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            if (dupCheck > 1) {
                throw new AssertionError("并发 join/leave 后出现重复 claim 行：" + dupCheck);
            }

            // 放平：确保两个假人都在协作组，供广播断言
            onDatabase(server, db -> {
                SyncMaterial.getSharedCollaborationManager()
                    .joinCollaboration(schematicId, materialId, BOT_A);
                return null;
            });

            // ===== 场景 C：全量广播不漏人 =====
            server.computeOnServer(s -> {
                Phase4Handler.broadcastAllMaterialStatus(s, schematicId);
                return null;
            });
            ctx.waitTicks(10);

            int aCount = botA.latestParticipantCount(materialId);
            if (aCount != 2) {
                throw new AssertionError("BotA 收到广播的参与者数应为 2，实际 " + aCount);
            }
            int bCount = botB.latestParticipantCount(materialId);
            if (bCount != 2) {
                throw new AssertionError("BotB 收到广播的参与者数应为 2，实际 " + bCount);
            }

            botA.despawn(server);
            botB.despawn(server);
            pool.shutdown();
            onDatabase(server, db -> {
                db.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", schematicId);
                db.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                return null;
            });
        }
    }

    private void assertClaimCount(TestDedicatedServerContext server, String schematicId,
                                  String player, int expected, String message) {
        int actual = onDatabase(server, db -> {
            try (var rs = db.executeQuery(
                "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND player_name = ? AND status = 'active'",
                schematicId, player)) {
                rs.next();
                return rs.getInt(1);
            }
        });
        if (actual != expected) {
            throw new AssertionError(message + "，实际 " + actual);
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
