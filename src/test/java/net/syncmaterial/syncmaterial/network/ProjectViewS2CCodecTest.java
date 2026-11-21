package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewS2CCodecTest {
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");
    private static final ProjectViewScope GLOBAL = ProjectViewScope.globalWarehouse();
    private static final String SNAPSHOT_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyResultShapeRoundtrips() {
        roundtrip(opened());
        roundtrip(progress(PROJECT, new byte[]{1, 2, 3}, SNAPSHOT_ID, 9, 0, 1, 3));

        var configuration = new ProjectViewS2CPacket.AreaConfigData("LIST", List.of(
                stagingArea(2, "Staging B"), stagingArea(1, "Staging A")));
        roundtrip(new ProjectViewS2CPacket(PROJECT, 5, 9, 4, 3,
                ProjectViewS2CPacket.Result.CONFIG, ProjectViewAction.AREA_CONFIG, true, "ok",
                0, true, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.of(configuration), Optional.empty()));

        var globalConfiguration = new ProjectViewS2CPacket.AreaConfigData("LIST_WAREHOUSES",
                List.of(warehouseArea(3, "Warehouse")));
        roundtrip(new ProjectViewS2CPacket(GLOBAL, 5, 9, 4, 3,
                ProjectViewS2CPacket.Result.CONFIG, ProjectViewAction.AREA_CONFIG, true, "ok",
                0, true, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.of(globalConfiguration), Optional.empty()));

        var warehouse = new ContainerObservation(
                new ContainerKey("minecraft:overworld", 10, 64, -5),
                Map.of("minecraft:stone", 64L, "minecraft:diamond", 3L), "");
        roundtrip(new ProjectViewS2CPacket(PROJECT, 5, 9, 4, 3,
                ProjectViewS2CPacket.Result.WAREHOUSE, ProjectViewAction.RESCAN, true, "ok",
                0, true, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.of(warehouse)));

        roundtrip(new ProjectViewS2CPacket(PROJECT, 5, 9, 4, 3,
                ProjectViewS2CPacket.Result.OPERATION, ProjectViewAction.JOIN, true, "joined",
                42, true, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty()));
        roundtrip(new ProjectViewS2CPacket(PROJECT, 0, 9, 4, 3,
                ProjectViewS2CPacket.Result.OPERATION, ProjectViewAction.LEAVE, false, "not claimed",
                42, false, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty()));
        roundtrip(new ProjectViewS2CPacket(PROJECT, 0, 9, 4, 3,
                ProjectViewS2CPacket.Result.INVALIDATED, ProjectViewAction.NONE, false,
                "generation changed", 0, false, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty()));
        roundtrip(new ProjectViewS2CPacket(PROJECT, 0, 9, 4, 3,
                ProjectViewS2CPacket.Result.ERROR, ProjectViewAction.CLOSE, false, "failed",
                0, false, "", 7, 8, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty()));
    }

    @Test
    void maximumProgressFragmentStillFitsTheConservativeS2CBudget() {
        ProjectViewS2CPacket packet = progress(PROJECT,
                new byte[ProjectViewLimits.PART_BYTES], SNAPSHOT_ID, 7, 0, 1,
                ProjectViewLimits.PART_BYTES);
        byte[] encoded = encode(packet);
        assertTrue(encoded.length <= ProjectViewLimits.S2C_MAX_BYTES,
                "encoded envelope was " + encoded.length);
        ProjectViewS2CPacket decoded = decode(encoded);
        assertEquals(packet, decoded);
        assertArrayEquals(packet.fragment(), decoded.fragment());
    }

    @Test
    void maximumPartCountIsBoundedByTheCeilingFormula() {
        ProjectViewS2CPacket maximum = new ProjectViewS2CPacket(PROJECT, 0, 1, 1, 0,
                ProjectViewS2CPacket.Result.PROGRESS_PART, ProjectViewAction.NONE, true, "",
                0, false, SNAPSHOT_ID, 1, 1, ProjectViewLimits.MAX_PART_COUNT - 1,
                ProjectViewLimits.MAX_PART_COUNT, ProjectViewLimits.TOTAL_BYTES, new byte[]{1, 2},
                Optional.empty(), Optional.empty());
        roundtrip(maximum);

        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 1, 1,
                0, ProjectViewLimits.MAX_PART_COUNT + 1, ProjectViewLimits.TOTAL_BYTES,
                new byte[]{1}, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 1, 1,
                0, 1, ProjectViewLimits.TOTAL_BYTES + 1, new byte[]{1},
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 1, 1,
                0, 1, ProjectViewLimits.PART_BYTES + 1,
                new byte[ProjectViewLimits.PART_BYTES + 1], Optional.empty(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 1, 1,
                0, 2, ProjectViewLimits.PART_BYTES, new byte[]{1},
                Optional.empty(), Optional.empty()));
    }

    @Test
    void resultSpecificShapesCannotReuseProgressFields() {
        var configuration = new ProjectViewS2CPacket.AreaConfigData("LIST",
                List.of(stagingArea(1, "A")));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.AREA_CONFIG, true, "ok", 0, true, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.AREA_CONFIG, true, "ok", 0, true, "", 7, 8, 1, 1, 1,
                new byte[]{1}, Optional.of(configuration), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 7, 8, 0, 1, 3,
                new byte[]{1, 2, 3}, Optional.of(configuration), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.JOIN, true, "", 0, false, SNAPSHOT_ID, 7, 8, 0, 1, 3,
                new byte[]{1, 2, 3}, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 1, false, SNAPSHOT_ID, 7, 8, 0, 1, 3,
                new byte[]{1, 2, 3}, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, true, SNAPSHOT_ID, 7, 8, 0, 1, 3,
                new byte[]{1, 2, 3}, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.OPENED,
                ProjectViewAction.NONE, true, "", 0, false, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 9, 4, 3, ProjectViewS2CPacket.Result.OPENED,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 7, 8, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.OPERATION,
                ProjectViewAction.JOIN, true, "joined", 0, true, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.ERROR,
                ProjectViewAction.NONE, true, "failed", 0, false, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.CLOSE, true, "ok", 0, true, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.of(configuration), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.AREA_CONFIG, true, "ok", 0, true, SNAPSHOT_ID, 7, 8,
                0, 0, 0, new byte[0], Optional.of(configuration), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.AREA_CONFIG, true, "ok", 0, true, "", 7, 8, 0, 0, 0,
                new byte[0],
                Optional.of(new ProjectViewS2CPacket.AreaConfigData("LIST_WAREHOUSES",
                        List.of(warehouseArea(1, "WH")))), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                GLOBAL, 5, 9, 4, 3, ProjectViewS2CPacket.Result.PROGRESS_PART,
                ProjectViewAction.NONE, true, "", 0, false, SNAPSHOT_ID, 7, 8, 0, 1, 3,
                new byte[]{1, 2, 3}, Optional.empty(), Optional.empty()));
    }

    @Test
    void oversizedTypedConfigurationIsRejectedInsteadOfSent() {
        List<AreaDefinition> areas = new ArrayList<>();
        String name = "n".repeat(ProjectViewLimits.AREA_NAME_LENGTH);
        String world = "a:" + "b".repeat(ProjectViewLimits.WORLD_ID_LENGTH - 2);
        for (int index = 1; index <= ProjectViewLimits.AREAS; index++) {
            AreaKind kind = index % 2 == 0 ? AreaKind.STAGING_AREA : AreaKind.WAREHOUSE;
            String projectId = kind == AreaKind.STAGING_AREA ? "project" : null;
            areas.add(new AreaDefinition(index, projectId, kind, name, world,
                    new AreaBounds(0, 0, 0, 1, 1, 1), 1, 1));
        }
        var configuration = new ProjectViewS2CPacket.AreaConfigData("LIST", areas);
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 5, 9, 4, 3, ProjectViewS2CPacket.Result.CONFIG,
                ProjectViewAction.AREA_CONFIG, true, "ok", 0, true, "", 7, 8, 0, 0, 0,
                new byte[0], Optional.of(configuration), Optional.empty()));
    }

    @Test
    void decodeRejectsOversizedFragmentsBeforeAllocationAndTrailingBytes() {
        RegistryFriendlyByteBuf oversized = buffer();
        try {
            writeProgressHeader(oversized, ProjectViewLimits.PART_BYTES + 1);
            oversized.writeVarInt(ProjectViewLimits.PART_BYTES + 1);
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectViewS2CPacket.CODEC.decode(oversized));
        } finally {
            oversized.release();
        }

        RegistryFriendlyByteBuf negative = buffer();
        try {
            writeProgressHeader(negative, 3);
            negative.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectViewS2CPacket.CODEC.decode(negative));
        } finally {
            negative.release();
        }

        RegistryFriendlyByteBuf oversizedResult = buffer();
        try {
            oversizedResult.writeBoolean(false);
            oversizedResult.writeUtf("p");
            oversizedResult.writeVarLong(0);
            oversizedResult.writeVarLong(1);
            oversizedResult.writeVarLong(1);
            oversizedResult.writeVarLong(0);
            oversizedResult.writeUtf("x".repeat(ProjectViewLimits.AREA_ACTION_LENGTH + 1));
            assertThrows(RuntimeException.class,
                    () -> ProjectViewS2CPacket.CODEC.decode(oversizedResult));
        } finally {
            oversizedResult.release();
        }

        byte[] encoded = encode(progress(PROJECT, new byte[]{1, 2, 3}, SNAPSHOT_ID, 9, 0, 1, 3));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> decode(trailing));
    }

    @Test
    void fragmentAccessorsAndEncodingAreDefensive() {
        byte[] fragment = {1, 2, 3};
        ProjectViewS2CPacket packet = progress(PROJECT, fragment, SNAPSHOT_ID, 9, 0, 1, 3);
        fragment[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, packet.fragment());
        byte[] accessed = packet.fragment();
        accessed[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, packet.fragment());
        assertEquals(packet, decode(encode(packet)));
    }

    @Test
    void messageAndSnapshotBoundsAreEnforced() {
        ProjectViewS2CPacket maximum = new ProjectViewS2CPacket(
                ProjectViewScope.project("p".repeat(100)), 0, 1, 1, 0,
                ProjectViewS2CPacket.Result.OPERATION, ProjectViewAction.NONE, true,
                "m".repeat(ProjectViewLimits.MESSAGE_LENGTH), 0, false, "", 1, 1, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty());
        roundtrip(maximum);
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.OPERATION,
                ProjectViewAction.NONE, true,
                "m".repeat(ProjectViewLimits.MESSAGE_LENGTH + 1), 0, false, "", 1, 1,
                0, 0, 0, new byte[0], Optional.empty(), Optional.empty()));

        ProjectViewS2CPacket maximumSnapshot = new ProjectViewS2CPacket(PROJECT, 5, 1, 1, 0,
                ProjectViewS2CPacket.Result.OPENED, ProjectViewAction.NONE, true, "",
                0, false, SNAPSHOT_ID, 1, 1, 0, 0, 0,
                new byte[0], Optional.empty(), Optional.empty());
        roundtrip(maximumSnapshot);
        assertThrows(IllegalArgumentException.class, () -> new ProjectViewS2CPacket(
                PROJECT, 0, 1, 1, 0, ProjectViewS2CPacket.Result.OPENED,
                ProjectViewAction.NONE, true, "",
                0, false, "s".repeat(ProjectViewLimits.SNAPSHOT_ID_LENGTH + 1), 1, 1,
                0, 0, 0, new byte[0], Optional.empty(), Optional.empty()));
    }

    @Test
    void publishedWireOrderForProgressPartIsStable() {
        ProjectViewS2CPacket packet = progress(PROJECT, new byte[]{1, 2}, SNAPSHOT_ID, 0,
                0, 1, 2);
        String expected = "000770726f6a656374000101000d50524f47524553535f50415254044e4f4e45"
                + "010000002430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                + "07080001020201020000";
        assertArrayEquals(HexFormat.of().parseHex(expected), encode(packet));
    }

    private static ProjectViewS2CPacket opened() {
        return new ProjectViewS2CPacket(PROJECT, 5, 1, 1, 0,
                ProjectViewS2CPacket.Result.OPENED, ProjectViewAction.NONE, true, "",
                0, false, SNAPSHOT_ID, 5, 6, 0, 0, 0, new byte[0],
                Optional.empty(), Optional.empty());
    }

    private static ProjectViewS2CPacket progress(ProjectViewScope scope, byte[] fragment,
                                                 String snapshotId, long sequence,
                                                 int partIndex, int partCount, int totalBytes) {
        return new ProjectViewS2CPacket(scope, 0, 1, 1, sequence,
                ProjectViewS2CPacket.Result.PROGRESS_PART, ProjectViewAction.NONE, true, "",
                0, false, snapshotId, 7, 8, partIndex, partCount, totalBytes, fragment,
                Optional.empty(), Optional.empty());
    }

    private static AreaDefinition stagingArea(int id, String name) {
        return new AreaDefinition(id, "project", AreaKind.STAGING_AREA, name,
                "minecraft:overworld", new AreaBounds(0, 0, 0, 15, 15, 15), 1, 1);
    }

    private static AreaDefinition warehouseArea(int id, String name) {
        return new AreaDefinition(id, null, AreaKind.WAREHOUSE, name,
                "minecraft:overworld", new AreaBounds(0, 0, 0, 15, 15, 15), 1, 1);
    }

    private static void writeProgressHeader(RegistryFriendlyByteBuf buffer, int totalBytes) {
        buffer.writeBoolean(false);
        buffer.writeUtf("project");
        buffer.writeVarLong(0);
        buffer.writeVarLong(1);
        buffer.writeVarLong(1);
        buffer.writeVarLong(0);
        buffer.writeUtf("PROGRESS_PART");
        buffer.writeUtf("NONE");
        buffer.writeBoolean(true);
        buffer.writeUtf("");
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
        buffer.writeUtf(SNAPSHOT_ID);
        buffer.writeVarLong(1);
        buffer.writeVarLong(1);
        buffer.writeVarInt(0);
        buffer.writeVarInt(1);
        buffer.writeVarInt(totalBytes);
    }

    private static byte[] encode(ProjectViewS2CPacket packet) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ProjectViewS2CPacket.CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static ProjectViewS2CPacket decode(byte[] bytes) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeBytes(bytes);
            return ProjectViewS2CPacket.CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void roundtrip(ProjectViewS2CPacket original) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ProjectViewS2CPacket.CODEC.encode(buffer, original);
            ProjectViewS2CPacket decoded = ProjectViewS2CPacket.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            assertEquals(original, decoded);
            assertArrayEquals(original.fragment(), decoded.fragment());
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
