package net.syncmaterial.syncmaterial.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.core.AreaBounds;
import net.syncmaterial.syncmaterial.core.CoreChecks;
import net.syncmaterial.syncmaterial.core.ProjectViewAction;
import net.syncmaterial.syncmaterial.core.ProjectViewLimits;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/**
 * Protocol-5 project view command (C2S).
 *
 * <p>One envelope with a strict action-specific shape. The scope is a typed
 * {@link ProjectViewScope}: project commands and the four global warehouse-management
 * actions are distinct names. Fields that do not belong to the selected action must be
 * empty; the codec rejects malformed combinations instead of dropping them on the server.
 * The new no-target area id is 0; the legacy adapter maps the old -1 sentinel with
 * {@link #normalizeLegacyAreaId(int)}. The existing Phase 1-4 packets and Hello stay
 * byte-for-byte frozen.</p>
 */
public record ProjectViewCommandC2SPacket(
        ProjectViewScope scope,
        long requestId,
        long viewId,
        long generation,
        ProjectViewAction action,
        String areaAction,
        int materialId,
        int areaId,
        int interestMask,
        Optional<StagingAreaConfigC2SPacket.AreaData> areaData,
        List<InventoryCount> inventory
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ProjectViewCommandC2SPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.PROJECT_VIEW_COMMAND);

    /** New convention: an action without an area target carries area id 0. */
    public static final int NO_AREA_ID = 0;
    /** Legacy adapter sentinel used by the Phase 1-4 GUI; never accepted on the new wire. */
    public static final int LEGACY_NO_AREA_ID = -1;

    /** Existing area-action whitelist; new names are a semantic change and need a protocol bump. */
    public static final Set<String> AREA_ACTIONS = Set.of(
            "LIST", "ADD", "RENAME", "DELETE", "UPDATE", "CLEAR",
            "LIST_WAREHOUSES", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE",
            "ADD_WAREHOUSE_REF", "REMOVE_WAREHOUSE_REF", "LIST_WAREHOUSE_REFS");

    /** The only area actions supported on a global warehouse scope. */
    public static final Set<String> GLOBAL_AREA_ACTIONS = Set.of(
            "LIST_WAREHOUSES", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE");

    private static final Set<String> AREA_ACTIONS_WITH_DATA = Set.of(
            "ADD", "RENAME", "UPDATE", "ADD_WAREHOUSE", "UPDATE_WAREHOUSE");
    private static final Set<String> AREA_ACTIONS_WITH_ID = Set.of(
            "RENAME", "UPDATE", "DELETE", "UPDATE_WAREHOUSE", "DELETE_WAREHOUSE",
            "ADD_WAREHOUSE_REF", "REMOVE_WAREHOUSE_REF");

    /**
     * Adapter-boundary normalization for the old no-target sentinel. The new envelope
     * itself only accepts 0, so callers translating legacy GUI calls must use this helper.
     */
    public static int normalizeLegacyAreaId(int areaId) {
        if (areaId == LEGACY_NO_AREA_ID) {
            return NO_AREA_ID;
        }
        if (areaId < 0) {
            throw new IllegalArgumentException("legacy areaId must be -1 or non-negative");
        }
        return areaId;
    }

    public static boolean isKnownAreaAction(String action) {
        return action != null && AREA_ACTIONS.contains(action);
    }

    public static boolean isGlobalAreaAction(String action) {
        return action != null && GLOBAL_AREA_ACTIONS.contains(action);
    }

    public static void requireKnownAreaAction(String action) {
        if (!isKnownAreaAction(action)) {
            throw new IllegalArgumentException("unknown area action: " + action);
        }
    }

    /** Enforces the typed scope/action pairing for every area-config command. */
    public static void requireAreaActionScope(String action, ProjectViewScope scope) {
        Objects.requireNonNull(scope, "scope");
        requireKnownAreaAction(action);
        boolean globalAction = GLOBAL_AREA_ACTIONS.contains(action);
        if (scope.global() != globalAction) {
            throw new IllegalArgumentException(action + (globalAction
                    ? " requires a global scope" : " requires a project scope"));
        }
    }

    /** Bounded replacement codec for the existing AreaData record (the old packet keeps its own). */
    public static final StreamCodec<RegistryFriendlyByteBuf, StagingAreaConfigC2SPacket.AreaData>
            AREA_DATA_CODEC = new StreamCodec<>() {
                @Override
                public StagingAreaConfigC2SPacket.AreaData decode(RegistryFriendlyByteBuf buffer) {
                    String name = buffer.readUtf(ProjectViewLimits.AREA_NAME_LENGTH);
                    int x1 = buffer.readVarInt();
                    int y1 = buffer.readVarInt();
                    int z1 = buffer.readVarInt();
                    int x2 = buffer.readVarInt();
                    int y2 = buffer.readVarInt();
                    int z2 = buffer.readVarInt();
                    Optional<String> world = buffer.readBoolean()
                            ? Optional.of(buffer.readUtf(ProjectViewLimits.WORLD_ID_LENGTH))
                            : Optional.empty();
                    StagingAreaConfigC2SPacket.AreaData data = new StagingAreaConfigC2SPacket.AreaData(
                            name, x1, y1, z1, x2, y2, z2, world);
                    validateAreaData(data);
                    return data;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer,
                                   StagingAreaConfigC2SPacket.AreaData data) {
                    validateAreaData(data);
                    buffer.writeUtf(data.name(), ProjectViewLimits.AREA_NAME_LENGTH);
                    buffer.writeVarInt(data.x1());
                    buffer.writeVarInt(data.y1());
                    buffer.writeVarInt(data.z1());
                    buffer.writeVarInt(data.x2());
                    buffer.writeVarInt(data.y2());
                    buffer.writeVarInt(data.z2());
                    buffer.writeBoolean(data.world().isPresent());
                    data.world().ifPresent(world ->
                            buffer.writeUtf(world, ProjectViewLimits.WORLD_ID_LENGTH));
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectViewCommandC2SPacket> CODEC =
            new StreamCodec<>() {
                @Override
                public ProjectViewCommandC2SPacket decode(RegistryFriendlyByteBuf buffer) {
                    boolean global = buffer.readBoolean();
                    String scopeId = buffer.readUtf(ProjectViewLimits.SCOPE_LENGTH);
                    ProjectViewScope scope = new ProjectViewScope(scopeId, global);
                    long requestId = buffer.readVarLong();
                    long viewId = buffer.readVarLong();
                    long generation = buffer.readVarLong();
                    ProjectViewAction action = ProjectViewAction.fromWireName(
                            buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH));
                    String areaAction = buffer.readBoolean()
                            ? buffer.readUtf(ProjectViewLimits.AREA_ACTION_LENGTH) : null;
                    int materialId = buffer.readVarInt();
                    int areaId = buffer.readVarInt();
                    int interestMask = buffer.readVarInt();
                    Optional<StagingAreaConfigC2SPacket.AreaData> areaData = buffer.readBoolean()
                            ? Optional.of(AREA_DATA_CODEC.decode(buffer)) : Optional.empty();
                    int inventoryCount = buffer.readVarInt();
                    if (inventoryCount < 0
                            || inventoryCount > ProjectViewLimits.INVENTORY_OBSERVATIONS) {
                        throw new IllegalArgumentException("inventory observations exceed "
                                + ProjectViewLimits.INVENTORY_OBSERVATIONS);
                    }
                    List<InventoryCount> inventory = new ArrayList<>(inventoryCount);
                    for (int index = 0; index < inventoryCount; index++) {
                        inventory.add(new InventoryCount(buffer.readVarInt(), buffer.readVarLong()));
                    }
                    ProjectViewCommandC2SPacket packet = new ProjectViewCommandC2SPacket(scope,
                            requestId, viewId, generation, action, areaAction, materialId, areaId,
                            interestMask, areaData, inventory);
                    if (buffer.readableBytes() != 0) {
                        throw new IllegalArgumentException("trailing bytes in project view command");
                    }
                    return packet;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer,
                                   ProjectViewCommandC2SPacket packet) {
                    buffer.writeBoolean(packet.scope().global());
                    buffer.writeUtf(packet.scope().id(), ProjectViewLimits.SCOPE_LENGTH);
                    buffer.writeVarLong(packet.requestId());
                    buffer.writeVarLong(packet.viewId());
                    buffer.writeVarLong(packet.generation());
                    buffer.writeUtf(packet.action().name(), ProjectViewLimits.AREA_ACTION_LENGTH);
                    buffer.writeBoolean(packet.areaAction() != null);
                    if (packet.areaAction() != null) {
                        buffer.writeUtf(packet.areaAction(), ProjectViewLimits.AREA_ACTION_LENGTH);
                    }
                    buffer.writeVarInt(packet.materialId());
                    buffer.writeVarInt(packet.areaId());
                    buffer.writeVarInt(packet.interestMask());
                    buffer.writeBoolean(packet.areaData().isPresent());
                    packet.areaData().ifPresent(data -> AREA_DATA_CODEC.encode(buffer, data));
                    buffer.writeVarInt(packet.inventory().size());
                    for (InventoryCount count : packet.inventory()) {
                        buffer.writeVarInt(count.materialId());
                        buffer.writeVarLong(count.count());
                    }
                }
            };

    public ProjectViewCommandC2SPacket {
        scope = Objects.requireNonNull(scope, "scope");
        ProjectViewLimits.requirePositiveId(requestId, "requestId");
        ProjectViewLimits.requirePositiveId(viewId, "viewId");
        ProjectViewLimits.requirePositiveId(generation, "generation");
        Objects.requireNonNull(action, "action");
        if (!action.isCommand()) {
            throw new IllegalArgumentException("A command cannot carry the NONE action");
        }
        areaData = Objects.requireNonNull(areaData, "areaData");
        inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        if (inventory.size() > ProjectViewLimits.INVENTORY_OBSERVATIONS) {
            throw new IllegalArgumentException("inventory observations exceed "
                    + ProjectViewLimits.INVENTORY_OBSERVATIONS);
        }
        Set<Integer> inventoryIds = new HashSet<>();
        for (InventoryCount count : inventory) {
            Objects.requireNonNull(count, "inventory entry");
            if (!inventoryIds.add(count.materialId())) {
                throw new IllegalArgumentException("duplicate inventory material id: "
                        + count.materialId());
            }
        }
        if (!ProjectViewLimits.isKnownInterestMask(interestMask)) {
            throw new IllegalArgumentException("unknown interest bits: " + interestMask);
        }

        // A compact constructor only assigns fields after the body; validate parameters directly.
        switch (action) {
            case OPEN -> {
                requireViewScope(action, scope);
                requireGlobalInterestMask(action, scope, interestMask);
                requireNoAreaFields(action, areaAction, areaData);
                requireZeroMaterialAndArea(action, materialId, areaId);
                requireInventoryEmpty(action, inventory);
            }
            case SET_INTERESTS -> {
                requireViewScope(action, scope);
                requireGlobalInterestMask(action, scope, interestMask);
                requireNoAreaFields(action, areaAction, areaData);
                requireZeroMaterialAndArea(action, materialId, areaId);
                requireInventoryEmpty(action, inventory);
            }
            case AREA_CONFIG -> {
                requireNoMaterialAndNoMask(action, materialId, interestMask);
                requireInventoryEmpty(action, inventory);
                requireAreaActionScope(areaAction, scope);
                if (areaId < 0) {
                    throw new IllegalArgumentException("areaId must be >= 0");
                }
                if (AREA_ACTIONS_WITH_ID.contains(areaAction) && areaId <= 0) {
                    throw new IllegalArgumentException(areaAction + " requires a positive areaId");
                }
                if (!AREA_ACTIONS_WITH_ID.contains(areaAction) && areaId != NO_AREA_ID) {
                    throw new IllegalArgumentException(areaAction + " must not carry an areaId");
                }
                boolean needsData = AREA_ACTIONS_WITH_DATA.contains(areaAction);
                if (needsData != areaData.isPresent()) {
                    throw new IllegalArgumentException(areaAction + (needsData
                            ? " requires area data" : " must not carry area data"));
                }
                areaData.ifPresent(ProjectViewCommandC2SPacket::validateAreaData);
            }
            case RESCAN -> {
                requireProjectScope(action, scope);
                requireNoAreaFields(action, areaAction, areaData);
                requireNoMaterialAndNoMask(action, materialId, interestMask);
                requireInventoryEmpty(action, inventory);
                if (areaId < 0) {
                    throw new IllegalArgumentException("areaId must be >= 0");
                }
            }
            case CLOSE -> {
                requireViewScope(action, scope);
                requireNoAreaFields(action, areaAction, areaData);
                requireNoMaterialAndNoMask(action, materialId, interestMask);
                requireInventoryEmpty(action, inventory);
                if (areaId != NO_AREA_ID) {
                    throw new IllegalArgumentException("CLOSE must not carry an areaId");
                }
            }
            case JOIN -> {
                requireProjectScope(action, scope);
                requireNoAreaFields(action, areaAction, areaData);
                requireNoMask(action, interestMask);
                if (materialId <= 0) {
                    throw new IllegalArgumentException("JOIN requires a positive materialId");
                }
                if (areaId != NO_AREA_ID) {
                    throw new IllegalArgumentException("JOIN must not carry an areaId");
                }
            }
            case LEAVE -> {
                requireProjectScope(action, scope);
                requireNoAreaFields(action, areaAction, areaData);
                requireNoMask(action, interestMask);
                requireInventoryEmpty(action, inventory);
                if (materialId <= 0) {
                    throw new IllegalArgumentException("LEAVE requires a positive materialId");
                }
                if (areaId != NO_AREA_ID) {
                    throw new IllegalArgumentException("LEAVE must not carry an areaId");
                }
            }
            default -> throw new IllegalArgumentException("unsupported command action: " + action);
        }

        if (estimatedBytes(scope, action, areaAction, areaData, inventory)
                > ProjectViewLimits.C2S_MAX_BYTES) {
            throw new IllegalArgumentException("project view command exceeds the C2S envelope limit");
        }
    }

    private static void requireProjectScope(ProjectViewAction action, ProjectViewScope scope) {
        if (scope.global()) {
            throw new IllegalArgumentException(action + " requires a project scope");
        }
    }

    static void requireViewScope(ProjectViewAction action, ProjectViewScope scope) {
        if (scope.global() && !scope.equals(ProjectViewScope.globalWarehouse())) {
            throw new IllegalArgumentException(action + " requires a project or the global warehouse scope");
        }
    }

    private static void requireGlobalInterestMask(ProjectViewAction action, ProjectViewScope scope,
                                                  int interestMask) {
        int globalMask = ProjectViewLimits.INTEREST_AREA_CONFIG | ProjectViewLimits.INTEREST_OUTLINES;
        if (scope.global() && (interestMask & ~globalMask) != 0) {
            throw new IllegalArgumentException(action + " global interests are AREA_CONFIG and OUTLINES only");
        }
    }

    private static void requireNoAreaFields(ProjectViewAction action, String areaAction,
                                            Optional<StagingAreaConfigC2SPacket.AreaData> areaData) {
        if (areaAction != null) {
            throw new IllegalArgumentException(action + " must not carry areaAction");
        }
        if (areaData.isPresent()) {
            throw new IllegalArgumentException(action + " must not carry area data");
        }
    }

    private static void requireNoMaterialAndNoMask(ProjectViewAction action, int materialId,
                                                   int interestMask) {
        if (materialId != 0) {
            throw new IllegalArgumentException(action + " must not carry a materialId");
        }
        requireNoMask(action, interestMask);
    }

    private static void requireZeroMaterialAndArea(ProjectViewAction action, int materialId,
                                                   int areaId) {
        if (materialId != 0) {
            throw new IllegalArgumentException(action + " must not carry a materialId");
        }
        if (areaId != NO_AREA_ID) {
            throw new IllegalArgumentException(action + " must not carry an areaId");
        }
    }

    private static void requireNoMask(ProjectViewAction action, int interestMask) {
        if (interestMask != 0) {
            throw new IllegalArgumentException(action + " must not carry an interest mask");
        }
    }

    private static void requireInventoryEmpty(ProjectViewAction action,
                                              List<InventoryCount> inventory) {
        if (!inventory.isEmpty()) {
            throw new IllegalArgumentException(action + " must not carry inventory observations");
        }
    }

    private static void validateAreaData(StagingAreaConfigC2SPacket.AreaData data) {
        Objects.requireNonNull(data, "area data");
        ProjectViewLimits.requireBoundedText(data.name(), "area name",
                ProjectViewLimits.AREA_NAME_LENGTH, false);
        // Reuse AreaBounds for coordinates, chunk count and world-height bounds.
        new AreaBounds(data.x1(), data.y1(), data.z1(), data.x2(), data.y2(), data.z2());
        Objects.requireNonNull(data.world(), "area world");
        data.world().ifPresent(world -> {
            ProjectViewLimits.requireBoundedText(world, "area world",
                    ProjectViewLimits.WORLD_ID_LENGTH, false);
            CoreChecks.requireRegistryId(world, "area world");
        });
    }

    /** Conservative upper bound; never used to truncate, only to reject an oversized envelope. */
    private static long estimatedBytes(ProjectViewScope scope, ProjectViewAction action,
                                       String areaAction,
                                       Optional<StagingAreaConfigC2SPacket.AreaData> areaData,
                                       List<InventoryCount> inventory) {
        long bytes = 16;
        bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(scope.id());
        bytes += 10 * 3; // request/view/generation VarLongs
        bytes += 5 + ProjectViewLimits.utfBytesUpperBound(action.name());
        if (areaAction != null) {
            bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(areaAction);
        } else {
            bytes += 1;
        }
        bytes += 15; // materialId, areaId, interestMask
        if (areaData.isPresent()) {
            StagingAreaConfigC2SPacket.AreaData data = areaData.get();
            bytes += 1 + 5 + ProjectViewLimits.utfBytesUpperBound(data.name()) + 30;
            bytes += 1 + (data.world().isPresent()
                    ? 5 + ProjectViewLimits.utfBytesUpperBound(data.world().get()) : 0);
        } else {
            bytes += 1;
        }
        bytes += 5;
        bytes += (long) inventory.size() * 15;
        return bytes;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** One JOIN inventory observation. Counts are long and may not be negative. */
    public record InventoryCount(int materialId, long count) {
        public InventoryCount {
            if (materialId <= 0) {
                throw new IllegalArgumentException("inventory materialId must be > 0");
            }
            if (count < 0) {
                throw new IllegalArgumentException("inventory count must be >= 0");
            }
        }
    }
}
