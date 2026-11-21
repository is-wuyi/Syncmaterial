package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import net.syncmaterial.syncmaterial.core.ProjectProgressSnapshot;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;

/**
 * Deterministic server-side splitter for one encoded progress snapshot.
 *
 * <p>Part count follows the protocol rule {@code ceil(totalBytes / 900000)} and can
 * never exceed {@link ProjectViewLimits#MAX_PART_COUNT}. All parts except the last are
 * exactly {@link ProjectViewLimits#PART_BYTES} long, so the split is deterministic for a
 * given payload. The total cap is 16 MiB and an empty or oversized payload is rejected.
 * The client assembler is the only component allowed to combine parts.</p>
 */
public final class ProjectProgressFragmenter {
    private ProjectProgressFragmenter() {}

    /** One encoded progress fragment and the metadata the S2C envelope must carry. */
    public record Fragment(int partIndex, int partCount, int totalBytes, byte[] fragment) {
        public Fragment {
            if (partIndex < 0 || partCount <= 0 || partIndex >= partCount) {
                throw new IllegalArgumentException("invalid fragment index/count");
            }
            if (totalBytes <= 0 || totalBytes > ProjectViewLimits.TOTAL_BYTES) {
                throw new IllegalArgumentException("invalid fragment totalBytes");
            }
            if (partCount != ProjectViewLimits.maxPartCount(totalBytes)) {
                throw new IllegalArgumentException(
                        "partCount does not match ceil(totalBytes / PART_BYTES)");
            }
            if (fragment == null || fragment.length == 0
                    || fragment.length > ProjectViewLimits.PART_BYTES) {
                throw new IllegalArgumentException("invalid fragment payload length");
            }
            if (fragment.length > totalBytes - (partCount - 1)) {
                throw new IllegalArgumentException("fragment leaves no room for remaining parts");
            }
            fragment = fragment.clone();
        }

        @Override
        public byte[] fragment() {
            return fragment.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Fragment that)) {
                return false;
            }
            return partIndex == that.partIndex && partCount == that.partCount
                    && totalBytes == that.totalBytes && Arrays.equals(fragment, that.fragment);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(partIndex, partCount, totalBytes);
            return 31 * result + Arrays.hashCode(fragment);
        }
    }

    /** Encodes one immutable core snapshot and splits it into protocol fragments. */
    public static List<Fragment> encode(ProjectProgressSnapshot snapshot) {
        return split(ProjectProgressCodec.encode(snapshot));
    }

    /** Splits an already encoded snapshot into canonical fragments. */
    public static List<Fragment> split(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length <= 0 || payload.length > ProjectViewLimits.TOTAL_BYTES) {
            throw new IllegalArgumentException("progress payload length is outside 1.."
                    + ProjectViewLimits.TOTAL_BYTES);
        }
        int partCount = ProjectViewLimits.maxPartCount(payload.length);
        if (partCount <= 0 || partCount > ProjectViewLimits.MAX_PART_COUNT) {
            throw new IllegalArgumentException("invalid part count for " + payload.length + " bytes");
        }
        List<Fragment> parts = new ArrayList<>(partCount);
        int offset = 0;
        for (int partIndex = 0; partIndex < partCount; partIndex++) {
            int length = Math.min(ProjectViewLimits.PART_BYTES, payload.length - offset);
            if (length <= 0) {
                throw new IllegalStateException("empty fragment while splitting progress payload");
            }
            byte[] fragment = Arrays.copyOfRange(payload, offset, offset + length);
            parts.add(new Fragment(partIndex, partCount, payload.length, fragment));
            offset += length;
        }
        if (offset != payload.length) {
            throw new IllegalStateException("fragment split did not consume the payload");
        }
        return List.copyOf(parts);
    }
}
