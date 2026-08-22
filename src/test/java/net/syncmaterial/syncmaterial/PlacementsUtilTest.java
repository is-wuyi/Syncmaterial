package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.syncmaterial.syncmaterial.server.PlacementsUtil;

/**
 * PlacementsUtil 解析逻辑测试。
 * 不依赖 Minecraft 运行时和文件系统。
 */
public class PlacementsUtilTest {

    @Test
    void parsePlacementNames_normalInput() {
        String json = """
            {
                "placements": [
                    {"id": "uuid-1", "display_name": "示例原理图"},
                    {"id": "uuid-2", "display_name": "测试建筑"}
                ]
            }
            """;

        Map<String, String> result = PlacementsUtil.parsePlacementNames(json);
        assertEquals(2, result.size());
        assertEquals("示例原理图", result.get("uuid-1"));
        assertEquals("测试建筑", result.get("uuid-2"));
    }

    @Test
    void parsePlacementNames_emptyPlacements() {
        String json = """
            {"placements": []}
            """;

        Map<String, String> result = PlacementsUtil.parsePlacementNames(json);
        assertTrue(result.isEmpty());
    }

    @Test
    void parsePlacementNames_missingDisplayName() {
        String json = """
            {
                "placements": [
                    {"id": "uuid-1"}
                ]
            }
            """;

        Map<String, String> result = PlacementsUtil.parsePlacementNames(json);
        assertEquals(1, result.size());
        assertEquals("unknown", result.get("uuid-1"));
    }

    @Test
    void parsePlacementNames_malformedJson() {
        Map<String, String> result = PlacementsUtil.parsePlacementNames("not valid json");
        assertTrue(result.isEmpty());
    }

    @Test
    void parsePlacementNames_missingPlacementsArray() {
        Map<String, String> result = PlacementsUtil.parsePlacementNames("{}");
        assertTrue(result.isEmpty());
    }
}
