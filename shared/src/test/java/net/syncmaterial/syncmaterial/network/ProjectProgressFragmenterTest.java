package net.syncmaterial.syncmaterial.network;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.ProgressParticipant;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterial;
import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectProgressFragmenterTest {

    @Test
    void exactMaximumPayloadSplitsIntoNineteenDeterministicParts() {
        byte[] payload = new byte[ProjectViewLimits.TOTAL_BYTES];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31);
        }
        List<ProjectProgressFragmenter.Fragment> parts = ProjectProgressFragmenter.split(payload);
        assertEquals(ProjectViewLimits.MAX_PART_COUNT, parts.size());
        assertEquals(ProjectViewLimits.TOTAL_BYTES, parts.get(0).totalBytes());
        int offset = 0;
        for (int index = 0; index < parts.size(); index++) {
            ProjectProgressFragmenter.Fragment part = parts.get(index);
            assertEquals(index, part.partIndex());
            assertEquals(parts.size(), part.partCount());
            int expectedLength = Math.min(ProjectViewLimits.PART_BYTES,
                    payload.length - offset);
            assertEquals(expectedLength, part.fragment().length);
            assertArrayEquals(Arrays.copyOfRange(payload, offset, offset + expectedLength),
                    part.fragment());
            offset += expectedLength;
        }
        assertEquals(payload.length, offset);
        assertEquals(parts, ProjectProgressFragmenter.split(payload));
    }

    @Test
    void boundaryTotalsUseTheCeilingFormula() {
        assertEquals(1, ProjectProgressFragmenter.split(
                new byte[ProjectViewLimits.PART_BYTES]).size());
        assertEquals(2, ProjectProgressFragmenter.split(
                new byte[ProjectViewLimits.PART_BYTES + 1]).size());
    }

    @Test
    void emptyAndOversizedPayloadsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressFragmenter.split(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressFragmenter.split(
                        new byte[ProjectViewLimits.TOTAL_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectProgressFragmenter.split(null));
    }

    @Test
    void encodedSnapshotUsesTheSameDeterministicSplit() {
        ProjectProgressSnapshot snapshot = new ProjectProgressSnapshot("p", "Project", 1, 2, 3,
                true, true, false, List.of(),
                List.of(new ProjectProgressMaterial(1, "minecraft:stone", 4, 5, 6,
                        List.of(new ProgressParticipant(new PlayerIdentity(
                                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                                "Alice"), 7)))));
        byte[] encoded = ProjectProgressCodec.encode(snapshot);
        assertEquals(ProjectProgressFragmenter.split(encoded),
                ProjectProgressFragmenter.encode(snapshot));
    }
}
