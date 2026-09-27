package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.ClientProtocolState;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket.AreaInfo;

/**
 * 世界侧备货区线框同步回归：
 * 1. 全量 LIST 响应是权威列表——被删除/改名的区域必须从客户端渲染器移除，
 *    否则残留幽灵线框和陈旧 serverId（此前只增不删）。
 * 2. 刷新不得重置用户的线框隐藏开关，也不得波及其它原理图。
 * 3. 断线后所有备货区线框状态必须清空，否则换服撞号会把上个服的线框画进新服。
 */
public class StagingAreaSnapshotClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String schematicId = "client-area-snapshot-" + System.nanoTime();
        String otherId = schematicId + "-other";
        try {
            try (var server = ctx.worldBuilder().createServer();
                 var connection = server.connect()) {
                ctx.waitFor(client -> ClientProtocolState.isUsable());
                ctx.runOnClient(client -> client.setScreenAndShow(null));

                sendSnapshot(server, "LIST", schematicId, "Initial", List.of(
                    area(41, "Old name", 4), area(57, "Deleted", 8), area(93, "Unchanged", 12)));
                sendSnapshot(server, "LIST", otherId, "Other schematic", List.of(area(105, "Other", 16)));
                ctx.waitFor(client -> {
                    var renderer = StagingAreaRenderer.getInstance();
                    var selection = renderer.getSelection(schematicId);
                    return selection != null && selection.getAllSubRegionNames().size() == 3
                        && renderer.getSelection(otherId) != null;
                });
                ctx.runOnClient(client -> {
                    var renderer = StagingAreaRenderer.getInstance();
                    renderer.setRenderEnabled(schematicId, false);
                    if (!Integer.valueOf(41).equals(renderer.getSelection(schematicId).getServerId("Old name"))) {
                        throw new AssertionError("初始区域 ID 未经网络响应同步，无法验证后续替换");
                    }
                });

                // 全量刷新：Old name 改名为 Renamed，Deleted 被删，Unchanged 保留
                sendSnapshot(server, "LIST", schematicId, "Updated", List.of(
                    area(41, "Renamed", 20), area(93, "Unchanged", 24)));
                ctx.waitFor(client -> "Updated".equals(
                    StagingAreaRenderer.getInstance().getSchematicName(schematicId)));
                ctx.runOnClient(client -> {
                    var renderer = StagingAreaRenderer.getInstance();
                    var selection = renderer.getSelection(schematicId);
                    Set<String> actual = Set.copyOf(selection.getAllSubRegionNames());
                    if (!actual.equals(Set.of("Renamed", "Unchanged"))) {
                        throw new AssertionError("全量响应后仍残留旧名称或已删除区域: " + actual);
                    }
                    if (selection.getServerId("Old name") != null || selection.getServerId("Deleted") != null) {
                        throw new AssertionError("已删除或改名区域的服务端 ID 映射未清理");
                    }
                    if (!Integer.valueOf(41).equals(selection.getServerId("Renamed"))
                            || !new BlockPos(24, 64, 24).equals(selection.getSubRegionBox("Unchanged").getPos1())) {
                        throw new AssertionError("区域 ID 或新坐标未同步");
                    }
                    if (renderer.isRenderEnabled(schematicId)) {
                        throw new AssertionError("刷新区域列表不应重置用户的线框隐藏开关");
                    }
                    if (!Set.copyOf(renderer.getSelection(otherId).getAllSubRegionNames()).equals(Set.of("Other"))) {
                        throw new AssertionError("更新一个原理图误清理了另一个原理图");
                    }
                });
            }

            // 断线后所有备货区线框状态必须清空
            ctx.waitTicks(20);
            ctx.runOnClient(client -> {
                var renderer = StagingAreaRenderer.getInstance();
                if (renderer.getSelection(schematicId) != null || renderer.getSelection(otherId) != null) {
                    throw new AssertionError("断线后备货区线框未清空，换服会带入上个服的线框");
                }
            });
        } finally {
            ctx.runOnClient(client -> StagingAreaRenderer.getInstance().clearAllSelections());
        }
    }

    private static AreaInfo area(int id, String name, int coordinate) {
        return new AreaInfo(id, name, coordinate, 64, coordinate,
            coordinate + 1, 65, coordinate + 1, "minecraft:overworld");
    }

    private static void sendSnapshot(TestDedicatedServerContext server, String action,
                                     String schematicId, String name, List<AreaInfo> areas) {
        server.runOnServer(instance -> ServerPlayNetworking.send(
            instance.getPlayerList().getPlayers().getFirst(),
            new StagingAreaConfigResponseS2CPacket(action, schematicId, name, true, "", areas)));
    }
}
