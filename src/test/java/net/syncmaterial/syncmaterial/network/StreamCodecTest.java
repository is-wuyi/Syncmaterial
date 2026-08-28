package net.syncmaterial.syncmaterial.network;

import io.netty.buffer.Unpooled;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网络包 codec roundtrip 测试。
 * 编码后解码，验证数据一致性，防止跨版本 codec 不兼容。
 */
class StreamCodecTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    private RegistryFriendlyByteBuf createBuf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private <T> T roundtrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T original) {
        RegistryFriendlyByteBuf buf = createBuf();
        try {
            codec.encode(buf, original);
            T decoded = codec.decode(buf);
            assertEquals(0, buf.readableBytes(), "编码/解码后应无剩余字节");
            return decoded;
        } finally {
            buf.release();
        }
    }

    // ==================== 版本握手包 ====================
    // 这两个包的字段结构永久冻结，测试同时起到"格式未被改动"的看门作用

    @Test
    void helloC2S_roundtrip() {
        var original = new HelloC2SPacket(3, "1.21.7-0.3.0-beta.25");
        var decoded = roundtrip(HelloC2SPacket.CODEC, original);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.modVersion(), decoded.modVersion());
    }

    @Test
    void helloS2C_roundtrip() {
        var original = new HelloS2CPacket(5, "1.21.7-0.4.0", true);
        var decoded = roundtrip(HelloS2CPacket.CODEC, original);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.modVersion(), decoded.modVersion());
        assertEquals(original.accepted(), decoded.accepted());
    }

    @Test
    void helloS2C_rejected_roundtrip() {
        var decoded = roundtrip(HelloS2CPacket.CODEC, new HelloS2CPacket(9, "unknown", false));
        assertFalse(decoded.accepted());
        assertEquals(9, decoded.protocolVersion());
    }

    // ==================== S2C 包 ====================

    @Test
    void collaborationStatus_roundtrip() {
        var original = new CollaborationStatusS2CPacket(
                "550e8400-e29b-41d4-a716-446655440000", 42, 64, 32, 16,
                List.of(
                        new CollaborationStatusS2CPacket.ParticipantInfo("Player1", 10),
                        new CollaborationStatusS2CPacket.ParticipantInfo("Player2", 20)
                ),
                List.of(
                        new CollaborationStatusS2CPacket.AreaFreshnessInfo("staging", 1, "Area1", "fresh"),
                        new CollaborationStatusS2CPacket.AreaFreshnessInfo("warehouse", 2, "Area2", "stale")
                )
        );
        var decoded = roundtrip(CollaborationStatusS2CPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialId(), decoded.materialId());
        assertEquals(original.totalCount(), decoded.totalCount());
        assertEquals(original.stagingCount(), decoded.stagingCount());
        assertEquals(original.warehouseCount(), decoded.warehouseCount());
        assertEquals(original.participants().size(), decoded.participants().size());
        assertEquals(original.participants().get(0).playerName(), decoded.participants().get(0).playerName());
        assertEquals(original.participants().get(0).count(), decoded.participants().get(0).count());
        assertEquals(original.freshnessInfo().size(), decoded.freshnessInfo().size());
        assertEquals(original.freshnessInfo().get(0).areaType(), decoded.freshnessInfo().get(0).areaType());
        assertEquals(original.freshnessInfo().get(0).areaId(), decoded.freshnessInfo().get(0).areaId());
    }

    @Test
    void collaborationStatus_emptyLists_roundtrip() {
        var original = new CollaborationStatusS2CPacket(
                "test-uuid", 1, 100, 50, 25,
                List.of(), List.of()
        );
        var decoded = roundtrip(CollaborationStatusS2CPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertTrue(decoded.participants().isEmpty());
        assertTrue(decoded.freshnessInfo().isEmpty());
    }

    @Test
    void materialStatsResponse_roundtrip() {
        var original = new MaterialStatsResponseS2CPacket(
                "test-uuid", "Test Schematic",
                List.of(
                        new MaterialEntry(1, new ItemStack(Items.STONE), 64, 32, 0, 32),
                        new MaterialEntry(2, new ItemStack(Items.DIAMOND), 10, 5, 0, 5)
                ),
                true, true, "OwnerName",
                List.of("Deputy1", "Deputy2"),
                true
        );
        var decoded = roundtrip(MaterialStatsResponseS2CPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.schematicName(), decoded.schematicName());
        assertEquals(original.materials().size(), decoded.materials().size());
        assertEquals(original.isOwner(), decoded.isOwner());
        assertEquals(original.isMainOwner(), decoded.isMainOwner());
        assertEquals(original.ownerName(), decoded.ownerName());
        assertEquals(original.deputyOwners(), decoded.deputyOwners());
        assertEquals(original.allowSelfClaim(), decoded.allowSelfClaim());
    }

    @Test
    void materialStatsResponse_emptyMaterials_roundtrip() {
        var original = new MaterialStatsResponseS2CPacket(
                "test-uuid", "Empty Schematic",
                List.of(),
                false, false, "", List.of(), false
        );
        var decoded = roundtrip(MaterialStatsResponseS2CPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertTrue(decoded.materials().isEmpty());
    }

    @Test
    void stagingAreaConfigResponse_roundtrip() {
        var original = new StagingAreaConfigResponseS2CPacket(
                "LIST", "test-uuid", "Test Schematic", true, "Success",
                List.of(
                        new StagingAreaConfigResponseS2CPacket.AreaInfo(1, "Area1", 0, 0, 0, 10, 10, 10, "minecraft:overworld"),
                        new StagingAreaConfigResponseS2CPacket.AreaInfo(2, "Area2", 100, 0, 100, 200, 10, 200, "minecraft:the_nether")
                )
        );
        var decoded = roundtrip(StagingAreaConfigResponseS2CPacket.CODEC, original);
        assertEquals(original.action(), decoded.action());
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.schematicName(), decoded.schematicName());
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
        assertEquals(original.areas().size(), decoded.areas().size());
        assertEquals(original.areas().get(0).areaId(), decoded.areas().get(0).areaId());
        assertEquals(original.areas().get(0).name(), decoded.areas().get(0).name());
        assertEquals(original.areas().get(0).x1(), decoded.areas().get(0).x1());
        assertEquals(original.areas().get(0).world(), decoded.areas().get(0).world());
    }

    @Test
    void stagingAreaConfigResponse_emptyAreas_roundtrip() {
        var original = new StagingAreaConfigResponseS2CPacket(
                "LIST", "test-uuid", "Test", true, "OK", List.of()
        );
        var decoded = roundtrip(StagingAreaConfigResponseS2CPacket.CODEC, original);
        assertTrue(decoded.areas().isEmpty());
    }

    @Test
    void playerListResponse_roundtrip() {
        var original = new PlayerListResponseS2CPacket(
                List.of(
                        new PlayerListResponseS2CPacket.PlayerInfo("Player1", true),
                        new PlayerListResponseS2CPacket.PlayerInfo("Player2", false)
                )
        );
        var decoded = roundtrip(PlayerListResponseS2CPacket.CODEC, original);
        assertEquals(original.players().size(), decoded.players().size());
        assertEquals(original.players().get(0).name(), decoded.players().get(0).name());
        assertEquals(original.players().get(0).online(), decoded.players().get(0).online());
        assertEquals(original.players().get(1).name(), decoded.players().get(1).name());
        assertEquals(original.players().get(1).online(), decoded.players().get(1).online());
    }

    @Test
    void playerListResponse_empty_roundtrip() {
        var original = new PlayerListResponseS2CPacket(List.of());
        var decoded = roundtrip(PlayerListResponseS2CPacket.CODEC, original);
        assertTrue(decoded.players().isEmpty());
    }

    @Test
    void warehouseContainerResponse_roundtrip() {
        var original = new WarehouseContainerResponseS2CPacket(
                List.of(
                        new WarehouseContainerResponseS2CPacket.ContainerEntry(100, 64, 200, List.of("minecraft:stone", "minecraft:diamond")),
                        new WarehouseContainerResponseS2CPacket.ContainerEntry(300, 64, 400, List.of("minecraft:iron_ingot"))
                )
        );
        var decoded = roundtrip(WarehouseContainerResponseS2CPacket.CODEC, original);
        assertEquals(original.containers().size(), decoded.containers().size());
        assertEquals(original.containers().get(0).posX(), decoded.containers().get(0).posX());
        assertEquals(original.containers().get(0).posY(), decoded.containers().get(0).posY());
        assertEquals(original.containers().get(0).posZ(), decoded.containers().get(0).posZ());
        assertEquals(original.containers().get(0).itemIds(), decoded.containers().get(0).itemIds());
    }

    @Test
    void ownerActionResponse_roundtrip() {
        var original = new OwnerActionResponseS2CPacket(
                true, "Success", "OwnerName", List.of("Deputy1", "Deputy2"), true
        );
        var decoded = roundtrip(OwnerActionResponseS2CPacket.CODEC, original);
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
        assertEquals(original.ownerName(), decoded.ownerName());
        assertEquals(original.deputyOwners(), decoded.deputyOwners());
        assertEquals(original.allowSelfClaim(), decoded.allowSelfClaim());
    }

    @Test
    void ownerActionResponse_failure_roundtrip() {
        var original = new OwnerActionResponseS2CPacket(
                false, "Permission denied", "", List.of(), false
        );
        var decoded = roundtrip(OwnerActionResponseS2CPacket.CODEC, original);
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
    }

    @Test
    void batchAssignResponse_roundtrip() {
        var original = new BatchAssignResponseS2CPacket(true, "Assigned successfully");
        var decoded = roundtrip(BatchAssignResponseS2CPacket.CODEC, original);
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
    }

    @Test
    void kickFromMaterialResponse_roundtrip() {
        var original = new KickFromMaterialResponseS2CPacket(true, "Kicked successfully");
        var decoded = roundtrip(KickFromMaterialResponseS2CPacket.CODEC, original);
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
    }

    @Test
    void rescanStagingAreaResponse_roundtrip() {
        var original = new RescanStagingAreaResponseS2CPacket(true, "Rescan complete");
        var decoded = roundtrip(RescanStagingAreaResponseS2CPacket.CODEC, original);
        assertEquals(original.success(), decoded.success());
        assertEquals(original.message(), decoded.message());
    }

    // ==================== C2S 包 ====================

    @Test
    void materialStatsRequest_roundtrip() {
        var original = new MaterialStatsRequestC2SPacket("test-uuid");
        var decoded = roundtrip(MaterialStatsRequestC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
    }

    @Test
    void joinCollaboration_roundtrip() {
        var original = new JoinCollaborationC2SPacket(
                "test-uuid", 42, Map.of(1, 10, 2, 20, 3, 30)
        );
        var decoded = roundtrip(JoinCollaborationC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialId(), decoded.materialId());
        assertEquals(original.inventoryCounts(), decoded.inventoryCounts());
    }

    @Test
    void joinCollaboration_emptyInventory_roundtrip() {
        var original = new JoinCollaborationC2SPacket("test-uuid", 1, Map.of());
        var decoded = roundtrip(JoinCollaborationC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialId(), decoded.materialId());
        assertTrue(decoded.inventoryCounts().isEmpty());
    }

    @Test
    void leaveCollaboration_roundtrip() {
        var original = new LeaveCollaborationC2SPacket("test-uuid", 42);
        var decoded = roundtrip(LeaveCollaborationC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialId(), decoded.materialId());
    }

    @Test
    void inventoryUpdate_roundtrip() {
        var original = new InventoryUpdateC2SPacket("test-uuid", 42, 64);
        var decoded = roundtrip(InventoryUpdateC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialId(), decoded.materialId());
        assertEquals(original.count(), decoded.count());
    }

    @Test
    void stagingAreaConfig_add_roundtrip() {
        var original = new StagingAreaConfigC2SPacket(
                "test-uuid", "ADD", 0,
                Optional.of(new StagingAreaConfigC2SPacket.AreaData(
                        "NewArea", 0, 0, 0, 10, 10, 10, Optional.of("minecraft:overworld")
                ))
        );
        var decoded = roundtrip(StagingAreaConfigC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.action(), decoded.action());
        assertEquals(original.areaId(), decoded.areaId());
        assertTrue(decoded.areaData().isPresent());
        assertEquals(original.areaData().get().name(), decoded.areaData().get().name());
        assertEquals(original.areaData().get().x1(), decoded.areaData().get().x1());
        assertEquals(original.areaData().get().world(), decoded.areaData().get().world());
    }

    @Test
    void stagingAreaConfig_delete_roundtrip() {
        var original = new StagingAreaConfigC2SPacket(
                "test-uuid", "DELETE", 5, Optional.empty()
        );
        var decoded = roundtrip(StagingAreaConfigC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.action(), decoded.action());
        assertEquals(original.areaId(), decoded.areaId());
        assertFalse(decoded.areaData().isPresent());
    }

    @Test
    void queryMaterialStatus_roundtrip() {
        var original = new QueryMaterialStatusC2SPacket("test-uuid");
        var decoded = roundtrip(QueryMaterialStatusC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
    }

    @Test
    void materialListClose_roundtrip() {
        var original = new MaterialListCloseC2SPacket("test-uuid");
        var decoded = roundtrip(MaterialListCloseC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
    }

    @Test
    void rescanStagingArea_roundtrip() {
        var original = new RescanStagingAreaC2SPacket("test-uuid");
        var decoded = roundtrip(RescanStagingAreaC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
    }

    @Test
    void ownerAction_transfer_roundtrip() {
        var original = new OwnerActionC2SPacket("test-uuid", "TRANSFER", "TargetPlayer");
        var decoded = roundtrip(OwnerActionC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.action(), decoded.action());
        assertEquals(original.targetPlayerName(), decoded.targetPlayerName());
    }

    @Test
    void ownerAction_toggleSelfClaim_roundtrip() {
        var original = new OwnerActionC2SPacket("test-uuid", "TOGGLE_SELF_CLAIM", "");
        var decoded = roundtrip(OwnerActionC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.action(), decoded.action());
        assertEquals(original.targetPlayerName(), decoded.targetPlayerName());
    }

    @Test
    void batchAssign_roundtrip() {
        var original = new BatchAssignC2SPacket(
                "test-uuid", List.of(1, 2, 3), List.of("Player1", "Player2")
        );
        var decoded = roundtrip(BatchAssignC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialIds(), decoded.materialIds());
        assertEquals(original.targetPlayers(), decoded.targetPlayers());
    }

    @Test
    void kickFromMaterial_roundtrip() {
        var original = new KickFromMaterialC2SPacket(
                "test-uuid", List.of(1, 2), "TargetPlayer"
        );
        var decoded = roundtrip(KickFromMaterialC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.materialIds(), decoded.materialIds());
        assertEquals(original.targetPlayer(), decoded.targetPlayer());
    }

    @Test
    void playerListRequest_roundtrip() {
        var original = new PlayerListRequestC2SPacket("test-uuid");
        var decoded = roundtrip(PlayerListRequestC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
    }

    @Test
    void warehouseContainerRequest_subscribe_roundtrip() {
        var original = new WarehouseContainerRequestC2SPacket("test-uuid", true);
        var decoded = roundtrip(WarehouseContainerRequestC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.subscribe(), decoded.subscribe());
    }

    @Test
    void warehouseContainerRequest_unsubscribe_roundtrip() {
        var original = new WarehouseContainerRequestC2SPacket("test-uuid", false);
        var decoded = roundtrip(WarehouseContainerRequestC2SPacket.CODEC, original);
        assertEquals(original.schematicId(), decoded.schematicId());
        assertEquals(original.subscribe(), decoded.subscribe());
    }
}
