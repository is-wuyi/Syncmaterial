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

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.selection.AreaSelection;

/**
 * 客户端收包后的状态落地测试（不在界面打开时的世界侧处理）：
 * 备货区选区同步、原理图删除清理、仓库容器缓存。
 */
class ClientPacketHandlerTest {

    private MockedStatic<MinecraftClient> clientMock;

    private static final String SCHEMATIC = "client-test-1";

    @BeforeAll
    static void setup() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void setUp() {
        // mock 客户端单例：currentScreen/player 字段默认 null（无界面/无玩家）
        clientMock = mockStatic(MinecraftClient.class);
        clientMock.when(MinecraftClient::getInstance).thenReturn(mock(MinecraftClient.class));
    }

    @AfterEach
    void tearDown() {
        clientMock.close();
        StagingAreaRenderer.getInstance().removeRenderData(SCHEMATIC);
        StagingAreaRenderer.getInstance().clearWarehouseContainers();
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
}
