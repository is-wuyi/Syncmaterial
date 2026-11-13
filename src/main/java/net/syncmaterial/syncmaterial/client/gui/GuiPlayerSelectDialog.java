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
//? if >=26.2 {
import fi.dy.masa.malilib.render.GuiContext;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

//? if >=26.2 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.syncmaterial.syncmaterial.client.TeamManagementClient;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamAction;
import net.syncmaterial.syncmaterial.core.TeamLimits;
import net.syncmaterial.syncmaterial.network.PlayerListResponseS2CPacket.PlayerInfo;

/**
 * 玩家选择弹窗（标准 MaLiLib 弹窗，替代旧版手绘 Overlay）。
 *
 * 四种模式共用一个弹窗：TRANSFER（转让，单选）、ADD_DEPUTY（加副负责人，多选）、
 * ASSIGN（批量分配，多选）、KICK（按材料踢人，多选）。打开时向服务端请求玩家
 * 目录，响应由 TeamManagementClient 按请求关联路由回来。
 *
 * 搜索框用 malilib GuiTextFieldGeneric（含光标/删除/中文输入法支持），
 * 列表滚动/斑马纹/悬停由 WidgetListBase 提供 —— 不再手写任何 UI 组件。
 * 父界面（材料列表或管理弹窗）通过 extractRenderState 透出。
 *
 * 界面仍只显示玩家名与在线状态；UUID 身份单独保存在选择行里，同名不同 UUID
 * 的条目不会在选择集合中坍缩。选中项按行 id 记录，而不是按名字。
 */
public class GuiPlayerSelectDialog extends GuiDialogBase
{
    private static final int PANEL_W = 300;
    private static final int LIST_ROWS = 10;
    private static final int ROW_H = 20;
    private static final int LIST_H = LIST_ROWS * ROW_H;

    private final GuiMaterialList materialList;
    private final String mode;
    private final SelectionModel selectionModel = new SelectionModel();
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

