package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.syncmaterial.syncmaterial.adapter.minecraft.FoundationAreaEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * New block entities do not call {@code setChanged}, so a newly placed chest would
 * otherwise stay invisible until a full chunk rescan. Observes the existing-map insertion
 * only; it never mutates, cancels or loads anything.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockEntity", at = @At("TAIL"))
    private void onBlockEntityInserted(BlockEntity blockEntity, CallbackInfo ci) {
        if (!(blockEntity instanceof Container)) {
            return;
        }
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide()) {
            return;
        }
        LevelChunk self = (LevelChunk) (Object) this;
        BlockPos pos = blockEntity.getBlockPos();
        if (self.getBlockEntities().get(pos) != blockEntity) {
            return;
        }
        FoundationAreaEvents.containerChanged((ServerLevel) blockEntity.getLevel(), pos);
    }
}
