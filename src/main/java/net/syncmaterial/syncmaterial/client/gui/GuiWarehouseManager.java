package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.gui.Message;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 全局仓库管理界面（Phase 5）
 * 入口：Litematica 主界面按钮
 * 功能：新建/编辑/删除全局仓库，支持准星选区
 */
public class GuiWarehouseManager extends GuiBase
{
    private final List<AreaInfo> warehouses = new ArrayList<>();
    private int scrollOffset = 0;

    private static final int PANEL_WIDTH = 400;
    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 12;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 30;

    public GuiWarehouseManager()
    {
        this.title = StringUtils.translate("syncmaterial.gui.title.warehouse_manager");
    }

    @Override
    public void initGui()
    {
        super.initGui();
        requestWarehouseList();
    }

    private void requestWarehouseList()
    {
        // 通过 StagingAreaConfigC2SPacket 请求全局仓库列表
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "LIST_WAREHOUSES", 0, Optional.empty()));
    }

    /**
     * 收到仓库列表响应
     */
    public void onWarehouseListResponse(List<AreaInfo> areas)
    {
        this.warehouses.clear();
        this.warehouses.addAll(areas);
        this.initGui();
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseClicked(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = HEADER_HEIGHT + 10;
        int listY = panelY + 10;
        int listX = panelX + 10;
        int listWidth = PANEL_WIDTH - 20;

        // 点击列表行
        if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= listY)
        {
            int row = (int) ((mouseY - listY) / ROW_HEIGHT) + scrollOffset;
            if (row >= 0 && row < warehouses.size())
            {
                // 检查是否点击了编辑或删除按钮
                AreaInfo wh = warehouses.get(row);
                int btnY = listY + (row - scrollOffset) * ROW_HEIGHT + 1;
                int deleteX = listX + listWidth - 50;
                int editX = deleteX - 50;

                if (mouseX >= deleteX && mouseX < deleteX + 48 && mouseY >= btnY && mouseY < btnY + 20)
                {
                    // 删除仓库
                    ClientPlayNetworking.send(new StagingAreaConfigC2SPacket("", "DELETE_WAREHOUSE", wh.areaId(), Optional.empty()));
                    requestWarehouseList();
                    return true;
                }
                if (mouseX >= editX && mouseX < editX + 48 && mouseY >= btnY && mouseY < btnY + 20)
                {
                    // 编辑仓库（打开子区域编辑器复用）
                    // TODO: 打开编辑界面
                    InfoUtils.showGuiOrActionBarMessage(Message.MessageType.INFO, "编辑功能开发中");
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    {
        int maxScroll = Math.max(0, warehouses.size() - VISIBLE_ROWS);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset - (int) Math.signum(verticalAmount), maxScroll));
        return true;
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = HEADER_HEIGHT + 10;
        int panelHeight = FOOTER_HEIGHT + VISIBLE_ROWS * ROW_HEIGHT + 20;
        int listY = panelY + 10;
        int listX = panelX + 10;
        int listWidth = PANEL_WIDTH - 20;

        // 面板背景
        drawContext.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xC0101010);
        RenderUtils.drawOutline(drawContext, panelX, panelY, PANEL_WIDTH, panelHeight, 1, 0xFFE0E0E0);

        // 标题
        drawContext.drawTextWithShadow(this.textRenderer,
                StringUtils.translate("syncmaterial.gui.title.warehouse_manager"),
                panelX + 10, panelY + 8, 0xFFFFFFFF);

        // 仓库列表
        int size = Math.min(warehouses.size(), VISIBLE_ROWS);
        for (int i = 0; i < size; i++)
        {
            int idx = i + scrollOffset;
            if (idx >= warehouses.size()) break;

            AreaInfo wh = warehouses.get(idx);
            int y = listY + i * ROW_HEIGHT;

            // 背景
            if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= y && mouseY < y + ROW_HEIGHT)
            {
                drawContext.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0xA0707070);
            }
            else if (i % 2 == 1)
            {
                drawContext.fill(listX, y, listX + listWidth, y + ROW_HEIGHT, 0xA0101010);
            }

            // 仓库名称 + 坐标
            String text = String.format("%s  [%d,%d,%d]~[%d,%d,%d]",
                    wh.name(), wh.x1(), wh.y1(), wh.z1(), wh.x2(), wh.y2(), wh.z2());
            drawContext.drawTextWithShadow(this.textRenderer, text, listX + 4, y + 6, 0xFFE0E0E0);

            // 删除按钮
            int deleteX = listX + listWidth - 50;
            int editX = deleteX - 50;
            boolean deleteHovered = mouseX >= deleteX && mouseX < deleteX + 48 && mouseY >= y && mouseY < y + 20;
            boolean editHovered = mouseX >= editX && mouseX < editX + 48 && mouseY >= y && mouseY < y + 20;

            drawContext.fill(editX, y + 1, editX + 48, y + 19, editHovered ? 0xFF5555AA : 0xFF444466);
            drawContext.drawCenteredTextWithShadow(this.textRenderer,
                    StringUtils.translate("syncmaterial.gui.button.edit"),
                    editX + 24, y + 6, 0xFFFFFFFF);

            drawContext.fill(deleteX, y + 1, deleteX + 48, y + 19, deleteHovered ? 0xFFAA5555 : 0xFF664444);
            drawContext.drawCenteredTextWithShadow(this.textRenderer,
                    StringUtils.translate("syncmaterial.gui.button.delete"),
                    deleteX + 24, y + 6, 0xFFFFFFFF);
        }

        // 底部按钮
        int btnY = listY + VISIBLE_ROWS * ROW_HEIGHT + 8;
        int addBtnX = panelX + PANEL_WIDTH / 2 - 60;

        boolean addHovered = mouseX >= addBtnX && mouseX < addBtnX + 120 && mouseY >= btnY && mouseY < btnY + 20;
        drawContext.fill(addBtnX, btnY, addBtnX + 120, btnY + 20, addHovered ? 0xFF55AA55 : 0xFF446644);
        drawContext.drawCenteredTextWithShadow(this.textRenderer,
                StringUtils.translate("syncmaterial.gui.button.add_warehouse"),
                addBtnX + 60, btnY + 6, 0xFFFFFFFF);

        // 关闭按钮
        int closeBtnX = panelX + PANEL_WIDTH / 2 + 10;
        boolean closeHovered = mouseX >= closeBtnX && mouseX < closeBtnX + 80 && mouseY >= btnY && mouseY < btnY + 20;
        drawContext.fill(closeBtnX, btnY, closeBtnX + 80, btnY + 20, closeHovered ? 0xFFAA5555 : 0xFF664444);
        drawContext.drawCenteredTextWithShadow(this.textRenderer,
                StringUtils.translate("syncmaterial.gui.button.close"),
                closeBtnX + 40, btnY + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    {
        if (super.onKeyTyped(keyCode, scanCode, modifiers))
        {
            return true;
        }

        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)
        {
            this.closeGui(true);
            return true;
        }
        return false;
    }

    /**
     * 仓库信息（从服务端接收）
     */
    public record AreaInfo(int areaId, String name, int x1, int y1, int z1, int x2, int y2, int z2, String world) {}
}
