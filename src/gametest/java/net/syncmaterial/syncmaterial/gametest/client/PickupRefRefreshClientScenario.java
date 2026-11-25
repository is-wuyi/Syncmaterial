package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.ProjectViewClient;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.client.session.ClientSessionManager;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.MaterialStatsRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.QueryMaterialStatusC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket;

/** Real protocol-4 pickup subscriptions and renderer projections for both backends. */
public final class PickupRefRefreshClientScenario {
    private PickupRefRefreshClientScenario() {}

    public record Fixture(String firstProject, String secondProject,
                          int firstWarehouse, int secondWarehouse,
                          BlockPos firstContainer, BlockPos secondContainer) {}

    public interface Backend {
        Fixture prepare(TestDedicatedServerContext server, String fixtureId);

        boolean references(TestDedicatedServerContext server, String projectId, int warehouseId);

        boolean hasContainer(TestDedicatedServerContext server, String projectId, BlockPos pos);

        boolean pickupSubscriptionsEmpty(TestDedicatedServerContext server);

        /** Foundation subscribes the actor to project updates after an area mutation; legacy does not. */
        default boolean areaEditSubscribesMaterialList() {
            return false;
        }

        void cleanup(TestDedicatedServerContext server, Fixture fixture);
    }

    @SuppressWarnings("try")
    public static void run(ClientGameTestContext ctx, Backend backend) {
        String fixtureId = "legacy-pickup-ref-" + System.nanoTime();
        int[] originalProtocol = {0};
        String[] originalModVersion = {""};
        Fixture fixture = null;
        try (var server = ctx.worldBuilder().createServer()) {
            try {
                try (var connection = server.connect()) {
                    ctx.waitTicks(20);
                    originalProtocol[0] = ctx.computeOnClient(client -> ClientProtocolState.getServerProtocolVersion());
                    originalModVersion[0] = ctx.computeOnClient(client -> ClientProtocolState.getServerModVersion());
                    Fixture prepared = backend.prepare(server, fixtureId);
                    fixture = prepared;
                    waitFor(ctx, () -> backend.hasContainer(server, prepared.firstProject(), prepared.firstContainer())
                                    && backend.hasContainer(server, prepared.secondProject(), prepared.secondContainer()),
                            "真实仓库容器未完成扫描");
                    runReferenceChanges(ctx, server, backend, prepared);
                }

                ctx.waitTicks(20);
                waitFor(ctx, () -> ctx.computeOnClient(client ->
                                StagingAreaRenderer.getInstance().getWarehouseContainers().isEmpty()
                                        && StagingAreaRenderer.getInstance().getWarehouseAreas().isEmpty()),
                        "断线后旧取货与线框缓存未清空");
                if (!backend.pickupSubscriptionsEmpty(server)) {
                    throw new AssertionError("断线后服务端旧取货订阅未清空");
                }

                try (var reconnected = server.connect()) {
                    ctx.waitTicks(20);
                    if (!ctx.computeOnClient(client ->
                            StagingAreaRenderer.getInstance().getWarehouseContainers().isEmpty())) {
                        throw new AssertionError("重连后旧取货容器在未重新订阅前复活");
                    }
                    DemoStepLogger.mark("demo-complete");
                }
            } finally {
                ctx.runOnClient(client -> {
                    ClientProtocolState.onHandshakeResponse(originalProtocol[0], originalModVersion[0], true);
                    ClientSessionManager.pickup().clear();
                    StagingAreaRenderer.getInstance().clearWarehouseAreas();
                    StagingAreaRenderer.getInstance().clearWarehouseContainers();
                });
                if (fixture != null) backend.cleanup(server, fixture);
            }
        }
    }

