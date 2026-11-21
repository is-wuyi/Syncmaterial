package net.syncmaterial.syncmaterial.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectProgressCodecTest {
    private static final int MAGIC = 0x50565031;
    private static final int VERSION = 2;
    private static final UUID ALICE = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    void roundtripIsDeterministicAndPreservesCoreSnapshotFacts() {
        ProjectProgressSnapshot snapshot = sample();
        byte[] first = ProjectProgressCodec.encode(snapshot);
        byte[] second = ProjectProgressCodec.encode(snapshot);
        assertArrayEquals(first, second);
        ProjectProgressSnapshot decoded = ProjectProgressCodec.decode(first);
        assertEquals(snapshot, decoded);
        assertEquals(Long.MAX_VALUE, decoded.materials().get(1).required());
        assertEquals(9, decoded.publicationSequence());
        assertTrue(decoded.areaCurrent());
        assertFalse(decoded.areaKnown());
        assertFalse(decoded.areaOverlap());
    }

    @Test
    void knownButNotCurrentNoWarningRoundtripsWithoutDerivingCurrent() {
        ProjectProgressSnapshot snapshot = new ProjectProgressSnapshot("p", "Project", 3, 7, 11,
                false, true, false, List.of(), List.of(material(1, "minecraft:stone", 10, 10, 0)));
        ProjectProgressSnapshot decoded = ProjectProgressCodec.decode(
                ProjectProgressCodec.encode(snapshot));
        assertFalse(decoded.areaCurrent());
        assertTrue(decoded.areaKnown());
        assertTrue(decoded.areaWarnings().isEmpty());
        assertFalse(decoded.preparationKnown());
        assertEquals(11, decoded.publicationSequence());
    }

    @Test
    void listOrderIsNormalizedIntoADeterministicPayload() {
        ProjectProgressMaterial stone = new ProjectProgressMaterial(
                1, "minecraft:stone", 100, 4, 5,
                List.of(new ProgressParticipant(new PlayerIdentity(null, "Bob"), 0),
                        new ProgressParticipant(new PlayerIdentity(ALICE, "Alice"), 3)));
        ProjectProgressMaterial diamond = new ProjectProgressMaterial(
                2, "minecraft:diamond", 10, 1, 2, List.of());
        AreaInventoryWarning stale = new AreaInventoryWarning(AreaKind.WAREHOUSE, 2, "WH", "stale");
        AreaInventoryWarning unknown = new AreaInventoryWarning(AreaKind.STAGING_AREA, 1, "ST", "unknown");

        ProjectProgressSnapshot unsorted = new ProjectProgressSnapshot("p", "Project", 3, 4, 1,
                false, false, true, List.of(stale, unknown), List.of(diamond, stone));
        ProjectProgressSnapshot sorted = new ProjectProgressSnapshot("p", "Project", 3, 4, 1,
                false, false, true, List.of(unknown, stale), List.of(stone, diamond));

        assertEquals(sorted.materials(), unsorted.materials());
        assertArrayEquals(ProjectProgressCodec.encode(sorted), ProjectProgressCodec.encode(unsorted));
        ProjectProgressSnapshot decoded = ProjectProgressCodec.decode(
                ProjectProgressCodec.encode(unsorted));
        assertEquals(sorted, decoded);
    }

    @Test
    void exactStringBoundariesAreAccepted() {
        String itemId = "a:" + "b".repeat(254);
        String areaText = "x".repeat(ProjectViewLimits.AREA_NAME_LENGTH);
        ProjectProgressSnapshot snapshot = new ProjectProgressSnapshot(
                "p".repeat(ProjectViewLimits.PROJECT_ID_LENGTH),
                "n".repeat(ProjectViewLimits.PROJECT_NAME_LENGTH), 1, 2, 3, true, true, false,
                List.of(),
                List.of(new ProjectProgressMaterial(1, itemId, 1, 2, 3, List.of())));
        assertEquals(snapshot, ProjectProgressCodec.decode(ProjectProgressCodec.encode(snapshot)));

        ProjectProgressSnapshot warning = new ProjectProgressSnapshot("p", "n", 0, 0, 0,
                false, false, false,
                List.of(new AreaInventoryWarning(AreaKind.WAREHOUSE, 1, areaText,
                        "s".repeat(ProjectViewLimits.AREA_STATUS_LENGTH))),
                List.of());
        assertEquals(warning, ProjectProgressCodec.decode(ProjectProgressCodec.encode(warning)));
    }

    @Test
    void invalidModelValuesAndBoundsAreRejectedBeforeEncoding() {
        ProjectProgressMaterial valid = material(1, "minecraft:stone", 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                " ", "n", 0, 0, 0, false, false, false, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "n", -1, 0, 0, false, false, false, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "n", 0, -1, 0, false, false, false, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "n", 0, 0, -1, false, false, false, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "n", 0, 0, 0, false, true, false,
                List.of(new AreaInventoryWarning(AreaKind.STAGING_AREA, 1, "A", "warning")),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterial(
                1, "stone", 1, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterial(
                1, "minecraft:stone", -1, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProgressParticipant(
                new PlayerIdentity(null, "Legacy"), 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressSnapshot(
                "p", "n", 0, 0, 0, false, false, false, List.of(),
                List.of(valid, valid)));

        ProjectProgressSnapshot tooLongProject = new ProjectProgressSnapshot(
                "p".repeat(ProjectViewLimits.PROJECT_ID_LENGTH + 1), "n", 0, 0, 0,
                false, false, false, List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.encode(tooLongProject));
        ProjectProgressSnapshot tooLongName = new ProjectProgressSnapshot(
                "p", "n".repeat(ProjectViewLimits.PROJECT_NAME_LENGTH + 1), 0, 0, 0,
                false, false, false, List.of(), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.encode(tooLongName));
    }

    @Test
    void malformedHeadersTruncationAndTrailingBytesAreRejected() {
        byte[] valid = ProjectProgressCodec.encode(sample());

        byte[] badMagic = valid.clone();
        badMagic[3] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(badMagic));

        byte[] badVersion = valid.clone();
        badVersion[7] = 99;
        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(badVersion));

        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(Arrays.copyOf(valid, valid.length - 3)));

        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(trailing));

        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(new byte[ProjectViewLimits.TOTAL_BYTES + 1]));
    }

    @Test
    void declaredLengthsAreBoundedBeforeAllocation() {
        byte[] oversizedProjectId = raw(out -> {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(ProjectViewLimits.PROJECT_ID_LENGTH * 4 + 1);
        });
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(oversizedProjectId));

        byte[] negativeCount = withPrefix(out -> out.writeInt(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(negativeCount));

        byte[] tooManyParticipants = withPrefix(out -> {
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(1);
            writeString(out, "minecraft:stone");
            out.writeLong(0);
            out.writeLong(0);
            out.writeLong(0);
            out.writeInt(ProjectViewLimits.MATERIAL_PARTICIPANTS + 1);
        });
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(tooManyParticipants));

        byte[] malformedUtf8 = raw(out -> {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(1);
            out.write(0xFF);
        });
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressCodec.decode(malformedUtf8));
    }

    @Test
    void unresolvedParticipantWithHeldCountIsRejectedByDecode() {
        ProjectProgressSnapshot snapshot = new ProjectProgressSnapshot("p", "Project", 0, 0, 0,
                false, true, false, List.of(),
                List.of(new ProjectProgressMaterial(1, "minecraft:stone", 0, 0, 0,
                        List.of(new ProgressParticipant(new PlayerIdentity(null, "Legacy"), 0)))));
        byte[] encoded = ProjectProgressCodec.encode(snapshot);
        ByteBuffer.wrap(encoded).putLong(encoded.length - Long.BYTES, 1L);
        assertThrows(IllegalArgumentException.class, () -> ProjectProgressCodec.decode(encoded));
    }

    private static ProjectProgressSnapshot sample() {
        ProjectProgressMaterial stone = new ProjectProgressMaterial(
                1, "minecraft:stone", 100, 4, 5,
                List.of(new ProgressParticipant(new PlayerIdentity(ALICE, "Alice"), 3)));
        ProjectProgressMaterial diamond = new ProjectProgressMaterial(
                2, "minecraft:diamond", Long.MAX_VALUE, 0, 0,
                List.of(new ProgressParticipant(new PlayerIdentity(null, "离线玩家"), 0)));
        return new ProjectProgressSnapshot("project", "项目名称", 7, 3, 9, true, false, false,
                List.of(new AreaInventoryWarning(AreaKind.WAREHOUSE, 2, "WH", "过期")),
                List.of(stone, diamond));
    }

    private static ProjectProgressMaterial material(int id, String itemId, long required,
                                                    long staging, long warehouse) {
        return new ProjectProgressMaterial(id, itemId, required, staging, warehouse, List.of());
    }

    private static byte[] withPrefix(Writer writer) {
        return raw(out -> {
            writePrefix(out);
            writer.write(out);
        });
    }

    private static void writePrefix(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        writeString(out, "p");
        writeString(out, "Project");
        out.writeLong(0); // catalogRevision
        out.writeLong(0); // inventoryRevision
        out.writeLong(0); // publicationSequence
        out.writeBoolean(false); // areaCurrent
        out.writeBoolean(true); // areaKnown
        out.writeBoolean(false); // areaOverlap
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] raw(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
