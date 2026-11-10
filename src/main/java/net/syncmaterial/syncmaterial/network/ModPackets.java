package net.syncmaterial.syncmaterial.network;

//? if >=26.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

public class ModPackets {
    private static final String NAMESPACE = "syncmaterial";

    /*
     * 频道 ID 的字符串值永久冻结，两个版本分支逐字一致。
     * 这里只重复声明资源 ID 的类型与构造方法名（ResourceLocation/Identifier 是
     * 两个不兼容的 final 类型，Java 无法用一份字段声明同时表达），
     * 字段顺序、payload codec 和协议语义都不在这里重复。
     */

    //? if >=26.2 {
    // 版本握手：进服后互报协议版本。这两个频道及其包格式永久冻结，详见 HelloC2SPacket
    public static final Identifier HELLO_C2S = id("hello_c2s");
    public static final Identifier HELLO_S2C = id("hello_s2c");

    public static final Identifier REQUEST_MATERIAL_LIST = id("request_material_list");
    public static final Identifier MATERIAL_LIST_RESPONSE = id("material_list_response");
    public static final Identifier MATERIAL_CATALOG_UPDATE = id("material_catalog_update");

    public static final Identifier QUERY_MATERIAL_STATUS = id("query_material_status");

    // Collaboration packets (Phase 1)
    public static final Identifier JOIN_COLLABORATION = id("join_collaboration");
    public static final Identifier LEAVE_COLLABORATION = id("leave_collaboration");
    public static final Identifier INVENTORY_UPDATE = id("inventory_update");
    public static final Identifier COLLABORATION_STATUS = id("collaboration_status");

    // Staging area packets (Phase 2)
    public static final Identifier STAGING_AREA_CONFIG = id("staging_area_config");
    public static final Identifier STAGING_AREA_CONFIG_RESPONSE = id("staging_area_config_response");

    // Rescan staging area packets
    public static final Identifier RESCAN_STAGING_AREA = id("rescan_staging_area");
    public static final Identifier RESCAN_STAGING_AREA_RESPONSE = id("rescan_staging_area_response");

    // Phase 4: 负责人管理与批量分配
    public static final Identifier OWNER_ACTION = id("owner_action");
    public static final Identifier OWNER_ACTION_RESPONSE = id("owner_action_response");
    public static final Identifier BATCH_ASSIGN = id("batch_assign");
    public static final Identifier BATCH_ASSIGN_RESPONSE = id("batch_assign_response");
    public static final Identifier KICK_FROM_MATERIAL = id("kick_from_material");
    public static final Identifier KICK_FROM_MATERIAL_RESPONSE = id("kick_from_material_response");
    public static final Identifier PLAYER_LIST_REQUEST = id("player_list_request");
    public static final Identifier PLAYER_LIST_RESPONSE = id("player_list_response");

    // Material list subscription
    public static final Identifier MATERIAL_LIST_CLOSE_C2S = id("material_list_close");

    // Phase 5: 仓库容器数据（取货模式订阅）
    public static final Identifier WAREHOUSE_CONTAINER_REQUEST = id("warehouse_container_request");
    public static final Identifier WAREHOUSE_CONTAINER_RESPONSE = id("warehouse_container_response");

    // Phase 5: 仓库区域线框（全局广播，不按原理图订阅）
    public static final Identifier WAREHOUSE_AREA_RESPONSE = id("warehouse_area_response");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
    //?} else {
    /*// 版本握手：进服后互报协议版本。这两个频道及其包格式永久冻结，详见 HelloC2SPacket
    public static final ResourceLocation HELLO_C2S = id("hello_c2s");
    public static final ResourceLocation HELLO_S2C = id("hello_s2c");

    public static final ResourceLocation REQUEST_MATERIAL_LIST = id("request_material_list");
    public static final ResourceLocation MATERIAL_LIST_RESPONSE = id("material_list_response");
    public static final ResourceLocation MATERIAL_CATALOG_UPDATE = id("material_catalog_update");

    public static final ResourceLocation QUERY_MATERIAL_STATUS = id("query_material_status");

    // Collaboration packets (Phase 1)
    public static final ResourceLocation JOIN_COLLABORATION = id("join_collaboration");
    public static final ResourceLocation LEAVE_COLLABORATION = id("leave_collaboration");
    public static final ResourceLocation INVENTORY_UPDATE = id("inventory_update");
    public static final ResourceLocation COLLABORATION_STATUS = id("collaboration_status");

    // Staging area packets (Phase 2)
    public static final ResourceLocation STAGING_AREA_CONFIG = id("staging_area_config");
    public static final ResourceLocation STAGING_AREA_CONFIG_RESPONSE = id("staging_area_config_response");

    // Rescan staging area packets
    public static final ResourceLocation RESCAN_STAGING_AREA = id("rescan_staging_area");
    public static final ResourceLocation RESCAN_STAGING_AREA_RESPONSE = id("rescan_staging_area_response");

    // Phase 4: 负责人管理与批量分配
    public static final ResourceLocation OWNER_ACTION = id("owner_action");
    public static final ResourceLocation OWNER_ACTION_RESPONSE = id("owner_action_response");
    public static final ResourceLocation BATCH_ASSIGN = id("batch_assign");
    public static final ResourceLocation BATCH_ASSIGN_RESPONSE = id("batch_assign_response");
    public static final ResourceLocation KICK_FROM_MATERIAL = id("kick_from_material");
    public static final ResourceLocation KICK_FROM_MATERIAL_RESPONSE = id("kick_from_material_response");
    public static final ResourceLocation PLAYER_LIST_REQUEST = id("player_list_request");
    public static final ResourceLocation PLAYER_LIST_RESPONSE = id("player_list_response");

    // Material list subscription
    public static final ResourceLocation MATERIAL_LIST_CLOSE_C2S = id("material_list_close");

    // Phase 5: 仓库容器数据（取货模式订阅）
    public static final ResourceLocation WAREHOUSE_CONTAINER_REQUEST = id("warehouse_container_request");
    public static final ResourceLocation WAREHOUSE_CONTAINER_RESPONSE = id("warehouse_container_response");

    // Phase 5: 仓库区域线框（全局广播，不按原理图订阅）
    public static final ResourceLocation WAREHOUSE_AREA_RESPONSE = id("warehouse_area_response");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
    *///?}
}
