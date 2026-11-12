package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * 备货区/仓库网络配置操作全集测试（除 ADD 外的全部 action，ADD 见 ModNetworkHandlerBehaviorTest）。
 * owner 管理层从显式上下文读取，不再使用 SyncMaterial 静态 getter。
 */
class StagingAreaConfigHandlerTest {

    private MockedStatic<ServerPlayNetworking> networkingMock;

    private LegacyNetworkTestSupport support;
    private MinecraftServer server;
    private ServerPlayer player;
    private LegacyServerContext owner;
    private StagingAreaManager manager;

    @BeforeAll
    static void setup() {
        LegacyNetworkTestSupport.bootstrap();
    }

    @BeforeEach
    void setUp() {
        support = new LegacyNetworkTestSupport("Operator1");
        server = support.server;
        player = support.player;
        owner = support.owner;
        manager = support.staging;
        support.handshake();

        networkingMock = mockStatic(ServerPlayNetworking.class);
    }

    @AfterEach
    void tearDown() {
        networkingMock.close();
        support.close();
    }

    private StagingAreaConfigResponseS2CPacket handle(StagingAreaConfigC2SPacket payload) {
        ModNetworkHandler.handleStagingAreaConfig(owner, payload, player, server);
        ArgumentCaptor<net.minecraft.network.protocol.common.custom.CustomPacketPayload> captor =
            ArgumentCaptor.forClass(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class);
        networkingMock.verify(() -> ServerPlayNetworking.send(eq(player), captor.capture()),
            atLeastOnce());
        return captor.getAllValues().stream()
            .filter(StagingAreaConfigResponseS2CPacket.class::isInstance)
            .map(StagingAreaConfigResponseS2CPacket.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("未发送 StagingAreaConfigResponseS2CPacket"));
    }

    private static StagingAreaConfigC2SPacket packet(String action, int areaId, Optional<AreaData> data) {
        return new StagingAreaConfigC2SPacket("s1", action, areaId, data);
    }

    private static AreaData areaData(String name, Optional<String> world) {
        return new AreaData(name, 0, 64, 0, 10, 70, 10, world);
    }

    // ========== 备货区操作 ==========

    @Test
    void list_subscribesAndResponds() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        var resp = handle(packet("LIST", 0, Optional.empty()));

