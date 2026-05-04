package net.syncmaterial.syncmaterial.network;

import net.minecraft.util.Identifier;

/**
 * 网络通信常量定义。
 */
public class ModPackets {
    /**
     * 材料列表响应数据包 ID。
     */
    public static final Identifier MATERIAL_LIST_RESPONSE = Identifier.of("syncmaterial", "material_list_response");

    /**
     * 请求材料列表数据包 ID。
     */
    public static final Identifier REQUEST_MATERIAL_LIST = Identifier.of("syncmaterial", "request_material_list");
}
