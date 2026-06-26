/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced DataManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.gui.*;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListStagingAreas;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetListWarehouseRefs;
import net.syncmaterial.syncmaterial.client.gui.widgets.WidgetStagingAreaEntry;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class GuiStagingAreaEditorNormal extends GuiBase
                                      implements StagingAreaEditorGui, StagingAreaSelector.SelectionCallback
{
    @Nullable private static GuiStagingAreaEditorNormal currentEditor;

    protected final AreaSelection selection;
    protected final String schematicId;
    protected WidgetCheckBox checkBoxCorner1;
    protected WidgetCheckBox checkBoxCorner2;
    protected int xNext;
    protected int yNext;
    protected int xOrigin;
    protected int xSet;
    @Nullable protected String selectionId;
    protected boolean needsServerLoad = false;
    protected boolean loadingFromServer = false;
    protected boolean needsWarehouseRefLoad = true;

    // 双列表
    @Nullable protected WidgetListStagingAreas stagingListWidget;
    @Nullable protected WidgetListWarehouseRefs warehouseListWidget;
    protected int stagingListY;
    protected int stagingListHeight;
    protected int warehouseListY;
    protected int warehouseListHeight;

    protected static StagingAreaConfigC2SPacket.AreaData toAreaData(String name, BlockPos pos1, BlockPos pos2) {
        return new StagingAreaConfigC2SPacket.AreaData(name,
            pos1.getX(), pos1.getY(), pos1.getZ(),
            pos2.getX(), pos2.getY(), pos2.getZ(), Optional.empty());
    }

    @Nullable
    public static GuiStagingAreaEditorNormal getCurrentEditor()
    {
        return currentEditor;
    }

    public static void clearCurrentEditor()
    {
        currentEditor = null;
    }

    public void setNeedsServerLoad()
    {
        this.needsServerLoad = true;
    }

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId)
    {
        this(selection, selectionId, "");
    }

    public GuiStagingAreaEditorNormal(AreaSelection selection, @Nullable String selectionId, String schematicId)
    {
        this.selection = selection;
        this.selectionId = selectionId;
        this.schematicId = schematicId;
        this.needsServerLoad = schematicId != null && !schematicId.isEmpty();
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.area_editor_normal");
    }

    public void setSelectionId(@Nullable String selectionId)
    {
        this.selectionId = selectionId;
    }

    @Override
    public String getSchematicId()
    {
        return this.schematicId;
    }

    @Override
    public void deleteArea(int areaId)
    {
        List<String> names = new ArrayList<>(this.selection.getAllSubRegionNames());
        if (areaId >= 0 && areaId < names.size())
        {
            String name = names.get(areaId);
            Integer serverId = this.selection.getServerId(name);

            this.selection.removeSubRegionBox(name);
            this.selection.removeServerId(name);

            if (serverId != null && this.schematicId != null)
            {
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        this.schematicId, "DELETE", serverId, Optional.empty()));
            }
        }
        this.refreshStagingList();
    }

    @Override
    public void refreshAreas()
    {
        this.refreshStagingList();
    }

    // ========== 网络响应 ==========

    public void onServerResponse(StagingAreaConfigResponseS2CPacket packet)
    {
        if (!packet.success())
        {
            SyncMaterial.LOGGER.warn("[StagingArea] 服务端响应失败: {}", packet.message());
            return;
        }

        String action = packet.action();

        // 仓库引用响应
        if ("LIST_WAREHOUSE_REFS".equals(action) || "ADD_WAREHOUSE_REF".equals(action) || "REMOVE_WAREHOUSE_REF".equals(action))
        {
            if (this.warehouseListWidget != null)
            {
                this.warehouseListWidget.setEntries(packet.areas());
            }
            return;
        }

        // 设置原理图名称
        if (packet.schematicName() != null && !packet.schematicName().isEmpty()) {
            StagingAreaRenderer.getInstance().setSchematicName(this.schematicId, packet.schematicName());
        }

        if (this.loadingFromServer)
        {
            this.loadingFromServer = false;

            java.util.Set<String> serverNames = new java.util.HashSet<>();
            for (var area : packet.areas())
            {
                serverNames.add(area.name());
                Box existing = this.selection.getSubRegionBox(area.name());
                if (existing != null)
                {
                    existing.setPos1(new BlockPos(area.x1(), area.y1(), area.z1()));
                    existing.setPos2(new BlockPos(area.x2(), area.y2(), area.z2()));
                }
                else
                {
                    BlockPos pos1 = new BlockPos(area.x1(), area.y1(), area.z1());
                    BlockPos pos2 = new BlockPos(area.x2(), area.y2(), area.z2());
                    this.selection.addSubRegionBox(new Box(pos1, pos2, area.name()), true);
                }
                this.selection.setServerId(area.name(), area.areaId());
            }

            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String name : this.selection.getAllSubRegionNames())
            {
                if (!serverNames.contains(name))
                {
                    toRemove.add(name);
                }
            }
            for (String name : toRemove)
            {
                this.selection.removeSubRegionBox(name);
                this.selection.removeServerId(name);
            }

            this.refreshStagingList();
            StagingAreaRenderer.getInstance().updateSelection(this.schematicId, this.selection);
        }
        else
        {
            for (var area : packet.areas())
            {
                if (this.selection.getServerId(area.name()) == null)
                {
                    this.selection.setServerId(area.name(), area.areaId());
                }
            }
            StagingAreaRenderer.getInstance().updateSelection(this.schematicId, this.selection);
        }
    }

    // ========== GUI 生命周期 ==========

    @Override
    public void initGui()
    {
        super.initGui();
        currentEditor = this;

        if (this.selection != null)
        {
            this.createTopButtons();
            this.createBottomButtons();
            this.createListWidgets();
            this.updateCheckBoxes();
        }
        else
        {
            this.addLabel(20, 30, 120, 12, 0xFFFFAA00, StringUtils.translate("syncmaterial.gui.error.no_selection"));
        }

        // 首次打开时从服务端加载备货区数据
        if (this.needsServerLoad && this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.needsServerLoad = false;
            this.loadingFromServer = true;
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    this.schematicId, "LIST", -1, Optional.empty()));
        }

        // 请求仓库引用列表（仅首次）
        if (this.needsWarehouseRefLoad && this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.needsWarehouseRefLoad = false;
            ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                    this.schematicId, "LIST_WAREHOUSE_REFS", 0, Optional.empty()));
        }
    }

    @Override
    public void removed()
    {
        super.removed();
        if (this.stagingListWidget != null) this.stagingListWidget.removed();
        if (this.warehouseListWidget != null) this.warehouseListWidget.removed();
        StagingAreaRenderer.getInstance().clearHighlightedBox();
    }

    // ========== 布局 ==========

    protected void createTopButtons()
    {
        int x = 10;
        int y = 26;

        // 添加备货区
        x += this.createButton(x, y, -1, ButtonListener.Type.CREATE_SUB_REGION) + 4;

        // 选区
        {
            String selectLabel = ButtonListener.Type.SELECT_AREA.getDisplayName();
            String selectHover = StringUtils.translate("syncmaterial.gui.button.select_area.hover");
            int selectWidth = StringUtils.getStringWidth(selectLabel) + 10;
            ButtonGeneric selectButton = new ButtonGeneric(x, y, selectWidth, 20, selectLabel, selectHover);
            this.addButton(selectButton, new ButtonListener(ButtonListener.Type.SELECT_AREA, null, null, this));
            x += selectWidth + 12;
        }

        // 备货区数量
        String str = String.valueOf(this.selection.getAllSubRegionNames().size());
        this.addLabel(x, y + 4, -1, 16, 0xFFFFFFFF,
                GuiBase.TXT_BOLD + StringUtils.translate("syncmaterial.gui.label.staging_areas", str));
    }

    protected void createBottomButtons()
    {
        int yBottom = this.getScreenHeight() - 26;

        // 返回按钮
        String label = StringUtils.translate("gui.back");
        int buttonWidth = StringUtils.getStringWidth(label) + 10;
        int xClose = this.getScreenWidth() - buttonWidth - 10;
        this.addButton(new ButtonGeneric(xClose, yBottom, buttonWidth, 20, label),
                new ButtonListener(ButtonListener.Type.CLOSE, null, null, this));
    }

    protected void createListWidgets()
    {
        int listX = 8;
        int listWidth = this.getScreenWidth() - 16;
        int contentTop = 48;
        int contentBottom = this.getScreenHeight() - 36;
        int totalHeight = contentBottom - contentTop;

        // 上半：备货区列表（约 55%）
        this.stagingListY = contentTop;
        this.stagingListHeight = (int)(totalHeight * 0.55);
        int stagingBottom = stagingListY + stagingListHeight;

        // 分隔线位置
        int separatorY = stagingBottom + 2;

        // 下半：仓库引用列表
        this.warehouseListY = separatorY + 14;
        this.warehouseListHeight = contentBottom - this.warehouseListY;

        // 备货区列表
        this.stagingListWidget = new WidgetListStagingAreas(listX, this.stagingListY, listWidth, this.stagingListHeight, this.selection, this);
        this.stagingListWidget.initGui();

        // 仓库引用列表
        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            this.warehouseListWidget = new WidgetListWarehouseRefs(listX, this.warehouseListY, listWidth, this.warehouseListHeight, this.schematicId);
            this.warehouseListWidget.initGui();
        }
    }

    private void refreshStagingList()
    {
        if (this.stagingListWidget != null)
        {
            this.stagingListWidget.refreshEntries();
        }
    }

    // ========== 渲染 ==========

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        if (this.stagingListWidget != null)
        {
            this.stagingListWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);
        }

        // 分隔线 + 标签
        int separatorY = this.stagingListY + this.stagingListHeight + 2;
        RenderUtils.drawRect(drawContext, 10, separatorY, this.getScreenWidth() - 20, 1, 0xFF555555);
        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            String warehouseLabel = StringUtils.translate("syncmaterial.gui.label.warehouse_refs_section",
                    String.valueOf(this.warehouseListWidget != null ? this.warehouseListWidget.getEntryCount() : 0));
            this.drawString(drawContext, GuiBase.TXT_BOLD + warehouseLabel, 14, separatorY + 3, 0xFF55AAFF);

            // 添加仓库按钮（标签右侧）
            String addLabel = StringUtils.translate("syncmaterial.gui.button.add_warehouse_ref");
            int addWidth = StringUtils.getStringWidth(addLabel) + 10;
            int addX = 14 + StringUtils.getStringWidth(GuiBase.TXT_BOLD + warehouseLabel) + 8;
            ButtonGeneric addBtn = new ButtonGeneric(addX, separatorY + 1, addWidth, 16, addLabel);
            this.addButton(addBtn, (btn, mouseBtn) -> {
                GuiBase.openGui(new GuiWarehouseSelect(this.schematicId));
            });
        }

        if (this.warehouseListWidget != null)
        {
            this.warehouseListWidget.drawContents(drawContext, mouseX, mouseY, partialTicks);
        }
    }

    @Override
    protected void drawHoveredWidget(DrawContext drawContext, int mouseX, int mouseY)
    {
        super.drawHoveredWidget(drawContext, mouseX, mouseY);

        if (!this.shouldRenderHoverStuff()) return;

        if (isMouseInRegion(mouseX, mouseY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            this.stagingListWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
        else if (isMouseInRegion(mouseX, mouseY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            this.warehouseListWidget.renderHoverEffects(drawContext, mouseX, mouseY);
        }
    }

    // ========== 事件分发 ==========

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseClicked(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        if (isMouseInRegion(mouseX, mouseY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            return this.stagingListWidget.onMouseClicked(mouseX, mouseY, mouseButton);
        }
        if (isMouseInRegion(mouseX, mouseY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            return this.warehouseListWidget.onMouseClicked(mouseX, mouseY, mouseButton);
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseReleased(mouseX, mouseY, mouseButton))
        {
            return true;
        }
        if (this.stagingListWidget != null) this.stagingListWidget.onMouseReleased(mouseX, mouseY, mouseButton);
        if (this.warehouseListWidget != null) this.warehouseListWidget.onMouseReleased(mouseX, mouseY, mouseButton);
        return false;
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount)
    {
        if (super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount))
        {
            return true;
        }
        if (isMouseInRegion(mouseX, mouseY, this.stagingListY, this.stagingListHeight) && this.stagingListWidget != null)
        {
            return this.stagingListWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        if (isMouseInRegion(mouseX, mouseY, this.warehouseListY, this.warehouseListHeight) && this.warehouseListWidget != null)
        {
            return this.warehouseListWidget.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return false;
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode != KeyCodes.KEY_ESCAPE && super.onKeyTyped(keyCode, scanCode, modifiers))
        {
            return true;
        }
        // 键盘事件优先给备货区列表（它有搜索栏）
        if (this.stagingListWidget != null && this.stagingListWidget.onKeyTyped(keyCode, scanCode, modifiers))
        {
            return true;
        }
        if (keyCode == KeyCodes.KEY_ESCAPE && super.onKeyTyped(keyCode, scanCode, modifiers))
        {
            return true;
        }
        return false;
    }

    @Override
    public boolean onCharTyped(char charIn, int modifiers)
    {
        if (super.onCharTyped(charIn, modifiers))
        {
            return true;
        }
        if (this.stagingListWidget != null && this.stagingListWidget.onCharTyped(charIn, modifiers))
        {
            return true;
        }
        return super.onCharTyped(charIn, modifiers);
    }

    @Override
    public void resize(MinecraftClient mc, int width, int height)
    {
        super.resize(mc, width, height);
        if (this.stagingListWidget != null) this.stagingListWidget.resize(mc, width, height);
        if (this.warehouseListWidget != null) this.warehouseListWidget.resize(mc, width, height);
    }

    private boolean isMouseInRegion(int mouseX, int mouseY, int regionY, int regionHeight)
    {
        return mouseX >= 8 && mouseX <= this.getScreenWidth() - 8
            && mouseY >= regionY && mouseY <= regionY + regionHeight;
    }

    // ========== 选区回调 ==========

    @Override
    public void onSelectionChange(@Nullable StagingAreaEntry entry)
    {
        if (entry != null)
        {
            this.selection.setSelectedSubRegionBox(entry.name());
            StagingAreaRenderer.getInstance().setHighlightedBox(this.schematicId, entry.name());
        }
    }

    protected List<StagingAreaEntry> getStagingAreaEntries()
    {
        List<StagingAreaEntry> entries = new ArrayList<>();
        int id = 0;
        for (String name : this.selection.getAllSubRegionNames())
        {
            Box box = this.selection.getSubRegionBox(name);
            if (box != null)
            {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                entries.add(new StagingAreaEntry(id++, name,
                        pos1.getX(), pos1.getY(), pos1.getZ(),
                        pos2.getX(), pos2.getY(), pos2.getZ(), ""));
            }
        }
        return entries;
    }

    public void onSelectionConfirmed(@Nullable String boxName, BlockPos pos1, BlockPos pos2)
    {
        if (boxName == null)
        {
            String newName = StringUtils.translate("syncmaterial.gui.label.staging_area_default", this.selection.getAllSubRegionNames().size() + 1);
            Box newBox = new Box(pos1, pos2, newName);
            this.selection.addSubRegionBox(newBox, true);
            this.selection.setSelectedSubRegionBox(newName);

            if (this.schematicId != null && !this.schematicId.isEmpty())
            {
                AreaData areaData = toAreaData(newName, pos1, pos2);
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        this.schematicId, "ADD", -1, Optional.of(areaData)));
            }
        }
        else
        {
            Box box = this.selection.getSubRegionBox(boxName);
            if (box != null)
            {
                box.setPos1(pos1);
                box.setPos2(pos2);

                Integer serverId = this.selection.getServerId(boxName);
                if (serverId != null && this.schematicId != null && !this.schematicId.isEmpty())
                {
                    AreaData areaData = toAreaData(boxName, pos1, pos2);
                    ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                            this.schematicId, "UPDATE", serverId, Optional.of(areaData)));
                }
            }
        }

        StagingAreaRenderer.getInstance().updateSelection(this.schematicId, this.selection);
        this.refreshStagingList();
    }

    @Nullable
    public ISelectionListener<StagingAreaEntry> getSelectionListener()
    {
        return entry -> {
            if (entry != null)
            {
                this.selection.setSelectedSubRegionBox(entry.name());
                StagingAreaRenderer.getInstance().setHighlightedBox(this.schematicId, entry.name());
            }
        };
    }

    // ========== 坐标编辑（保持不变） ==========

    protected void renameSubRegion()
    {
    }

    protected int createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label = "";
        WidgetCheckBox widget = null;

        switch (corner)
        {
            case CORNER_1:
                label = StringUtils.translate("syncmaterial.gui.label.corner_1");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner1 = widget;
                break;
            case CORNER_2:
                label = StringUtils.translate("syncmaterial.gui.label.corner_2");
                widget = new WidgetCheckBox(x, y + 3, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, label);
                this.checkBoxCorner2 = widget;
                break;
        }

        if (widget != null)
        {
            widget.setListener(new CheckBoxListener(corner, this));
            this.addWidget(widget);
        }
        y += 14;

        this.createCoordinateInput(x, y, width, CoordinateType.X, corner);
        y += 20;

        this.createCoordinateInput(x, y, width, CoordinateType.Y, corner);
        y += 20;

        this.createCoordinateInput(x, y, width, CoordinateType.Z, corner);
        y += 22;

        this.createButton(x + 10, y, -1, corner, ButtonListener.Type.MOVE_TO_PLAYER);
        y += 22;

        return y;
    }

    protected void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        String label = coordType.name() + ":";
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, label);
        int offset = 12;

        y += 2;
        BlockPos pos = corner == Corner.NONE ? this.selection.getEffectiveOrigin() : this.getBox().getPosition(corner);
        String text = "";
        ButtonListener.Type type = null;

        switch (coordType)
        {
            case X:
                text = String.valueOf(pos.getX());
                type = ButtonListener.Type.NUDGE_COORD_X;
                break;
            case Y:
                text = String.valueOf(pos.getY());
                type = ButtonListener.Type.NUDGE_COORD_Y;
                break;
            case Z:
                text = String.valueOf(pos.getZ());
                type = ButtonListener.Type.NUDGE_COORD_Z;
                break;
        }

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.textRenderer);
        TextFieldListener listener = new TextFieldListener(coordType, corner, this);
        textField.setTextWrapper(text);
        this.addTextField(textField, listener);

        this.createCoordinateButton(x + offset + width + 4, y, corner, coordType, type);
    }

    protected int createButton(int x, int y, int width, ButtonListener.Type type)
    {
        return this.createButton(x, y, width, null, type);
    }

    protected int createButton(int x, int y, int width, @Nullable Corner corner, ButtonListener.Type type)
    {
        String label = type.getDisplayName();
        if (width == -1)
        {
            width = StringUtils.getStringWidth(label) + 10;
        }
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        ButtonListener listener = new ButtonListener(type, corner, null, this);
        this.addButton(button, listener);
        return width;
    }

    protected void createCoordinateButton(int x, int y, Corner corner, CoordinateType coordType, ButtonListener.Type type)
    {
        String hover = StringUtils.translate("syncmaterial.gui.button.hover.plus_minus_tip");
        ButtonGeneric button = new ButtonGeneric(x, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        ButtonListener listener = new ButtonListener(type, corner, coordType, this);
        this.addButton(button, listener);
    }

    protected void updateCheckBoxes()
    {
        if (this.checkBoxCorner1 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_1;
            this.checkBoxCorner1.setChecked(checked, false);
        }
        if (this.checkBoxCorner2 != null)
        {
            boolean checked = this.selection.getSelectedSubRegionBox() != null && this.selection.getSelectedSubRegionBox().getSelectedCorner() == Corner.CORNER_2;
            this.checkBoxCorner2.setChecked(checked, false);
        }
    }

    @Nullable
    protected Box getBox()
    {
        return null;
    }

    protected void updatePosition(String numberString, Corner corner, CoordinateType type)
    {
        try
        {
            int value = Integer.parseInt(numberString);
            this.selection.setCoordinate(this.getBox(), corner, type, value);
        }
        catch (NumberFormatException e)
        {
        }
    }

    protected void moveCoordinate(int amount, Corner corner, CoordinateType type)
    {
        int oldValue = 0;
        if (corner == Corner.NONE)
        {
            oldValue = PositionUtils.getCoordinate(this.selection.getEffectiveOrigin(), type);
        }
        else if (this.getBox() != null)
        {
            oldValue = this.getBox().getCoordinate(corner, type);
        }
        this.selection.setCoordinate(this.getBox(), corner, type, oldValue + amount);
    }

    protected void sendCoordinateUpdate(Corner corner)
    {
        if (corner == Corner.NONE || this.schematicId == null)
        {
            return;
        }

        Box box = this.getBox();
        if (box == null)
        {
            box = this.selection.getSelectedSubRegionBox();
        }
        if (box == null)
        {
            return;
        }

        Integer serverId = this.selection.getServerId(box.getName());
        if (serverId == null)
        {
            return;
        }

        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();
        AreaData areaData = toAreaData(box.getName(), pos1, pos2);
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                this.schematicId, "UPDATE", serverId, Optional.of(areaData)));
    }

    // ========== 内部类 ==========

    protected static class ButtonListener implements IButtonActionListener
    {
        private final GuiStagingAreaEditorNormal parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        public ButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType, GuiStagingAreaEditorNormal parent)
        {
            this.type = type;
            this.corner = corner;
            this.coordinateType = coordinateType;
            this.parent = parent;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            int amount = mouseButton == 1 ? -1 : 1;
            if (GuiBase.isCtrlDown()) { amount *= 100; }
            if (GuiBase.isShiftDown()) { amount *= 10; }
            if (GuiBase.isAltDown()) { amount *= 5; }

            this.parent.setNextMessageType(MessageType.ERROR);

            switch (this.type)
            {
                case NUDGE_COORD_X:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case NUDGE_COORD_Y:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case NUDGE_COORD_Z:
                    this.parent.moveCoordinate(amount, this.corner, this.coordinateType);
                    this.parent.sendCoordinateUpdate(this.corner);
                    break;

                case CREATE_SUB_REGION:
                {
                    GuiTextInput gui = new GuiTextInput(512, "litematica.gui.title.area_editor.sub_region_name", "", null, new SubRegionCreator(this.parent));
                    gui.setParent(this.parent);
                    GuiBase.openGui(gui);
                    break;
                }

                case SET_BOX_NAME:
                {
                    this.parent.renameSubRegion();
                    break;
                }

                case MOVE_TO_PLAYER:
                    if (this.parent.mc.player != null)
                    {
                        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.parent.mc.player);

                        if (this.corner == Corner.NONE)
                        {
                            this.parent.selection.setExplicitOrigin(pos);
                        }
                        else
                        {
                            this.parent.selection.setSelectedSubRegionCornerPos(pos, this.corner);
                            this.parent.sendCoordinateUpdate(this.corner);
                        }
                    }
                    break;

                case SELECT_AREA:
                {
                    Box selectedBox = this.parent.selection.getSelectedSubRegionBox();
                    String boxName = selectedBox != null ? selectedBox.getName() : null;
                    BlockPos pos1 = selectedBox != null ? selectedBox.getPos1() : null;
                    BlockPos pos2 = selectedBox != null ? selectedBox.getPos2() : null;

                    StagingAreaSelector.getInstance().start(this.parent, this.parent, boxName, pos1, pos2);
                    return;
                }

                case CLOSE:
                    GuiBase.openGui(this.parent.getParent());
                    return;
            }

            this.parent.initGui();
        }

        public enum Type
        {
            SET_BOX_NAME            ("syncmaterial.gui.button.rename_staging_area"),
            CREATE_SUB_REGION       ("syncmaterial.gui.button.add_staging_area"),
            SELECT_AREA             ("syncmaterial.gui.button.select_area"),
            MOVE_TO_PLAYER          ("litematica.gui.button.move_to_player"),
            CLOSE                   ("gui.back"),
            NUDGE_COORD_X           (""),
            NUDGE_COORD_Y           (""),
            NUDGE_COORD_Z           ("");

            private final String translationKey;

            private Type(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getTranslationKey()
            {
                return this.translationKey;
            }

            public String getDisplayName(Object... args)
            {
                return StringUtils.translate(this.translationKey, args);
            }
        }
    }

    protected static class TextFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiStagingAreaEditorNormal parent;
        private final CoordinateType type;
        private final Corner corner;

        public TextFieldListener(CoordinateType type, Corner corner, GuiStagingAreaEditorNormal parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            this.parent.updatePosition(textField.getTextWrapper(), this.corner, this.type);
            this.parent.sendCoordinateUpdate(this.corner);
            return false;
        }
    }

    public static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            return false;
        }
    }

    protected static class SubRegionCreator implements IStringConsumerFeedback
    {
        private final GuiStagingAreaEditorNormal gui;

        private SubRegionCreator(GuiStagingAreaEditorNormal gui)
        {
            this.gui = gui;
        }

        @Override
        public boolean setString(String string)
        {
            BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.gui.mc.player);
            this.gui.selection.createNewSubRegionBox(pos, string);

            Box box = this.gui.selection.getSubRegionBox(string);
            if (box != null && this.gui.schematicId != null)
            {
                BlockPos p1 = box.getPos1();
                BlockPos p2 = box.getPos2();
                AreaData areaData = toAreaData(string, p1, p2);
                ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                        this.gui.schematicId, "ADD", -1, Optional.of(areaData)));
            }

            this.gui.initGui();
            return true;
        }
    }

    protected static class CheckBoxListener implements ISelectionListener<WidgetCheckBox>
    {
        private final GuiStagingAreaEditorNormal gui;
        private final Corner corner;

        public CheckBoxListener(Corner corner, GuiStagingAreaEditorNormal gui)
        {
            this.gui = gui;
            this.corner = corner;
        }

        @Override
        public void onSelectionChange(WidgetCheckBox entry)
        {
            if (entry.isChecked())
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(true);
                    this.gui.selection.clearCurrentSelectedCorner();
                }
                else
                {
                    this.gui.selection.setOriginSelected(false);
                    this.gui.selection.setCurrentSelectedCorner(this.corner);
                }
            }
            else
            {
                if (this.corner == Corner.NONE)
                {
                    this.gui.selection.setOriginSelected(false);
                }
                else
                {
                    this.gui.selection.clearCurrentSelectedCorner();
                }
            }

            this.gui.updateCheckBoxes();
        }
    }
}
