//? if <26.2 {
package net.syncmaterial.syncmaterial.adapter.litematica;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pick-stack context containing one schematic state, never a live game world. */
final class MaterialStateView implements LevelReader {
    private final BlockState state;

    MaterialStateView(BlockState state) {
        this.state = Objects.requireNonNull(state);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return BlockPos.ZERO.equals(pos) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getMinY() { return 0; }

    @Override
    public int getHeight() { return 1; }

    @Override
    public boolean isClientSide() { return true; }

    @Override
    public boolean hasChunk(int x, int z) { return false; }

    @Override
    public RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Override
    public FeatureFlagSet enabledFeatures() { return FeatureFlags.VANILLA_SET; }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) { throw unsupported(); }

    @Override
    public BlockGetter getChunkForCollisions(int x, int z) { throw unsupported(); }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) { throw unsupported(); }

    @Override
    public int getSkyDarken() { throw unsupported(); }

    @Override
    public BiomeManager getBiomeManager() { throw unsupported(); }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { throw unsupported(); }

    @Override
    public int getSeaLevel() { throw unsupported(); }

    @Override
    public DimensionType dimensionType() { throw unsupported(); }

    @Override
    public float getShade(Direction direction, boolean shade) { throw unsupported(); }

    @Override
    public LevelLightEngine getLightEngine() { throw unsupported(); }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) { throw unsupported(); }

    @Override
    public WorldBorder getWorldBorder() { throw unsupported(); }

    @Override
    public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Material pick-stack cannot access chunks, entities or world metadata");
    }
}
//?}
