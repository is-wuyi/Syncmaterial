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
        BlockEntity self = (BlockEntity) (Object) this;

        if (!(self instanceof Inventory)) {
            return;
        }

        if (self.getWorld() == null) {
            return;
        }

        if (self.getWorld().isClient()) {
            return;
        }

        ServerWorld world = (ServerWorld) self.getWorld();
        BlockPos pos = self.getPos();

        StagingAreaManager manager = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (manager == null) {
            return;
        }

        if (manager.isInAnyStagingArea(pos, world)) {
            manager.scheduleContainerScan(pos, world);
        }
    }

    @Inject(method = "markRemoved", at = @At("HEAD"))
    private void onMarkRemoved(net.minecraft.world.World world, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;

        if (!(self instanceof Inventory)) {
            return;
        }

        if (world.isClient()) {
            return;
        }

        ServerWorld serverWorld = (ServerWorld) world;
        BlockPos pos = self.getPos();

        StagingAreaManager manager = net.syncmaterial.syncmaterial.SyncMaterial.getServerStagingAreaManager();
        if (manager == null) {
            return;
        }

        if (manager.isInAnyStagingArea(pos, serverWorld)) {
            manager.onContainerRemoved(pos, serverWorld);
        }
    }
}