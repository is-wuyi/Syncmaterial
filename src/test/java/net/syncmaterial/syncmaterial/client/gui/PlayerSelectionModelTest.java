package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;
import org.junit.jupiter.api.Test;

/**
 * 玩家选择模型的纯函数测试：界面仍只显示名字+在线状态，但选择按行 id 保存，
 * 同名不同 UUID 不得坍缩；旧服务端只有名字时回退为未解析身份。
 */
class PlayerSelectionModelTest {
    private static final UUID ALICE_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void sameNameDifferentUuidRowsStayDistinctAndSelectable() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        model.setDirectory(List.of(
                new PlayerDirectoryEntry(new PlayerIdentity(ALICE_UUID, "Alex"), true),
                new PlayerDirectoryEntry(new PlayerIdentity(OTHER_UUID, "Alex"), false)));

        List<GuiPlayerSelectDialog.SelectionModel.Row> rows = model.rows();
        assertEquals(2, rows.size(), "同名不同 UUID 必须保留两行");
        assertNotEquals(rows.get(0).id(), rows.get(1).id());

        model.toggle(rows.get(0).id());
        model.toggle(rows.get(1).id());
        assertEquals(2, model.selectedIdentities().size());
        assertEquals(2, model.selectedCount());
        assertTrue(model.selectedIdentities().contains(new PlayerIdentity(ALICE_UUID, "Alex")));
        assertTrue(model.selectedIdentities().contains(new PlayerIdentity(OTHER_UUID, "Alex")));
    }

    @Test
    void stringSelectionRefusesAmbiguousSameName() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        model.setDirectory(List.of(
                new PlayerDirectoryEntry(new PlayerIdentity(ALICE_UUID, "Alex"), true),
                new PlayerDirectoryEntry(new PlayerIdentity(OTHER_UUID, "Alex"), false)));

        assertNull(model.firstByName("Alex"), "同名多身份时字符串选择必须无歧义失败");
        assertEquals(new PlayerIdentity(ALICE_UUID, "Alex"),
                model.firstByIdentity(new PlayerIdentity(ALICE_UUID, "Alex")).identity());
    }

    @Test
    void legacyPlayersKeepDisplayFactsButHaveNullUuid() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        model.setLegacyPlayers(List.of(new PlayerInfo("Alice", true), new PlayerInfo("Bob", false)));

        List<GuiPlayerSelectDialog.SelectionModel.Row> rows = model.rows();
        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).identity().name());
        assertNull(rows.get(0).identity().uuid());
        assertTrue(rows.get(0).online());
        assertFalse(rows.get(1).online());

        model.toggle(rows.get(1).id());
        assertEquals(List.of("Bob"), model.selectedNames());
    }

    @Test
    void prefilledDeputyIdentitiesPreserveUuid() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        model.setPrefilledIdentities(List.of(new PlayerIdentity(ALICE_UUID, "Alice")));

        assertEquals(1, model.rows().size());
        model.selectOnly(model.rows().get(0).id());
        assertEquals(List.of(new PlayerIdentity(ALICE_UUID, "Alice")), model.selectedIdentities());
    }

    @Test
    void filterIsCaseInsensitiveAndLeavesSelectionAlone() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        model.setDirectory(List.of(
                new PlayerDirectoryEntry(new PlayerIdentity(ALICE_UUID, "Alice"), true),
                new PlayerDirectoryEntry(new PlayerIdentity(OTHER_UUID, "Bob"), false)));
        model.selectOnly(model.rows().get(0).id());

        assertEquals(1, model.filtered("ALI").size());
        assertEquals(1, model.selectedCount());
        assertTrue(model.filtered("zed").isEmpty());
    }

    @Test
    void rowCountIsBoundedByDirectoryLimit() {
        GuiPlayerSelectDialog.SelectionModel model = new GuiPlayerSelectDialog.SelectionModel();
        List<PlayerDirectoryEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < net.syncmaterial.syncmaterial.core.TeamLimits.DIRECTORY + 50; index++) {
            entries.add(new PlayerDirectoryEntry(new PlayerIdentity(
                    new UUID(0, index + 1L), "P" + index), false));
        }
        model.setDirectory(entries);

        assertTrue(model.rows().size() <= net.syncmaterial.syncmaterial.core.TeamLimits.DIRECTORY);
    }
}
