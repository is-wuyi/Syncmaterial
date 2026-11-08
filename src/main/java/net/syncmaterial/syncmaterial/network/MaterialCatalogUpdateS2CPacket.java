package net.syncmaterial.syncmaterial.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.syncmaterial.syncmaterial.api.MaterialEntry;

/** Updates an existing material view without opening or replacing a screen. */
public record MaterialCatalogUpdateS2CPacket(String schematicId, String schematicName, long revision, List<MaterialEntry> materials)
        implements CustomPacketPayload {
    public static final Type<MaterialCatalogUpdateS2CPacket> ID = new Type<>(ModPackets.MATERIAL_CATALOG_UPDATE);
    public static final StreamCodec<RegistryFriendlyByteBuf, MaterialCatalogUpdateS2CPacket> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaterialCatalogUpdateS2CPacket::schematicId,
            ByteBufCodecs.STRING_UTF8, MaterialCatalogUpdateS2CPacket::schematicName,
            ByteBufCodecs.VAR_LONG, MaterialCatalogUpdateS2CPacket::revision,
            MaterialEntry.PACKET_CODEC.apply(ByteBufCodecs.list()), MaterialCatalogUpdateS2CPacket::materials,
            MaterialCatalogUpdateS2CPacket::new);

    public MaterialCatalogUpdateS2CPacket {
        if (schematicId == null || schematicId.isBlank() || schematicName == null || revision < 0) {
            throw new IllegalArgumentException("Invalid material catalog identity");
        }
        materials = List.copyOf(materials);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
