package net.syncmaterial.syncmaterial.network;

import net.minecraft.util.Identifier;

public class ModPackets {
    public static final Identifier REQUEST_MATERIAL_LIST = Identifier.of("syncmaterial", "request_material_list");
    public static final Identifier MATERIAL_LIST_RESPONSE = Identifier.of("syncmaterial", "material_list_response");

    public static final Identifier CLAIM_MATERIAL = Identifier.of("syncmaterial", "claim_material");
    public static final Identifier CLAIM_RESULT = Identifier.of("syncmaterial", "claim_result");
    public static final Identifier QUERY_MATERIAL_STATUS = Identifier.of("syncmaterial", "query_material_status");
    public static final Identifier MATERIAL_STATUS_RESPONSE = Identifier.of("syncmaterial", "material_status_response");
}