        verify(manager).subscribe(player, "s1");
        assertTrue(resp.success());
        assertEquals("LIST", resp.action());
    }

    @Test
    void rename_withData_renamesAndBroadcasts() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        var resp = handle(packet("RENAME", 7, Optional.of(areaData("新名字", Optional.empty()))));

        verify(manager).renameStagingArea(7, "s1", "新名字");
        verify(manager).broadcastUpdate("s1");
        assertTrue(resp.success());
        assertEquals("备货区已重命名", resp.message());
    }

    @Test
    void rename_withoutData_rejected() {
        var resp = handle(packet("RENAME", 7, Optional.empty()));

        verify(manager, never()).renameStagingArea(anyInt(), any(), any());
        assertFalse(resp.success());
        assertEquals("缺少区域数据", resp.message());
    }

    @Test
    void update_updatesRescansAndBroadcasts() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        var resp = handle(packet("UPDATE", 7, Optional.of(areaData("改名扩区", Optional.empty()))));

        verify(manager).updateStagingArea(eq(7), eq("s1"), eq("改名扩区"), isNull(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(manager).rescanStagingArea(7);
        verify(manager).broadcastUpdate("s1");
        assertTrue(resp.success());
        assertEquals("备货区已更新", resp.message());
    }

    @Test
    void update_withWorld_migratesDimension() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        var resp = handle(packet("UPDATE", 7,
                Optional.of(areaData("跨维度", Optional.of("minecraft:the_nether")))));

        verify(manager).updateStagingArea(eq(7), eq("s1"), eq("跨维度"), eq("minecraft:the_nether"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertTrue(resp.success());
    }

    @Test
    void update_withoutData_rejected() {
        var resp = handle(packet("UPDATE", 7, Optional.empty()));

        verify(manager, never()).updateStagingArea(anyInt(), any(), any(), any(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertFalse(resp.success());
    }

    @Test
    void delete_removesAndBroadcasts() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of());

        var resp = handle(packet("DELETE", 7, Optional.empty()));

        verify(manager).removeStagingArea(7, "s1");
        verify(manager).broadcastUpdate("s1");
        assertTrue(resp.success());
        assertEquals("备货区已删除", resp.message());
    }

    @Test
    void clear_removesAllAreasOfSchematic() {
        when(manager.getStagingAreas("s1")).thenReturn(List.of(
            new StagingAreaManager.StagingArea(1, "minecraft:overworld", "A", 0, 0, 0, 1, 1, 1),
            new StagingAreaManager.StagingArea(2, "minecraft:overworld", "B", 0, 0, 0, 1, 1, 1)));

        var resp = handle(packet("CLEAR", 0, Optional.empty()));

        verify(manager).removeStagingArea(1, "s1");
        verify(manager).removeStagingArea(2, "s1");
        assertTrue(resp.success());
        assertEquals("已清除所有备货区", resp.message());
    }

    // ========== 仓库操作（Phase 5） ==========

    @Test
    void listWarehouses_returnsAll() {
        when(manager.getAllWarehouses()).thenReturn(List.of(
            new StagingAreaManager.Warehouse(3, "仓A", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));

        var resp = handle(packet("LIST_WAREHOUSES", 0, Optional.empty()));

        assertTrue(resp.success());
        assertEquals(1, resp.areas().size());
        assertEquals("仓A", resp.areas().get(0).name());
    }

    @Test
    void addWarehouse_success() {
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(9);

        var resp = handle(packet("ADD_WAREHOUSE", 0,
                Optional.of(areaData("新仓库", Optional.of("minecraft:overworld")))));

        verify(manager).addWarehouse(eq("新仓库"), eq("minecraft:overworld"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertTrue(resp.success());
        assertEquals("仓库已创建", resp.message());
    }

    @Test
    void addWarehouse_failure() {
        when(manager.addWarehouse(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(-1);

        var resp = handle(packet("ADD_WAREHOUSE", 0,
                Optional.of(areaData("新仓库", Optional.empty()))));

        assertFalse(resp.success());
        assertEquals("创建失败", resp.message());
    }

    @Test
    void updateWarehouse_updates() {
        var resp = handle(packet("UPDATE_WAREHOUSE", 9, Optional.of(areaData("改名", Optional.empty()))));

        verify(manager).updateWarehouse(eq(9), eq("改名"), isNull(),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertTrue(resp.success());
        assertEquals("仓库已更新", resp.message());
    }

    @Test
    void updateWarehouse_withWorld_migratesDimension() {
        var resp = handle(packet("UPDATE_WAREHOUSE", 9,
                Optional.of(areaData("跨维度仓库", Optional.of("minecraft:the_end")))));

        verify(manager).updateWarehouse(eq(9), eq("跨维度仓库"), eq("minecraft:the_end"),
            anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertTrue(resp.success());
    }

    @Test
    void deleteWarehouse_deletes() {
        var resp = handle(packet("DELETE_WAREHOUSE", 9, Optional.empty()));

        verify(manager).deleteWarehouse(9);
        assertTrue(resp.success());
        assertEquals("仓库已删除", resp.message());
    }

    @Test
    void addWarehouseRef_addsAndReturnsRefs() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "仓A", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));

        var resp = handle(packet("ADD_WAREHOUSE_REF", 9, Optional.empty()));

        verify(manager).addWarehouseReference("s1", 9);
        assertTrue(resp.success());
        assertEquals(1, resp.areas().size());
    }

    @Test
    void removeWarehouseRef_removes() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of());

        var resp = handle(packet("REMOVE_WAREHOUSE_REF", 9, Optional.empty()));

        verify(manager).removeWarehouseReference("s1", 9);
        assertTrue(resp.success());
    }

    @Test
    void listWarehouseRefs_returnsRefs() {
        when(manager.getWarehousesForSchematic("s1")).thenReturn(List.of(
            new StagingAreaManager.Warehouse(9, "仓A", "minecraft:overworld", 0, 0, 0, 5, 5, 5)));

        var resp = handle(packet("LIST_WAREHOUSE_REFS", 0, Optional.empty()));

        assertTrue(resp.success());
        assertEquals(1, resp.areas().size());
    }

    // ========== 异常分支 ==========

    @Test
    void unknownAction_rejected() {
        var resp = handle(packet("DROP_TABLE", 0, Optional.empty()));

        assertFalse(resp.success());
        assertTrue(resp.message().startsWith("未知操作"));
        verifyNoInteractions(manager);
    }

    @Test
    void managerMissing_rejected() {
        when(owner.staging()).thenReturn(null);

        var resp = handle(packet("LIST", 0, Optional.empty()));

        assertFalse(resp.success());
        assertEquals("备货区服务未初始化", resp.message());
    }

    @Test
    void isAreaGeometryValid_acceptsNormalAndRejectsAbusive() {
        assertTrue(ModNetworkHandler.isAreaGeometryValid(0, 64, 0, 5, 70, 5));
        assertTrue(ModNetworkHandler.isAreaGeometryValid(-100, 0, -100, 100, 100, 100));
        assertFalse(ModNetworkHandler.isAreaGeometryValid(0, 64, 0, 40_000_000, 64, 0));
        assertFalse(ModNetworkHandler.isAreaGeometryValid(
            Integer.MIN_VALUE, 64, Integer.MIN_VALUE, Integer.MAX_VALUE, 64, Integer.MAX_VALUE));
        assertFalse(ModNetworkHandler.isAreaGeometryValid(0, -999999, 0, 1, 999999, 1));
    }
}
