package net.syncmaterial.syncmaterial.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "markDirty", at = @At("HEAD"))
    private void onMarkDirty(CallbackInfo ci) {
        StagingAreaManager mgr = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (mgr != null) {
            this.withStagingAreaContext(mgr, mgr::scheduleContainerScan);
        }
    }

    @Inject(method = "markRemoved", at = @At("HEAD"))
    private void onMarkRemoved(CallbackInfo ci) {
        StagingAreaManager mgr = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (mgr != null) {
            this.withStagingAreaContext(mgr, mgr::onContainerRemoved);
        }
    }

    private void withStagingAreaContext(StagingAreaManager mgr, java.util.function.BiConsumer<BlockPos, ServerWorld> action) {
        BlockEntity self = (BlockEntity) (Object) this;

        if (!(self instanceof Inventory)) {
            return;
        }

        if (self.getWorld() == null || self.getWorld().isClient()) {
            return;
        }

        ServerWorld world = (ServerWorld) self.getWorld();
        BlockPos pos = self.getPos();

        if (mgr.isInAnyInventoryArea(pos, world)) {
            action.accept(pos, world);
        }
    }
}