package net.syncmaterial.syncmaterial.network;

import org.junit.jupiter.api.Test;

//? if >=26.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包 ID 注册表守卫：ID 拼错/撞车 = 断包，且任何 roundtrip 测试都抓不到
 * （收发双方用同一个错误 ID 依然"互通"）。这里保证 ID 清单本身的
 * 基本性质：数量符合预期、两两唯一、命名空间正确、路径格式合法。
 */
class ModPacketsTest {

    private static final List<Object> ALL_IDS = List.<Object>of(
        ModPackets.HELLO_C2S, ModPackets.HELLO_S2C,
        ModPackets.REQUEST_MATERIAL_LIST, ModPackets.MATERIAL_LIST_RESPONSE,
        ModPackets.MATERIAL_LIST_CLOSE_C2S, ModPackets.QUERY_MATERIAL_STATUS,
        ModPackets.JOIN_COLLABORATION, ModPackets.LEAVE_COLLABORATION,
        ModPackets.INVENTORY_UPDATE, ModPackets.COLLABORATION_STATUS, ModPackets.STAGING_AREA_CONFIG,
        ModPackets.STAGING_AREA_CONFIG_RESPONSE, ModPackets.RESCAN_STAGING_AREA,
        ModPackets.RESCAN_STAGING_AREA_RESPONSE, ModPackets.OWNER_ACTION,
        ModPackets.OWNER_ACTION_RESPONSE, ModPackets.BATCH_ASSIGN,
        ModPackets.BATCH_ASSIGN_RESPONSE, ModPackets.KICK_FROM_MATERIAL,
        ModPackets.KICK_FROM_MATERIAL_RESPONSE, ModPackets.PLAYER_LIST_REQUEST,
        ModPackets.PLAYER_LIST_RESPONSE, ModPackets.WAREHOUSE_CONTAINER_REQUEST,
        ModPackets.WAREHOUSE_CONTAINER_RESPONSE, ModPackets.WAREHOUSE_AREA_RESPONSE,
        ModPackets.MATERIAL_CATALOG_UPDATE
    );

    @Test
    void idCount_matchesRegisteredPayloads() {
        assertEquals(26, ALL_IDS.size(), "新增/删除包时必须同步更新本清单");
    }

    @Test
    void ids_areUnique() {
        Set<Object> seen = new HashSet<>();
        for (Object id : ALL_IDS) {
            assertTrue(seen.add(id), "包 ID 重复: " + id);
        }
    }

    @Test
    void ids_useSyncmaterialNamespace() {
        for (Object raw : ALL_IDS) {
            //? if >=26.2 {
            Identifier id = (Identifier) raw;
            //?} else {
            /*ResourceLocation id = (ResourceLocation) raw;
            *///?}
            assertEquals("syncmaterial", id.getNamespace(), () -> "命名空间错误: " + id);
            assertFalse(id.getPath().isEmpty());
            assertTrue(id.getPath().matches("[a-z0-9_/.-]+"), () -> "路径含非法字符: " + id);
        }
    }
}
