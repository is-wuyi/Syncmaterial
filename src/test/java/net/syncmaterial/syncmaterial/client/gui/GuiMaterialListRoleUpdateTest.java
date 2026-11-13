package net.syncmaterial.syncmaterial.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 角色推送落地的边界测试：本地身份从非负责人升级为负责人时，即使名字/副负责人
 * 列表都没变，右栏也必须重建；推送不得触碰材料目录或区域库存状态。
 */
class GuiMaterialListRoleUpdateTest {
    private static final String PROJECT = "project-a";
    private static final PlayerIdentity OWNER = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000010"), "Owner");
    private static final PlayerIdentity ACTOR = new PlayerIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000011"), "Actor");

    private GuiMaterialList guiWith(String ownerName, String deputy, boolean ownerFlag,
                                    boolean mainOwnerFlag) {
        GuiMaterialList gui = mock(GuiMaterialList.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        SyncMaterialList sync = mock(SyncMaterialList.class);
        when(sync.getSchematicId()).thenReturn(PROJECT);
        List<String> deputies = new ArrayList<>();
        if (deputy != null) {
            deputies.add(deputy);
        }
        setField(gui, "materialList", sync);
        setField(gui, "ownerName", ownerName);
        setField(gui, "deputyOwners", deputies);
        setField(gui, "allowSelfClaim", true);
        setField(gui, "isOwner", ownerFlag);
        setField(gui, "isMainOwner", mainOwnerFlag);
        setField(gui, "mc", mock(Minecraft.class));
        return gui;
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("missing field " + name);
    }

    @Test
    void gainedOwnershipRebuildsManagementPanelEvenWhenNamesUnchanged() {
        GuiMaterialList gui = guiWith("Owner", null, false, false);
        try (MockedStatic<ClientScreenAccess> screens = mockStatic(ClientScreenAccess.class)) {
            screens.when(() -> ClientScreenAccess.currentScreen(any(Minecraft.class))).thenReturn(gui);
            doNothing().when(gui).initGui();

            TeamState state = new TeamState(PROJECT, ACTOR, List.of(), true);
            gui.applyTeamState(state, ACTOR);

            assertTrue(gui.isOwner(), "本地身份应升级为负责人");
            assertTrue(gui.isMainOwner(), "UUID 匹配主负责人时应升级");
            verify(gui).initGui();
        }
    }

    @Test
    void unchangedRolesDoNotRebuildOrTouchCatalog() {
        GuiMaterialList gui = guiWith("Owner", null, true, true);
        SyncMaterialList sync = (SyncMaterialList) getField(gui, "materialList");
        try (MockedStatic<ClientScreenAccess> screens = mockStatic(ClientScreenAccess.class)) {
            screens.when(() -> ClientScreenAccess.currentScreen(any(Minecraft.class))).thenReturn(gui);
            doNothing().when(gui).initGui();

            TeamState state = new TeamState(PROJECT, OWNER, List.of(), true);
            gui.applyTeamState(state, OWNER);

            verify(gui, never()).initGui();
            verify(sync, never()).setMaterialEntries(any(), anyLong());
            verify(sync, never()).invalidateAreaInventory();
            assertTrue(gui.isOwner());
        }
    }

    @Test
    void legacyBlankOwnerDoesNotWipeKnownOwner() {
        GuiMaterialList gui = guiWith("Owner", null, true, true);
        try (MockedStatic<ClientScreenAccess> screens = mockStatic(ClientScreenAccess.class)) {
            screens.when(() -> ClientScreenAccess.currentScreen(any(Minecraft.class))).thenReturn(gui);
            doNothing().when(gui).initGui();

            gui.applyLegacyOwnerState("", List.of(), false);

            assertEquals("Owner", gui.getOwnerName(), "失败回包的空负责人不得覆盖已知负责人");
            assertFalse(gui.isAllowSelfClaim());
        }
    }

    @Test
    void legacyBlankOwnerLeavesMainOwnerIdentityWhenNotRecomputed() {
        GuiMaterialList gui = guiWith("Owner", null, true, true);
        try (MockedStatic<ClientScreenAccess> screens = mockStatic(ClientScreenAccess.class)) {
            screens.when(() -> ClientScreenAccess.currentScreen(any(Minecraft.class))).thenReturn(gui);
            doNothing().when(gui).initGui();

            gui.applyLegacyOwnerState(null, null, true);

            assertEquals("Owner", gui.getOwnerName());
            assertTrue(gui.isMainOwner());
        }
    }

    private static Object getField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("missing field " + name);
    }
}
