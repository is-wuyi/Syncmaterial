package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.syncmaterial.syncmaterial.network.OwnerActionC2SPacket;

/**
 * 负责人管理弹窗（标准 MaLiLib 弹窗，替代旧版手绘 Overlay）。
 *
 * 结构与 GuiWarehouseRefPopup 一致：GuiDialogBase + ButtonGeneric，
 * 弹窗背景通过重绘父界面（malilib GuiTextInputBase 同款机制），玩家可以透过弹窗看到材料列表。
 *
 * 与旧 Overlay 的核心区别：按钮的坐标在 initGui 里算一次（malilib 负责渲染与
 * 命中），纯文本行的位置同样在 initGui 算好存入 staticLines —— 渲染只读这一
 * 份数据，不再有"渲染与点击两处各算一遍坐标"的同步负担。
 *
 * 数据真身在 GuiMaterialList（ownerName / deputyOwners / allowSelfClaim），
 * 本弹窗每次刷新从它读取；网络响应由 ModNetworkHandlerClient 按 currentScreen
 * 路由到本类的 onOwnerActionResponse。
 */
public class GuiOwnerManagementDialog extends GuiDialogBase
{
    private static final int PANEL_W = 300;
    private static final int LEFT_PAD = 15;
    private static final int INNER_W = PANEL_W - LEFT_PAD * 2;
    private static final int ROW_H = 22;

    private final GuiMaterialList materialList;
    private final List<String> descLines;
    /** initGui 算好的静态文本行：布局的单一来源，drawContents 只读 */
    private final List<StaticLine> staticLines = new ArrayList<>();

    private record StaticLine(String text, int x, int y, int color) {}

    public GuiOwnerManagementDialog(GuiMaterialList materialList)
    {
        this.materialList = materialList;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.management");
        this.setParent(materialList);
        this.descLines = wrapText(StringUtils.translate("syncmaterial.gui.label.management_desc"), INNER_W - 12);
        this.setWidthAndHeight(PANEL_W, calcPanelHeight());
        this.centerOnScreen();
    }

    private int calcPanelHeight()
    {
        int rows = Math.max(1, materialList.getDeputyOwners().size());
        int h = 22;                       // 标题区
        h += 12;                          // 原理图名副标题
        h += descLines.size() * 11 + 8;   // 说明文字
        h += 14;                          // 区块头「负责人」
        h += (rows + 1) * ROW_H;          // 主负责人行 + 副负责人行们
        if (materialList.isMainOwner()) h += ROW_H; // 添加副负责人按钮
        h += 8 + ROW_H;                   // 自行认领行
        h += 8 + 20;                      // 关闭按钮
        h += 10;                          // 底部留白
        return h;
    }

