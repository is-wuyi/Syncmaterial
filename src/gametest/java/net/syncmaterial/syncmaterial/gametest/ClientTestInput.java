package net.syncmaterial.syncmaterial.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.syncmaterial.syncmaterial.client.ClientScreenAccess;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListEntry;

/**
 * Real mouse-input helpers for the production material-list row button and container slots.
 *
 * <p>These helpers deliberately use {@link ClientGameTestContext#getInput()} so the
 * click travels through Minecraft's real {@code MouseHandler} → {@code Screen}
 * dispatch. Reflection is used only to locate the production widget bounds
 * ({@code WidgetListBase.listWidgets}, {@code WidgetContainer.subWidgets},
 * {@code ButtonBase.displayString}); the action itself is never invoked directly.</p>
 */
public final class ClientTestInput {

    private ClientTestInput() {}

    /** Presses the production row button for the material, leaving the mouse button down. */
    public static void pressMaterialRowButton(ClientGameTestContext context, GuiMaterialList gui,
                                              int materialId, String expectedLabelKey) {
        String expected = StringUtils.translate(expectedLabelKey);
        double[] point = context.computeOnClient(client -> {
            if (ClientScreenAccess.currentScreen(client) != gui) {
                throw new AssertionError("材料列表不是当前界面，无法点击行按钮");
            }
            ButtonBase button = rowButton(gui, materialId, expected);
            return toScreenPoint(client, button.getX() + button.getWidth() / 2.0,
                    button.getY() + button.getHeight() / 2.0);
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().holdMouse(0);
    }

    /** Current production row-button label, read after a real click. */
    public static String materialRowButtonLabel(ClientGameTestContext context, GuiMaterialList gui,
                                                int materialId) {
        return context.computeOnClient(client -> {
            ButtonBase button = rowButton(gui, materialId, null);
            return displayString(button);
        });
    }

    public static void releaseLeftMouse(ClientGameTestContext context) {
        context.getInput().releaseMouse(0);
    }

    /** Presses a real container slot. The server click packet is sent by the normal menu path. */
    public static void pressContainerSlot(ClientGameTestContext context, int slotIndex) {
        double[] point = context.computeOnClient(client -> {
            Screen screen = ClientScreenAccess.currentScreen(client);
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                throw new AssertionError("当前界面不是容器界面，无法点击槽位");
            }
            int leftPos = intField(AbstractContainerScreen.class, container, "leftPos");
            int topPos = intField(AbstractContainerScreen.class, container, "topPos");
            List<Slot> slots = container.getMenu().slots;
            if (slotIndex < 0 || slotIndex >= slots.size()) {
                throw new AssertionError("槽位序号越界: " + slotIndex + "/" + slots.size());
            }
            Slot slot = slots.get(slotIndex);
            return toScreenPoint(client, leftPos + slot.x + 8.0, topPos + slot.y + 8.0);
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().holdMouse(0);
    }

    private static double[] toScreenPoint(Minecraft client, double guiX, double guiY) {
        var window = client.getWindow();
        double screenX = guiX * window.getScreenWidth() / (double) window.getGuiScaledWidth();
        double screenY = guiY * window.getScreenHeight() / (double) window.getGuiScaledHeight();
        if (screenX < 0 || screenY < 0
                || screenX > window.getScreenWidth() || screenY > window.getScreenHeight()) {
            throw new AssertionError("目标控件超出窗口范围: gui=" + guiX + "," + guiY
                    + " screen=" + screenX + "," + screenY);
        }
        return new double[]{screenX, screenY};
    }

    private static ButtonBase rowButton(GuiMaterialList gui, int materialId, String expectedLabel) {
        Object listWidget = getListWidget(gui);
        List<?> rowWidgets = listField(WidgetListBase.class, listWidget, "listWidgets");
        for (Object row : rowWidgets) {
            if (!(row instanceof WidgetListEntryBase<?> entryWidget)) continue;
            if (!(entryWidget.getEntry() instanceof MaterialListEntry material)
                    || material.getDatabaseId() != materialId) continue;
            List<?> children = listField(WidgetContainer.class, row, "subWidgets");
            String actual = null;
            for (Object child : children) {
                if (!(child instanceof ButtonBase button)) continue;
                actual = displayString(button);
                if (expectedLabel == null || expectedLabel.equals(actual)) {
                    return button;
                }
            }
            throw new AssertionError("材料行 " + materialId + " 没有期望按钮「" + expectedLabel
                    + "」，实际为「" + actual + "」");
        }
        throw new AssertionError("当前可见列表找不到材料行 " + materialId);
    }

    private static Object getListWidget(GuiMaterialList gui) {
        try {
            Method method = GuiListBase.class.getDeclaredMethod("getListWidget");
            method.setAccessible(true);
            return method.invoke(gui);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取材料列表 widget", failure);
        }
    }

    private static String displayString(ButtonBase button) {
        try {
            Object value = field(ButtonBase.class, "displayString").get(button);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取按钮文本", failure);
        }
    }

    private static int intField(Class<?> owner, Object target, String name) {
        try {
            return field(owner, name).getInt(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取字段 " + name, failure);
        }
    }

    private static List<?> listField(Class<?> owner, Object target, String name) {
        try {
            return (List<?>) field(owner, name).get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取列表字段 " + name, failure);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
