package net.syncmaterial.syncmaterial.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.syncmaterial.syncmaterial.core.AreaInventoryWarning;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;

/**
 * Deterministic bounded binary codec for the immutable progress-core snapshot.
 *
 * <p>The snapshot is the business model itself: project/catalog facts, the independent
 * publication sequence and area-current/known/overlap flags, area warnings and every
 * claim participant with its effective held count. Item ids remain strings and all
 * counts remain {@code long}; no ItemStack or registry lookup happens here. Area
 * configuration geometry is deliberately not duplicated in this payload because the
 * protocol's CONFIG result already carries it.</p>
 *
 * <p>The format is private to protocol 5 and versioned by a magic/version header.
 * Encoding is capped at {@link ProjectViewLimits#TOTAL_BYTES}; decoding checks every
 * length before allocating, rejects trailing or truncated bytes and rejects malformed
 * UTF-8. Lists are emitted in canonical order so equal snapshots produce identical
 * bytes.</p>
 */
public final class ProjectProgressCodec {
    private static final int MAGIC = 0x50565031; // "PVP1"
    private static final int VERSION = 2;

    private static final Comparator<AreaInventoryWarning> WARNING_ORDER =
            Comparator.comparing((AreaInventoryWarning info) -> info.kind().wireName())
                    .thenComparingInt(AreaInventoryWarning::areaId)
                    .thenComparing(AreaInventoryWarning::areaName)
                    .thenComparing(AreaInventoryWarning::status);

    private ProjectProgressCodec() {}

    public static byte[] encode(ProjectProgressSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        try {
            LimitedByteArrayOutputStream bytes = new LimitedByteArrayOutputStream(
                    ProjectViewLimits.TOTAL_BYTES);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            writeString(out, snapshot.projectId(), ProjectViewLimits.PROJECT_ID_LENGTH);
            writeString(out, snapshot.projectName(), ProjectViewLimits.PROJECT_NAME_LENGTH);
            out.writeLong(snapshot.catalogRevision());
            out.writeLong(snapshot.inventoryRevision());
            out.writeLong(snapshot.publicationSequence());
            out.writeBoolean(snapshot.areaCurrent());
            out.writeBoolean(snapshot.areaKnown());
            out.writeBoolean(snapshot.areaOverlap());

            List<AreaInventoryWarning> warnings = sortedWarnings(snapshot.areaWarnings());
            writeCount(out, warnings.size(), ProjectViewLimits.AREA_FRESHNESS);
            for (AreaInventoryWarning info : warnings) {
                writeString(out, info.kind().wireName(), ProjectViewLimits.AREA_ACTION_LENGTH);
                out.writeInt(info.areaId());
                writeString(out, info.areaName(), ProjectViewLimits.AREA_NAME_LENGTH);
                writeString(out, info.status(), ProjectViewLimits.AREA_STATUS_LENGTH);
            }

            writeCount(out, snapshot.materials().size(), ProjectViewLimits.CATALOG_MATERIALS);
            for (ProjectProgressMaterial material : snapshot.materials()) {
                out.writeInt(material.materialId());
                writeString(out, material.itemId(), ProjectViewLimits.MATERIAL_ID_LENGTH);
                out.writeLong(material.required());
                out.writeLong(material.staging());
                out.writeLong(material.warehouse());
                writeCount(out, material.participants().size(),
                        ProjectViewLimits.MATERIAL_PARTICIPANTS);
                for (ProgressParticipant participant : material.participants()) {
                    PlayerIdentity player = participant.player();
                    out.writeBoolean(player.hasUuid());
                    if (player.hasUuid()) {
                        UUID uuid = player.uuid();
                        out.writeLong(uuid.getMostSignificantBits());
                        out.writeLong(uuid.getLeastSignificantBits());
                    }
                    writeString(out, player.name(), ProjectViewLimits.PLAYER_NAME_LENGTH);
                    out.writeLong(participant.effectiveHeld());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to encode project progress", exception);
        }
    }

    public static ProjectProgressSnapshot decode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length > ProjectViewLimits.TOTAL_BYTES) {
            throw new IllegalArgumentException("progress payload exceeds "
                    + ProjectViewLimits.TOTAL_BYTES + " bytes");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("invalid progress payload magic");
            }
            if (in.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported progress payload version");
            }

            String projectId = readString(in, ProjectViewLimits.PROJECT_ID_LENGTH);
            String projectName = readString(in, ProjectViewLimits.PROJECT_NAME_LENGTH);
            long catalogRevision = in.readLong();
            long inventoryRevision = in.readLong();
            long publicationSequence = in.readLong();
            boolean areaCurrent = in.readBoolean();
            boolean areaKnown = in.readBoolean();
            boolean areaOverlap = in.readBoolean();

            int freshnessCount = readCount(in, ProjectViewLimits.AREA_FRESHNESS);
            List<AreaInventoryWarning> freshness = new ArrayList<>(freshnessCount);
            for (int index = 0; index < freshnessCount; index++) {
                AreaKind kind = AreaKind.fromWireName(
                        readString(in, ProjectViewLimits.AREA_ACTION_LENGTH));
                int areaId = in.readInt();
                String areaName = readString(in, ProjectViewLimits.AREA_NAME_LENGTH);
                String status = readString(in, ProjectViewLimits.AREA_STATUS_LENGTH);
                freshness.add(new AreaInventoryWarning(kind, areaId, areaName, status));
            }
            freshness = sortedWarnings(freshness);

            int materialCount = readCount(in, ProjectViewLimits.CATALOG_MATERIALS);
            List<ProjectProgressMaterial> materials = new ArrayList<>(materialCount);
            for (int index = 0; index < materialCount; index++) {
                int materialId = in.readInt();
                String itemId = readString(in, ProjectViewLimits.MATERIAL_ID_LENGTH);
                long required = in.readLong();
                long staging = in.readLong();
                long warehouse = in.readLong();
                int participantCount = readCount(in, ProjectViewLimits.MATERIAL_PARTICIPANTS);
                List<ProgressParticipant> participants = new ArrayList<>(participantCount);
                for (int participantIndex = 0; participantIndex < participantCount; participantIndex++) {
                    UUID uuid = null;
                    if (in.readBoolean()) {
                        uuid = new UUID(in.readLong(), in.readLong());
                    }
                    String name = readString(in, ProjectViewLimits.PLAYER_NAME_LENGTH);
                    long count = in.readLong();
                    participants.add(new ProgressParticipant(new PlayerIdentity(uuid, name), count));
                }
                materials.add(new ProjectProgressMaterial(materialId, itemId, required, staging,
                        warehouse, participants));
            }

            if (in.read() != -1) {
                throw new IllegalArgumentException("trailing bytes in progress payload");
            }
            return new ProjectProgressSnapshot(projectId, projectName, catalogRevision,
                    inventoryRevision, publicationSequence, areaCurrent, areaKnown, areaOverlap,
                    freshness, materials);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("truncated progress payload", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("malformed progress payload", exception);
        }
    }

