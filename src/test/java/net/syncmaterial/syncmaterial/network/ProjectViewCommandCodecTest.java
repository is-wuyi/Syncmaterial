package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewCommandCodecTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");
    private static final ProjectViewScope GLOBAL = ProjectViewScope.globalWarehouse();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyLegalCommandShapeRoundtrips() {
        roundtrip(command(PROJECT, ProjectViewAction.OPEN, null, 0, 0,
                ProjectViewLimits.INTEREST_ALL, Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.SET_INTERESTS, null, 0, 0, 0,
                Optional.empty(), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.OPEN, null, 0, 0,
                ProjectViewLimits.INTEREST_AREA_CONFIG, Optional.empty(), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.SET_INTERESTS, null, 0, 0,
                ProjectViewLimits.INTEREST_OUTLINES, Optional.empty(), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.CLOSE, null, 0, 0, 0,
                Optional.empty(), List.of()));

        var data = new StagingAreaConfigC2SPacket.AreaData(
                "Staging", -10, 0, -10, 20, 30, 20, Optional.of("minecraft:overworld"));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0,
                Optional.of(data), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "UPDATE", 0, 9, 0,
                Optional.of(data), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "DELETE", 0, 9, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "ADD_WAREHOUSE_REF", 0, 9, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "REMOVE_WAREHOUSE_REF", 0, 9, 0,
                Optional.empty(), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.AREA_CONFIG, "LIST_WAREHOUSES", 0, 0, 0,
                Optional.empty(), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.AREA_CONFIG, "ADD_WAREHOUSE", 0, 0, 0,
                Optional.of(data), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.AREA_CONFIG, "UPDATE_WAREHOUSE", 0, 9, 0,
                Optional.of(data), List.of()));
        roundtrip(command(GLOBAL, ProjectViewAction.AREA_CONFIG, "DELETE_WAREHOUSE", 0, 9, 0,
                Optional.empty(), List.of()));

        roundtrip(command(PROJECT, ProjectViewAction.RESCAN, null, 0, 0, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.RESCAN, null, 0, 9, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.CLOSE, null, 0, 0, 0,
                Optional.empty(), List.of()));
        roundtrip(command(PROJECT, ProjectViewAction.LEAVE, null, 7, 0, 0,
                Optional.empty(), List.of()));

        List<ProjectViewCommandC2SPacket.InventoryCount> inventory = new ArrayList<>();
        for (int index = 1; index <= ProjectViewLimits.INVENTORY_OBSERVATIONS; index++) {
            inventory.add(new ProjectViewCommandC2SPacket.InventoryCount(index,
                    1_000_000_000L + index));
        }
        ProjectViewCommandC2SPacket join = roundtrip(command(PROJECT, ProjectViewAction.JOIN,
                null, 7, 0, 0, Optional.empty(), inventory));
        assertEquals(ProjectViewLimits.INVENTORY_OBSERVATIONS, join.inventory().size());
        assertEquals(1_000_000_001L, join.inventory().get(0).count());
    }

    @Test
    void publishedWireOrderForCloseIsStable() {
        ProjectViewCommandC2SPacket packet = new ProjectViewCommandC2SPacket(PROJECT, 1, 2, 3,
                ProjectViewAction.CLOSE, null, 0, 0, 0, Optional.empty(), List.of());
        assertArrayEquals(HexFormat.of().parseHex("000770726f6a65637401020305434c4f5345000000000000"),
                encode(packet));
        assertEquals(packet, decode(encode(packet)));
    }

    @Test
    void strictPerActionShapeRejectsSmuggledFields() {
        var data = new StagingAreaConfigC2SPacket.AreaData(
                "Staging", 0, 0, 0, 1, 1, 1, Optional.empty());
        List<ProjectViewCommandC2SPacket.InventoryCount> one =
                List.of(new ProjectViewCommandC2SPacket.InventoryCount(1, 1));

        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.OPEN, null, 1, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.OPEN, null, 0, 1, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.OPEN, "ADD", 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.SET_INTERESTS, null, 0, 0, 0, Optional.empty(), one));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 1, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "DELETE", 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 0, Optional.of(data), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 1, 0, 0, Optional.of(data), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.RESCAN, null, 1, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.CLOSE, null, 0, 1, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.JOIN, null, 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.JOIN, null, 7, 0, 0, Optional.of(data), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.LEAVE, null, 7, 0, 0, Optional.empty(), one));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                PROJECT, 0, 2, 3, ProjectViewAction.OPEN, null, 0, 0, 0,
                Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                PROJECT, 1, 0, 3, ProjectViewAction.OPEN, null, 0, 0, 0,
                Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                PROJECT, 1, 2, 0, ProjectViewAction.OPEN, null, 0, 0, 0,
                Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                PROJECT, 1, 2, 3, ProjectViewAction.NONE, null, 0, 0, 0,
                Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                PROJECT, 1, 2, 3, ProjectViewAction.OPEN, null, 0, 0,
                ProjectViewLimits.INTEREST_ALL | 32, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, -1, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, -1, 0, Optional.of(data), List.of()));
    }

    @Test
    void typedScopeRejectsInvalidGlobalActionPairings() {
        var data = new StagingAreaConfigC2SPacket.AreaData(
                "Staging", 0, 0, 0, 1, 1, 1, Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.OPEN, null, 0, 0,
                ProjectViewLimits.INTEREST_MATERIALS, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.SET_INTERESTS, null, 0, 0,
                ProjectViewLimits.INTEREST_TEAM, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(ProjectViewScope.global("other"),
                ProjectViewAction.OPEN, null, 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.RESCAN, null, 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.LEAVE, null, 7, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD_WAREHOUSE", 0, 0, 0,
                Optional.of(data), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(data), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.AREA_CONFIG, "LIST", 0, 0, 0, Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(GLOBAL,
                ProjectViewAction.AREA_CONFIG, "ADD_WAREHOUSE_REF", 0, 1, 0,
                Optional.empty(), List.of()));
    }

    @Test
    void legacyNoTargetAreaIdIsNormalizedOnlyAtTheAdapterBoundary() {
        assertEquals(0, ProjectViewCommandC2SPacket.normalizeLegacyAreaId(-1));
        assertEquals(0, ProjectViewCommandC2SPacket.normalizeLegacyAreaId(0));
        assertEquals(9, ProjectViewCommandC2SPacket.normalizeLegacyAreaId(9));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectViewCommandC2SPacket.normalizeLegacyAreaId(-2));
    }

    @Test
    void inventoryAndTextBoundsAreEnforced() {
        List<ProjectViewCommandC2SPacket.InventoryCount> tooMany = new ArrayList<>();
        for (int index = 1; index <= ProjectViewLimits.INVENTORY_OBSERVATIONS + 1; index++) {
            tooMany.add(new ProjectViewCommandC2SPacket.InventoryCount(index, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.JOIN, null, 7, 0, 0, Optional.empty(), tooMany));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.JOIN, null, 7, 0, 0, Optional.empty(), List.of(
                        new ProjectViewCommandC2SPacket.InventoryCount(1, 1),
                        new ProjectViewCommandC2SPacket.InventoryCount(1, 2))));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewCommandC2SPacket(
                new ProjectViewScope("p".repeat(ProjectViewLimits.SCOPE_LENGTH + 1), false),
                1, 2, 3, ProjectViewAction.OPEN, null, 0, 0, 0, Optional.empty(), List.of()));

        var longestName = new StagingAreaConfigC2SPacket.AreaData(
                "n".repeat(ProjectViewLimits.AREA_NAME_LENGTH), 0, 0, 0, 1, 1, 1,
                Optional.of("a:" + "b".repeat(ProjectViewLimits.WORLD_ID_LENGTH - 2)));
        roundtrip(command(PROJECT, ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0,
                Optional.of(longestName), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(
                        new StagingAreaConfigC2SPacket.AreaData(
                                "n".repeat(ProjectViewLimits.AREA_NAME_LENGTH + 1),
                                0, 0, 0, 1, 1, 1, Optional.empty())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(
                        new StagingAreaConfigC2SPacket.AreaData(
                                "n", 30_000_001, 0, 0, 1, 1, 1, Optional.empty())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(
                        new StagingAreaConfigC2SPacket.AreaData(
                                "n", 0, 0, 0, 527, 1, 527, Optional.empty())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(
                        new StagingAreaConfigC2SPacket.AreaData(
                                "n", 0, 0, 0, 1, 1, 1, Optional.of("not a world"))), List.of()));
        assertThrows(IllegalArgumentException.class, () -> command(PROJECT,
                ProjectViewAction.AREA_CONFIG, "ADD", 0, 0, 0, Optional.of(
                        new StagingAreaConfigC2SPacket.AreaData(
                                "n", 0, 0, 0, 1, 1, 1,
                                Optional.of("a:" + "b".repeat(
                                        ProjectViewLimits.WORLD_ID_LENGTH - 1)))),
                List.of()));
    }

    @Test
    void decodeRejectsUnknownActionsOversizedListsAndTrailingBytes() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer, PROJECT, "OPEN");
            buffer.writeVarInt(ProjectViewLimits.INVENTORY_OBSERVATIONS + 1);
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectViewCommandC2SPacket.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }

        assertThrows(IllegalArgumentException.class, () -> decode(manual("NONE")));
        assertThrows(IllegalArgumentException.class, () -> decode(manual("EXPLODE")));

        RegistryFriendlyByteBuf oversizedScope = buffer();
        try {
            oversizedScope.writeBoolean(false);
            oversizedScope.writeUtf("x".repeat(ProjectViewLimits.SCOPE_LENGTH + 1));
            assertThrows(RuntimeException.class,
                    () -> ProjectViewCommandC2SPacket.CODEC.decode(oversizedScope));
        } finally {
            oversizedScope.release();
        }

        RegistryFriendlyByteBuf oversizedAreaAction = buffer();
        try {
            oversizedAreaAction.writeBoolean(false);
            oversizedAreaAction.writeUtf("p");
            oversizedAreaAction.writeVarLong(1);
            oversizedAreaAction.writeVarLong(2);
            oversizedAreaAction.writeVarLong(3);
            oversizedAreaAction.writeUtf("AREA_CONFIG");
            oversizedAreaAction.writeBoolean(true);
            oversizedAreaAction.writeUtf("x".repeat(ProjectViewLimits.AREA_ACTION_LENGTH + 1));
            assertThrows(RuntimeException.class,
                    () -> ProjectViewCommandC2SPacket.CODEC.decode(oversizedAreaAction));
        } finally {
            oversizedAreaAction.release();
        }

        RegistryFriendlyByteBuf badMaskEnvelope = buffer();
        try {
            badMaskEnvelope.writeBoolean(false);
            badMaskEnvelope.writeUtf("p");
            badMaskEnvelope.writeVarLong(1);
            badMaskEnvelope.writeVarLong(2);
            badMaskEnvelope.writeVarLong(3);
            badMaskEnvelope.writeUtf("OPEN");
            badMaskEnvelope.writeBoolean(false);
            badMaskEnvelope.writeVarInt(0);
            badMaskEnvelope.writeVarInt(0);
            badMaskEnvelope.writeVarInt(ProjectViewLimits.INTEREST_ALL + 1);
            badMaskEnvelope.writeBoolean(false);
            badMaskEnvelope.writeVarInt(0);
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectViewCommandC2SPacket.CODEC.decode(badMaskEnvelope));
        } finally {
            badMaskEnvelope.release();
        }

        byte[] encoded = encode(new ProjectViewCommandC2SPacket(PROJECT, 1, 2, 3,
                ProjectViewAction.CLOSE, null, 0, 0, 0, Optional.empty(), List.of()));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> decode(trailing));
    }

    @Test
    void channelsAndProtocolVersionAreAdditive() {
        assertEquals("syncmaterial:project_view_command", ModPackets.PROJECT_VIEW_COMMAND.toString());
        assertEquals("syncmaterial:project_view", ModPackets.PROJECT_VIEW.toString());
        assertEquals("syncmaterial:team_command", ModPackets.TEAM_COMMAND.toString());
        assertEquals(5, ProtocolVersion.CURRENT);
        assertEquals(5, ProtocolVersion.SCOPED_PROJECT_VIEWS);
        assertEquals(1, ProtocolVersion.MIN_COMPATIBLE);
        assertTrue(ProtocolVersion.CURRENT >= ProtocolVersion.SCOPED_PROJECT_VIEWS);
    }

    private static ProjectViewCommandC2SPacket command(ProjectViewScope scope,
                                                       ProjectViewAction action,
                                                       String areaAction,
                                                       int materialId, int areaId,
                                                       int interestMask,
                                                       Optional<StagingAreaConfigC2SPacket.AreaData> data,
                                                       List<ProjectViewCommandC2SPacket.InventoryCount> inventory) {
        return new ProjectViewCommandC2SPacket(scope, 11, 22, 33, action, areaAction,
                materialId, areaId, interestMask, data, inventory);
    }

    private static void writeHeader(RegistryFriendlyByteBuf buffer, ProjectViewScope scope,
                                    String action) {
        buffer.writeBoolean(scope.global());
        buffer.writeUtf(scope.id());
        buffer.writeVarLong(1);
        buffer.writeVarLong(2);
        buffer.writeVarLong(3);
        buffer.writeUtf(action);
        buffer.writeBoolean(false);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
    }

    private static ProjectViewCommandC2SPacket decode(byte[] bytes) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeBytes(bytes);
            return ProjectViewCommandC2SPacket.CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] manual(String action) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer, PROJECT, action);
            buffer.writeVarInt(0);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] encode(ProjectViewCommandC2SPacket packet) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ProjectViewCommandC2SPacket.CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static ProjectViewCommandC2SPacket roundtrip(ProjectViewCommandC2SPacket original) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ProjectViewCommandC2SPacket.CODEC.encode(buffer, original);
            ProjectViewCommandC2SPacket decoded = ProjectViewCommandC2SPacket.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            assertEquals(original, decoded);
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
