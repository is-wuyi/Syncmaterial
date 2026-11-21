package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.AreaDefinition;
import net.syncmaterial.syncmaterial.core.AreaKind;
import net.syncmaterial.syncmaterial.core.ContainerKey;
import net.syncmaterial.syncmaterial.core.ContainerObservation;
import net.syncmaterial.syncmaterial.core.CoreChecks;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/**
 * Protocol-5 project view result (S2C): a correlated operation result, a typed configuration/
 * warehouse value, or one raw fragment of a chunked progress snapshot.
 *
 * <p>The envelope is strictly result-specific. Progress fields and the raw fragment exist
 * only on {@link Result#PROGRESS_PART}; configuration and warehouse results carry their own
 * typed values and must not smuggle data through progress fields. The scope is typed so a
 * global warehouse action can never be confused with a project id. A maximum fragment plus
 * bounded metadata is checked against the conservative S2C safety budget before the packet
 * can exist.</p>
 *
 * <p>The {@code byte[]} component is copied on construction and on every accessor call so a
 * GUI or renderer can never mutate an assembled payload in place.</p>
 */
public record ProjectViewS2CPacket(
        ProjectViewScope scope,
        long requestId,
        long viewId,
        long generation,
        long sequence,
        Result result,
        ProjectViewAction action,
        boolean success,
        String message,
        int materialId,
        boolean changed,
        String snapshotId,
        long catalogRevision,
        long inventoryRevision,
        int partIndex,
        int partCount,
        int totalBytes,
        byte[] fragment,
        Optional<AreaConfigData> configuration,
        Optional<ContainerObservation> warehouse
) implements CustomPacketPayload, ProjectViewPart {

    public static final CustomPacketPayload.Type<ProjectViewS2CPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.PROJECT_VIEW);

    public enum Result {
        OPENED,
        PROGRESS_PART,
        CONFIG,
        WAREHOUSE,
        OPERATION,
        INVALIDATED,
        DELETED,
        ERROR;

        public static Result fromWireName(String name) {
            for (Result result : values()) {
                if (result.name().equals(name)) {
                    return result;
                }
            }
            throw new IllegalArgumentException("unknown project view result: " + name);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaConfigData> CONFIGURATION_CODEC =
            new StreamCodec<>() {
                @Override
                public AreaConfigData decode(RegistryFriendlyByteBuf buffer) {
                    String action = buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH);
                    int count = buffer.readVarInt();
                    if (count < 0 || count > ProjectViewLimits.AREAS) {
                        throw new IllegalArgumentException("area list exceeds "
                                + ProjectViewLimits.AREAS);
                    }
                    List<AreaDefinition> areas = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        int areaId = buffer.readVarInt();
                        String projectId = buffer.readBoolean()
                                ? buffer.readUtf(ProjectViewLimits.PROJECT_ID_LENGTH) : null;
                        AreaKind kind = AreaKind.fromWireName(
                                buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH));
                        String name = buffer.readUtf(ProjectViewLimits.AREA_NAME_LENGTH);
                        String world = buffer.readUtf(ProjectViewLimits.WORLD_ID_LENGTH);
                        int x1 = buffer.readVarInt();
                        int y1 = buffer.readVarInt();
                        int z1 = buffer.readVarInt();
                        int x2 = buffer.readVarInt();
                        int y2 = buffer.readVarInt();
                        int z2 = buffer.readVarInt();
                        long revision = buffer.readVarLong();
                        long geometryRevision = buffer.readVarLong();
                        areas.add(new AreaDefinition(areaId, projectId, kind, name, world,
                                new AreaBounds(x1, y1, z1, x2, y2, z2), revision,
                                geometryRevision));
                    }
                    return new AreaConfigData(action, areas);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, AreaConfigData data) {
                    buffer.writeUtf(data.action(), ProjectViewLimits.AREA_ACTION_LENGTH);
                    buffer.writeVarInt(data.areas().size());
                    for (AreaDefinition area : data.areas()) {
                        buffer.writeVarInt(area.id());
                        buffer.writeBoolean(area.projectId() != null);
                        if (area.projectId() != null) {
                            buffer.writeUtf(area.projectId(), ProjectViewLimits.PROJECT_ID_LENGTH);
                        }
                        buffer.writeUtf(area.kind().wireName(), ProjectViewLimits.AREA_ACTION_LENGTH);
                        buffer.writeUtf(area.name(), ProjectViewLimits.AREA_NAME_LENGTH);
                        buffer.writeUtf(area.world(), ProjectViewLimits.WORLD_ID_LENGTH);
                        buffer.writeVarInt(area.bounds().x1());
                        buffer.writeVarInt(area.bounds().y1());
                        buffer.writeVarInt(area.bounds().z1());
                        buffer.writeVarInt(area.bounds().x2());
                        buffer.writeVarInt(area.bounds().y2());
                        buffer.writeVarInt(area.bounds().z2());
                        buffer.writeVarLong(area.revision());
                        buffer.writeVarLong(area.geometryRevision());
                    }
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerObservation>
            WAREHOUSE_CODEC = new StreamCodec<>() {
                @Override
                public ContainerObservation decode(RegistryFriendlyByteBuf buffer) {
                    String world = buffer.readUtf(ProjectViewLimits.WORLD_ID_LENGTH);
                    int x = buffer.readVarInt();
                    int y = buffer.readVarInt();
                    int z = buffer.readVarInt();
                    String warning = buffer.readUtf(ProjectViewLimits.MESSAGE_LENGTH);
                    int count = buffer.readVarInt();
                    if (count < 0 || count > ProjectViewLimits.CONTAINER_ENTRIES) {
                        throw new IllegalArgumentException("warehouse observation exceeds "
                                + ProjectViewLimits.CONTAINER_ENTRIES + " entries");
                    }
                    java.util.Map<String, Long> counts = new java.util.TreeMap<>();
                    for (int index = 0; index < count; index++) {
                        String itemId = buffer.readUtf(ProjectViewLimits.MATERIAL_ID_LENGTH);
                        long itemCount = buffer.readVarLong();
                        if (counts.put(itemId, itemCount) != null) {
                            throw new IllegalArgumentException("duplicate warehouse item id: " + itemId);
                        }
                    }
                    return new ContainerObservation(new ContainerKey(world, x, y, z), counts, warning);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ContainerObservation observation) {
                    validateWarehouse(observation);
                    buffer.writeUtf(observation.key().world(), ProjectViewLimits.WORLD_ID_LENGTH);
                    buffer.writeVarInt(observation.key().x());
                    buffer.writeVarInt(observation.key().y());
                    buffer.writeVarInt(observation.key().z());
                    buffer.writeUtf(observation.warning(), ProjectViewLimits.MESSAGE_LENGTH);
                    buffer.writeVarInt(observation.counts().size());
                    for (java.util.Map.Entry<String, Long> entry : observation.counts().entrySet()) {
                        buffer.writeUtf(entry.getKey(), ProjectViewLimits.MATERIAL_ID_LENGTH);
                        buffer.writeVarLong(entry.getValue());
                    }
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectViewS2CPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public ProjectViewS2CPacket decode(RegistryFriendlyByteBuf buffer) {
                    boolean global = buffer.readBoolean();
                    String scopeId = buffer.readUtf(ProjectViewLimits.SCOPE_LENGTH);
                    ProjectViewScope scope = new ProjectViewScope(scopeId, global);
                    long requestId = buffer.readVarLong();
                    long viewId = buffer.readVarLong();
                    long generation = buffer.readVarLong();
                    long sequence = buffer.readVarLong();
                    Result result = Result.fromWireName(
                            buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH));
                    ProjectViewAction action = ProjectViewAction.fromWireName(
                            buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH));
                    boolean success = buffer.readBoolean();
                    String message = buffer.readUtf(ProjectViewLimits.MESSAGE_LENGTH);
                    int materialId = buffer.readVarInt();
                    boolean changed = buffer.readBoolean();
                    String snapshotId = buffer.readUtf(ProjectViewLimits.SNAPSHOT_ID_LENGTH);
                    long catalogRevision = buffer.readVarLong();
                    long inventoryRevision = buffer.readVarLong();
                    int partIndex = buffer.readVarInt();
                    int partCount = buffer.readVarInt();
                    int totalBytes = buffer.readVarInt();
                    int fragmentLength = buffer.readVarInt();
                    if (fragmentLength < 0 || fragmentLength > ProjectViewLimits.PART_BYTES) {
                        throw new IllegalArgumentException("fragment exceeds "
                                + ProjectViewLimits.PART_BYTES + " bytes");
                    }
                    byte[] fragment = new byte[fragmentLength];
                    buffer.readBytes(fragment);
                    Optional<AreaConfigData> configuration = buffer.readBoolean()
                            ? Optional.of(CONFIGURATION_CODEC.decode(buffer)) : Optional.empty();
                    Optional<ContainerObservation> warehouse = buffer.readBoolean()
                            ? Optional.of(WAREHOUSE_CODEC.decode(buffer)) : Optional.empty();
                    ProjectViewS2CPacket packet = new ProjectViewS2CPacket(scope, requestId, viewId,
                            generation, sequence, result, action, success, message, materialId,
                            changed, snapshotId, catalogRevision, inventoryRevision, partIndex,
                            partCount, totalBytes, fragment, configuration, warehouse);
                    if (buffer.readableBytes() != 0) {
                        throw new IllegalArgumentException("trailing bytes in project view result");
                    }
                    return packet;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ProjectViewS2CPacket packet) {
                    buffer.writeBoolean(packet.scope().global());
                    buffer.writeUtf(packet.scope().id(), ProjectViewLimits.SCOPE_LENGTH);
                    buffer.writeVarLong(packet.requestId());
                    buffer.writeVarLong(packet.viewId());
                    buffer.writeVarLong(packet.generation());
                    buffer.writeVarLong(packet.sequence());
                    buffer.writeUtf(packet.result().name(), ProjectViewLimits.AREA_ACTION_LENGTH);
                    buffer.writeUtf(packet.action().name(), ProjectViewLimits.AREA_ACTION_LENGTH);
                    buffer.writeBoolean(packet.success());
                    buffer.writeUtf(packet.message(), ProjectViewLimits.MESSAGE_LENGTH);
                    buffer.writeVarInt(packet.materialId());
                    buffer.writeBoolean(packet.changed());
                    buffer.writeUtf(packet.snapshotId(), ProjectViewLimits.SNAPSHOT_ID_LENGTH);
                    buffer.writeVarLong(packet.catalogRevision());
                    buffer.writeVarLong(packet.inventoryRevision());
                    buffer.writeVarInt(packet.partIndex());
                    buffer.writeVarInt(packet.partCount());
                    buffer.writeVarInt(packet.totalBytes());
                    buffer.writeVarInt(packet.fragment.length);
                    buffer.writeBytes(packet.fragment);
                    buffer.writeBoolean(packet.configuration().isPresent());
                    packet.configuration().ifPresent(data -> CONFIGURATION_CODEC.encode(buffer, data));
                    buffer.writeBoolean(packet.warehouse().isPresent());
                    packet.warehouse().ifPresent(data -> WAREHOUSE_CODEC.encode(buffer, data));
                }
            };

    public ProjectViewS2CPacket {
        scope = Objects.requireNonNull(scope, "scope");
        if (requestId < 0) {
            throw new IllegalArgumentException("requestId must be >= 0 (0 is a push)");
        }
        ProjectViewLimits.requirePositiveId(viewId, "viewId");
        ProjectViewLimits.requirePositiveId(generation, "generation");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(message, "message");
        if (message.length() > ProjectViewLimits.MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message exceeds "
                    + ProjectViewLimits.MESSAGE_LENGTH + " characters");
        }
        if (materialId < 0) {
            throw new IllegalArgumentException("materialId must be >= 0");
        }
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotId.length() > ProjectViewLimits.SNAPSHOT_ID_LENGTH) {
            throw new IllegalArgumentException("snapshotId exceeds "
                    + ProjectViewLimits.SNAPSHOT_ID_LENGTH + " characters");
        }
        if (catalogRevision < 0 || inventoryRevision < 0) {
            throw new IllegalArgumentException("revisions must be >= 0");
        }
        configuration = Objects.requireNonNull(configuration, "configuration");
        warehouse = Objects.requireNonNull(warehouse, "warehouse");
        configuration.ifPresent(ProjectViewS2CPacket::validateConfiguration);
        warehouse.ifPresent(ProjectViewS2CPacket::validateWarehouse);
        fragment = Objects.requireNonNull(fragment, "fragment").clone();
        if (fragment.length > ProjectViewLimits.PART_BYTES) {
            throw new IllegalArgumentException("fragment exceeds "
                    + ProjectViewLimits.PART_BYTES + " bytes");
        }

        // A compact constructor only assigns fields after the body; validate parameters directly.
        switch (result) {
            case PROGRESS_PART -> {
                requireProjectScope(result, scope);
                if (!success) {
                    throw new IllegalArgumentException("PROGRESS_PART must be successful");
                }
                if (action != ProjectViewAction.NONE) {
                    throw new IllegalArgumentException("PROGRESS_PART must not carry a command action");
                }
                if (materialId != 0 || changed) {
                    throw new IllegalArgumentException("PROGRESS_PART must not carry operation fields");
                }
                ProjectViewLimits.requireUuidString(snapshotId, "snapshotId");
                if (partCount <= 0 || partCount > ProjectViewLimits.MAX_PART_COUNT) {
                    throw new IllegalArgumentException("invalid partCount " + partCount);
                }
                if (partIndex < 0 || partIndex >= partCount) {
                    throw new IllegalArgumentException("invalid partIndex " + partIndex);
                }
                if (totalBytes <= 0 || totalBytes > ProjectViewLimits.TOTAL_BYTES) {
                    throw new IllegalArgumentException("invalid totalBytes " + totalBytes);
                }
                if (partCount != ProjectViewLimits.maxPartCount(totalBytes)) {
                    throw new IllegalArgumentException(
                            "partCount does not match ceil(totalBytes / PART_BYTES)");
                }
                if (fragment.length == 0) {
                    throw new IllegalArgumentException("PROGRESS_PART fragment is empty");
                }
                if (fragment.length > totalBytes - (partCount - 1)) {
                    throw new IllegalArgumentException("fragment leaves no room for remaining parts");
                }
                if (configuration.isPresent() || warehouse.isPresent()) {
                    throw new IllegalArgumentException("PROGRESS_PART must not carry typed data");
                }
            }
            case OPENED -> {
                ProjectViewCommandC2SPacket.requireViewScope(action, scope);
                if (!success) {
                    throw new IllegalArgumentException("OPENED must be successful");
                }
                if (requestId <= 0) {
                    throw new IllegalArgumentException("OPENED must be correlated (requestId > 0)");
                }
                if (action != ProjectViewAction.NONE) {
                    throw new IllegalArgumentException("OPENED must not carry a command action");
                }
                ProjectViewLimits.requireUuidString(snapshotId, "snapshotId");
                requireNoProgressOrTypedData(result, partIndex, partCount, totalBytes,
                        fragment, configuration, warehouse);
                if (materialId != 0 || changed) {
                    throw new IllegalArgumentException("OPENED must not carry operation fields");
                }
            }
            case CONFIG -> {
                if (configuration.isEmpty()) {
                    throw new IllegalArgumentException("CONFIG requires area configuration data");
                }
                if (warehouse.isPresent()) {
                    throw new IllegalArgumentException("CONFIG must not carry warehouse data");
                }
                if (action != ProjectViewAction.NONE && action != ProjectViewAction.AREA_CONFIG
                        && action != ProjectViewAction.RESCAN) {
                    throw new IllegalArgumentException("CONFIG result action is not applicable");
                }
                ProjectViewCommandC2SPacket.requireAreaActionScope(
                        configuration.get().action(), scope);
                requireNoProgressData(result, partIndex, partCount, totalBytes, fragment);
                requireNoSnapshotForUntypedResult(result, snapshotId);
            }
            case WAREHOUSE -> {
                if (warehouse.isEmpty() && !"COMPLETE".equals(message)) {
                    throw new IllegalArgumentException("WAREHOUSE requires an observation or COMPLETE boundary");
                }
                if (configuration.isPresent()) {
                    throw new IllegalArgumentException("WAREHOUSE must not carry configuration data");
                }
                if (action != ProjectViewAction.NONE && action != ProjectViewAction.AREA_CONFIG
                        && action != ProjectViewAction.RESCAN) {
                    throw new IllegalArgumentException("WAREHOUSE result action is not applicable");
                }
                requireNoProgressData(result, partIndex, partCount, totalBytes, fragment);
                requireNoSnapshotForUntypedResult(result, snapshotId);
            }
            case OPERATION -> {
                if (configuration.isPresent() || warehouse.isPresent()) {
                    throw new IllegalArgumentException("OPERATION must not carry typed data");
                }
                requireNoProgressData(result, partIndex, partCount, totalBytes, fragment);
                requireNoSnapshotForUntypedResult(result, snapshotId);
                if ((action == ProjectViewAction.JOIN || action == ProjectViewAction.LEAVE)
                        && materialId <= 0) {
                    throw new IllegalArgumentException("JOIN/LEAVE results require a materialId");
                }
                if ((action == ProjectViewAction.JOIN || action == ProjectViewAction.LEAVE)
                        && scope.global()) {
                    throw new IllegalArgumentException("JOIN/LEAVE results are project-scoped");
                }
            }
            case INVALIDATED, DELETED, ERROR -> {
                if (configuration.isPresent() || warehouse.isPresent()) {
                    throw new IllegalArgumentException(result + " must not carry typed data");
                }
                requireNoProgressData(result, partIndex, partCount, totalBytes, fragment);
                requireNoSnapshotForUntypedResult(result, snapshotId);
                if (result == Result.ERROR && success) {
                    throw new IllegalArgumentException("ERROR must not be successful");
                }
            }
        }

        if (estimatedBytes(scope, action, result, message, snapshotId, fragment, configuration,
                warehouse) > ProjectViewLimits.S2C_MAX_BYTES) {
            throw new IllegalArgumentException("project view envelope exceeds the S2C byte limit");
        }
    }

    private static void requireProjectScope(Result result, ProjectViewScope scope) {
        if (scope.global()) {
            throw new IllegalArgumentException(result + " requires a project scope");
        }
    }

    private static void requireNoProgressData(Result result, int partIndex, int partCount,
                                              int totalBytes, byte[] fragment) {
        if (partIndex != 0 || partCount != 0 || totalBytes != 0 || fragment.length != 0) {
            throw new IllegalArgumentException(result + " must not reuse progress fields");
        }
    }

    private static void requireNoProgressOrTypedData(Result result, int partIndex, int partCount,
                                                     int totalBytes, byte[] fragment,
                                                     Optional<AreaConfigData> configuration,
                                                     Optional<ContainerObservation> warehouse) {
        requireNoProgressData(result, partIndex, partCount, totalBytes, fragment);
        if (configuration.isPresent() || warehouse.isPresent()) {
            throw new IllegalArgumentException(result + " must not carry typed data");
        }
    }

    private static void requireNoSnapshotForUntypedResult(Result result, String snapshotId) {
        if (!snapshotId.isBlank()) {
            throw new IllegalArgumentException(result + " must not carry a snapshotId");
        }
    }

    private static void validateConfiguration(AreaConfigData data) {
        Objects.requireNonNull(data, "configuration data");
    }

    private static void validateWarehouse(ContainerObservation observation) {
        Objects.requireNonNull(observation, "warehouse observation");
        ProjectViewLimits.requireBoundedText(observation.key().world(), "warehouse world",
                ProjectViewLimits.WORLD_ID_LENGTH, false);
        CoreChecks.requireRegistryId(observation.key().world(), "warehouse world");
        ProjectViewLimits.requireBoundedText(observation.warning(), "warehouse warning",
                ProjectViewLimits.MESSAGE_LENGTH, true);
        if (observation.counts().size() > ProjectViewLimits.CONTAINER_ENTRIES) {
            throw new IllegalArgumentException("warehouse observation exceeds "
                    + ProjectViewLimits.CONTAINER_ENTRIES + " entries");
        }
        for (java.util.Map.Entry<String, Long> entry : observation.counts().entrySet()) {
            CoreChecks.requireRegistryId(entry.getKey(), "warehouse item id");
            ProjectViewLimits.requireNonNegative(entry.getValue(), "warehouse item count");
        }
    }

    /** Conservative upper bound; rejects an oversized envelope instead of truncating anything. */
    private static long estimatedBytes(ProjectViewScope scope, ProjectViewAction action,
                                       Result result, String message, String snapshotId,
                                       byte[] fragment,
                                       Optional<AreaConfigData> configuration,
                                       Optional<ContainerObservation> warehouse) {
        long bytes = 16;
        bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(scope.id());
        bytes += 10 * 4; // request/view/generation/sequence
        bytes += 2 * (5 + ProjectViewLimits.utfBytesUpperBound(result.name()));
        bytes += 5 + ProjectViewLimits.utfBytesUpperBound(action.name());
        bytes += 2;
        bytes += 5 + ProjectViewLimits.utfBytesUpperBound(message);
        bytes += 5;
        bytes += 5 + ProjectViewLimits.utfBytesUpperBound(snapshotId);
        bytes += 10 * 2;
        bytes += 5 * 3;
        bytes += 5L + fragment.length;
        if (configuration.isPresent()) {
            AreaConfigData data = configuration.get();
            bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(data.action()) + 5;
            for (AreaDefinition area : data.areas()) {
                bytes += 5 + 1;
                if (area.projectId() != null) {
                    bytes += 5 + ProjectViewLimits.utfBytesUpperBound(area.projectId());
                }
                bytes += 5 + ProjectViewLimits.utfBytesUpperBound(area.kind().wireName());
                bytes += 5 + ProjectViewLimits.utfBytesUpperBound(area.name());
                bytes += 5 + ProjectViewLimits.utfBytesUpperBound(area.world());
                bytes += 6 * 5 + 2 * 10;
            }
        } else {
            bytes += 1;
        }
        if (warehouse.isPresent()) {
            ContainerObservation data = warehouse.get();
            bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(data.key().world());
            bytes += 3 * 5;
            bytes += 5 + ProjectViewLimits.utfBytesUpperBound(data.warning());
            bytes += 5;
            for (java.util.Map.Entry<String, Long> entry : data.counts().entrySet()) {
                bytes += 5 + ProjectViewLimits.utfBytesUpperBound(entry.getKey()) + 10;
            }
        } else {
            bytes += 1;
        }
        return bytes;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    @Override
    public boolean isProgressPart() {
        return result == Result.PROGRESS_PART;
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
        if (!(other instanceof ProjectViewS2CPacket packet)) {
            return false;
        }
        return requestId == packet.requestId && viewId == packet.viewId
                && generation == packet.generation && sequence == packet.sequence
                && success == packet.success && materialId == packet.materialId
                && changed == packet.changed && catalogRevision == packet.catalogRevision
                && inventoryRevision == packet.inventoryRevision && partIndex == packet.partIndex
                && partCount == packet.partCount && totalBytes == packet.totalBytes
                && scope.equals(packet.scope) && result == packet.result
                && action == packet.action && message.equals(packet.message)
                && snapshotId.equals(packet.snapshotId)
                && Arrays.equals(fragment, packet.fragment)
                && configuration.equals(packet.configuration) && warehouse.equals(packet.warehouse);
    }

    @Override
    public int hashCode() {
        int resultCode = Objects.hash(scope, requestId, viewId, generation, sequence, result, action,
                success, message, materialId, changed, snapshotId, catalogRevision,
                inventoryRevision, partIndex, partCount, totalBytes, configuration, warehouse);
        return 31 * resultCode + Arrays.hashCode(fragment);
    }

    /** Typed area-configuration result. Areas are sorted and defensively copied. */
    public record AreaConfigData(String action, List<AreaDefinition> areas) {
        public AreaConfigData {
            ProjectViewCommandC2SPacket.requireKnownAreaAction(action);
            List<AreaDefinition> copy = new ArrayList<>(Objects.requireNonNull(areas, "areas"));
            if (copy.size() > ProjectViewLimits.AREAS) {
                throw new IllegalArgumentException("area list exceeds " + ProjectViewLimits.AREAS);
            }
            for (AreaDefinition area : copy) {
                Objects.requireNonNull(area, "area");
                ProjectViewLimits.requireBoundedText(area.name(), "area name",
                        ProjectViewLimits.AREA_NAME_LENGTH, false);
                ProjectViewLimits.requireBoundedText(area.world(), "area world",
                        ProjectViewLimits.WORLD_ID_LENGTH, false);
                CoreChecks.requireRegistryId(area.world(), "area world");
                if (area.projectId() != null) {
                    ProjectViewLimits.requireBoundedText(area.projectId(), "area project id",
                            ProjectViewLimits.PROJECT_ID_LENGTH, false);
                }
            }
            copy.sort(java.util.Comparator.comparingInt(AreaDefinition::id));
            Set<Integer> ids = new HashSet<>();
            for (AreaDefinition area : copy) {
                if (!ids.add(area.id())) {
                    throw new IllegalArgumentException("duplicate area id: " + area.id());
                }
            }
            areas = List.copyOf(copy);
        }
    }
}
