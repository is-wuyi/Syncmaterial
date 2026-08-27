package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.selection.AreaSelection;

/**
 * 客户端收包后的状态落地测试（不在界面打开时的世界侧处理）：
 * 备货区选区同步、原理图删除清理、仓库容器缓存。
 */
class ClientPacketHandlerTest {

    private MockedStatic<Minecraft> clientMock;

    private static final String SCHEMATIC = "client-test-1";

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        // mock 客户端单例：currentScreen() 因 gui 字段为 null 返回 null（无打开界面），
        // player 等字段同样为 null，走"无界面/无玩家"分支
        clientMock = mockStatic(Minecraft.class);
        clientMock.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
    }

    @AfterEach
    void tearDown() {
        clientMock.close();
        StagingAreaRenderer.getInstance().removeRenderData(SCHEMATIC);
        StagingAreaRenderer.getInstance().clearWarehouseContainers();
        StagingAreaRenderer.getInstance().clearWarehouseAreas();
    }

    private static StagingAreaConfigResponseS2CPacket.AreaInfo area(int id, String name) {
        return new StagingAreaConfigResponseS2CPacket.AreaInfo(
            id, name, 0, 64, 0, 5, 70, 5, "minecraft:overworld");
    }

    @Test
    void configResponse_withAreas_buildsSelectionAndServerIds() {
        var payload = new StagingAreaConfigResponseS2CPacket("LIST", SCHEMATIC, "我的建筑", true, "",
            List.of(area(1, "区域A"), area(2, "区域B")));

        ModNetworkHandlerClient.handleStagingAreaConfigResponseForWorld(payload);

        var renderer = StagingAreaRenderer.getInstance();
        AreaSelection selection = renderer.getSelection(SCHEMATIC);
        assertNotNull(selection, "收到区域列表后应创建选区");
        assertEquals(2, selection.getAllSubRegionBoxes().size());
        assertEquals(Integer.valueOf(1), selection.getServerId("区域A"), "serverId 应映射到子区域名");
        assertEquals(Integer.valueOf(2), selection.getServerId("区域B"));
        assertEquals("我的建筑", renderer.getSchematicName(SCHEMATIC));
    }

    @Test
    void configResponse_emptyAreas_clearsRenderData() {
        StagingAreaRenderer.getInstance().updateSelection(SCHEMATIC, new AreaSelection());

        var payload = new StagingAreaConfigResponseS2CPacket("LIST", SCHEMATIC, "", true, "", List.of());
        ModNetworkHandlerClient.handleStagingAreaConfigResponseForWorld(payload);

        assertNull(StagingAreaRenderer.getInstance().getSelection(SCHEMATIC), "空区域列表应清理渲染数据");
    }

    @Test
    void configResponse_schematicDeleted_clearsEverything() {
        StagingAreaRenderer.getInstance().updateSelection(SCHEMATIC, new AreaSelection());
        StagingAreaRenderer.getInstance().setSchematicName(SCHEMATIC, "旧名字");

        var payload = new StagingAreaConfigResponseS2CPacket("SCHEMATIC_DELETED", SCHEMATIC, "", true, "", List.of());
        ModNetworkHandlerClient.handleStagingAreaConfigResponseForWorld(payload);

        assertNull(StagingAreaRenderer.getInstance().getSelection(SCHEMATIC));
        assertNull(StagingAreaRenderer.getInstance().getSchematicName(SCHEMATIC));
    }

    @Test
    void configResponse_emptyName_keepsExistingName() {
        StagingAreaRenderer.getInstance().setSchematicName(SCHEMATIC, "已有的名字");

        // 带 area 走正常同步路径（空 areas 会清理包括名字在内的全部渲染数据，另测）
        var payload = new StagingAreaConfigResponseS2CPacket("LIST", SCHEMATIC, "", true, "",
            List.of(area(1, "区域A")));
        ModNetworkHandlerClient.handleStagingAreaConfigResponseForWorld(payload);

        assertEquals("已有的名字", StagingAreaRenderer.getInstance().getSchematicName(SCHEMATIC),
            "空名称不应覆盖已有标注");
    }

    @Test
    void warehouseContainers_cachedForPickupHighlight() {
        var containers = List.of(new WarehouseContainerResponseS2CPacket.ContainerEntry(
            1, 64, 1, List.of("minecraft:stone")));

        ModNetworkHandlerClient.handleWarehouseContainerResponse(
            new WarehouseContainerResponseS2CPacket(containers));

        assertEquals(containers, StagingAreaRenderer.getInstance().getWarehouseContainers(),
            "取货模式容器数据应进入客户端缓存");
    }

    // ========== 仓库区域线框 ==========

    @Test
    void warehouseAreas_cachedWithReferencedFlags() {
        var warehouses = List.of(area(3, "仓库A"), area(4, "仓库B"));

        ModNetworkHandlerClient.handleWarehouseAreaResponse(
            new WarehouseAreaResponseS2CPacket(warehouses, List.of(3)));

        var renderer = StagingAreaRenderer.getInstance();
        assertEquals(warehouses, renderer.getWarehouseAreas(), "仓库区域数据应进入客户端缓存");
        assertTrue(renderer.isWarehouseReferenced(3), "被引用的仓库应标记为高亮");
        assertFalse(renderer.isWarehouseReferenced(4), "未被引用的仓库不应高亮");
    }

    @Test
    void warehouseAreas_emptyBroadcast_clearsCache() {
        ModNetworkHandlerClient.handleWarehouseAreaResponse(
            new WarehouseAreaResponseS2CPacket(List.of(area(3, "仓库A")), List.of(3)));

        ModNetworkHandlerClient.handleWarehouseAreaResponse(
            new WarehouseAreaResponseS2CPacket(List.of(), List.of()));

        assertTrue(StagingAreaRenderer.getInstance().getWarehouseAreas().isEmpty(),
            "仓库全部删除后应清空线框数据");
        assertFalse(StagingAreaRenderer.getInstance().isWarehouseReferenced(3));
    }

    @Test
    void warehouseAreas_doNotLeakIntoStagingSelections() {
        // 仓库类响应的 schematicId 是空串；若不拦截，仓库会被当成备货区
        // 写进 key 为 "" 的 selections，并按备货区颜色渲染
        var payload = new StagingAreaConfigResponseS2CPacket(
            "LIST_WAREHOUSES", "", "", true, "ok", List.of(area(3, "仓库A")));

        ModNetworkHandlerClient.handleStagingAreaConfigResponseForWorld(payload);

        assertNull(StagingAreaRenderer.getInstance().getSelection(""),
            "仓库响应不得污染备货区选区数据");
    }
}
