package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.gametest.ClientTestInput;
import net.syncmaterial.syncmaterial.core.ClaimSnapshot;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamCommand;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Foundation-backed protocol-5 claim intent end-to-end test.
 *
 * <p>Mirrors {@code ClaimIntentClosedLoopClientGameTest} but the authoritative claim
 * is the isolated Foundation writer database, not the legacy SQLite schema. The row
 * action remains the production {@link SyncMaterialList#claimEntry(MaterialListEntry)}
 * used by the real list button.</p>
 *
 * <p>The project is owned by another player so that toggling self-claim off can produce
 * a genuine server rejection while the already-open GUI still believes self-claim is
 * allowed. That rejection must clear the pending intent without writing a claim.</p>
 */
public class FoundationClaimIntentClosedLoopClientGameTest implements FabricClientGameTest {

    private static final String PROJECT = "foundation-claim-intent-" + UUID.randomUUID();
    private static final PlayerIdentity PROJECT_OWNER =
            new PlayerIdentity(UUID.randomUUID(), "FoundationOwner");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        int materialId = -1;
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
          try {
            ctx.waitTicks(20);
            materialId = server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                runtime.store().ingest(new SchematicMetadata(
                                PROJECT, "Foundation claim intent", Path.of("foundation-claim-intent.litematic"),
                                "claim-intent", PROJECT_OWNER),
                        List.of(new MaterialCount("minecraft:dirt", 64)), runtime.interpretationId()).join();
                return runtime.store().findProject(PROJECT).join().orElseThrow()
                        .materials().get(0).id();
            });
            final int mid = materialId;

            ctx.waitFor(client -> ClientProtocolState.serverSupports(
                    ProtocolVersion.SCOPED_PROJECT_VIEWS));
            ctx.runOnClient(client -> {
                if (!net.syncmaterial.syncmaterial.client.SyncMaterialClient.requestMaterialList(PROJECT)) {
                    throw new AssertionError("Foundation 认领项目未建立");
                }
            });
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitFor(client -> ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
            waitForProjectProgress(ctx);

            GuiMaterialList gui = ctx.computeOnClient(client ->
                    (GuiMaterialList) ClientScreenAccess.currentScreen(client));
            SyncMaterialList list = ctx.computeOnClient(client -> gui.getMaterialList());
            ViewRef view = waitForView(ctx, server);
            long oldRequestId = ctx.computeOnClient(client -> {
                long id = TeamManagementClient.allocateProjectViewRequestId();
                if (id <= 0) throw new AssertionError("无法分配旧请求编号");
                return id;
            });

            // ===== 1. JOIN through a real mouse click on the production row button =====
            ClientTestInput.pressMaterialRowButton(ctx, gui, mid,
                    "syncmaterial.gui.button.join_collaboration");
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("真实点击 JOIN 按钮后未进入 pending 状态");
            }
            if (isClaimedNow(ctx, list, mid)) {
                throw new AssertionError("JOIN 意图不能在本机直接标记为已认领");
            }
            ctx.runOnClient(client -> injectStaleOperation(
                    client, view, oldRequestId, mid, ProjectViewAction.JOIN));
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("旧操作回包清除了新的 JOIN 意图");
            }
            if (isClaimedNow(ctx, list, mid)) {
                throw new AssertionError("旧操作回包把新 JOIN 意图标记为完成");
            }
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    !list.isClaimPending(mid)
                            && entryIn(list, mid).isCurrentPlayerClaimed()
                            && entryIn(list, mid).getParticipants().stream()
                            .anyMatch(participant -> participant.playerName().equals("Player0"))))) {
                throw new AssertionError("JOIN 未收敛到 Foundation 权威认领状态: pending="
                        + ctx.computeOnClient(client -> list.isClaimPending(mid))
                        + ", claimed=" + ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed())
                        + ", participants=" + ctx.computeOnClient(client -> entryIn(list, mid).getParticipants()));
            }
            if (!hasClaim(server, mid)) {
                throw new AssertionError("客户端显示已认领，但 Foundation 存储没有对应 claim");
            }

            // ===== 2. LEAVE through the same production row button =====
            ClientTestInput.pressMaterialRowButton(ctx, gui, mid,
                    "syncmaterial.gui.button.leave_collaboration");
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("真实点击 LEAVE 按钮后未进入 pending 状态");
            }
            ctx.runOnClient(client -> injectStaleOperation(
                    client, view, oldRequestId, mid, ProjectViewAction.LEAVE));
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("旧操作回包清除了新的 LEAVE 意图");
            }
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    !list.isClaimPending(mid) && !entryIn(list, mid).isCurrentPlayerClaimed()))) {
                throw new AssertionError("LEAVE 未收敛到未认领状态: pending="
                        + ctx.computeOnClient(client -> list.isClaimPending(mid))
                        + ", claimed=" + ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed()));
            }
            if (hasClaim(server, mid)) {
                throw new AssertionError("LEAVE 后 Foundation 存储仍有认领");
            }

            // ===== 3. Rejection clears the intent without creating a claim =====
            ctx.waitFor(client -> gui.getTeamState() != null && gui.isAllowSelfClaim());
            server.computeOnServer(instance -> {
                ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                var outcome = runtime.store().applyTeamCommand(
                        new TeamCommand(PROJECT, 1, TeamAction.TOGGLE_SELF_CLAIM, -1,
                                List.of(), List.of()),
                        PROJECT_OWNER, project -> true, () -> true).join();
                if (!outcome.success()) {
                    throw new AssertionError("关闭 Foundation 自行认领失败: " + outcome);
                }
                return null;
            });
            if (!gui.isAllowSelfClaim()) {
                throw new AssertionError("前提失败：界面自行认领开关已被提前更新");
            }
            ClientTestInput.pressMaterialRowButton(ctx, gui, mid,
                    "syncmaterial.gui.button.join_collaboration");
            if (!Boolean.TRUE.equals(ctx.computeOnClient(client -> list.isClaimPending(mid)))) {
                throw new AssertionError("被拒绝的真实 JOIN 点击仍应短暂进入 pending 状态");
            }
            ClientTestInput.releaseLeftMouse(ctx);
            if (!waitFor(ctx, () -> ctx.computeOnClient(client ->
                    !list.isClaimPending(mid) && !entryIn(list, mid).isCurrentPlayerClaimed()))) {
                throw new AssertionError("Foundation 拒绝后 pending 未清除或错误标记为已认领: pending="
                        + ctx.computeOnClient(client -> list.isClaimPending(mid))
                        + ", claimed=" + ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed()));
            }
            if (hasClaim(server, mid)) {
                throw new AssertionError("被拒绝的 JOIN 在 Foundation 存储写入了认领");
            }
          } finally {
            if (materialId > 0) {
                server.computeOnServer(instance -> {
                    ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
                    if (runtime != null) runtime.store().removeProject(PROJECT).join();
                    return null;
                });
            }
          }
        } finally {
            ClientTestInput.releaseLeftMouse(ctx);
            ctx.runOnClient(client -> {
                ClientScreenAccess.showScreen(client, null);
                net.syncmaterial.syncmaterial.client.InventoryWatcher.clearContext();
            });
        }
    }

    private static void injectStaleOperation(net.minecraft.client.Minecraft client, ViewRef view,
                                             long requestId, int materialId, ProjectViewAction action) {
        var outcome = new net.syncmaterial.syncmaterial.core.CollaborationOutcome(
                action == ProjectViewAction.JOIN
                        ? net.syncmaterial.syncmaterial.core.CollaborationOutcome.Action.JOIN
                        : net.syncmaterial.syncmaterial.core.CollaborationOutcome.Action.LEAVE,
                net.syncmaterial.syncmaterial.core.CollaborationOutcome.Status.APPLIED, PROJECT, materialId);
        ProjectViewS2CPacket stale = ProjectViewPackets.operation(
                ProjectViewScope.project(PROJECT), requestId, view.viewId(), view.generation(), outcome);
        ProjectViewClient session = ProjectViewClient.current().orElseThrow(() ->
                new AssertionError("项目视图会话缺失，无法注入旧回包"));
        session.onPacket(client.getConnection(), TeamManagementClient.connectionToken(), stale);
    }

    private static ViewRef waitForView(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            ViewRef view = server.computeOnServer(instance -> {
                var views = ServerRuntimeRegistry.get(instance).views()
                        .views(PROJECT, InterestKind.MATERIALS);
                return views.isEmpty() ? null
                        : new ViewRef(views.get(0).viewId(), views.get(0).generation());
            });
            if (view != null) return view;
        }
        throw new AssertionError("Foundation 服务端未建立项目视图，无法验证旧回包关联");
    }

    private static void waitForProjectProgress(ClientGameTestContext ctx) {
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("材料列表未收到 Foundation 协议 5 权威进度");
        }
    }

    private static MaterialListEntry entryIn(SyncMaterialList list, int mid) {
        for (MaterialListEntry candidate : list.getMaterialsAll()) {
            if (candidate.getDatabaseId() == mid) return candidate;
        }
        throw new AssertionError("材料列表中找不到 id=" + mid);
    }

    private static boolean isClaimedNow(ClientGameTestContext ctx, SyncMaterialList list, int mid) {
        return ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed());
    }

    private static MaterialListEntry findEntry(ClientGameTestContext ctx, int mid) {
        return ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) {
                throw new AssertionError("材料列表已关闭");
            }
            return entryIn(gui.getMaterialList(), mid);
        });
    }

    private static boolean waitFor(ClientGameTestContext ctx,
                                   java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 200; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }

    private static boolean hasClaim(TestDedicatedServerContext server, int mid) {
        return Boolean.TRUE.equals(server.computeOnServer(instance -> {
            ServerRuntime runtime = ServerRuntimeRegistry.get(instance);
            List<ClaimSnapshot> claims = runtime.store().claims(PROJECT).join();
            return claims.stream().anyMatch(claim -> claim.materialId() == mid
                    && claim.player().name().equals("Player0"));
        }));
    }

    private record ViewRef(long viewId, long generation) {}
}
