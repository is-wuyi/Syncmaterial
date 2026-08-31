package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.syncmaterial.syncmaterial.network.BatchAssignC2SPacket;
import net.syncmaterial.syncmaterial.network.KickFromMaterialC2SPacket;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListRequestC2SPacket;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;

/**
 * 玩家选择弹窗（标准 MaLiLib 弹窗，替代旧版手绘 Overlay）。
 *
 * 四种模式共用一个弹窗：TRANSFER（转让，单选）、ADD_DEPUTY（加副负责人，多选）、
 * ASSIGN（批量分配，多选）、KICK（按材料踢人，多选）。打开时向服务端请求玩家
 * 列表，响应由 ModNetworkHandlerClient 路由到 onPlayerListResponse。
 *
 * 搜索框用 malilib GuiTextFieldGeneric（含光标/删除/中文输入法支持），
 * 列表滚动/斑马纹/悬停由 WidgetListBase 提供 —— 不再手写任何 UI 组件。
 * 父界面（材料列表或管理弹窗）通过 extractRenderState 透出。
 */
public class GuiPlayerSelectDialog extends GuiDialogBase
{
    private static final int PANEL_W = 300;
    private static final int LIST_ROWS = 10;
    private static final int ROW_H = 20;
    private static final int LIST_H = LIST_ROWS * ROW_H;

    private final GuiMaterialList materialList;
    private final String mode;
    private final List<PlayerInfo> players = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean loading = true;

    private fi.dy.masa.malilib.gui.GuiTextFieldGeneric searchField;
    private ButtonGeneric confirmButton;
    /** 已提交等待响应的标志（ButtonBase 没有 enabled getter，自行维护防重复提交） */
    private boolean submitted = false;
    private PlayerListWidget listWidget;

    public GuiPlayerSelectDialog(GuiMaterialList materialList, String mode, GuiBase parent)
    {
        this.materialList = materialList;
        this.mode = mode;
        this.useTitleHierarchy = false;
        this.title = titleForMode();
        this.setParent(parent);

        // 头部(22) + 标题(12) + 提示(12) + 搜索框(20) + 列表(200) + 信息行(14) + 按钮(20) + 边距
        this.setWidthAndHeight(PANEL_W, 22 + 12 + 12 + 20 + LIST_H + 14 + 20 + 24);
        this.centerOnScreen();

        ClientPlayNetworking.send(new PlayerListRequestC2SPacket(materialList.getSchematicId()));
    }

    private String titleForMode()
    {
        return switch (mode)
        {
            case "ASSIGN" -> StringUtils.translate("syncmaterial.gui.title.select_assign_player");
            case "KICK" -> StringUtils.translate("syncmaterial.gui.title.select_kick_player");
            default -> StringUtils.translate("syncmaterial.gui.label.select_player");
        };
    }

    private String hintForMode()
    {
        return switch (mode)
        {
            case "ASSIGN" -> StringUtils.translate("syncmaterial.gui.hint.assign_to_player");
            case "KICK" -> StringUtils.translate("syncmaterial.gui.hint.kick_from_player");
            default -> StringUtils.translate("syncmaterial.gui.hint.select_player");
        };
    }

    /** 网络响应入口（ModNetworkHandlerClient 按 currentScreen 路由） */
    public void onPlayerListResponse(List<PlayerInfo> players)
    {
        this.players.clear();
        if (players != null) this.players.addAll(players);
        this.loading = false;
        this.selected.clear();
        this.initGui();
    }

    /** OwnerAction 响应（TRANSFER / ADD_DEPUTY 模式的确认结果） */
    public void onOwnerActionResponse(boolean success, String message,
                                      String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim)
    {
        this.materialList.updateOwnerState(newOwnerName, newDeputyOwners, newAllowSelfClaim);
        if (success)
        {
            // 回到管理界面（转让/加副负责人只从管理弹窗发起，parent 必是它）
            if (this.getParent() instanceof GuiOwnerManagementDialog mgmt)
            {
                mgmt.refreshFromMaterialList();
            }
            GuiBase.openGui(this.getParent());
        }
        else
        {
            this.submitted = false;
            this.confirmButton.setEnabled(true);
            this.addMessage(MessageType.ERROR, message);
        }
    }

    /** 批量分配响应（ASSIGN 模式） */
    public void onBatchAssignResponse(boolean success, String message)
    {
        this.materialList.applyBatchAssignResult(success);
        if (success)
        {
            GuiBase.openGui(this.getParent());
        }
        else
        {
            this.submitted = false;
            this.confirmButton.setEnabled(true);
            this.addMessage(MessageType.ERROR, message);
        }
    }

    /** 踢出响应（KICK 模式） */
    public void onKickResponse(boolean success, String message)
    {
        this.materialList.applyKickResult(success);
        if (success)
        {
            GuiBase.openGui(this.getParent());
        }
        else
        {
            this.submitted = false;
            this.confirmButton.setEnabled(true);
            this.addMessage(MessageType.ERROR, message);
        }
    }

