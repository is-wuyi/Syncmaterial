package net.syncmaterial.syncmaterial.network;

import net.minecraft.resources.Identifier;

public class ModPackets {
    // 版本握手：进服后互报协议版本。这两个频道及其包格式永久冻结，详见 HelloC2SPacket
    public static final Identifier HELLO_C2S = Identifier.fromNamespaceAndPath("syncmaterial", "hello_c2s");
    public static final Identifier HELLO_S2C = Identifier.fromNamespaceAndPath("syncmaterial", "hello_s2c");

    public static final Identifier REQUEST_MATERIAL_LIST = Identifier.fromNamespaceAndPath("syncmaterial", "request_material_list");
    public static final Identifier MATERIAL_LIST_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "material_list_response");

    public static final Identifier QUERY_MATERIAL_STATUS = Identifier.fromNamespaceAndPath("syncmaterial", "query_material_status");

    // Collaboration packets (Phase 1)
    public static final Identifier JOIN_COLLABORATION = Identifier.fromNamespaceAndPath("syncmaterial", "join_collaboration");
    public static final Identifier LEAVE_COLLABORATION = Identifier.fromNamespaceAndPath("syncmaterial", "leave_collaboration");
    public static final Identifier INVENTORY_UPDATE = Identifier.fromNamespaceAndPath("syncmaterial", "inventory_update");
    public static final Identifier COLLABORATION_STATUS = Identifier.fromNamespaceAndPath("syncmaterial", "collaboration_status");

    // Staging area packets (Phase 2)
    public static final Identifier STAGING_AREA_CONFIG = Identifier.fromNamespaceAndPath("syncmaterial", "staging_area_config");
    public static final Identifier STAGING_AREA_CONFIG_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "staging_area_config_response");

    // Rescan staging area packets
    public static final Identifier RESCAN_STAGING_AREA = Identifier.fromNamespaceAndPath("syncmaterial", "rescan_staging_area");
    public static final Identifier RESCAN_STAGING_AREA_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "rescan_staging_area_response");

    // Phase 4: 负责人管理与批量分配
    public static final Identifier OWNER_ACTION = Identifier.fromNamespaceAndPath("syncmaterial", "owner_action");
    public static final Identifier OWNER_ACTION_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "owner_action_response");
    public static final Identifier BATCH_ASSIGN = Identifier.fromNamespaceAndPath("syncmaterial", "batch_assign");
    public static final Identifier BATCH_ASSIGN_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "batch_assign_response");
    public static final Identifier KICK_FROM_MATERIAL = Identifier.fromNamespaceAndPath("syncmaterial", "kick_from_material");
    public static final Identifier KICK_FROM_MATERIAL_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "kick_from_material_response");
    public static final Identifier PLAYER_LIST_REQUEST = Identifier.fromNamespaceAndPath("syncmaterial", "player_list_request");
    public static final Identifier PLAYER_LIST_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "player_list_response");

    // Material list subscription
    public static final Identifier MATERIAL_LIST_CLOSE_C2S = Identifier.fromNamespaceAndPath("syncmaterial", "material_list_close");

    // Phase 5: 仓库容器数据（取货模式订阅）
    public static final Identifier WAREHOUSE_CONTAINER_REQUEST = Identifier.fromNamespaceAndPath("syncmaterial", "warehouse_container_request");
    public static final Identifier WAREHOUSE_CONTAINER_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "warehouse_container_response");

    // Phase 5: 仓库区域线框（全局广播，不按原理图订阅）
    public static final Identifier WAREHOUSE_AREA_RESPONSE = Identifier.fromNamespaceAndPath("syncmaterial", "warehouse_area_response");
}
