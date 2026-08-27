package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void onMarkDirty(CallbackInfo ci) {
        StagingAreaManager mgr = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (mgr != null) {
            this.withStagingAreaContext(mgr, mgr::scheduleContainerScan);
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void onMarkRemoved(CallbackInfo ci) {
        StagingAreaManager mgr = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (mgr != null) {
            this.withStagingAreaContext(mgr, mgr::onContainerRemoved);
        }
    }

    private void withStagingAreaContext(StagingAreaManager mgr, java.util.function.BiConsumer<BlockPos, ServerLevel> action) {
        BlockEntity self = (BlockEntity) (Object) this;

        if (!(self instanceof Container)) {
            return;
        }

        if (self.getLevel() == null || self.getLevel().isClientSide()) {
            return;
        }

        ServerLevel world = (ServerLevel) self.getLevel();
        BlockPos pos = self.getBlockPos();

        if (mgr.isInAnyContainerArea(pos, world)) {
            action.accept(pos, world);
        }
    }
}
