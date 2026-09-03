package net.syncmaterial.syncmaterial.client.gui;

import java.util.List;

import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 玩家选择弹窗搜索过滤的纯函数测试。
 * 过滤本体 GuiPlayerSelectDialog.filterPlayers 与列表渲染共用同一实现，
 * 这里锁住空查询、大小写不敏感、无匹配三种语义。
 */
class PlayerFilterTest {

    private final List<PlayerInfo> players = List.of(
            new PlayerInfo("Alice", true),
            new PlayerInfo("bob", false),
            new PlayerInfo("Charlie", true));

    @Test
    void emptyQuery_returnsAll()
    {
        assertSame(players, GuiPlayerSelectDialog.filterPlayers(players, ""));
        assertSame(players, GuiPlayerSelectDialog.filterPlayers(players, null));
    }

    @Test
    void caseInsensitiveContains()
    {
        List<PlayerInfo> result = GuiPlayerSelectDialog.filterPlayers(players, "ALI");
        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).name());

        // 小写查询匹配大写名字
        List<PlayerInfo> lower = GuiPlayerSelectDialog.filterPlayers(players, "char");
        assertEquals(1, lower.size());
        assertEquals("Charlie", lower.get(0).name());
    }

    @Test
    void noMatch_returnsEmpty()
    {
        assertTrue(GuiPlayerSelectDialog.filterPlayers(players, "zed").isEmpty());
    }

    @Test
    void substringMatch_keepsAllHits()
    {
        // "li" 同时命中 Alice 与 Charlie
        List<PlayerInfo> result = GuiPlayerSelectDialog.filterPlayers(players, "li");
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> p.name().equals("Alice")));
        assertTrue(result.stream().anyMatch(p -> p.name().equals("Charlie")));
    }
}
