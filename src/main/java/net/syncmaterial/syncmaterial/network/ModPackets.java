package net.syncmaterial.syncmaterial.network;

import net.minecraft.util.Identifier;

public class ModPackets {
    public static final Identifier REQUEST_MATERIAL_LIST = Identifier.of("syncmaterial", "request_material_list");
    public static final Identifier MATERIAL_LIST_RESPONSE = Identifier.of("syncmaterial", "material_list_response");

    public static final Identifier CLAIM_MATERIAL = Identifier.of("syncmaterial", "claim_material");
    public static final Identifier CLAIM_RESULT = Identifier.of("syncmaterial", "claim_result");
    public static final Identifier QUERY_MATERIAL_STATUS = Identifier.of("syncmaterial", "query_material_status");
    public static final Identifier MATERIAL_STATUS_RESPONSE = Identifier.of("syncmaterial", "material_status_response");

    // Collaboration packets (Phase 1)
    public static final Identifier JOIN_COLLABORATION = Identifier.of("syncmaterial", "join_collaboration");
    public static final Identifier LEAVE_COLLABORATION = Identifier.of("syncmaterial", "leave_collaboration");
    public static final Identifier INVENTORY_UPDATE = Identifier.of("syncmaterial", "inventory_update");
    public static final Identifier COLLABORATION_STATUS = Identifier.of("syncmaterial", "collaboration_status");

    // Staging area packets (Phase 2)
    public static final Identifier STAGING_AREA_CONFIG = Identifier.of("syncmaterial", "staging_area_config");
    public static final Identifier STAGING_AREA_CONFIG_RESPONSE = Identifier.of("syncmaterial", "staging_area_config_response");

    // Rescan staging area packets
    public static final Identifier RESCAN_STAGING_AREA = Identifier.of("syncmaterial", "rescan_staging_area");
    public static final Identifier RESCAN_STAGING_AREA_RESPONSE = Identifier.of("syncmaterial", "rescan_staging_area_response");

    // Phase 4: 负责人管理与批量分配
    public static final Identifier OWNER_ACTION = Identifier.of("syncmaterial", "owner_action");
    public static final Identifier OWNER_ACTION_RESPONSE = Identifier.of("syncmaterial", "owner_action_response");
    public static final Identifier BATCH_ASSIGN = Identifier.of("syncmaterial", "batch_assign");
    public static final Identifier BATCH_ASSIGN_RESPONSE = Identifier.of("syncmaterial", "batch_assign_response");
    public static final Identifier KICK_FROM_MATERIAL = Identifier.of("syncmaterial", "kick_from_material");
    public static final Identifier KICK_FROM_MATERIAL_RESPONSE = Identifier.of("syncmaterial", "kick_from_material_response");
    public static final Identifier PLAYER_LIST_REQUEST = Identifier.of("syncmaterial", "player_list_request");
    public static final Identifier PLAYER_LIST_RESPONSE = Identifier.of("syncmaterial", "player_list_response");

    // Material list subscription
    public static final Identifier MATERIAL_LIST_CLOSE_C2S = Identifier.of("syncmaterial", "material_list_close");
}