    @Override
    public void initGui()
    {
        super.initGui();
        if (this.loading)
        {
            return;
        }

        int leftX = this.dialogLeft + 10;
        int innerW = PANEL_W - 20;

        // 搜索框（malilib 文本框：自带光标闪烁、退格、输入法支持）
        this.searchField = new fi.dy.masa.malilib.gui.GuiTextFieldGeneric(
                leftX, this.dialogTop + 46, innerW, 16, this.font);
        this.searchField.setTextWrapper("");
        this.addTextField(this.searchField, textField -> {
            if (GuiPlayerSelectDialog.this.listWidget != null)
            {
                GuiPlayerSelectDialog.this.listWidget.refreshEntries();
            }
            return false;
        });

        // 玩家列表
        this.listWidget = new PlayerListWidget(leftX, this.dialogTop + 68, innerW, LIST_H);
        this.listWidget.initGui();

        // 底部按钮
        int btnY = this.dialogTop + 68 + LIST_H + 18;
        this.confirmButton = new ButtonGeneric(0, btnY, -1, true,
                StringUtils.translate("syncmaterial.gui.button.confirm"));
        ButtonGeneric cancelButton = new ButtonGeneric(0, btnY, -1, true,
                StringUtils.translate("syncmaterial.gui.button.cancel"));

        int totalW = this.confirmButton.getWidth() + 4 + cancelButton.getWidth();
        this.confirmButton.setPosition(this.dialogLeft + (PANEL_W - totalW) / 2, btnY);
        cancelButton.setPosition(this.confirmButton.getX() + this.confirmButton.getWidth() + 4, btnY);

        this.confirmButton.setEnabled(!this.selected.isEmpty());
        this.addButton(this.confirmButton, (btn, mouseBtn) -> confirm());
        this.addButton(cancelButton, (btn, mouseBtn) -> GuiBase.openGui(this.getParent()));
    }

    private void toggleSelection(String name)
    {
        if ("TRANSFER".equals(this.mode))
        {
            // 转让负责人只允许单选
            this.selected.clear();
            this.selected.add(name);
        }
        else if (!this.selected.remove(name))
        {
            this.selected.add(name);
        }
        if (this.confirmButton != null)
        {
            this.confirmButton.setEnabled(!this.selected.isEmpty());
        }
    }

    // ========== 测试钩子（与条目点击 / 确认按钮走同一方法）==========

    /** 玩家列表是否已加载（测试用它等待真实 PlayerListResponse 到达） */
    public boolean hasLoadedPlayers()
    {
        return !this.loading;
    }

    /** 等价于点击一个玩家条目 */
    public void selectPlayer(String name)
    {
        toggleSelection(name);
    }

    public List<String> getSelectedPlayers()
    {
        return new ArrayList<>(this.selected);
    }

    public void confirmSelection()
    {
        confirm();
    }

    /** 确认按钮 / Enter：按模式发包（与旧 handleOverlayConfirm 相同的协议语义） */
    private void confirm()
    {
        if (this.selected.isEmpty() || this.submitted)
        {
            return;
        }
        this.submitted = true; // 防重复提交，等响应决定去留
        this.confirmButton.setEnabled(false);

        String schematicId = this.materialList.getSchematicId();
        List<String> players = new ArrayList<>(this.selected);
        List<Integer> materialIds = new ArrayList<>(this.materialList.getSelectedMaterialIds());

        switch (this.mode)
        {
            case "ASSIGN" -> ClientPlayNetworking.send(new BatchAssignC2SPacket(schematicId, materialIds, players));
            case "KICK" -> {
                for (String player : players)
                {
                    ClientPlayNetworking.send(new KickFromMaterialC2SPacket(schematicId, materialIds, player));
                }
            }
            case "ADD_DEPUTY" -> {
                for (String player : players)
                {
                    ClientPlayNetworking.send(new OwnerActionC2SPacket(schematicId, "ADD_DEPUTY", player));
                }
            }
            case "TRANSFER" -> ClientPlayNetworking.send(
                    new OwnerActionC2SPacket(schematicId, "TRANSFER", players.get(0)));
        }
    }

    @Override
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        // 背后可见：重绘父界面后再叠半透明面板
        if (this.getParent() != null)
        {
            this.getParent().extractRenderState(drawContext.getGuiGraphics(), mouseX, mouseY, partialTicks);
        }

        RenderUtils.drawOutlinedBox(drawContext, this.dialogLeft, this.dialogTop,
                this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);

        this.drawStringWithShadow(drawContext, this.getTitleString(),
                this.dialogLeft + 10, this.dialogTop + 4, COLOR_WHITE);
        this.drawString(drawContext, this.hintForMode(),
                this.dialogLeft + 10, this.dialogTop + 18, 0xFFAAAAAA);

        if (this.loading)
        {
            this.drawString(drawContext, StringUtils.translate("syncmaterial.gui.label.loading"),
                    this.dialogLeft + 10, this.dialogTop + 50, 0xFFAAAAAA);
        }
        else if (this.listWidget != null)
        {
            this.listWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);

