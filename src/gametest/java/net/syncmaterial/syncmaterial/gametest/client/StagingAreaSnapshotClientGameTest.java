package net.syncmaterial.syncmaterial.gametest.client;

import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;

/** Real server configuration publication, exact replacement and disconnect cleanup. */
public class StagingAreaSnapshotClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        String project = "client-area-snapshot-" + System.nanoTime();
        String unrelated = project + "-unrelated";
        var rendering = new StagingAreaRenderer.State[1];
        try (var server = ctx.worldBuilder().createServer()) {
            try (var connection = server.connect()) {
            ctx.waitFor(client -> ClientProtocolState.isUsable());
            server.runOnServer(instance -> {
                try {
                    net.syncmaterial.syncmaterial.SyncMaterial.getSharedDatabase().executeUpdate(
                            "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                            project, "Area snapshot", "/area-snapshot.litematic", "Player0");
                } catch (java.sql.SQLException failure) {
                    throw new RuntimeException("无法建立快照项目", failure);
                }
            });
            ProjectViewScope scope = ProjectViewScope.project(project);
            ctx.runOnClient(client -> ProjectViewClient.current().ifPresent(view ->
                    view.openEditor(project)));
            ctx.waitFor(client -> ProjectViewClient.current().map(view ->
                    view.isAcceptedView(view.viewToken(scope))).orElse(false));

            int[] ids = server.computeOnServer(instance -> {
                var manager = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
                int oldId = manager.addStagingArea(project, "minecraft:overworld", "Old name",
                        4, 64, 4, 5, 65, 5);
                int deletedId = manager.addStagingArea(project, "minecraft:overworld", "Deleted",
                        8, 64, 8, 9, 65, 9);
                int unchangedId = manager.addStagingArea(project, "minecraft:overworld", "Unchanged",
                        12, 64, 12, 13, 65, 13);
                manager.broadcastUpdate(project);
                return new int[] {oldId, deletedId, unchangedId};
            });
            ctx.waitFor(client -> areaNames(project).equals(Set.of("Old name", "Deleted", "Unchanged")));
            ctx.runOnClient(client -> StagingAreaRenderer.getInstance().setRenderEnabled(project, false));

            ctx.runOnClient(client -> SyncMaterialClient.clearActiveSchematic(unrelated));
            if (!areaNames(project).equals(Set.of("Old name", "Deleted", "Unchanged"))) {
                throw new AssertionError("无关项目删除清除了当前项目的权威投影");
            }

            server.runOnServer(instance -> {
                var manager = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
                manager.updateStagingArea(ids[0], project, "Renamed", 20, 64, 20, 21, 65, 21);
                manager.removeStagingArea(ids[1], project);
                manager.updateStagingArea(ids[2], project, "Unchanged", 24, 64, 24, 25, 65, 25);
                manager.broadcastUpdate(project);
            });
            if (!waitForCondition(ctx, () -> areaNames(project).equals(Set.of("Renamed", "Unchanged")))) {
                throw new AssertionError(describeProjection(scope, project));
            }
            var snapshot = ClientSessionManager.current().orElseThrow().rendering().projectAreas(project);
            if (snapshot == null || snapshot.areas().stream().noneMatch(area ->
                    area.areaId() == ids[0] && area.name().equals("Renamed")
                            && area.bounds().x1() == 20 && area.bounds().z1() == 20)) {
                throw new AssertionError("全量替换没有保留真实 area ID 与新坐标");
            }
            if (snapshot.areas().stream().anyMatch(area ->
                    area.name().equals("Old name") || area.name().equals("Deleted"))) {
                throw new AssertionError("全量替换仍残留旧名称或已删除区域");
            }
            if (StagingAreaRenderer.getInstance().isRenderEnabled(project)) {
                throw new AssertionError("刷新权威快照不应重置用户的线框隐藏开关");
            }

            rendering[0] = ctx.computeOnClient(client -> ClientSessionManager.current()
                    .orElseThrow().rendering());
            }
            ctx.waitTicks(20);
            if (rendering[0].projectAreas(project) != null || rendering[0].projectAreas(unrelated) != null) {
                throw new AssertionError("断线后权威项目线框未清空");
            }
        } finally {
            ctx.runOnClient(client -> StagingAreaRenderer.getInstance().clearAllSelections());
        }
    }

    private static boolean waitForCondition(ClientGameTestContext ctx,
            java.util.function.Supplier<Boolean> condition) {
        for (int elapsed = 0; elapsed < 100; elapsed += 5) {
            ctx.waitTicks(5);
            if (Boolean.TRUE.equals(condition.get())) return true;
        }
        return false;
    }

    private static String describeProjection(ProjectViewScope scope, String project) {
        return "更新快照未形成: areas=" + areaNames(project) + "，"
                + ClientSessionManager.current().map(session -> {
                    var view = session.projectView();
                    var token = view.viewToken(scope);
                    var store = view.projectStore().orElse(null);
                    return "accepted=" + view.isAcceptedView(token) + "，token=" + token
                            + "，storeScope=" + (store == null ? null : store.scope())
                            + "，valid=" + (store != null && store.valid())
                            + "，sequence=" + (store == null ? -1 : store.configurationSequence("LIST"))
                            + "，config=" + (store == null ? null : store.configuration("LIST"))
                            + "，projection=" + session.rendering().projectAreas(project);
                }).orElse("无会话");
    }

    private static Set<String> areaNames(String project) {
        var snapshot = ClientSessionManager.current().orElseThrow().rendering().projectAreas(project);
        return snapshot == null ? Set.of() : snapshot.areas().stream()
                .map(StagingAreaRenderer.State.ProjectArea::name).collect(Collectors.toSet());
    }
}
