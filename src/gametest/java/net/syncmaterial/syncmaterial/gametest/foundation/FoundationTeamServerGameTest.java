package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.MaterialSnapshot;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectSnapshot;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.core.TeamOutcome;
import net.syncmaterial.syncmaterial.core.TeamState;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Real Foundation management scenarios against the live dedicated server runtime and
 * its real SQLite store. Async assertions are driven by server ticks through
 * {@link GameTestSequence}; no game-test thread ever blocks on a future that only
 * completes on the same server thread.
 *
 * <p>Pure DB fixtures ({@code ingest}, {@code reconcilePlayers}, {@code join},
 * {@code reportInventory}) are joined directly because they complete on the store
 * writer thread and never re-enter the server thread. Team commands are awaited by
 * ticks and then inspected.</p>
 */
public class FoundationTeamServerGameTest {
    @GameTest(structure = "empty", maxTicks = 400)
    public void ownerDeputyPermissionsAndMaterialIdsSurviveReimport(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = "team-owner-" + UUID.randomUUID();
        PlayerIdentity owner = player("Owner");
        PlayerIdentity deputy = player("Deputy");
        PlayerIdentity newOwner = player("NewOwner");
        PlayerIdentity outsider = player("Outsider");
        try {
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> cleanup(runtime, projectId));
            createProject(runtime, projectId, "owner-permissions", owner,
                    List.of(new MaterialCount("minecraft:stone", 64),
                            new MaterialCount("minecraft:dirt", 32)), "hash-one");
            runtime.store().reconcilePlayers(List.of(deputy, newOwner, outsider)).join();
            ProjectSnapshot before = snapshot(runtime, projectId);
            int stoneId = materialId(before, "minecraft:stone");
            int dirtId = materialId(before, "minecraft:dirt");

            GameTestSequence sequence = helper.startSequence();

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 1, TeamAction.ADD_DEPUTY,
                            List.of(), deputy), owner, () -> true),
                    "主负责人添加副负责人", outcome -> {
                        assertSuccess(outcome, "添加副负责人");
                        TeamState state = outcome.state();
                        assertTrue(state != null, "成功结果必须携带权威团队状态");
                        assertTrue(state.isMainOwner(owner), "原主负责人身份必须保留");
                        assertTrue(state.deputies().stream()
                                        .anyMatch(value -> deputy.uuid().equals(value.uuid())),
                                "副负责人必须以 UUID 持久化");
                    });

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 2, TeamAction.TOGGLE_SELF_CLAIM,
                            List.of()), deputy, () -> true),
                    "副负责人切换自行认领", outcome -> {
                        assertSuccess(outcome, "副负责人切换自行认领");
                        assertFalse(outcome.state().allowSelfClaim(), "开关必须真实翻转");
                    });

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 3, TeamAction.TRANSFER,
                            List.of(), newOwner), deputy, () -> true),
                    "副负责人转让被拒绝", outcome -> {
                        assertEquals(TeamOutcome.Status.DENIED, outcome.status(),
                                "转让只能由主负责人执行");
                        assertTrue(outcome.state().isMainOwner(owner),
                                "被拒绝的转让不得改变主负责人");
                    });

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 4, TeamAction.ADD_DEPUTY,
                            List.of(), outsider), deputy, () -> true),
                    "副负责人添加副负责人被拒绝", outcome ->
                            assertEquals(TeamOutcome.Status.DENIED, outcome.status(),
                                    "添加副负责人只能由主负责人执行"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 5, TeamAction.REMOVE_DEPUTY,
                            List.of(), deputy), deputy, () -> true),
                    "副负责人移除副负责人被拒绝", outcome ->
                            assertEquals(TeamOutcome.Status.DENIED, outcome.status(),
                                    "移除副负责人只能由主负责人执行"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 6, TeamAction.TOGGLE_SELF_CLAIM,
                            List.of()), outsider, () -> true),
                    "非负责人切换被拒绝", outcome ->
                            assertEquals(TeamOutcome.Status.DENIED, outcome.status(),
                                    "非负责人不能切换自行认领"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 7, TeamAction.TRANSFER,
                            List.of(), newOwner), owner, () -> true),
                    "主负责人转让", outcome -> {
                        assertSuccess(outcome, "转让负责人");
                        assertTrue(outcome.state().isMainOwner(newOwner),
                                "新负责人 UUID 必须生效");
                        assertFalse(outcome.state().isOwner(owner),
                                "转让后原主负责人不能仍拥有管理权限");
                    });

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 8, TeamAction.TOGGLE_SELF_CLAIM,
                            List.of()), owner, () -> true),
                    "转让后旧负责人切换被拒绝", outcome ->
                            assertEquals(TeamOutcome.Status.DENIED, outcome.status(),
                                    "转让后旧负责人必须失去管理权限"));

            thenResult(helper, sequence, () -> runtime.store().ingest(
                    new SchematicMetadata(projectId, "owner-permissions-reimport",
                            Path.of("owner-permissions-reimport.litematic"), "hash-two", newOwner),
                    List.of(new MaterialCount("minecraft:dirt", 96),
                            new MaterialCount("minecraft:stone", 128)),
                    runtime.interpretationId()), "重新导入原理图", ignored -> {
                ProjectSnapshot after = snapshot(runtime, projectId);
                assertEquals(stoneId, materialId(after, "minecraft:stone"),
                        "重新导入后保留物品的材料 ID 必须稳定");
                assertEquals(dirtId, materialId(after, "minecraft:dirt"),
                        "重新导入后保留物品的材料 ID 必须稳定");
                TeamState state = runtime.teams().state(projectId).join().orElseThrow();
                assertTrue(state.isMainOwner(newOwner), "重新导入不得重置负责人");
                assertTrue(state.deputies().stream()
                                .anyMatch(value -> deputy.uuid().equals(value.uuid())),
                        "重新导入不得丢失副负责人 UUID");
                assertFalse(state.allowSelfClaim(), "重新导入不得重置自行认领策略");
            });

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 9, TeamAction.TOGGLE_SELF_CLAIM,
                            List.of()), newOwner, () -> true),
                    "新负责人切换自行认领", outcome -> {
                        assertSuccess(outcome, "新负责人切换自行认领");
                        assertTrue(outcome.state().allowSelfClaim(), "新负责人必须拥有开关权限");
                    });

            sequence.thenExecute(() -> runtime.store().removeProject(projectId).join());
            sequence.thenSucceed();
        } catch (RuntimeException error) {
            cleanup(runtime, projectId);
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 400)
    public void kickMatchesOnlySameProjectPersistentAndRuntimeInventory(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectA = "team-kick-a-" + UUID.randomUUID();
        String projectB = "team-kick-b-" + UUID.randomUUID();
        PlayerIdentity ownerA = player("KickOwnerA");
        PlayerIdentity ownerB = player("KickOwnerB");
        PlayerIdentity target = player("KickTarget");
        try {
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> {
                runtime.disconnected(target.uuid());
                cleanup(runtime, projectA);
                cleanup(runtime, projectB);
            });
            createProject(runtime, projectA, "kick-a", ownerA,
                    List.of(new MaterialCount("minecraft:stone", 64),
                            new MaterialCount("minecraft:dirt", 64)), "kick-a");
            createProject(runtime, projectB, "kick-b", ownerB,
                    List.of(new MaterialCount("minecraft:stone", 32)), "kick-b");
            runtime.store().reconcilePlayers(List.of(target)).join();
            ProjectSnapshot snapshotA = snapshot(runtime, projectA);
            int stoneA = materialId(snapshotA, "minecraft:stone");
            int dirtA = materialId(snapshotA, "minecraft:dirt");
            int stoneB = materialId(snapshot(runtime, projectB), "minecraft:stone");

            runtime.store().join(projectA, stoneA, target).join();
            runtime.store().join(projectA, dirtA, target).join();
            runtime.store().join(projectB, stoneB, target).join();
            runtime.connected(target.uuid());
            runtime.reportInventory(projectA, stoneA, target, 7).join();
            runtime.reportInventory(projectA, dirtA, target, 3).join();
            runtime.reportInventory(projectB, stoneB, target, 9).join();
            assertTrue(runtime.inventoryCount(target.uuid(), projectA, stoneA) == 7,
                    "测试前置：项目 A 运行时库存应存在");

            GameTestSequence sequence = helper.startSequence();
            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectA, 1, TeamAction.KICK,
                            List.of(stoneA, stoneB, dirtA), target), ownerA, () -> true),
                    "按材料踢出", outcome -> {
                        assertSuccess(outcome, "按材料踢出");
                        assertEquals(3, outcome.processedCount(),
                                "跨项目材料 ID 也计为一次处理尝试");
                        assertEquals(2, outcome.changedCount(),
                                "只有本项目实际存在的两个目标被删除");
                        assertEquals(Set.of(stoneA, dirtA), outcome.changedMaterials(),
                                "变更材料只能属于本项目");
                        assertEquals(Set.of(stoneA, dirtA), outcome.removedObservations().stream()
                                        .map(observation -> observation.materialId())
                                        .collect(java.util.stream.Collectors.toSet()),
                                "只允许清除本项目材料的运行时观测");
                    });

            sequence.thenExecute(() -> {
                List<net.syncmaterial.syncmaterial.core.ClaimSnapshot> claimsA =
                        runtime.store().claims(projectA).join();
                assertTrue(claimsA.isEmpty(), "项目 A 的认领必须全部清除");
                var inventoryA = runtime.store().inventory(projectA, target).join();
                assertTrue(inventoryA.isEmpty(), "项目 A 的持久库存必须清除");
                assertEquals(0L, runtime.inventoryCount(target.uuid(), projectA, stoneA),
                        "项目 A 的运行时库存必须清除");
                assertEquals(0L, runtime.inventoryCount(target.uuid(), projectA, dirtA),
                        "项目 A 的运行时库存必须清除");

                var claimsB = runtime.store().claims(projectB).join();
                assertTrue(claimsB.stream().anyMatch(claim -> claim.materialId() == stoneB),
                        "跨项目材料不得被同一踢出命令删除");
                var inventoryB = runtime.store().inventory(projectB, target).join();
                assertEquals(9L, (long) inventoryB.getOrDefault(stoneB, 0L),
                        "项目 B 的持久库存必须保持原值");
                assertEquals(9L, runtime.inventoryCount(target.uuid(), projectB, stoneB),
                        "项目 B 的运行时库存必须保持原值");
                runtime.disconnected(target.uuid());
                runtime.store().removeProject(projectA).join();
                runtime.store().removeProject(projectB).join();
            });
            sequence.thenSucceed();
        } catch (RuntimeException error) {
            runtime.disconnected(target.uuid());
            cleanup(runtime, projectA);
            cleanup(runtime, projectB);
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 400)
    public void assignmentIsMembershipWithoutInventedInventoryAndKeepsCatalog(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = "team-assign-" + UUID.randomUUID();
        PlayerIdentity owner = player("AssignOwner");
        PlayerIdentity deputy = player("AssignDeputy");
        PlayerIdentity target = player("AssignTarget");
        try {
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> {
                runtime.disconnected(owner.uuid());
                cleanup(runtime, projectId);
            });
            createProject(runtime, projectId, "assign", owner,
                    List.of(new MaterialCount("minecraft:stone", 64),
                            new MaterialCount("minecraft:dirt", 32)), "assign");
            runtime.store().reconcilePlayers(List.of(deputy, target)).join();
            ProjectSnapshot snapshot = snapshot(runtime, projectId);
            int stoneId = materialId(snapshot, "minecraft:stone");
            int dirtId = materialId(snapshot, "minecraft:dirt");
            runtime.connected(owner.uuid());
            long revision = runtime.catalogs().sent(owner.uuid(),
                    runtime.connection(owner.uuid()), snapshot);

            GameTestSequence sequence = helper.startSequence();
            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 1, TeamAction.ADD_DEPUTY,
                            List.of(), deputy), owner, () -> true),
                    "准备副负责人", outcome -> assertSuccess(outcome, "添加副负责人"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 2, TeamAction.ASSIGN,
                            List.of(stoneId, stoneId, dirtId), target), deputy, () -> true),
                    "副负责人批量分配", outcome -> {
                        assertSuccess(outcome, "副负责人批量分配");
                        assertEquals(3, outcome.processedCount(),
                                "重复材料仍计为处理尝试，与原有批处理语义一致");
                        assertEquals(2, outcome.changedCount(),
                                "重复材料只产生一条真实认领");
                        assertEquals(2, runtime.store().claims(projectId).join().size(),
                                "分配必须为每种材料建立认领");
                        assertTrue(runtime.store().inventory(projectId, target).join().isEmpty(),
                                "分配不得伪造持久库存");
                        assertEquals(0L, runtime.inventoryCount(target.uuid(), projectId, stoneId),
                                "分配不得伪造运行时库存");
                    });

            sequence.thenExecute(() -> assertEquals(revision, runtime.catalogs().currentRevision(owner.uuid(),
                            projectId, runtime.connection(owner.uuid())).orElseThrow(),
                    "分配不得重置材料目录投递序号"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 3, TeamAction.TOGGLE_SELF_CLAIM,
                            List.of()), owner, () -> true),
                    "负责人切换自行认领", outcome -> assertSuccess(outcome, "切换自行认领"));

            sequence.thenExecute(() -> assertEquals(revision, runtime.catalogs().currentRevision(owner.uuid(),
                            projectId, runtime.connection(owner.uuid())).orElseThrow(),
                    "角色变更不得重置材料目录投递序号"));

            thenResult(helper, sequence,
                    () -> runtime.teams().execute(command(projectId, 4, TeamAction.ASSIGN,
                            List.of(stoneId, stoneId, dirtId), target), owner, () -> true),
                    "重复分配", second -> {
                        assertSuccess(second, "重复分配");
                        assertEquals(3, second.processedCount(), "重复分配仍上报处理尝试数");
                        assertEquals(0, second.changedCount(), "已存在的认领不得重复插入");
                        assertEquals(2, runtime.store().claims(projectId).join().size(),
                                "重复分配不得改变认领集合");
                        assertEquals(0L, runtime.inventoryCount(target.uuid(), projectId, stoneId),
                                "重复分配仍不得伪造库存");
                    });

            sequence.thenExecute(() -> {
                runtime.disconnected(owner.uuid());
                runtime.store().removeProject(projectId).join();
            });
            sequence.thenSucceed();
        } catch (RuntimeException error) {
            runtime.disconnected(owner.uuid());
            cleanup(runtime, projectId);
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 300)
    public void directoryKeepsOnlineAndOfflineUuidIdentities(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = "team-directory-" + UUID.randomUUID();
        PlayerIdentity owner = player("DirectoryOwner");
        PlayerIdentity deputy = player("DirectoryDeputy");
        PlayerIdentity claimant = player("DirectoryClaimant");
        PlayerIdentity online = player("DirectoryOnline");
        PlayerIdentity cached = player("DirectoryCached");
        try {
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> cleanup(runtime, projectId));
            createProject(runtime, projectId, "directory", owner,
                    List.of(new MaterialCount("minecraft:stone", 64)), "directory");
            runtime.store().reconcilePlayers(List.of(deputy, claimant, cached)).join();
            int materialId = materialId(snapshot(runtime, projectId), "minecraft:stone");
            runtime.store().join(projectId, materialId, claimant).join();

            GameTestSequence sequence = helper.startSequence();
            thenResult(helper, sequence,
                    () -> runtime.teams().directory(projectId, List.of(online), () -> List.of(cached)),
                    "玩家目录", view -> {
                assertFalse(view.limited(), "小型目录不得报告截断");
                List<PlayerDirectoryEntry> players = view.players();
                PlayerDirectoryEntry onlineEntry = entry(players, online.uuid());
                assertTrue(onlineEntry != null && onlineEntry.online(),
                        "在线玩家必须标记为在线");
                assertEquals(online.name(), onlineEntry.player().name(),
                        "在线玩家名字必须保留");
                PlayerDirectoryEntry cachedEntry = entry(players, cached.uuid());
                assertTrue(cachedEntry != null && !cachedEntry.online(),
                        "缓存玩家必须离线但保留真实 UUID");
                assertTrue(entry(players, owner.uuid()) != null,
                        "项目负责人必须出现在目录中");
                assertTrue(entry(players, deputy.uuid()) != null,
                        "已知玩家必须出现在目录中");
                assertTrue(entry(players, claimant.uuid()) != null,
                        "当前项目认领者必须出现在目录中");
                runtime.store().removeProject(projectId).join();
            });
            sequence.thenSucceed();
        } catch (RuntimeException error) {
            cleanup(runtime, projectId);
            throw error;
        }
    }

    @GameTest(structure = "empty", maxTicks = 300)
    public void admittedCommitPersistsAfterInitiatorDisconnects(GameTestHelper helper) {
        ServerRuntime runtime = runtime(helper);
        String projectId = "team-commit-" + UUID.randomUUID();
        PlayerIdentity owner = player("CommitOwner");
        CompletableFuture<?>[] started = new CompletableFuture<?>[1];
        try {
            net.syncmaterial.syncmaterial.gametest.GameTestCleanup.register(helper, () -> {
                runtime.disconnected(owner.uuid());
                cleanup(runtime, projectId);
            });
            createProject(runtime, projectId, "commit", owner,
                    List.of(new MaterialCount("minecraft:stone", 64)), "commit");
            runtime.connected(owner.uuid());
            runtime.subscribe(owner.uuid(), projectId);

            GameTestSequence sequence = helper.startSequence();
            // Admission is intentionally already true: this scenario covers a commit
            // whose initiating connection disappears after submission. The writer is
            // never blocked, so other concurrent GameTests cannot deadlock.
            sequence.thenExecute(() -> {
                started[0] = runtime.teams().execute(
                        command(projectId, 1, TeamAction.TOGGLE_SELF_CLAIM, List.of()), owner, () -> true);
                runtime.disconnected(owner.uuid());
            });
            sequence.thenWaitUntil(() -> {
                if (started[0] == null || !started[0].isDone()) {
                    throw helper.assertionException(Component.literal("等待断线后的提交完成"));
                }
            });
            sequence.thenExecute(() -> {
                TeamOutcome outcome = (TeamOutcome) started[0].join();
                assertSuccess(outcome, "断线后完成的角色变更");
                assertFalse(outcome.state().allowSelfClaim(),
                        "提交结果必须是数据库中的真实状态");
                assertFalse(runtime.store().teamState(projectId).join().orElseThrow().allowSelfClaim(),
                        "断线不得回滚已提交事务");
                assertEquals(0L, runtime.inventoryCount(owner.uuid(), projectId,
                                materialId(snapshot(runtime, projectId), "minecraft:stone")),
                        "断线后离线玩家不再贡献运行时库存");
            });
            sequence.thenSucceed();
        } catch (RuntimeException error) {
            runtime.disconnected(owner.uuid());
            cleanup(runtime, projectId);
            throw error;
        }
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private static ServerRuntime runtime(GameTestHelper helper) {
        ServerRuntime runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        if (runtime == null || !runtime.isOpen()) {
            throw helper.assertionException(Component.literal("Foundation runtime 未初始化"));
        }
        return runtime;
    }

    private static void createProject(ServerRuntime runtime, String projectId, String prefix,
                                      PlayerIdentity owner, List<MaterialCount> counts, String hash) {
        runtime.store().ingest(new SchematicMetadata(projectId, prefix,
                        Path.of(prefix + ".litematic"), "hash-" + hash, owner),
                counts, runtime.interpretationId()).join();
    }

    private static ProjectSnapshot snapshot(ServerRuntime runtime, String projectId) {
        return runtime.store().findProject(projectId).join()
                .orElseThrow(() -> new IllegalStateException("project disappeared: " + projectId));
    }

    private static int materialId(ProjectSnapshot snapshot, String itemId) {
        return snapshot.materials().stream()
                .filter(material -> material.itemId().equals(itemId))
                .map(MaterialSnapshot::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing material: " + itemId));
    }

    private static TeamCommand command(String projectId, long requestId, TeamAction action,
                                       List<Integer> materials, PlayerIdentity... targets) {
        return new TeamCommand(projectId, requestId, action, -1, materials, List.of(targets));
    }

    private static PlayerIdentity player(String name) {
        return new PlayerIdentity(UUID.randomUUID(), name);
    }

    private static PlayerDirectoryEntry entry(List<PlayerDirectoryEntry> players, UUID uuid) {
        return players.stream()
                .filter(value -> uuid.equals(value.player().uuid()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Adds a tick-driven wait for a real async result. The condition throws to request
     * another server tick; after completion the assertion runs on the server thread.
     */
    private static <T> void thenResult(GameTestHelper helper, GameTestSequence sequence,
                                       java.util.function.Supplier<CompletableFuture<T>> starter,
                                       String label, Consumer<T> assertion) {
        CompletableFuture<?>[] started = new CompletableFuture<?>[1];
        sequence.thenExecute(() -> started[0] = starter.get());
        sequence.thenWaitUntil(() -> {
            if (started[0] == null || !started[0].isDone()) {
                throw helper.assertionException(Component.literal("等待" + label));
            }
        });
        sequence.thenExecute(() -> {
            T result;
            try {
                @SuppressWarnings("unchecked")
                CompletableFuture<T> future = (CompletableFuture<T>) started[0];
                result = future.join();
            } catch (CompletionException failure) {
                throw new AssertionError(label + " 失败: " + failure.getCause(), failure.getCause());
            }
            assertion.accept(result);
        });
    }

    private static void assertSuccess(TeamOutcome outcome, String label) {
        assertTrue(outcome != null && outcome.success(), label + " 应成功，实际为 "
                + (outcome == null ? "null" : outcome.status()));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + "，期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + "，期望 " + expected + "，实际 " + actual);
        }
    }

    private static void cleanup(ServerRuntime runtime, String projectId) {
        try {
            runtime.store().removeProject(projectId).join();
        } catch (RuntimeException ignored) {
            // Best effort; project ids are unique per test.
        }
    }
}
