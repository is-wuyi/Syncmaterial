/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: removed Litematica-specific dependencies, adapted for server-side storage.
 */

package net.syncmaterial.syncmaterial.selection;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class Box
{
    @Nullable private BlockPos pos1;
    @Nullable private BlockPos pos2;
    private BlockPos size = BlockPos.ZERO;
    private String name = "Unnamed";
    private Corner selectedCorner = Corner.NONE;
    /**
     * 该区域所属维度（服务端 {@code AreaInfo.world}）。
     * 空/null 表示未知（旧服务端或尚未同步），渲染时按"任何维度都显示"处理，
     * 避免升级瞬间把已有线框全部隐藏。
     */
    @Nullable private String world;

    public Box()
    {
        this.pos1 = BlockPos.ZERO;
        this.pos2 = BlockPos.ZERO;
        this.updateSize();
    }

    public Box(@Nullable BlockPos pos1, @Nullable BlockPos pos2, String name)
    {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.name = name;

        this.updateSize();
    }

    @Nullable
    public BlockPos getPos1()
    {
        return this.pos1;
    }

    @Nullable
    public BlockPos getPos2()
    {
        return this.pos2;
    }

    public BlockPos getSize()
    {
        return this.size;
    }

    public String getName()
    {
        return this.name;
    }

    public Corner getSelectedCorner()
    {
        return this.selectedCorner;
    }

    /** 所属维度；null 表示未知，渲染不得据此隐藏线框 */
    @Nullable
    public String getWorld()
    {
        return this.world;
    }

    public void setWorld(@Nullable String world)
    {
        this.world = world;
    }

    public void setPos1(@Nullable BlockPos pos)
    {
        this.pos1 = pos;
        this.updateSize();
    }

    public void setPos2(@Nullable BlockPos pos)
    {
        this.pos2 = pos;
        this.updateSize();
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public void setSelectedCorner(Corner corner)
    {
        this.selectedCorner = corner;
    }

    private void updateSize()
    {
        if (this.pos1 != null && this.pos2 != null)
        {
            BlockPos diff = this.pos2.subtract(this.pos1);
            int x = diff.getX() >= 0 ? diff.getX() + 1 : diff.getX() - 1;
            int y = diff.getY() >= 0 ? diff.getY() + 1 : diff.getY() - 1;
            int z = diff.getZ() >= 0 ? diff.getZ() + 1 : diff.getZ() - 1;
            this.size = new BlockPos(x, y, z);
        }
        else if (this.pos1 == null && this.pos2 == null)
        {
            this.size = BlockPos.ZERO;
        }
        else
        {
            this.size = new BlockPos(1, 1, 1);
        }
    }

    public BlockPos getPosition(Corner corner)
    {
        return corner == Corner.CORNER_1 ? this.getPos1() : this.getPos2();
    }

    public int getCoordinate(Corner corner, CoordinateType type)
    {
        BlockPos pos = this.getPosition(corner);

        switch (type)
        {
            case X: return pos.getX();
            case Y: return pos.getY();
            case Z: return pos.getZ();
        }

        return 0;
    }

    protected void setPosition(BlockPos pos, Corner corner)
    {
        if (corner == Corner.CORNER_1)
        {
            this.setPos1(pos);
        }
        else if (corner == Corner.CORNER_2)
        {
            this.setPos2(pos);
        }
    }

    public void setCoordinate(int value, Corner corner, CoordinateType type)
    {
        BlockPos pos = this.getPosition(corner);
        switch (type)
        {
            case X -> pos = new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> pos = new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> pos = new BlockPos(pos.getX(), pos.getY(), value);
        }
        this.setPosition(pos, corner);
    }
}