        if ("REMOVE_DEPUTY".equals(mode))
        {
            // 移除副负责人不发玩家目录请求：名单直接用当前副负责人，
            // 有权威 TeamState 时携带 UUID，旧服务端则回退为名字。
            this.selectionModel.setPrefilledIdentities(materialList.getDeputyIdentities());
            this.loading = false;
        }
        else
        {
            // 请求在构造函数中发出，响应可能早于界面显示；TeamManagementClient
            // 保存的是本弹窗对象，因此不要求它此刻已经是 currentScreen。
            TeamManagementClient.requestPlayerList(this);
        }
    }

    /**
     * 移除副负责人专用：名单直接用当前副负责人列表，不发 PlayerListRequest ——
     * 服务端的玩家列表只含在线玩家和上过服的人，副负责人未必在内，
     * 而要移除的人必然已经在副负责人列表里。
     */
    public static GuiPlayerSelectDialog forDeputyRemoval(GuiMaterialList materialList, GuiBase parent)
    {
        return new GuiPlayerSelectDialog(materialList, "REMOVE_DEPUTY", parent);
    }

    private String titleForMode()
    {
        return switch (mode)
        {
            case "ASSIGN" -> StringUtils.translate("syncmaterial.gui.title.select_assign_player");
            case "KICK" -> StringUtils.translate("syncmaterial.gui.title.select_kick_player");
            case "REMOVE_DEPUTY" -> StringUtils.translate("syncmaterial.gui.title.remove_deputy");
            default -> StringUtils.translate("syncmaterial.gui.label.select_player");
        };
    }

    /**
     * 搜索过滤本体：按玩家名大小写不敏感的包含匹配。
     * 抽成静态方法是为了单测直接断言（空查询原样返回、大小写、无匹配），
     * 列表渲染与测试共用同一实现。
     */
    static List<PlayerInfo> filterPlayers(List<PlayerInfo> players, String filter)
    {
        if (filter == null || filter.isEmpty())
        {
            return players;
        }
        String lower = filter.toLowerCase();
        return players.stream().filter(p -> p.name().toLowerCase().contains(lower)).toList();
    }

    private String hintForMode()
    {
        return switch (mode)
        {
            case "ASSIGN" -> StringUtils.translate("syncmaterial.gui.hint.assign_to_player");
            case "KICK" -> StringUtils.translate("syncmaterial.gui.hint.kick_from_player");
            case "REMOVE_DEPUTY" -> StringUtils.translate("syncmaterial.gui.hint.remove_deputy");
            default -> StringUtils.translate("syncmaterial.gui.hint.select_player");
        };
    }

    public GuiMaterialList getMaterialList()
    {
        return this.materialList;
    }

    public String getSchematicId()
    {
        return this.materialList.getSchematicId();
    }

    public String getMode()
    {
        return this.mode;
    }

    /** 网络响应入口：协议 4 玩家目录（UUID 与在线状态分开保存）。 */
    public void onPlayerDirectoryResponse(List<PlayerDirectoryEntry> players)
    {
        if ("REMOVE_DEPUTY".equals(this.mode))
        {
            return;
        }
        this.selectionModel.setDirectory(players);
        this.loading = false;
        this.initGui();
    }

    /** 网络响应入口：旧服务端玩家列表（只有名字与在线状态）。 */
    public void onPlayerListResponse(List<PlayerInfo> players)
    {
        if ("REMOVE_DEPUTY".equals(this.mode))
        {
            return; // 预设名单模式不接收服务端玩家列表
        }
        this.selectionModel.setLegacyPlayers(players);
        this.loading = false;
        this.initGui();
    }

    public void onPlayerListFailed(String message)
    {
        this.selectionModel.clearRows();
        this.loading = false;
        if (message != null && !message.isBlank())
        {
            this.addMessage(MessageType.ERROR, message);
        }
        this.initGui();
    }

    /** 目录被服务端上限截断等提示：只显示信息，不改变可选列表或按钮状态。 */
    public void onPlayerDirectoryHint(String message)
    {
        if (message != null && !message.isBlank())
        {
            this.addMessage(MessageType.INFO, message);
        }
    }

    /** 操作组全部成功：回到原始父界面（内部切换，不触发关闭失效）。 */
    public void onGroupSucceeded(TeamAction action)
    {
        GuiBase.openGui(this.getParent());
    }

    /** 操作组结束但存在失败：保留选中的玩家，重新启用确认按钮。 */
    public void onGroupFailed(String message)
    {
        this.submitted = false;
        if (this.confirmButton != null)
        {
            this.confirmButton.setEnabled(!this.selectionModel.isEmpty());
        }
        if (message != null && !message.isBlank())
        {
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
                leftX, this.dialogTop + 46, innerW, 16, this.mc.font);
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

        this.confirmButton.setEnabled(!this.selectionModel.isEmpty() && !this.submitted);
        this.addButton(this.confirmButton, (btn, mouseBtn) -> confirm());
        this.addButton(cancelButton, (btn, mouseBtn) -> dismiss());
    }

    private void toggleSelection(SelectionModel.Row row)
    {
        if ("TRANSFER".equals(this.mode))
        {
            // 转让负责人只允许单选
            this.selectionModel.selectOnly(row.id());
        }
        else
        {
            this.selectionModel.toggle(row.id());
        }
        if (this.confirmButton != null)
        {
            this.confirmButton.setEnabled(!this.selectionModel.isEmpty());
        }
    }

    /** 关闭弹窗：用户取消/Esc/关屏都使本弹窗发起的待处理请求失效。 */
    private void dismiss()
    {
        TeamManagementClient.onDialogDismissed(this);
        GuiBase.openGui(this.getParent());
    }

    @Override
    public void closeGui(boolean showParent)
    {
        TeamManagementClient.onDialogDismissed(this);
        super.closeGui(showParent);
    }

    @Override
    public void removed()
    {
        // 标准 setScreen 替换屏幕时不走 closeGui；弹窗一旦离开屏幕就不再收任何回包。
        TeamManagementClient.onDialogRemoved(this);
        super.removed();
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
        SelectionModel.Row row = this.selectionModel.firstByName(name);
        if (row != null)
        {
            toggleSelection(row);
        }
    }

    /** 等价于点击指定身份对应的行；用于同名不同 UUID 的选择测试。 */
    public void selectPlayer(PlayerIdentity identity)
    {
        SelectionModel.Row row = this.selectionModel.firstByIdentity(identity);
        if (row != null)
        {
            toggleSelection(row);
        }
    }

    /** 测试钩子：读取弹窗当前可选玩家名单 */
    public List<PlayerInfo> getAvailablePlayers()
    {
        return this.selectionModel.rows().stream()
                .map(row -> new PlayerInfo(row.identity().name(), row.online()))
                .toList();
    }

    /** 测试钩子：读取当前可选的完整身份（含 UUID，可能同名多行）。 */
    public List<PlayerIdentity> getAvailablePlayerIdentities()
    {
        return this.selectionModel.rows().stream()
                .map(SelectionModel.Row::identity)
                .toList();
    }

    public List<String> getSelectedPlayers()
    {
        return this.selectionModel.selectedNames();
    }

    /** 测试钩子：当前选中项的身份；同名不同 UUID 会保留为不同条目。 */
    public List<PlayerIdentity> getSelectedIdentities()
    {
        return this.selectionModel.selectedIdentities();
    }

    public void confirmSelection()
    {
        confirm();
    }

    /** 确认按钮 / Enter：整组提交给 TeamManagementClient（协议 4 或旧包 FIFO）。 */
    private void confirm()
    {
        if (this.selectionModel.isEmpty() || this.submitted)
        {
            return;
        }
        this.submitted = true; // 防重复提交，等全部响应决定去留
        this.confirmButton.setEnabled(false);

        TeamAction action = actionForMode();
        List<PlayerIdentity> targets = this.selectionModel.selectedIdentities();
        List<Integer> materialIds = action.usesMaterials()
                ? new ArrayList<>(this.materialList.getSelectedMaterialIds()) : List.of();
        String error = TeamManagementClient.submitGroup(this.materialList, this, action, targets, materialIds);
        if (error != null)
        {
            this.submitted = false;
            this.confirmButton.setEnabled(!this.selectionModel.isEmpty());
            this.addMessage(MessageType.ERROR, error);
        }
    }

    private TeamAction actionForMode()
    {
        return switch (this.mode)
        {
            case "ASSIGN" -> TeamAction.ASSIGN;
            case "KICK" -> TeamAction.KICK;
            case "ADD_DEPUTY" -> TeamAction.ADD_DEPUTY;
            case "REMOVE_DEPUTY" -> TeamAction.REMOVE_DEPUTY;
            default -> TeamAction.TRANSFER;
        };
    }

    @Override
    //? if >=26.2 {
    public void drawContents(GuiContext drawContext, int mouseX, int mouseY, float partialTicks)
    //?} else {
    /*public void drawContents(GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks)
    *///?}
    {
        // 背后可见：重绘父界面后再叠半透明面板
        if (this.getParent() != null)
        {
            //? if >=26.2 {
            this.getParent().extractRenderState(drawContext.getGuiGraphics(), mouseX, mouseY, partialTicks);
            //?} else {
            /*this.getParent().render(drawContext, mouseX, mouseY, partialTicks);
            *///?}
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
                List<String> selected = this.selectionModel.selectedNames();
                info = selected.isEmpty()
                        ? StringUtils.translate("syncmaterial.gui.label.please_select_player")
                        : StringUtils.translate("syncmaterial.gui.button.transfer_to", selected.get(0));
            }
            else if ("ADD_DEPUTY".equals(this.mode) || "REMOVE_DEPUTY".equals(this.mode))
            {
                info = StringUtils.translate("syncmaterial.gui.label.players_selected_count",
                        this.selectionModel.selectedCount());
            }
            else
            {
                info = StringUtils.translate("syncmaterial.gui.label.players_and_materials_count",
                        this.selectionModel.selectedCount(), this.materialList.getSelectedMaterialIds().size());
            }
            this.drawString(drawContext, info, this.dialogLeft + 10, this.dialogTop + 68 + LIST_H + 6, 0xFFAAAAAA);
        }

        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    //? if >=26.2 {
    protected void drawHoveredWidget(GuiContext drawContext, int mouseX, int mouseY)
    //?} else {
    /*protected void drawHoveredWidget(GuiGraphics drawContext, int mouseX, int mouseY)
    *///?}
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);
        if (this.listWidget != null)
        {
            this.listWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseClicked(MouseButtonEvent event, boolean isDoubleClick)
    //?} else {
    /*public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        if (super.onMouseClicked(event, isDoubleClick))
        //?} else {
        /*if (super.onMouseClicked(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }
        //? if >=26.2 {
        if (this.listWidget != null && this.listWidget.onMouseClicked(event, isDoubleClick))
        //?} else {
        /*if (this.listWidget != null && this.listWidget.onMouseClicked(mouseX, mouseY, mouseButton))
        *///?}
        {
            return true;
        }
        // 点击弹窗外不关闭（防止误操作丢失已选玩家，与旧 Overlay 行为一致）
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseReleased(MouseButtonEvent event)
    //?} else {
    /*public boolean onMouseReleased(int mouseX, int mouseY, int mouseButton)
    *///?}
    {
        //? if >=26.2 {
        super.onMouseReleased(event);
        if (this.listWidget != null) this.listWidget.onMouseReleased(event);
        //?} else {
        /*super.onMouseReleased(mouseX, mouseY, mouseButton);
        if (this.listWidget != null) this.listWidget.onMouseReleased(mouseX, mouseY, mouseButton);
        *///?}
        return false;
    }

    @Override
    //? if >=26.2 {
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    //?} else {
    /*public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    *///?}
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
    //? if >=26.2 {
    public boolean onKeyTyped(KeyEvent event)
    //?} else {
    /*public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    *///?}
    {
        //? if >=26.2 {
        int key = event.key();
        //?} else {
        /*int key = keyCode;
        *///?}
        if (key == KeyCodes.KEY_ESCAPE)
        {
            dismiss();
            return true;
        }
        if (key == KeyCodes.KEY_ENTER && this.confirmButton != null && !this.submitted)
        {
            confirm();
            return true;
        }
        //? if >=26.2 {
        return super.onKeyTyped(event);
        //?} else {
        /*return super.onKeyTyped(key, scanCode, modifiers);
        *///?}
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    // ========== 选择模型（无 Minecraft 依赖，单测可直接验证） ==========

    static final class SelectionModel
    {
        static final class Row
        {
            private final long id;
            private final PlayerIdentity identity;
            private final boolean online;

            Row(long id, PlayerIdentity identity, boolean online)
            {
                this.id = id;
                this.identity = identity;
                this.online = online;
            }

            long id() { return this.id; }
            PlayerIdentity identity() { return this.identity; }
            boolean online() { return this.online; }
        }

        private final List<Row> rows = new ArrayList<>();
        private final Set<Long> selected = new LinkedHashSet<>();
        private long nextRowId = 1;

        void setDirectory(List<PlayerDirectoryEntry> entries)
        {
            clearRows();
            if (entries == null)
            {
                return;
            }
            for (PlayerDirectoryEntry entry : entries)
            {
                if (entry != null && entry.player() != null)
                {
                    if (this.rows.size() >= TeamLimits.DIRECTORY)
                    {
                        break;
                    }
                    this.rows.add(new Row(this.nextRowId++, entry.player(), entry.online()));
                }
            }
        }

        void setLegacyPlayers(List<PlayerInfo> players)
        {
            clearRows();
            if (players == null)
            {
                return;
            }
            for (PlayerInfo player : players)
            {
                if (player != null && player.name() != null && !player.name().isBlank())
                {
                    if (this.rows.size() >= TeamLimits.DIRECTORY)
                    {
                        break;
                    }
                    this.rows.add(new Row(this.nextRowId++,
                            new PlayerIdentity(null, player.name()), player.online()));
                }
            }
        }

        void setPrefilledIdentities(List<PlayerIdentity> identities)
        {
            clearRows();
            if (identities == null)
            {
                return;
            }
            for (PlayerIdentity identity : identities)
            {
                if (identity != null)
                {
                    this.rows.add(new Row(this.nextRowId++, identity, false));
                }
            }
        }

        void clearRows()
        {
            this.rows.clear();
            this.selected.clear();
        }

        List<Row> rows()
        {
            return List.copyOf(this.rows);
        }

        List<Row> filtered(String filter)
        {
            if (filter == null || filter.isEmpty())
            {
                return this.rows;
            }
            String lower = filter.toLowerCase();
            return this.rows.stream()
                    .filter(row -> row.identity().name().toLowerCase().contains(lower))
                    .toList();
        }

        void toggle(long rowId)
        {
            if (!this.selected.remove(rowId))
            {
                for (Row row : this.rows)
                {
                    if (row.id() == rowId)
                    {
                        this.selected.add(rowId);
                        return;
                    }
                }
            }
        }

        void selectOnly(long rowId)
        {
            this.selected.clear();
            this.selected.add(rowId);
        }

        boolean isSelected(Row row)
        {
            return this.selected.contains(row.id());
        }

        boolean isEmpty()
        {
            return this.selected.isEmpty();
        }

        int selectedCount()
        {
            return this.selected.size();
        }

        List<PlayerIdentity> selectedIdentities()
        {
            List<PlayerIdentity> identities = new ArrayList<>(this.selected.size());
            for (Row row : this.rows)
            {
                if (this.selected.contains(row.id()))
                {
                    identities.add(row.identity());
                }
            }
            return identities;
        }

        List<String> selectedNames()
        {
            List<String> names = new ArrayList<>(this.selected.size());
            for (PlayerIdentity identity : selectedIdentities())
            {
                names.add(identity.name());
            }
            return names;
        }

        Row firstByName(String name)
        {
            if (name == null)
            {
                return null;
            }
            Row match = null;
            for (Row row : this.rows)
            {
                if (row.identity().name().equalsIgnoreCase(name))
                {
                    if (match != null)
                    {
                        // 同名多身份：字符串选择有歧义，测试/旧调用不得任选其一
                        return null;
                    }
                    match = row;
                }
            }
            return match;
        }

        Row firstByIdentity(PlayerIdentity identity)
        {
            if (identity == null)
            {
                return null;
            }
            for (Row row : this.rows)
            {
                if (row.identity().equals(identity))
                {
                    return row;
                }
            }
            return null;
        }
    }

    // ========== 玩家列表 Widget ==========

    private class PlayerListWidget extends WidgetListBase<SelectionModel.Row, PlayerEntryWidget>
    {
        public PlayerListWidget(int x, int y, int width, int height)
        {
            super(x, y, width, height, null);
            this.browserEntryHeight = ROW_H;
            this.shouldSortList = false;
        }

        //? if >=26.2 {
        public void renderHoverEffects(GuiContext drawContext, int mouseX, int mouseY)
        //?} else {
        /*public void renderHoverEffects(GuiGraphics drawContext, int mouseX, int mouseY)
        *///?}
        {
            // super. 显式指向 WidgetListBase 的实现：外部类 GuiPlayerSelectDialog
            // 也覆写了同名方法，不加限定会被 SpotBugs 判为歧义调用
            super.drawHoveredWidget(drawContext, mouseX, mouseY);
            this.drawButtonHoverTexts(drawContext, mouseX, mouseY, 0f);
        }

        @Override
        protected Collection<SelectionModel.Row> getAllEntries()
        {
            String filter = searchField != null ? searchField.getTextWrapper() : "";
            return selectionModel.filtered(filter);
        }

        @Override
        protected Comparator<SelectionModel.Row> getComparator()
        {
            return Comparator.comparing(row -> row.identity().name());
        }

        @Override
        protected List<String> getEntryStringsForFilter(SelectionModel.Row entry)
        {
            return List.of(entry.identity().name().toLowerCase());
        }

        @Override
        protected PlayerEntryWidget createListEntryWidget(int x, int y, int listIndex, boolean isOdd, SelectionModel.Row entry)
        {
            return new PlayerEntryWidget(x, y, this.browserEntryWidth, this.browserEntryHeight, isOdd, entry, listIndex);
        }
    }

    // ========== 条目 Widget ==========

    private class PlayerEntryWidget extends WidgetListEntryBase<SelectionModel.Row>
    {
        private final SelectionModel.Row entry;
        private final boolean isOdd;

        public PlayerEntryWidget(int x, int y, int width, int height, boolean isOdd, SelectionModel.Row entry, int listIndex)
        {
            super(x, y, width, height, entry, listIndex);
            this.entry = entry;
            this.isOdd = isOdd;
        }

        @Override
        //? if >=26.2 {
        public boolean onMouseClicked(MouseButtonEvent event, boolean isDoubleClick)
        //?} else {
        /*public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
        *///?}
        {
            //? if >=26.2 {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            //?} else {
            /*boolean isDoubleClick = false;
            *///?}
            //? if >=26.2 {
            if (super.onMouseClicked(event, isDoubleClick))
            //?} else {
            /*if (super.onMouseClicked(mouseX, mouseY, mouseButton))
            *///?}
            {
                return true;
            }
            if (this.isMouseOver(mouseX, mouseY))
            {
                toggleSelection(this.entry);
                return true;
            }
            return false;
        }

        @Override
        //? if >=26.2 {
        public void render(GuiContext drawContext, int mouseX, int mouseY, boolean selected)
        //?} else {
        /*public void render(GuiGraphics drawContext, int mouseX, int mouseY, boolean selected)
        *///?}
        {
            boolean isSelected = selectionModel.isSelected(this.entry);

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
            this.drawString(drawContext, this.x + 4, this.y + 6, nameColor, prefix + this.entry.identity().name());

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
