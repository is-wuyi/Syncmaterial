package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.adapter.minecraft.FoundationAreaEvents;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void onMarkDirty(CallbackInfo ci) {
        this.withServerContainerContext((world, pos) -> {
            var owner = LegacyServerRegistry.get(world.getServer());
            if (owner != null && owner.staging().isInAnyContainerArea(pos, world)) {
                owner.staging().scheduleContainerScan(pos, world);
            }
            FoundationAreaEvents.containerChanged(world, pos);
        });
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void onMarkRemoved(CallbackInfo ci) {
        this.withServerContainerContext((world, pos) -> {
            var owner = LegacyServerRegistry.get(world.getServer());
            if (owner != null && owner.staging().isInAnyContainerArea(pos, world)) {
                owner.staging().onContainerRemoved(pos, world);
            }
            FoundationAreaEvents.containerRemovalSuspected(world, pos);
        });
    }

    private void withServerContainerContext(java.util.function.BiConsumer<ServerLevel, BlockPos> action) {
        BlockEntity self = (BlockEntity) (Object) this;

        if (!(self instanceof Container)) {
            return;
        }

        if (self.getLevel() == null || self.getLevel().isClientSide()) {
            return;
        }

        action.accept((ServerLevel) self.getLevel(), self.getBlockPos());
    }
}