            // 底部选择信息行
            String info;
            if ("TRANSFER".equals(this.mode))
            {
                info = this.selected.isEmpty()
                        ? StringUtils.translate("syncmaterial.gui.label.please_select_player")
                        : StringUtils.translate("syncmaterial.gui.button.transfer_to", this.selected.iterator().next());
            }
            else if ("ADD_DEPUTY".equals(this.mode))
            {
                info = StringUtils.translate("syncmaterial.gui.label.players_selected_count", this.selected.size());
            }
            else
            {
                info = StringUtils.translate("syncmaterial.gui.label.players_and_materials_count",
                        this.selected.size(), this.materialList.getSelectedMaterialIds().size());
            }
            this.drawString(drawContext, info, this.dialogLeft + 10, this.dialogTop + 68 + LIST_H + 6, 0xFFAAAAAA);
        }

        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY)
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);
        if (this.listWidget != null)
        {
            this.listWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent event, boolean isDoubleClick)
    {
        if (super.onMouseClicked(event, isDoubleClick))
        {
            return true;
        }
        if (this.listWidget != null && this.listWidget.onMouseClicked(event, isDoubleClick))
        {
            return true;
        }
        // 点击弹窗外不关闭（防止误操作丢失已选玩家，与旧 Overlay 行为一致）
        return false;
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent event)
    {
        super.onMouseReleased(event);
        if (this.listWidget != null) this.listWidget.onMouseReleased(event);
        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        if (this.listWidget != null && this.listWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyTyped(KeyEvent event)
    {
        if (event.key() == KeyCodes.KEY_ESCAPE)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        if (event.key() == KeyCodes.KEY_ENTER && this.confirmButton != null && !this.submitted)
        {
            confirm();
            return true;
        }
        return super.onKeyTyped(event);
    }

    public boolean shouldPause()
    {
        return false;
    }

    // ========== 玩家列表 Widget ==========

    private class PlayerListWidget extends WidgetListBase<PlayerInfo, PlayerEntryWidget>
    {
        public PlayerListWidget(int x, int y, int width, int height)
        {
            super(x, y, width, height, null);
            this.browserEntryHeight = ROW_H;
            this.shouldSortList = false;
        }

        public void renderHoverEffects(GuiContext drawContext, int mouseX, int mouseY)
        {
            // super. 显式指向 WidgetListBase 的实现：外部类 GuiPlayerSelectDialog
            // 也覆写了同名方法，不加限定会被 SpotBugs 判为歧义调用
            super.drawHoveredWidget(drawContext, mouseX, mouseY);
            this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
        }

        @Override
        protected Collection<PlayerInfo> getAllEntries()
        {
            String filter = searchField != null ? searchField.getTextWrapper() : "";
            if (filter == null || filter.isEmpty())
            {
                return players;
            }
            String lower = filter.toLowerCase();
            return players.stream().filter(p -> p.name().toLowerCase().contains(lower)).toList();
        }

        @Override
        protected Comparator<PlayerInfo> getComparator()
        {
            return Comparator.comparing(PlayerInfo::name);
        }

        @Override
        protected List<String> getEntryStringsForFilter(PlayerInfo entry)
        {
            return List.of(entry.name().toLowerCase());
        }

        @Override
        protected PlayerEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd, PlayerInfo entry)
        {
            return new PlayerEntryWidget(x, y, this.browserEntryWidth, this.browserEntryHeight, isOdd, entry, listIndex);
        }
    }

    // ========== 条目 Widget ==========

    private class PlayerEntryWidget extends WidgetListEntryBase<PlayerInfo>
    {
        private final PlayerInfo entry;
        private final boolean isOdd;

        public PlayerEntryWidget(int x, int y, int width, int height, boolean isOdd, PlayerInfo entry, int listIndex)
        {
            super(x, y, width, height, entry, listIndex);
            this.entry = entry;
            this.isOdd = isOdd;
        }

        @Override
        public boolean onMouseClicked(MouseButtonEvent event, boolean isDoubleClick)
        {
            if (super.onMouseClicked(event, isDoubleClick))
            {
                return true;
            }
            if (this.isMouseOver((int) event.x(), (int) event.y()))
            {
                toggleSelection(this.entry.name());
                return true;
            }
            return false;
        }

        @Override
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
        {
            boolean isSelected = GuiPlayerSelectDialog.this.selected.contains(this.entry.name());

            if (isSelected)
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA02A5A2A);
            }
            else if (this.isMouseOver(mouseX, mouseY))
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0707070);
            }
            else if (this.isOdd)
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0101010);
            }
            else
            {
                RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, 0xA0303030);
            }

            // 选中标记
            String prefix = isSelected ? "✓ " : "  ";
            int nameColor = this.entry.online() ? 0xFF55FF55 : 0xFFAAAAAA;
            this.drawString(drawContext, this.x + 4, this.y + 6, nameColor, prefix + this.entry.name());

            // 在线状态
            if (this.entry.online())
            {
                this.drawString(drawContext, this.x + this.width - 24, this.y + 6, 0xFF55FF55,
                        StringUtils.translate("syncmaterial.gui.label.online"));
            }

            super.render(drawContext, mouseX, mouseY, selected);
        }
    }
}
