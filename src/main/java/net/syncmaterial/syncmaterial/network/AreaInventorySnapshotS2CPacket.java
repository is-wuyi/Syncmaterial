package net.syncmaterial.syncmaterial.network;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Authoritative area-inventory snapshot for one project (protocol 3).
 *
 * <p>The snapshot is bound to the material catalog delivery sequence the client currently
 * has on screen ({@code catalogRevision}) and to a monotonically increasing inventory
 * {@code sequence}. The client rejects a snapshot whose catalog is stale or whose sequence
 * is not newer than the last applied one, so an async read cannot overwrite a newer view.</p>
 *
 * <p>The material list is the full current catalog (including zero counts). An empty list is
 * a valid, fully observed empty catalog. {@code known} states whether every configured area
 * was observed: unknown counts never mean "prepared". {@code freshnessInfo} lists the areas
 * that are not currently fresh; an empty list clears previously shown freshness warnings.</p>
 */
public record AreaInventorySnapshotS2CPacket(
        String schematicId,
        long catalogRevision,
        long sequence,
        boolean known,
        List<MaterialInventory> materials,
        List<CollaborationStatusS2CPacket.AreaFreshnessInfo> freshnessInfo
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AreaInventorySnapshotS2CPacket> ID =
            new CustomPacketPayload.Type<>(ModPackets.AREA_INVENTORY_SNAPSHOT);

    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialInventory> MATERIAL_INVENTORY_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MaterialInventory::materialId,
                    ByteBufCodecs.VAR_INT, MaterialInventory::stagingCount,
                    ByteBufCodecs.VAR_INT, MaterialInventory::warehouseCount,
                    MaterialInventory::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaInventorySnapshotS2CPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, AreaInventorySnapshotS2CPacket::schematicId,
                    ByteBufCodecs.VAR_LONG, AreaInventorySnapshotS2CPacket::catalogRevision,
                    ByteBufCodecs.VAR_LONG, AreaInventorySnapshotS2CPacket::sequence,
                    ByteBufCodecs.BOOL, AreaInventorySnapshotS2CPacket::known,
                    MATERIAL_INVENTORY_CODEC.apply(ByteBufCodecs.list()), AreaInventorySnapshotS2CPacket::materials,
                    CollaborationStatusS2CPacket.FRESHNESS_CODEC.apply(ByteBufCodecs.list()),
                    AreaInventorySnapshotS2CPacket::freshnessInfo,
                    AreaInventorySnapshotS2CPacket::new);

    public AreaInventorySnapshotS2CPacket {
        if (schematicId == null || schematicId.isBlank()) {
            throw new IllegalArgumentException("Invalid area inventory project id");
        }
        if (catalogRevision < 0 || sequence < 0) {
            throw new IllegalArgumentException("Invalid area inventory revision or sequence");
        }
        freshnessInfo = List.copyOf(freshnessInfo);
        if (known && hasFreshnessWarning(freshnessInfo)) {
            throw new IllegalArgumentException("Known area inventory cannot carry freshness warnings");
        }
        materials = List.copyOf(materials);
        Set<Integer> seen = new HashSet<>();
        for (MaterialInventory material : materials) {
            if (!seen.add(material.materialId())) {
                throw new IllegalArgumentException("Duplicate material id " + material.materialId());
            }
        }
    }

    /** Unknown/freshness statuses are user-visible warnings; blank statuses are not. */
    public static boolean hasFreshnessWarning(List<CollaborationStatusS2CPacket.AreaFreshnessInfo> info) {
        for (CollaborationStatusS2CPacket.AreaFreshnessInfo entry : info) {
            if (entry.status() != null && !entry.status().isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** One material's authoritative area contribution. Counts are non-negative ints. */
    public record MaterialInventory(int materialId, int stagingCount, int warehouseCount) {
        public MaterialInventory {
            if (materialId <= 0 || stagingCount < 0 || warehouseCount < 0) {
                throw new IllegalArgumentException("Invalid material inventory entry");
            }
        }
    }
}
