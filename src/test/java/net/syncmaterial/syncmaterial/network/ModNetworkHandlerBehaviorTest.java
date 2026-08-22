package net.syncmaterial.syncmaterial.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;

/**
 * ModNetworkHandler C2S 行为测试：
 * JoinCollaboration 的自行认领门控、InventoryUpdate 的协作者门控、validate* 边界。
 */
class ModNetworkHandlerBehaviorTest {

    private MockedStatic<SyncMaterial> syncMaterialMock;
    private MockedStatic<ServerPlayNetworking> networkingMock;

    private SchematicDatabase db;
    private CollaborationManager cm;
    private MinecraftServer server;
    private ServerPlayerEntity player;

    @BeforeAll
    static void setup() {
        // mock ServerPlayerEntity 会触发 Entity 静态初始化，需要注册表（与执行顺序无关）
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    void setUp() {
        db = mock(SchematicDatabase.class);
        cm = mock(CollaborationManager.class);
        server = mock(MinecraftServer.class);
        player = mock(ServerPlayerEntity.class);
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), "Player1"));

        syncMaterialMock = mockStatic(SyncMaterial.class);
        syncMaterialMock.when(SyncMaterial::getSharedDatabase).thenReturn(db);

        networkingMock = mockStatic(ServerPlayNetworking.class);

        ModNetworkHandler.initializeServices(mock(DatabaseQueryService.class), cm);
    }

    @AfterEach
    void tearDown() {
        syncMaterialMock.close();
        networkingMock.close();
    }

    // ========== JoinCollaboration: 自行认领门控 ==========

    @Test
    void join_selfClaimDisabledAndNonOwner_silentlyDropped() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(false);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10)), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void join_selfClaimDisabledButOwner_proceeds() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(false);
        when(db.isOwner("s1", "Player1")).thenReturn(true);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of(1, 10, 2, 20)), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
        // 加入成功后，背包上报逐条写入
        verify(cm).updatePlayerInventory("Player1", "s1", 1, 10);
        verify(cm).updatePlayerInventory("Player1", "s1", 2, 20);
    }

    @Test
    void join_selfClaimEnabled_anyPlayerCanJoin() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenReturn(true);
        when(db.isOwner("s1", "Player1")).thenReturn(false);
        when(cm.joinCollaboration("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm).joinCollaboration("s1", 42, "Player1");
    }

    @Test
    void join_dbError_dropsRequest() throws Exception {
        when(db.getAllowSelfClaim("s1")).thenThrow(new RuntimeException("db down"));

        ModNetworkHandler.handleJoinCollaboration(
            new JoinCollaborationC2SPacket("s1", 42, Map.of()), player, server);

        verify(cm, never()).joinCollaboration(any(), anyInt(), any());
    }

    // ========== InventoryUpdate: 协作者门控 ==========

    @Test
    void inventoryUpdate_byNonCollaborator_ignored() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(false);

        ModNetworkHandler.handleInventoryUpdate(
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm, never()).updatePlayerInventory(any(), any(), anyInt(), anyInt());
    }

    @Test
    void inventoryUpdate_byCollaborator_updatesCount() throws Exception {
        when(cm.isCollaborating("s1", 42, "Player1")).thenReturn(true);

        ModNetworkHandler.handleInventoryUpdate(
            new InventoryUpdateC2SPacket("s1", 42, 64), player, server);

        verify(cm).updatePlayerInventory("Player1", "s1", 42, 64);
    }

    // ========== validate* 边界 ==========

    @Test
    void validateSchematicId_boundaries() {
        assertTrue(ModNetworkHandler.validateSchematicId("a"));
        assertTrue(ModNetworkHandler.validateSchematicId("x".repeat(100)), "100 字符应通过");
        assertFalse(ModNetworkHandler.validateSchematicId("x".repeat(101)), "101 字符应拒绝");
    }

    @Test
    void validateSchematicId_nullOrBlank() {
        assertFalse(ModNetworkHandler.validateSchematicId(null));
        assertFalse(ModNetworkHandler.validateSchematicId(""));
        assertFalse(ModNetworkHandler.validateSchematicId("   "));
    }

    @Test
    void validateMaterialId_negativeRejected() {
        assertTrue(ModNetworkHandler.validateMaterialId(0));
        assertFalse(ModNetworkHandler.validateMaterialId(-1));
    }

    @Test
    void validateCount_negativeRejected() {
        assertTrue(ModNetworkHandler.validateCount(0));
        assertFalse(ModNetworkHandler.validateCount(-1));
    }

    @Test
    void validateStagingAction_whitelist() {
        String[] valid = {"LIST", "ADD", "RENAME", "DELETE", "UPDATE", "CLEAR",
            "LIST_WAREHOUSES", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE",
            "ADD_WAREHOUSE_REF", "REMOVE_WAREHOUSE_REF", "LIST_WAREHOUSE_REFS"};
        for (String action : valid) {
            assertTrue(ModNetworkHandler.validateStagingAction(action), "应接受: " + action);
        }
        assertFalse(ModNetworkHandler.validateStagingAction(null));
        assertFalse(ModNetworkHandler.validateStagingAction("lowercase"));
        assertFalse(ModNetworkHandler.validateStagingAction("DROP_TABLE"));
    }

    @Test
    void validateOwnerAction_whitelistAndCaseSensitivity() {
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TRANSFER"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("ADD_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("REMOVE_DEPUTY"));
        assertDoesNotThrow(() -> Phase4Handler.validateOwnerAction("TOGGLE_SELF_CLAIM"));
        // 白名单区分大小写
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("transfer"));
        assertThrows(IllegalArgumentException.class, () -> Phase4Handler.validateOwnerAction("HACK"));
    }
}
