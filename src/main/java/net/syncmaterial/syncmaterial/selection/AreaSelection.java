//? if >=26 {
/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: removed Litematica-specific dependencies, adapted for server-side storage.
 */

package net.syncmaterial.syncmaterial.selection;

import java.util.*;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;

import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class AreaSelection
{
    protected final Map<String, Box> subRegionBoxes;
    protected final Map<String, Integer> serverIdMap;
    protected boolean originSelected;
    protected BlockPos calculatedOrigin;
    protected boolean calculatedOriginDirty;
    @Nullable protected BlockPos explicitOrigin;
    @Nullable protected String currentBox;

    public AreaSelection()
    {
        this.subRegionBoxes = new HashMap<>();
        this.serverIdMap = new HashMap<>();
        this.calculatedOrigin = BlockPos.ZERO;
        this.calculatedOriginDirty = true;
        this.explicitOrigin = null;
    }

    public Integer getServerId(String boxName)
    {
        return this.serverIdMap.get(boxName);
    }

    public void setServerId(String boxName, int serverId)
    {
        this.serverIdMap.put(boxName, serverId);
    }

    public void removeServerId(String boxName)
    {
        this.serverIdMap.remove(boxName);
    }

    public void clearServerIds()
    {
        this.serverIdMap.clear();
    }

    protected void markDirty()
    {
        this.calculatedOriginDirty = true;
    }

    @Nullable
    public String getCurrentSubRegionBoxName()
    {
        return this.currentBox;
    }

    public boolean setSelectedSubRegionBox(@Nullable String name)
    {
        if (name == null || this.subRegionBoxes.containsKey(name))
        {
            this.currentBox = name;
            return true;
        }

        return false;
    }

    public void setOriginSelected(boolean selected)
    {
        this.originSelected = selected;
    }

    /**
     * Returns the effective origin point. This is the explicit origin point, if one has been set,
     * otherwise it's an automatically calculated origin point, located at the minimum corner
     * of all the boxes.
     * @return ()
     */
    public BlockPos getEffectiveOrigin()
    {
        if (this.explicitOrigin != null)
        {
            return this.explicitOrigin;
        }
        else
        {
            if (this.calculatedOriginDirty)
            {
                this.updateCalculatedOrigin();
            }

            return this.calculatedOrigin;
        }
    }

    public void setExplicitOrigin(@Nullable BlockPos origin)
    {
        this.explicitOrigin = origin;

        if (origin == null)
        {
            this.originSelected = false;
        }
    }

    protected void updateCalculatedOrigin()
    {
        this.calculatedOrigin = BlockPos.ZERO;
        this.calculatedOriginDirty = false;
    }

    @Nullable
    public Box getSubRegionBox(String name)
    {
        return this.subRegionBoxes.get(name);
    }

    @Nullable
    public Box getSelectedSubRegionBox()
    {
        return this.currentBox != null ? this.subRegionBoxes.get(this.currentBox) : null;
    }

    public Collection<String> getAllSubRegionNames()
    {
        return this.subRegionBoxes.keySet();
    }

    public List<Box> getAllSubRegionBoxes()
    {
        return ImmutableList.copyOf(this.subRegionBoxes.values());
    }

    public ImmutableMap<String, Box> getAllSubRegions()
    {
        ImmutableMap.Builder<String, Box> builder = ImmutableMap.builder();
        builder.putAll(this.subRegionBoxes);
        return builder.build();
    }

    @Nullable
    public String createNewSubRegionBox(BlockPos pos1, final String nameIn)
    {
        this.clearCurrentSelectedCorner();
        this.setOriginSelected(false);

        String name = nameIn;
        int i = 1;

        while (this.subRegionBoxes.containsKey(name))
        {
            name = nameIn + " " + i;
            i++;
        }

        Box box = new Box();
        box.setName(name);
        box.setSelectedCorner(Corner.CORNER_1);
        this.currentBox = name;
        this.subRegionBoxes.put(name, box);
        this.setSubRegionCornerPos(box, Corner.CORNER_1, pos1);
        this.setSubRegionCornerPos(box, Corner.CORNER_2, pos1);

        return name;
    }

    public void clearCurrentSelectedCorner()
    {
        this.setCurrentSelectedCorner(Corner.NONE);
    }

    public void setCurrentSelectedCorner(Corner corner)
    {
        Box box = this.getSelectedSubRegionBox();

        if (box != null)
        {
            box.setSelectedCorner(corner);
        }
    }

    /**
     * Adds the given SelectionBox, if either replace is true, or there isn't yet a box by the same name.
     * @param box ()
     * @param replace ()
     * @return true if the box was successfully added, false if replace was false and there was already a box with the same name
     */
    public boolean addSubRegionBox(Box box, boolean replace)
    {
        if (replace || this.subRegionBoxes.containsKey(box.getName()) == false)
        {
            this.subRegionBoxes.put(box.getName(), box);
            this.markDirty();
            return true;
        }

        return false;
    }

    public boolean removeSubRegionBox(String name)
    {
        boolean success = this.subRegionBoxes.remove(name) != null;
        this.markDirty();

        if (success && name.equals(this.currentBox))
        {
            this.currentBox = null;
        }

        return success;
    }

    public boolean renameSubRegionBox(String oldName, String newName)
    {
        Box box = this.subRegionBoxes.get(oldName);

        if (box != null)
        {
            if (this.subRegionBoxes.containsKey(newName))
            {
                return false;
            }

            this.subRegionBoxes.remove(oldName);
            box.setName(newName);
            this.subRegionBoxes.put(newName, box);

            if (this.currentBox != null && this.currentBox.equals(oldName))
            {
                this.currentBox = newName;
            }

            return true;
        }

        return false;
    }

    public void setSelectedSubRegionCornerPos(BlockPos pos, Corner corner)
    {
        Box box = this.getSelectedSubRegionBox();

        if (box != null)
        {
            this.setSubRegionCornerPos(box, corner, pos);
        }
    }

    public void setSubRegionCornerPos(Box box, Corner corner, BlockPos pos)
    {
        if (corner == Corner.CORNER_1)
        {
            box.setPos1(pos);
            this.markDirty();
        }
        else if (corner == Corner.CORNER_2)
        {
            box.setPos2(pos);
            this.markDirty();
        }
    }

    public void setCoordinate(@Nullable Box box, Corner corner, CoordinateType type, int value)
    {
        if (box != null && corner != null && corner != Corner.NONE)
        {
            box.setCoordinate(value, corner, type);
            this.markDirty();
        }
        else if (this.explicitOrigin != null)
        {
            switch (type)
            {
                case X -> this.setExplicitOrigin(new BlockPos(value, this.explicitOrigin.getY(), this.explicitOrigin.getZ()));
                case Y -> this.setExplicitOrigin(new BlockPos(this.explicitOrigin.getX(), value, this.explicitOrigin.getZ()));
                case Z -> this.setExplicitOrigin(new BlockPos(this.explicitOrigin.getX(), this.explicitOrigin.getY(), value));
            }
        }
    }

    public BlockPos getSubRegionCornerPos(Box box, Corner corner)
    {
        return corner == Corner.CORNER_2 ? box.getPos2() : box.getPos1();
    }
}
//?} else {
/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public License v3 (LGPL-3.0).
 * Original code from Litematica by masa (https://github.com/sakura-kyoko/litematica)
 * Licensed under LGPL-3.0: https://www.gnu.org/licenses/lgpl-3.0.html
 * Modified for SyncMaterial: removed Litematica-specific dependencies, adapted for server-side storage.
 */

package net.syncmaterial.syncmaterial.selection;

import java.util.*;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;
import fi.dy.masa.litematica.util.PositionUtils.Corner;

public class AreaSelection
{
    protected final Map<String, Box> subRegionBoxes;
    protected final Map<String, Integer> serverIdMap;
    protected boolean originSelected;
    protected BlockPos calculatedOrigin;
    protected boolean calculatedOriginDirty;
    @Nullable protected BlockPos explicitOrigin;
    @Nullable protected String currentBox;

    public AreaSelection()
    {
        this.subRegionBoxes = new HashMap<>();
        this.serverIdMap = new HashMap<>();
        this.calculatedOrigin = BlockPos.ORIGIN;
        this.calculatedOriginDirty = true;
        this.explicitOrigin = null;
    }

    public Integer getServerId(String boxName)
    {
        return this.serverIdMap.get(boxName);
    }

    public void setServerId(String boxName, int serverId)
    {
        this.serverIdMap.put(boxName, serverId);
    }

    public void removeServerId(String boxName)
    {
        this.serverIdMap.remove(boxName);
    }

    public void clearServerIds()
    {
        this.serverIdMap.clear();
    }

    protected void markDirty()
    {
        this.calculatedOriginDirty = true;
    }

    @Nullable
    public String getCurrentSubRegionBoxName()
    {
        return this.currentBox;
    }

    public boolean setSelectedSubRegionBox(@Nullable String name)
    {
        if (name == null || this.subRegionBoxes.containsKey(name))
        {
            this.currentBox = name;
            return true;
        }

        return false;
    }

    public void setOriginSelected(boolean selected)
    {
        this.originSelected = selected;
    }

    /**
     * Returns the effective origin point. This is the explicit origin point, if one has been set,
     * otherwise it's an automatically calculated origin point, located at the minimum corner
     * of all the boxes.
     * @return ()
     */
    public BlockPos getEffectiveOrigin()
    {
        if (this.explicitOrigin != null)
        {
            return this.explicitOrigin;
        }
        else
        {
            if (this.calculatedOriginDirty)
            {
                this.updateCalculatedOrigin();
            }

            return this.calculatedOrigin;
        }
    }

    public void setExplicitOrigin(@Nullable BlockPos origin)
    {
        this.explicitOrigin = origin;

        if (origin == null)
        {
            this.originSelected = false;
        }
    }

    protected void updateCalculatedOrigin()
    {
        this.calculatedOrigin = BlockPos.ORIGIN;
        this.calculatedOriginDirty = false;
    }

    @Nullable
    public Box getSubRegionBox(String name)
    {
        return this.subRegionBoxes.get(name);
    }

    @Nullable
    public Box getSelectedSubRegionBox()
    {
        return this.currentBox != null ? this.subRegionBoxes.get(this.currentBox) : null;
    }

    public Collection<String> getAllSubRegionNames()
    {
        return this.subRegionBoxes.keySet();
    }

    public List<Box> getAllSubRegionBoxes()
    {
        return ImmutableList.copyOf(this.subRegionBoxes.values());
    }

    public ImmutableMap<String, Box> getAllSubRegions()
    {
        ImmutableMap.Builder<String, Box> builder = ImmutableMap.builder();
        builder.putAll(this.subRegionBoxes);
        return builder.build();
    }

    @Nullable
    public String createNewSubRegionBox(BlockPos pos1, final String nameIn)
    {
        this.clearCurrentSelectedCorner();
        this.setOriginSelected(false);

        String name = nameIn;
        int i = 1;

        while (this.subRegionBoxes.containsKey(name))
        {
            name = nameIn + " " + i;
            i++;
        }

        Box box = new Box();
        box.setName(name);
        box.setSelectedCorner(Corner.CORNER_1);
        this.currentBox = name;
        this.subRegionBoxes.put(name, box);
        this.setSubRegionCornerPos(box, Corner.CORNER_1, pos1);
        this.setSubRegionCornerPos(box, Corner.CORNER_2, pos1);

        return name;
    }

    public void clearCurrentSelectedCorner()
    {
        this.setCurrentSelectedCorner(Corner.NONE);
    }

    public void setCurrentSelectedCorner(Corner corner)
    {
        Box box = this.getSelectedSubRegionBox();

        if (box != null)
        {
            box.setSelectedCorner(corner);
        }
    }

    /**
     * Adds the given SelectionBox, if either replace is true, or there isn't yet a box by the same name.
     * @param box ()
     * @param replace ()
     * @return true if the box was successfully added, false if replace was false and there was already a box with the same name
     */
    public boolean addSubRegionBox(Box box, boolean replace)
    {
        if (replace || this.subRegionBoxes.containsKey(box.getName()) == false)
        {
            this.subRegionBoxes.put(box.getName(), box);
            this.markDirty();
            return true;
        }

        return false;
    }

    public boolean removeSubRegionBox(String name)
    {
        boolean success = this.subRegionBoxes.remove(name) != null;
        this.markDirty();

        if (success && name.equals(this.currentBox))
        {
            this.currentBox = null;
        }

        return success;
    }

    public boolean renameSubRegionBox(String oldName, String newName)
    {
        Box box = this.subRegionBoxes.get(oldName);

        if (box != null)
        {
            if (this.subRegionBoxes.containsKey(newName))
            {
                return false;
            }

            this.subRegionBoxes.remove(oldName);
            box.setName(newName);
            this.subRegionBoxes.put(newName, box);

            if (this.currentBox != null && this.currentBox.equals(oldName))
            {
                this.currentBox = newName;
            }

            return true;
        }

        return false;
    }

    public void setSelectedSubRegionCornerPos(BlockPos pos, Corner corner)
    {
        Box box = this.getSelectedSubRegionBox();

        if (box != null)
        {
            this.setSubRegionCornerPos(box, corner, pos);
        }
    }

    public void setSubRegionCornerPos(Box box, Corner corner, BlockPos pos)
    {
        if (corner == Corner.CORNER_1)
        {
            box.setPos1(pos);
            this.markDirty();
        }
        else if (corner == Corner.CORNER_2)
        {
            box.setPos2(pos);
            this.markDirty();
        }
    }

    public void setCoordinate(@Nullable Box box, Corner corner, CoordinateType type, int value)
    {
        if (box != null && corner != null && corner != Corner.NONE)
        {
            box.setCoordinate(value, corner, type);
            this.markDirty();
        }
        else if (this.explicitOrigin != null)
        {
            switch (type)
            {
                case X -> this.setExplicitOrigin(new BlockPos(value, this.explicitOrigin.getY(), this.explicitOrigin.getZ()));
                case Y -> this.setExplicitOrigin(new BlockPos(this.explicitOrigin.getX(), value, this.explicitOrigin.getZ()));
                case Z -> this.setExplicitOrigin(new BlockPos(this.explicitOrigin.getX(), this.explicitOrigin.getY(), value));
            }
        }
    }

    public BlockPos getSubRegionCornerPos(Box box, Corner corner)
    {
        return corner == Corner.CORNER_2 ? box.getPos2() : box.getPos1();
    }
}
//?}
