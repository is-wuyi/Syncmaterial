/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: replaced DataManager with StagingAreaManager, added network sync.
 */

package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.selection.AreaSelection;
import net.syncmaterial.syncmaterial.selection.Box;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

/**
 * 子区域编辑器：显示两个角的坐标编辑界面，独立于主列表界面
 */
public class GuiStagingAreaEditorSubRegion extends GuiBase
        implements StagingAreaSelector.SelectionCallback
{
    protected final AreaSelection selection;
    protected final String schematicId;
    protected final Box box;
    protected GuiTextFieldGeneric textFieldBoxName;
    protected WidgetCheckBox checkBoxCorner1;
    protected WidgetCheckBox checkBoxCorner2;

    /**
     * 坐标编辑走去抖：文本框每敲一个字符就触发 onTextChange，而服务端每次
     * UPDATE 都会重扫备货区并广播状态。按钮类操作走 flushNow 立即发出。
     */
    protected final UpdateDebouncer coordinateDebouncer =
            new UpdateDebouncer(GuiStagingAreaEditorNormal.COORD_QUIET_TICKS);
    private final EditorSaveTracker saveTracker = new EditorSaveTracker();

    public EditorSaveTracker.State getSaveState() {
        return saveTracker.state();
    }

    public GuiStagingAreaEditorSubRegion(AreaSelection selection, Box box, @Nullable String selectionId)
    {
        this(selection, box, selectionId, "");
    }

    public GuiStagingAreaEditorSubRegion(AreaSelection selection, Box box, @Nullable String selectionId, String schematicId)
    {
        this.selection = selection;
        this.schematicId = schematicId;
        this.box = box;
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("litematica.gui.title.area_editor_sub_region");
    }

    public String getSchematicId() {
        return this.schematicId;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        if (this.schematicId != null && !this.schematicId.isEmpty()) {
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                    session.openEditor(this.schematicId));
            // 工作副本预览随界面重建重新登记；与父编辑器共用同一 AreaSelection
            StagingAreaRenderer.getInstance().setEditorPreview(this.schematicId, this.selection);
        }

        int x = 12;
        int y = 24;
        int width = 202;

        // 箱子名称
        String label = StringUtils.translate("litematica.gui.label.area_editor.box_name");
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, label);
        y += 13;

        this.textFieldBoxName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.mc.font);
        this.textFieldBoxName.setTextWrapper(this.box.getName());
        this.addTextField(this.textFieldBoxName, new TextFieldListenerDummy());
        this.createButton(x + width + 4, y, -1, SubRegionButtonListener.Type.SET_BOX_NAME);
        y += 22;

        // 准星选区：用视线重新框定范围，替代逐个改坐标
        String selectHover = StringUtils.translate("syncmaterial.gui.button.select_area.hover");
        String selectLabel = SubRegionButtonListener.Type.SELECT_AREA.getDisplayName();
        int selectWidth = StringUtils.getStringWidth(selectLabel) + 10;
        ButtonGeneric selectButton = new ButtonGeneric(x, y, selectWidth, 20, selectLabel, selectHover);
        this.addButton(selectButton, new SubRegionButtonListener(SubRegionButtonListener.Type.SELECT_AREA, null, null, this));
        y += 24;

        // 两个角的坐标
        x = 12;
        width = 68;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_1);
        x += width + 42;
        this.createCoordinateInputs(x, y, width, Corner.CORNER_2);

        // 返回按钮
        y = this.getScreenHeight() - 26;
        String backLabel = StringUtils.translate("gui.back");
        int backWidth = StringUtils.getStringWidth(backLabel) + 10;
        this.addButton(new ButtonGeneric(12, y, backWidth, 20, backLabel),
                new SubRegionButtonListener(SubRegionButtonListener.Type.BACK, null, null, this));
    }

    // ========== 坐标编辑 ==========

    protected int createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label;
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
            default:
                return y;
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
        this.createButton(x + 10, y, -1, SubRegionButtonListener.Type.MOVE_TO_PLAYER);
        y += 22;

        return y;
    }

    protected void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        String label = coordType.name() + ":";
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, label);
        int offset = 12;

        y += 2;
        BlockPos pos = this.box.getPosition(corner);
        String text = "";
        SubRegionButtonListener.Type type = null;

        switch (coordType)
        {
            case X:
                text = String.valueOf(pos.getX());
                type = SubRegionButtonListener.Type.NUDGE_COORD_X;
                break;
            case Y:
                text = String.valueOf(pos.getY());
                type = SubRegionButtonListener.Type.NUDGE_COORD_Y;
                break;
            case Z:
                text = String.valueOf(pos.getZ());
                type = SubRegionButtonListener.Type.NUDGE_COORD_Z;
                break;
        }

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.mc.font);
        TextFieldListener listener = new TextFieldListener(coordType, corner, this);
        textField.setTextWrapper(text);
        this.addTextField(textField, listener);

        String hover = StringUtils.translate("syncmaterial.gui.button.hover.plus_minus_tip");
        ButtonGeneric button = new ButtonGeneric(x + offset + width + 4, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        this.addButton(button, new SubRegionButtonListener(type, corner, coordType, this));
    }

    protected int createButton(int x, int y, int width, SubRegionButtonListener.Type type)
    {
        String label = type.getDisplayName();
        if (width == -1)
        {
            width = StringUtils.getStringWidth(label) + 10;
        }
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        this.addButton(button, new SubRegionButtonListener(type, null, null, this));
        return width;
    }

    // ========== 坐标操作 ==========

    /** 应用一次坐标输入；返回 false 表示输入仍不合法（未产生编辑意图）。 */
    protected boolean updatePosition(String numberString, Corner corner, CoordinateType type)
    {
        try
        {
            int value = Integer.parseInt(numberString);
            this.selection.setCoordinate(this.box, corner, type, value);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    protected void moveCoordinate(int amount, Corner corner, CoordinateType type)
    {
        int oldValue = this.box.getCoordinate(corner, type);
        this.selection.setCoordinate(this.box, corner, type, oldValue + amount);
    }

    protected void sendCoordinateUpdate(Corner corner)
    {
        if (this.schematicId == null) return;

        Integer serverId = this.selection.getServerId(this.box.getName());
        if (serverId == null) return;

        // 先补发已登记未发出的输入（失效载荷会被去抖器丢弃），再立即发送本次动作
        this.coordinateDebouncer.flushNow();
        BlockPos pos1 = this.box.getPos1();
        BlockPos pos2 = this.box.getPos2();
        AreaData areaData = GuiStagingAreaEditorNormal.toAreaData(this.box.getName(), pos1, pos2);
        writeAreaConfig("UPDATE", serverId, areaData);
    }

    private long writeAreaConfig(String action, int areaId, AreaData data) {
        return ProjectViewEditorSupport.write(net.syncmaterial.syncmaterial.core.ProjectViewScope.project(schematicId),
                areaId, action, Optional.of(data),
                new StagingAreaConfigC2SPacket(schematicId, action, areaId, Optional.of(data)), saveTracker);
    }

    /**
     * 登记一次延迟发出的坐标更新，供文本框逐字符输入使用。
     *
     * <p>合法输入在登记时即产生 {@link EditorSaveTracker} 版本；去抖载荷携带该版本与
     * 登记时刻的目标主键及完整坐标快照，发送时不再读取当前 Box，也不重新制造版本。
     * 连续输入只发最后一次；上下文失效或被更新的动作取代时载荷被丢弃。</p>
     */
    protected void scheduleCoordinateUpdate(Corner corner)
    {
        if (this.schematicId == null || this.schematicId.isEmpty())
        {
            return;
        }
        Integer serverId = this.selection.getServerId(this.box.getName());
        if (serverId == null || this.box.getPos1() == null || this.box.getPos2() == null)
        {
            return;
        }

        long version = this.saveTracker.edit();
        net.syncmaterial.syncmaterial.core.ProjectViewScope scope =
                net.syncmaterial.syncmaterial.core.ProjectViewScope.project(this.schematicId);
        ProjectViewEditorSupport.EditFence fence = ProjectViewEditorSupport.captureFence(scope);
        AreaData payload = GuiStagingAreaEditorNormal.toAreaData(
                this.box.getName(), this.box.getPos1(), this.box.getPos2());
        int targetId = serverId;
        String projectId = this.schematicId;
        this.coordinateDebouncer.schedule(() ->
                        ProjectViewEditorSupport.writePrepared(scope, targetId, "UPDATE",
                                Optional.of(payload),
                                new StagingAreaConfigC2SPacket(projectId, "UPDATE", targetId, Optional.of(payload)),
                                this.saveTracker, version),
                ProjectViewEditorSupport.intentValidity(fence, version, this.saveTracker));
    }

    /** 驱动去抖计时。GuiBase 继承自 Screen，每客户端 tick 调用一次 */
    @Override
    public void tick()
    {
        super.tick();
        this.coordinateDebouncer.tick();
    }

    @Override
    //? if >=26.2 {
    protected void drawContents(fi.dy.masa.malilib.render.GuiContext drawContext, int mouseX, int mouseY, float partialTicks) {
    //?} else {
    /*protected void drawContents(net.minecraft.client.gui.GuiGraphics drawContext, int mouseX, int mouseY, float partialTicks) {
    *///?}
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);
        String status = ProjectViewEditorSupport.saveStatus(saveTracker.state());
        if (!status.isEmpty()) {
            this.drawString(drawContext, status, this.getScreenWidth() - 12
                    - fi.dy.masa.malilib.util.StringUtils.getStringWidth(status),
                    this.getScreenHeight() - 14, 0xFFB0B0B0);
        }
    }

    @Override
    public void closeGui(boolean showParent) {
        // 正常退出固定顺序：先补发未发出的编辑意图，再释放 editor 兴趣
        this.coordinateDebouncer.flushNow();
        if (this.schematicId != null && !this.schematicId.isEmpty()) {
            net.syncmaterial.syncmaterial.client.ProjectViewClient.current().ifPresent(session ->
                    session.setEditor(this.schematicId, false));
        }
        super.closeGui(showParent);
    }

    /** 关界面前补发未发出的坐标改动，否则刚敲的值会被丢掉 */
    @Override
    public void removed()
    {
        this.coordinateDebouncer.flushNow();
        super.removed();
    }

    protected void renameSubRegion()
    {
        String oldName = this.box.getName();
        String newName = this.textFieldBoxName.getTextWrapper();
        this.selection.renameSubRegionBox(oldName, newName);

        if (this.schematicId != null)
        {
            Integer serverId = this.selection.getServerId(oldName);
            if (serverId != null)
            {
                BlockPos pos1 = this.box.getPos1();
                BlockPos pos2 = this.box.getPos2();
                AreaData areaData = GuiStagingAreaEditorNormal.toAreaData(newName, pos1, pos2);
                writeAreaConfig("RENAME", serverId, areaData);

                this.selection.removeServerId(oldName);
                this.selection.setServerId(newName, serverId);
            }
        }
    }

    protected void updateCheckBoxes()
    {
        if (this.checkBoxCorner1 != null)
        {
            boolean checked = this.box.getSelectedCorner() == Corner.CORNER_1;
            this.checkBoxCorner1.setChecked(checked, false);
        }
        if (this.checkBoxCorner2 != null)
        {
            boolean checked = this.box.getSelectedCorner() == Corner.CORNER_2;
            this.checkBoxCorner2.setChecked(checked, false);
        }
    }

    // ========== 准星选区回调 ==========

    @Override
    public void onSelectionConfirmed(@Nullable String boxName, @Nullable BlockPos pos1, @Nullable BlockPos pos2)
    {
        if (pos1 == null || pos2 == null)
        {
            return;
        }

        // 先补发已登记未发出的坐标输入（失效载荷会被去抖器丢弃），再应用本次选区结果
        this.coordinateDebouncer.flushNow();

        this.box.setPos1(pos1);
        this.box.setPos2(pos2);

        if (this.schematicId != null && !this.schematicId.isEmpty())
        {
            // 目标已被权威配置删除时 getServerId 返回 null：不降级为新建，只更新本地预览
            Integer serverId = this.selection.getServerId(this.box.getName());
            if (serverId != null)
            {
                // 带当前维度：准星只能在玩家所在维度选点，跨维度重选必须一起迁移 world
                AreaData areaData = GuiStagingAreaEditorNormal.toAreaData(
                        this.box.getName(), pos1, pos2, GuiStagingAreaEditorNormal.currentWorldId());
                writeAreaConfig("UPDATE", serverId, areaData);
            }

            // 工作副本只作为本地预览登记；权威投影由协议 5 快照负责
            StagingAreaRenderer.getInstance().setEditorPreview(this.schematicId, this.selection);
        }

        this.initGui();
    }

    // ========== 内部类 ==========

    /**
     * 准星重选的初始角点：box 属于玩家当前维度时带入旧角点（就地改框）；
     * box 属于其他维度（换维度后旧坐标）则不带初始值 —— 旧 world 坐标
     * 不能被当作新维度的初始角点。
     */
    private BlockPos[] initialCornersForSelection()
    {
        String boxWorld = this.box.getWorld();
        String currentWorld = GuiStagingAreaEditorNormal.currentWorldId();
        if (boxWorld == null || boxWorld.isBlank() || boxWorld.equals(currentWorld))
        {
            return new BlockPos[] { this.box.getPos1(), this.box.getPos2() };
        }
        return new BlockPos[] { null, null };
    }

    private static class SubRegionButtonListener implements IButtonActionListener
    {
        private final GuiStagingAreaEditorSubRegion parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        public SubRegionButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType, GuiStagingAreaEditorSubRegion parent)
        {
            this.type = type;
            this.corner = corner;
            this.coordinateType = coordinateType;
            this.parent = parent;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            int amount = CoordinateNudge.amount(mouseButton);

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
                case SET_BOX_NAME:
                    this.parent.renameSubRegion();
                    break;
                case MOVE_TO_PLAYER:
                    if (this.parent.mc.player != null)
                    {
                        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(this.parent.mc.player);
                        this.parent.selection.setSelectedSubRegionCornerPos(pos, this.corner);
                        this.parent.sendCoordinateUpdate(this.corner);
                    }
                    break;
                case SELECT_AREA:
                    // 直接 start()：本界面不是 MaLiLib 弹窗，按钮回调里没有后续的 setScreen，
                    // 不存在 SubRegionCreator 那里的覆盖时序问题。
                    // 传入备货区上下文：正式渲染跳过这个 box，选区用备货区配色接手绘制
                    // 若 box 属于其他维度（换维度后旧坐标），不把旧 world 坐标当初始角点
                    BlockPos[] initial = this.parent.initialCornersForSelection();
                    StagingAreaSelector.getInstance().start(this.parent, this.parent,
                            this.parent.box.getName(), initial[0], initial[1],
                            StagingAreaSelector.TargetType.STAGING_AREA, this.parent.schematicId, -1);
                    return;
                case BACK:
                    this.parent.closeGui(true);
                    return;
            }
            this.parent.initGui();
        }

        public enum Type
        {
            SET_BOX_NAME("syncmaterial.gui.button.rename_staging_area"),
            SELECT_AREA("syncmaterial.gui.button.select_area"),
            MOVE_TO_PLAYER("litematica.gui.button.move_to_player"),
            BACK("gui.back"),
            NUDGE_COORD_X(""),
            NUDGE_COORD_Y(""),
            NUDGE_COORD_Z("");

            private final String translationKey;
            private Type(String translationKey) { this.translationKey = translationKey; }
            public String getDisplayName(Object... args) { return StringUtils.translate(this.translationKey, args); }
        }
    }

    private static class TextFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiStagingAreaEditorSubRegion parent;
        private final CoordinateType type;
        private final Corner corner;

        public TextFieldListener(CoordinateType type, Corner corner, GuiStagingAreaEditorSubRegion parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            // 只有合法坐标才登记编辑意图；非法中间态（空串、单负号）不产生版本
            if (this.parent.updatePosition(textField.getTextWrapper(), this.corner, this.type)) {
                this.parent.scheduleCoordinateUpdate(this.corner);
            }
            return false;
        }
    }

    private static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField) { return false; }
    }

    private static class CheckBoxListener implements fi.dy.masa.malilib.gui.interfaces.ISelectionListener<WidgetCheckBox>
    {
        private final GuiStagingAreaEditorSubRegion gui;
        private final Corner corner;

        public CheckBoxListener(Corner corner, GuiStagingAreaEditorSubRegion gui)
        {
            this.gui = gui;
            this.corner = corner;
        }

        @Override
        public void onSelectionChange(WidgetCheckBox entry)
        {
            if (entry.isChecked())
            {
                this.gui.selection.setCurrentSelectedCorner(this.corner);
            }
            else
            {
                this.gui.selection.clearCurrentSelectedCorner();
            }
            this.gui.updateCheckBoxes();
        }
    }
}
