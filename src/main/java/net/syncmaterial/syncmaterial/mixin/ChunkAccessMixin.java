package net.syncmaterial.syncmaterial.mixin;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the chunk's pending-NBT map. {@code LevelChunk} inherits the field from
 * {@link ChunkAccess}, so the accessor must be applied to the declaring class; targeting
 * {@code LevelChunk} directly cannot resolve inherited fields. No method reads or promotes
 * the pending block entity.
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessMixin {
    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> syncmaterial$pendingBlockEntities();
}