    /** 简单逐字符折行（跳过 § 格式码），构造期算一次，不依赖 Screen 状态 */
    private static List<String> wrapText(String text, int maxWidth)
    {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            cur.append(c);
            if (StringUtils.getStringWidth(cur.toString()) > maxWidth && cur.length() > 1)
            {
                lines.add(cur.substring(0, cur.length() - 1));
                cur = new StringBuilder().append(c);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.staticLines.clear();

        int leftX = this.dialogLeft + LEFT_PAD;
        int y = this.dialogTop + 22;

        // 副标题：原理图名
        this.staticLines.add(new StaticLine(
                StringUtils.translate("syncmaterial.gui.label.schematic", this.materialList.getMaterialList().getTitle()),
                leftX, y, 0xFFAAAAAA));
        y += 16;

        // 说明文字（带左侧竖线装饰，避免像输入框）
        for (String line : this.descLines)
        {
            this.staticLines.add(new StaticLine(line, leftX + 8, y, 0xFF888888));
            y += 11;
        }
        y += 8;

        // 区块头
        this.staticLines.add(new StaticLine(
                StringUtils.translate("syncmaterial.gui.label.owner"), leftX + 6, y + 4, 0xFFE0E0E0));
        y += 14;

        // 主负责人行
        this.staticLines.add(new StaticLine(
                StringUtils.translate("syncmaterial.gui.label.main_owner", this.materialList.getOwnerName()),
                leftX + 6, y + 6, 0xFF55FF55));
        if (this.materialList.isMainOwner())
        {
            // rightAlign=true：x 参数是右边缘，按钮向左延伸
            ButtonGeneric transferBtn = new ButtonGeneric(leftX + INNER_W, y, -1, true,
                    StringUtils.translate("syncmaterial.gui.button.transfer"));
            this.addButton(transferBtn, (btn, mouseBtn) -> openTransfer());
        }
        y += ROW_H;

        // 副负责人行
        for (String deputy : this.materialList.getDeputyOwners())
        {
            this.staticLines.add(new StaticLine(
                    StringUtils.translate("syncmaterial.gui.label.deputy_owner", deputy),
                    leftX + 6, y + 6, 0xFF55FF55));
            if (this.materialList.isMainOwner())
            {
                ButtonGeneric delBtn = new ButtonGeneric(leftX + INNER_W, y, -1, true, GuiBase.TXT_RED + "×");
                String target = deputy;
                this.addButton(delBtn, (btn, mouseBtn) -> removeDeputy(target));
            }
            y += ROW_H;
        }
        if (this.materialList.getDeputyOwners().isEmpty())
        {
            this.staticLines.add(new StaticLine(
                    StringUtils.translate("syncmaterial.gui.label.deputy_owner_none"),
                    leftX + 6, y + 6, 0xFFA0A0A0));
            y += ROW_H;
        }

        // 添加副负责人按钮（主负责人专属）
        if (this.materialList.isMainOwner())
        {
            ButtonGeneric addBtn = new ButtonGeneric(0, y, -1, true,
                    StringUtils.translate("syncmaterial.gui.button.add_deputy"));
            addBtn.setPosition(leftX + (INNER_W - addBtn.getWidth()) / 2, y);
            this.addButton(addBtn, (btn, mouseBtn) -> openAddDeputy());
            y += ROW_H;
        }

        y += 8;

        // 自行认领行：文本 + 右侧开关按钮
        boolean allow = this.materialList.isAllowSelfClaim();
        this.staticLines.add(new StaticLine(
                StringUtils.translate("syncmaterial.gui.label.self_claim",
                        StringUtils.translate(allow ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off")),
                leftX + 6, y + 6, 0xFFE0E0E0));
        ButtonGeneric toggleBtn = new ButtonGeneric(leftX + INNER_W, y, -1, true,
                StringUtils.translate(allow ? "syncmaterial.gui.label.toggle_on" : "syncmaterial.gui.label.toggle_off"));
        this.addButton(toggleBtn, (btn, mouseBtn) -> toggleSelfClaim());
        y += ROW_H + 8;

        // 关闭按钮
        ButtonGeneric closeBtn = new ButtonGeneric(0, y, -1, true,
                StringUtils.translate("syncmaterial.gui.button.close"));
        closeBtn.setPosition(this.dialogLeft + (PANEL_W - closeBtn.getWidth()) / 2, y);
        this.addButton(closeBtn, (btn, mouseBtn) -> GuiBase.openGui(this.getParent()));
    }

    // ========== 按钮动作（测试钩子与按钮 lambda 走同一方法）==========

    public void openTransfer()
    {
        openPlayerSelect("TRANSFER");
    }

    public void openAddDeputy()
    {
        openPlayerSelect("ADD_DEPUTY");
    }

    public void toggleSelfClaim()
    {
        ClientPlayNetworking.send(new OwnerActionC2SPacket(
                this.materialList.getSchematicId(), "TOGGLE_SELF_CLAIM", ""));
    }

    public void removeDeputy(String target)
    {
        ClientPlayNetworking.send(new OwnerActionC2SPacket(
                this.materialList.getSchematicId(), "REMOVE_DEPUTY", target));
    }

    private void openPlayerSelect(String mode)
    {
        GuiBase.openGui(new GuiPlayerSelectDialog(this.materialList, mode, this));
    }

    /**
     * 数据已变（OwnerActionResponse），重算布局刷新。
     * 若当前正开着玩家选择弹窗（转让/添加副负责人的确认结果），由那边自行处理返回。
     */
    public void refreshFromMaterialList()
    {
        this.setWidthAndHeight(PANEL_W, calcPanelHeight());
        this.centerOnScreen();
        this.initGui();
    }

    /** 网络响应入口（ModNetworkHandlerClient 按 currentScreen 路由） */
    public void onOwnerActionResponse(boolean success, String message,
                                      String newOwnerName, List<String> newDeputyOwners, boolean newAllowSelfClaim)
    {
        this.materialList.updateOwnerState(newOwnerName, newDeputyOwners, newAllowSelfClaim);
        this.refreshFromMaterialList();
        this.addMessage(success ? MessageType.SUCCESS : MessageType.ERROR, message);
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        // 背后可见：重绘父界面（材料列表）后再叠半透明面板
        if (this.getParent() != null)
        {
            this.getParent().render(drawContext, mouseX, mouseY, partialTicks);
        }

        RenderUtils.drawOutlinedBox(drawContext, this.dialogLeft, this.dialogTop,
                this.dialogWidth, this.dialogHeight, 0xE0000000, COLOR_HORIZONTAL_BAR);

        this.drawStringWithShadow(drawContext, this.getTitleString(),
                this.dialogLeft + 10, this.dialogTop + 4, COLOR_WHITE);

        // 说明区块的左侧竖线装饰
        int descY = this.dialogTop + 22 + 16;
        drawContext.fill(this.dialogLeft + LEFT_PAD, descY,
                this.dialogLeft + LEFT_PAD + 3, descY + this.descLines.size() * 11 - 2, 0xFF888888);

        for (StaticLine line : this.staticLines)
        {
            this.drawString(drawContext, line.text(), line.x(), line.y(), line.color());
        }

        this.drawButtons(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseClicked(mouseX, mouseY, mouseButton))
        {
            return true;
        }
        // 点击面板外关闭（与旧 Overlay 行为一致）
        if (mouseX < this.dialogLeft || mouseX > this.dialogLeft + this.dialogWidth
                || mouseY < this.dialogTop || mouseY > this.dialogTop + this.dialogHeight)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == KeyCodes.KEY_ESCAPE)
        {
            GuiBase.openGui(this.getParent());
            return true;
        }
        return super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    public boolean shouldPause()
    {
        return false;
    }
}
