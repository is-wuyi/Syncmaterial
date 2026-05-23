package net.syncmaterial.syncmaterial.client.gui;

import net.minecraft.util.math.BlockPos;

public class StagingAreaData {
    private int id;
    private String name;
    private BlockPos pos1;
    private BlockPos pos2;
    private String world;

    public StagingAreaData(int id, String name, BlockPos pos1, BlockPos pos2, String world) {
        this.id = id;
        this.name = name;
        this.pos1 = pos1 != null ? pos1 : BlockPos.ORIGIN;
        this.pos2 = pos2 != null ? pos2 : BlockPos.ORIGIN;
        this.world = world;
    }

    public static StagingAreaData fromEntry(StagingAreaEntry entry) {
        return new StagingAreaData(
                entry.areaId(),
                entry.name(),
                new BlockPos(entry.x1(), entry.y1(), entry.z1()),
                new BlockPos(entry.x2(), entry.y2(), entry.z2()),
                entry.world()
        );
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BlockPos getPos1() { return pos1; }
    public void setPos1(BlockPos pos1) { this.pos1 = pos1; }
    public BlockPos getPos2() { return pos2; }
    public void setPos2(BlockPos pos2) { this.pos2 = pos2; }
    public String getWorld() { return world; }

    public BlockPos getMinPos() {
        return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
    }

    public BlockPos getMaxPos() {
        return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );
    }

    public int getSizeX() { return Math.abs(pos2.getX() - pos1.getX()) + 1; }
    public int getSizeY() { return Math.abs(pos2.getY() - pos1.getY()) + 1; }
    public int getSizeZ() { return Math.abs(pos2.getZ() - pos1.getZ()) + 1; }
}
