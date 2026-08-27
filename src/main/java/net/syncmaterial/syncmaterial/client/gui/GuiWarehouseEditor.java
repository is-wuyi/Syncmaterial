package net.syncmaterial.syncmaterial.client.gui;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket;
import net.syncmaterial.syncmaterial.network.StagingAreaConfigC2SPacket.AreaData;

import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

/**
 * 仓库编辑界面：名称、两角坐标、准星选区、所属维度。
 *
 * 不复用 GuiStagingAreaEditorSubRegion，因为那个类建立在 AreaSelection + Box
 * （Litematica 的可变选区模型）之上，靠 selection.getServerId(name) 取 ID 发包；
 * 仓库侧是不可变的 WarehouseEntry，带 world 字段，发的是 UPDATE_WAREHOUSE + warehouseId。
 * 两者数据模型不共享，强行抽公共基类的成本高于收益。
 */
public class GuiWarehouseEditor extends GuiBase
        implements StagingAreaSelector.SelectionCallback
{
    private final GuiWarehouseManager manager;
    private final int warehouseId;

    private String name;
    private String world;
    private BlockPos pos1;
    private BlockPos pos2;

    private GuiTextFieldGeneric textFieldName;

    public GuiWarehouseEditor(WarehouseEntry entry, GuiWarehouseManager manager)
    {
        this.manager = manager;
        this.warehouseId = entry.warehouseId();
        this.name = entry.name();
        this.world = entry.world();
        this.pos1 = new BlockPos(entry.x1(), entry.y1(), entry.z1());
        this.pos2 = new BlockPos(entry.x2(), entry.y2(), entry.z2());
        this.useTitleHierarchy = false;
        this.title = StringUtils.translate("syncmaterial.gui.title.warehouse_editor");
    }

    @Override
    public void initGui()
    {
        super.initGui();

        int x = 12;
        int y = 24;
        int width = 202;

        // 仓库名称
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, StringUtils.translate("syncmaterial.gui.label.warehouse_name"));
        y += 13;

        this.textFieldName = new GuiTextFieldGeneric(x, y + 2, width, 16, this.font);
        this.textFieldName.setTextWrapper(this.name);
        this.addTextField(this.textFieldName, new TextFieldListenerDummy());
        this.createButton(x + width + 4, y, ButtonListener.Type.SET_NAME);
        y += 22;

        // 准星选区
        String selectHover = StringUtils.translate("syncmaterial.gui.button.select_area.hover");
        String selectLabel = ButtonListener.Type.SELECT_AREA.getDisplayName();
        int selectWidth = StringUtils.getStringWidth(selectLabel) + 10;
        ButtonGeneric selectButton = new ButtonGeneric(x, y, selectWidth, 20, selectLabel, selectHover);
        this.addButton(selectButton, new ButtonListener(ButtonListener.Type.SELECT_AREA, null, null, this));
        y += 24;

        // 所属维度：与玩家当前维度不一致时标黄提示，避免误改到别的维度
        String worldLabel = StringUtils.translate("syncmaterial.gui.label.warehouse_world",
                shortWorldName(this.world));
        boolean sameWorld = this.world != null && this.world.equals(currentWorldId());
        this.addLabel(x, y, -1, 16, sameWorld ? 0xFFAAAAAA : 0xFFFFAA00, worldLabel);
        y += 18;

        // 两个角的坐标
        int coordWidth = 68;
        int coordX = x;
        this.createCoordinateInputs(coordX, y, coordWidth, Corner.CORNER_1);
        this.createCoordinateInputs(coordX + coordWidth + 42, y, coordWidth, Corner.CORNER_2);

        // 返回按钮
        int yBottom = this.getScreenHeight() - 26;
        String backLabel = StringUtils.translate("gui.back");
        int backWidth = StringUtils.getStringWidth(backLabel) + 10;
        this.addButton(new ButtonGeneric(12, yBottom, backWidth, 20, backLabel),
                new ButtonListener(ButtonListener.Type.BACK, null, null, this));
    }

    /** 维度 ID 去掉命名空间前缀，界面上更紧凑 */
    private static String shortWorldName(@Nullable String worldId)
    {
        if (worldId == null || worldId.isEmpty())
        {
            return "?";
        }
        int idx = worldId.indexOf(':');
        return idx >= 0 && idx < worldId.length() - 1 ? worldId.substring(idx + 1) : worldId;
    }

    @Nullable
    private static String currentWorldId()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
                ? mc.player.level().dimension().identifier().toString()
                : null;
    }

    // ========== 坐标编辑 ==========

    private void createCoordinateInputs(int x, int y, int width, Corner corner)
    {
        String label = StringUtils.translate(corner == Corner.CORNER_1
                ? "syncmaterial.gui.label.corner_1"
                : "syncmaterial.gui.label.corner_2");
        this.addLabel(x, y, -1, 16, 0xFFFFFFFF, label);
        y += 14;

        this.createCoordinateInput(x, y, width, CoordinateType.X, corner);
        y += 20;
        this.createCoordinateInput(x, y, width, CoordinateType.Y, corner);
        y += 20;
        this.createCoordinateInput(x, y, width, CoordinateType.Z, corner);
        y += 22;

        this.createButton(x + 10, y, ButtonListener.Type.MOVE_TO_PLAYER, corner);
    }

    private void createCoordinateInput(int x, int y, int width, CoordinateType coordType, Corner corner)
    {
        this.addLabel(x, y, 20, 20, 0xFFFFFFFF, coordType.name() + ":");
        int offset = 12;
        y += 2;

        BlockPos pos = corner == Corner.CORNER_1 ? this.pos1 : this.pos2;
        String text = String.valueOf(getCoordinate(pos, coordType));

        GuiTextFieldInteger textField = new GuiTextFieldInteger(x + offset, y, width, 16, this.font);
        textField.setTextWrapper(text);
        this.addTextField(textField, new CoordinateFieldListener(coordType, corner, this));

        String hover = StringUtils.translate("syncmaterial.gui.button.hover.plus_minus_tip");
        ButtonGeneric button = new ButtonGeneric(x + offset + width + 4, y, Icons.BUTTON_PLUS_MINUS_16, hover);
        this.addButton(button, new ButtonListener(ButtonListener.Type.NUDGE_COORD, corner, coordType, this));
    }

    private static int getCoordinate(BlockPos pos, CoordinateType type)
    {
        return switch (type)
        {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static BlockPos withCoordinate(BlockPos pos, CoordinateType type, int value)
    {
        return switch (type)
        {
            case X -> new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }

    private int createButton(int x, int y, ButtonListener.Type type)
    {
        return this.createButton(x, y, type, null);
    }

    private int createButton(int x, int y, ButtonListener.Type type, @Nullable Corner corner)
    {
        String label = type.getDisplayName();
        int width = StringUtils.getStringWidth(label) + 10;
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        this.addButton(button, new ButtonListener(type, corner, null, this));
        return width;
    }

    // ========== 数据操作 ==========

    private void setCoordinate(Corner corner, CoordinateType type, int value)
    {
        if (corner == Corner.CORNER_1)
        {
            this.pos1 = withCoordinate(this.pos1, type, value);
        }
        else
        {
            this.pos2 = withCoordinate(this.pos2, type, value);
        }
        this.sendUpdate();
    }

    private void nudgeCoordinate(Corner corner, CoordinateType type, int amount)
    {
        BlockPos pos = corner == Corner.CORNER_1 ? this.pos1 : this.pos2;
        this.setCoordinate(corner, type, getCoordinate(pos, type) + amount);
    }

    private void moveToPlayer(Corner corner)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }

        BlockPos pos = fi.dy.masa.malilib.util.position.PositionUtils.getEntityBlockPos(mc.player);
        if (corner == Corner.CORNER_1)
        {
            this.pos1 = pos;
        }
        else
        {
            this.pos2 = pos;
        }

        // 移到玩家意味着这个角落到了玩家所在维度，整体迁移过去
        String playerWorld = currentWorldId();
        if (playerWorld != null)
        {
            this.world = playerWorld;
        }
        this.sendUpdate();
    }

    private void applyName()
    {
        String newName = this.textFieldName.getTextWrapper();
        if (newName == null || newName.trim().isEmpty())
        {
            return;
        }
        this.name = newName.trim();
        this.sendUpdate();
    }

    /**
     * 立即发包（与备货区的即时生效行为一致）。
     * 注意服务端 UPDATE_WAREHOUSE 会触发全量重扫，所以不要在高频路径上调用。
     */
    private void sendUpdate()
    {
        AreaData data = new AreaData(this.name,
                this.pos1.getX(), this.pos1.getY(), this.pos1.getZ(),
                this.pos2.getX(), this.pos2.getY(), this.pos2.getZ(),
                Optional.ofNullable(this.world));
        ClientPlayNetworking.send(new StagingAreaConfigC2SPacket(
                "", "UPDATE_WAREHOUSE", this.warehouseId, Optional.of(data)));
        this.manager.requestRefresh();
    }

    // ========== 准星选区回调 ==========

    @Override
    public void onSelectionConfirmed(@Nullable String boxName, @Nullable BlockPos pos1, @Nullable BlockPos pos2)
    {
        if (pos1 == null || pos2 == null)
        {
            return;
        }

        this.pos1 = pos1;
        this.pos2 = pos2;
        // 准星只能在玩家所在维度选点，所以范围重选等同于迁移到该维度
        String playerWorld = currentWorldId();
        if (playerWorld != null)
        {
            this.world = playerWorld;
        }
        this.sendUpdate();
        this.initGui();
    }

    // ========== 内部类 ==========

    private static class ButtonListener implements IButtonActionListener
    {
        private final GuiWarehouseEditor parent;
        private final Type type;
        @Nullable private final Corner corner;
        @Nullable private final CoordinateType coordinateType;

        ButtonListener(Type type, @Nullable Corner corner, @Nullable CoordinateType coordinateType,
                       GuiWarehouseEditor parent)
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
                case SET_NAME:
                    this.parent.applyName();
                    break;

                case NUDGE_COORD:
                    if (this.corner != null && this.coordinateType != null)
                    {
                        this.parent.nudgeCoordinate(this.corner, this.coordinateType, amount);
                    }
                    break;

                case MOVE_TO_PLAYER:
                    if (this.corner != null)
                    {
                        this.parent.moveToPlayer(this.corner);
                    }
                    break;

                case SELECT_AREA:
                    // 传入仓库上下文：区域框用仓库配色，且正式渲染跳过这个仓库，
                    // 视觉上就是在就地改它的框
                    StagingAreaSelector.getInstance().start(this.parent, this.parent,
                            this.parent.name, this.parent.pos1, this.parent.pos2,
                            StagingAreaSelector.TargetType.WAREHOUSE, null, this.parent.warehouseId);
                    return;

                case BACK:
                    this.parent.closeGui(true);
                    return;
            }

            this.parent.initGui();
        }

        enum Type
        {
            SET_NAME("syncmaterial.gui.button.rename"),
            SELECT_AREA("syncmaterial.gui.button.select_area"),
            MOVE_TO_PLAYER("litematica.gui.button.move_to_player"),
            BACK("gui.back"),
            NUDGE_COORD("");

            private final String translationKey;

            Type(String translationKey)
            {
                this.translationKey = translationKey;
            }

            String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }

    private static class CoordinateFieldListener implements ITextFieldListener<GuiTextFieldGeneric>
    {
        private final GuiWarehouseEditor parent;
        private final CoordinateType type;
        private final Corner corner;

        CoordinateFieldListener(CoordinateType type, Corner corner, GuiWarehouseEditor parent)
        {
            this.type = type;
            this.corner = corner;
            this.parent = parent;
        }

        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            try
            {
                this.parent.setCoordinate(this.corner, this.type,
                        Integer.parseInt(textField.getTextWrapper()));
            }
            catch (NumberFormatException e)
            {
                // 输入中途（空串、只有负号）不算错误，忽略即可
            }
            return false;
        }
    }

    private static class TextFieldListenerDummy implements ITextFieldListener<GuiTextFieldGeneric>
    {
        @Override
        public boolean onTextChange(GuiTextFieldGeneric textField)
        {
            return false;
        }
    }
}
