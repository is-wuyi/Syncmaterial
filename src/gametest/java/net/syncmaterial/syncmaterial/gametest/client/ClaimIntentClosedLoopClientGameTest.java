package net.syncmaterial.syncmaterial.gametest.client;

import java.sql.SQLException;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.gametest.ClientTestInput;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.ProjectViewPackets;
import net.syncmaterial.syncmaterial.network.ProjectViewS2CPacket;
import net.syncmaterial.syncmaterial.network.ProtocolVersion;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/**
 * Legacy-backed protocol-5 claim intent end-to-end test.
 *
 * <p>The only mutation entry is {@link SyncMaterialList#claimEntry(MaterialListEntry)},
 * the same method invoked by the real row button listener in
 * {@code WidgetMaterialListEntry}. This proves the UI intent flow rather than
 * hand-sending JOIN/LEAVE packets:</p>
 *
 * <ol>
 *   <li>row button JOIN sets a pending intent and disables the row;</li>
 *   <li>an unrelated/older operation response must not complete that intent;</li>
 *   <li>the authoritative project publication marks the material claimed and clears
 *       the pending intent;</li>
 *   <li>a second row action becomes LEAVE with the same pending/correlation rules;</li>
 *   <li>a server-side rejection clears the pending intent without creating a claim.</li>
 * </ol>
 *
 * <p>Fresh legacy database rows stay authoritative: the final claim state is asserted
 * against the real {@code claims} table, not against a client-only flag.</p>
 */
public class ClaimIntentClosedLoopClientGameTest implements FabricClientGameTest {

    private static final String PROJECT = "claim-intent-" + System.nanoTime();
    private static final String DIRT = "minecraft:dirt";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        int materialId = -1;
        try (var server = ctx.worldBuilder().createServer();
             var connection = server.connect()) {
          try {
            ctx.waitTicks(20);
            materialId = insertProject(server);
            final int mid = materialId;

            // Real frozen request opens the list; the protocol-5 OPEN follows from the
            // production screen constructor path.
            ctx.waitFor(client -> ClientProtocolState.serverSupports(
                    ProtocolVersion.SCOPED_PROJECT_VIEWS));
            ctx.runOnClient(client -> ClientPlayNetworking.send(
                    new MaterialStatsRequestC2SPacket(PROJECT)));
            ctx.waitForScreen(GuiMaterialList.class);
            ctx.waitFor(client -> ClientProtocolState.serverSupports(ProtocolVersion.SCOPED_PROJECT_VIEWS));
            waitForProjectProgress(ctx);

            GuiMaterialList gui = ctx.computeOnClient(client ->
                    (GuiMaterialList) ClientScreenAccess.currentScreen(client));
            SyncMaterialList list = ctx.computeOnClient(client -> gui.getMaterialList());

            // Wait for the real protocol-5 view before forging an old response.
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
                throw new AssertionError("JOIN 未收敛到权威认领状态: pending="
                        + ctx.computeOnClient(client -> list.isClaimPending(mid))
                        + ", claimed=" + ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed())
                        + ", participants=" + ctx.computeOnClient(client -> entryIn(list, mid).getParticipants()));
            }
            if (!hasClaim(server, mid, "Player0")) {
                throw new AssertionError("客户端显示已认领，但服务端 claims 表没有对应行");
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
            if (hasClaim(server, mid, "Player0")) {
                throw new AssertionError("LEAVE 后服务端 claims 表仍有玩家认领");
            }

            // ===== 3. Rejection clears the intent but never creates a claim =====
            ctx.waitFor(client -> gui.getTeamState() != null && gui.isAllowSelfClaim());
            onDatabase(server, database -> {
                database.executeUpdate(
                        "UPDATE schematics SET allow_self_claim = 0 WHERE id = ?", PROJECT);
                return null;
            });
            // The GUI's allowSelfClaim is intentionally stale (no team push after a direct
            // database edit), so the row still sends JOIN. The real server must reject it.
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
                throw new AssertionError("服务端拒绝后 pending 未清除或错误标记为已认领: pending="
                        + ctx.computeOnClient(client -> list.isClaimPending(mid))
                        + ", claimed=" + ctx.computeOnClient(client -> entryIn(list, mid).isCurrentPlayerClaimed()));
            }
            if (hasClaim(server, mid, "Player0")) {
                throw new AssertionError("被拒绝的 JOIN 在服务端写入了认领");
            }
          } finally {
            cleanup(server, materialId);
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
                var views = LegacyServerRegistry.get(instance).views()
                        .views(PROJECT, InterestKind.MATERIALS);
                return views.isEmpty() ? null
                        : new ViewRef(views.get(0).viewId(), views.get(0).generation());
            });
            if (view != null) return view;
        }
        throw new AssertionError("服务端未建立项目视图，无法验证旧回包关联");
    }

    private static void waitForProjectProgress(ClientGameTestContext ctx) {
        if (!waitFor(ctx, () -> ctx.computeOnClient(client -> {
            if (!(ClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)) return false;
            return gui.getMaterialList().hasProjectProgress()
                    && !gui.getMaterialList().getMaterialsAll().isEmpty();
        }))) {
            throw new AssertionError("材料列表未收到协议 5 权威进度");
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

    private static boolean hasClaim(TestDedicatedServerContext server, int mid, String player) {
        int count = onDatabase(server, database -> {
            try (var result = database.executeQuery(
                    "SELECT COUNT(*) FROM claims WHERE schematic_id = ? AND material_id = ? "
                            + "AND player_name = ? AND status = 'active'",
                    PROJECT, mid, player)) {
                result.next();
                return result.getInt(1);
            }
        });
        return count > 0;
    }

    private static int insertProject(TestDedicatedServerContext server) {
        return onDatabase(server, database -> {
            database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    PROJECT, "Claim Intent Test", "/claim-intent.litematic", "Owner");
            database.executeUpdate(
                    "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                    PROJECT, DIRT, 64);
            try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private static void cleanup(TestDedicatedServerContext server, int mid) {
        if (mid <= 0) return;
        onDatabase(server, database -> {
            database.executeUpdate("DELETE FROM claims WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM player_inventories WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", PROJECT);
            database.executeUpdate("DELETE FROM schematics WHERE id = ?", PROJECT);
            return null;
        });
    }

    private static <T> T onDatabase(TestDedicatedServerContext server,
                                    SqlFunction<SchematicDatabase, T> action) {
        return server.computeOnServer(instance -> {
            try {
                return action.apply(SyncMaterial.getSharedDatabase());
            } catch (SQLException failure) {
                throw new RuntimeException("测试数据库操作失败", failure);
            }
        });
    }

    private record ViewRef(long viewId, long generation) {}

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
