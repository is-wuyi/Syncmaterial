package net.syncmaterial.syncmaterial.core;

import java.util.UUID;

/**
 * Hard bounds shared by the protocol-5 project-view envelopes and the client assembler.
 *
 * <p>These limits are validation limits, not truncation targets: a value above a limit is
 * rejected, never silently shortened and reported as success. Two independent byte budgets
 * exist because the two directions carry different workloads: C2S inventory observations and
 * area commands are small, while the S2C envelope keeps a conservative margin below the
 * registered custom-payload frame limit even with one maximum-size progress fragment.</p>
 */
public final class ProjectViewLimits {
    /** Project/scope identity carried by the command and snapshot. */
    public static final int PROJECT_ID_LENGTH = 100;
    public static final int PROJECT_NAME_LENGTH = 256;
    /** Bound for the id inside a typed project or global {@link ProjectViewScope}. */
    public static final int SCOPE_LENGTH = PROJECT_ID_LENGTH;

    public static final int AREA_ACTION_LENGTH = 32;
    public static final int AREA_NAME_LENGTH = 256;
    public static final int AREA_STATUS_LENGTH = 64;
    public static final int WORLD_ID_LENGTH = 256;
    public static final int MATERIAL_ID_LENGTH = 256;
    public static final int PLAYER_NAME_LENGTH = 64;
    public static final int MESSAGE_LENGTH = 512;
    /** Snapshot ids are canonical UUID strings. */
    public static final int SNAPSHOT_ID_LENGTH = 36;

    /** One C2S JOIN may report at most this many material observations. */
    public static final int INVENTORY_OBSERVATIONS = 64;
    public static final int CATALOG_MATERIALS = 4096;
    public static final int AREAS = 1024;
    public static final int AREA_FRESHNESS = 1024;
    /** Participants per material in one progress snapshot. */
    public static final int MATERIAL_PARTICIPANTS = 4096;
    /** Containers/item lines accepted in a typed warehouse observation. */
    public static final int CONTAINER_ENTRIES = 4096;

    /** Interest bits: MATERIALS, TEAM, AREA_CONFIG, OUTLINES, WAREHOUSE_CONTENTS. */
    public static final int INTEREST_MATERIALS = 1;
    public static final int INTEREST_TEAM = 1 << 1;
    public static final int INTEREST_AREA_CONFIG = 1 << 2;
    public static final int INTEREST_OUTLINES = 1 << 3;
    public static final int INTEREST_WAREHOUSE_CONTENTS = 1 << 4;
    public static final int INTEREST_ALL = INTEREST_MATERIALS | INTEREST_TEAM
            | INTEREST_AREA_CONFIG | INTEREST_OUTLINES | INTEREST_WAREHOUSE_CONTENTS;

    /** Maximum raw bytes of one progress fragment. */
    public static final int PART_BYTES = 900_000;
    /** Maximum total decoded progress payload. */
    public static final int TOTAL_BYTES = 16 * 1024 * 1024;
    /** ceil(TOTAL_BYTES / PART_BYTES), computed without floating point. */
    public static final int MAX_PART_COUNT = (TOTAL_BYTES + PART_BYTES - 1) / PART_BYTES;
    /** Concurrent incomplete assemblies retained by the client. Never evicted silently. */
    public static final int MAX_CONCURRENT_ASSEMBLIES = 4;
    /** Safety bound for explicitly opened connection records retained by the assembler. */
    public static final int MAX_ACTIVE_CONNECTIONS = 8;
    /** Safety bound for explicitly opened view records retained by the assembler. */
    public static final int MAX_ACTIVE_VIEWS = 64;

    /**
     * Conservative S2C safety budget below the registered custom-payload path's real
     * bound. The 21-bit frame length caps a registered payload at 2,097,151 bytes; the
     * 1,048,576 value seen in {@code ClientboundCustomPayloadPacket} is the
     * {@code DiscardedPayload} fallback limit for unknown payload ids, not the registered
     * codec limit. Keeping the lower value leaves headroom for the payload id and frame
     * prefix.
     */
    public static final int S2C_MAX_BYTES = 1_048_576;
    /**
     * Conservative C2S command budget. The 32,767 value likewise belongs to the
     * {@code DiscardedPayload} fallback for unknown serverbound payload ids; registered
     * command envelopes are bounded by the frame length. The command envelope itself is
     * far below either value.
     */
    public static final int C2S_MAX_BYTES = 32_767;

    public static final long ASSEMBLY_TIMEOUT_MILLIS = 30_000L;
    public static final long DEFAULT_TICK_MILLIS = 50L;

    private ProjectViewLimits() {}

    /** True for ids that must be strictly positive on the command envelope. */
    public static boolean isPositiveId(long value) {
        return value > 0;
    }

    /** Positive view/request/generation ids are encoded as signed VarLong, so keep them positive. */
    public static void requirePositiveId(long value, String field) {
        if (!isPositiveId(value)) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    public static boolean isKnownInterestMask(int mask) {
        return mask >= 0 && (mask & ~INTEREST_ALL) == 0;
    }

    /**
     * Exact legal part count for a given declared total, or 0 when the total is invalid.
     * The protocol requires {@code partCount == ceil(total / PART_BYTES)}; each fragment
     * is non-empty, which this formula guarantees for positive totals.
     */
    public static int maxPartCount(int totalBytes) {
        if (totalBytes <= 0 || totalBytes > TOTAL_BYTES) {
            return 0;
        }
        return Math.min(MAX_PART_COUNT, (totalBytes + PART_BYTES - 1) / PART_BYTES);
    }

    /** Upper bound of UTF-8 bytes written by a Minecraft {@code writeUtf} value. */
    public static int utfBytesUpperBound(String value) {
        return value == null ? 0 : value.length() * 3;
    }

    /** Shared validation: reject, never truncate. */
    public static void requireBoundedText(String value, String field, int maxLength,
                                          boolean allowEmpty) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (!allowEmpty && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
    }

    public static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
    }

    /** Requires a canonical lowercase UUID string, never an arbitrary opaque token. */
    public static void requireUuidString(String value, String field) {
        requireBoundedText(value, field, SNAPSHOT_ID_LENGTH, false);
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID string", exception);
        }
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException(field + " must be a canonical lowercase UUID string");
        }
    }
}