    private static List<AreaInventoryWarning> sortedWarnings(List<AreaInventoryWarning> warnings) {
        List<AreaInventoryWarning> copy = new ArrayList<>(warnings);
        copy.sort(WARNING_ORDER);
        return List.copyOf(copy);
    }

    private static void writeCount(DataOutputStream out, int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("count " + count + " exceeds " + maximum);
        }
        out.writeInt(count);
    }

    private static int readCount(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("count " + count + " exceeds " + maximum);
        }
        return count;
    }

    private static void writeString(DataOutputStream out, String value, int maxChars)
            throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException("string exceeds " + maxChars + " characters");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxChars * 4) {
            throw new IllegalArgumentException("encoded string exceeds bound");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxChars) throws IOException {
        int byteLength = in.readInt();
        if (byteLength < 0 || byteLength > maxChars * 4) {
            throw new IllegalArgumentException("string length " + byteLength + " exceeds bound");
        }
        if (byteLength > in.available()) {
            throw new IllegalArgumentException("truncated string");
        }
        byte[] bytes = new byte[byteLength];
        in.readFully(bytes);
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("malformed UTF-8 string", exception);
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException("string exceeds " + maxChars + " characters");
        }
        return value;
    }

    /** Rejects writes above an explicit budget instead of allocating an unbounded array. */
    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maximum;

        private LimitedByteArrayOutputStream(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ensureCapacityWithinLimit(length);
            super.write(bytes, offset, length);
        }

        private void ensureCapacityWithinLimit(int extra) {
            if (extra < 0 || count + (long) extra > maximum) {
                throw new IllegalArgumentException("progress payload exceeds " + maximum + " bytes");
            }
        }
    }
}