    private static void runReferenceChanges(ClientGameTestContext ctx, TestDedicatedServerContext server,
                                            Backend backend, Fixture fixture) {
        ctx.runOnClient(client -> {
            ProjectViewClient.current().ifPresent(session -> session.closeProject(true));
            ClientProtocolState.onHandshakeResponse(4, "simulated-legacy-pickup", true);
            ClientSessionManager.pickup().setActive(true);
            StagingAreaRenderer.getInstance().clearWarehouseAreas();
            StagingAreaRenderer.getInstance().clearWarehouseContainers();
            for (String projectId : List.of(fixture.firstProject(), fixture.secondProject())) {
                ClientPlayNetworking.send(new WarehouseContainerRequestC2SPacket(projectId, true));
            }
            openMaterialList(fixture.secondProject());
        });
        waitForProjection(ctx, fixture, true, true, false, true,
                "初始两个项目的旧取货／线框订阅未建立");
        ctx.runOnClient(client -> ClientScreenAccess.showScreen(client, null));
        DemoStepLogger.mark("pickup-baseline");

        // Add the second warehouse to project A without resubscribing either project.
        DemoStepLogger.mark("add-shared-reference");
        reference(ctx, fixture.firstProject(), fixture.secondWarehouse(), "ADD_WAREHOUSE_REF");
        waitFor(ctx, () -> backend.references(server, fixture.firstProject(), fixture.secondWarehouse()),
                "新增引用的服务端写入未生效");
        waitForProjection(ctx, fixture, true, true, backend.areaEditSubscribesMaterialList(), true,
                "新增引用后旧取货未主动出现新容器");
        DemoStepLogger.mark("shared-reference-added");

        // Removing A's reference must preserve B's shared warehouse and container.
        DemoStepLogger.mark("remove-project-a-reference");
        reference(ctx, fixture.firstProject(), fixture.secondWarehouse(), "REMOVE_WAREHOUSE_REF");
        waitFor(ctx, () -> !backend.references(server, fixture.firstProject(), fixture.secondWarehouse())
                        && backend.references(server, fixture.secondProject(), fixture.secondWarehouse()),
                "跨项目服务端引用状态未按预期更新");
        waitForProjection(ctx, fixture, true, true, backend.areaEditSubscribesMaterialList(), true,
                "跨项目共享仓库被过早清除");
        DemoStepLogger.mark("shared-reference-preserved");

        // B is the last owner of warehouse 2; removing it must clear only that container.
        DemoStepLogger.mark("remove-last-shared-reference");
        reference(ctx, fixture.secondProject(), fixture.secondWarehouse(), "REMOVE_WAREHOUSE_REF");
        waitFor(ctx, () -> !backend.references(server, fixture.secondProject(), fixture.secondWarehouse()),
                "共享项目最后引用的服务端移除未生效");
        waitForProjection(ctx, fixture, true, false, backend.areaEditSubscribesMaterialList(), false,
                "最后引用移除后共享仓库仍然高亮");
        DemoStepLogger.mark("shared-reference-removed");

        // Opening A establishes its own material-list wireframe, independently of B's pickup union.
        DemoStepLogger.mark("open-project-a");
        ctx.runOnClient(client -> openMaterialList(fixture.firstProject()));
        waitForProjection(ctx, fixture, true, false, true, false,
                "项目A材料清单的仓库线框未建立");
        ctx.runOnClient(client -> ClientScreenAccess.showScreen(client, null));
        DemoStepLogger.mark("project-a-wireframe");

        // Removing A's last warehouse must deliver an explicit empty snapshot.
        DemoStepLogger.mark("remove-final-reference");
        reference(ctx, fixture.firstProject(), fixture.firstWarehouse(), "REMOVE_WAREHOUSE_REF");
        waitFor(ctx, () -> !backend.references(server, fixture.firstProject(), fixture.firstWarehouse()),
                "项目A最后引用的服务端移除未生效");
        waitForProjection(ctx, fixture, false, false, false, false,
                "最后一个仓库移除后未收到空的旧取货快照");
        DemoStepLogger.mark("pickup-cleared");
    }

    private static void openMaterialList(String projectId) {
        ClientPlayNetworking.send(new MaterialStatsRequestC2SPacket(projectId));
        ClientPlayNetworking.send(new QueryMaterialStatusC2SPacket(projectId));
    }

    private static void reference(ClientGameTestContext ctx, String projectId,
                                  int warehouseId, String action) {
        ctx.runOnClient(client -> ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                projectId, action, warehouseId, Optional.empty())));
    }

    private static boolean projection(ClientGameTestContext ctx, Fixture fixture,
                                      boolean firstContainer, boolean secondContainer,
                                      boolean firstReference, boolean secondReference) {
        return ctx.computeOnClient(client -> {
            var renderer = StagingAreaRenderer.getInstance();
            var containers = renderer.getWarehouseContainers();
            return contains(containers, fixture.firstContainer()) == firstContainer
                    && contains(containers, fixture.secondContainer()) == secondContainer
                    && renderer.isWarehouseReferenced(fixture.firstWarehouse()) == firstReference
                    && renderer.isWarehouseReferenced(fixture.secondWarehouse()) == secondReference;
        });
    }

    private static boolean contains(
            List<net.syncmaterial.syncmaterial.network.WarehouseContainerResponseS2CPacket.ContainerEntry> entries,
            BlockPos pos) {
        return entries.stream().anyMatch(entry -> entry.posX() == pos.getX()
                && entry.posY() == pos.getY() && entry.posZ() == pos.getZ()
                && entry.itemIds().contains("minecraft:dirt"));
    }

    private static void waitForProjection(ClientGameTestContext ctx, Fixture fixture,
                                          boolean firstContainer, boolean secondContainer,
                                          boolean firstReference, boolean secondReference,
                                          String message) {
        try {
            waitFor(ctx, () -> projection(ctx, fixture, firstContainer, secondContainer,
                    firstReference, secondReference), message);
        } catch (AssertionError failure) {
            String actual = ctx.computeOnClient(client -> {
                var renderer = StagingAreaRenderer.getInstance();
                return "containers=" + renderer.getWarehouseContainers()
                        + ", firstRef=" + renderer.isWarehouseReferenced(fixture.firstWarehouse())
                        + ", secondRef=" + renderer.isWarehouseReferenced(fixture.secondWarehouse())
                        + ", areas=" + renderer.getWarehouseAreas().size();
            });
            throw new AssertionError(message + "；实际投影 " + actual, failure);
        }
    }

    private static void waitFor(ClientGameTestContext ctx, BooleanSupplier condition, String message) {
        for (int elapsed = 0; elapsed < 300; elapsed += 5) {
            ctx.waitTicks(5);
            if (condition.getAsBoolean()) return;
        }
        throw new AssertionError(message);
    }
}
