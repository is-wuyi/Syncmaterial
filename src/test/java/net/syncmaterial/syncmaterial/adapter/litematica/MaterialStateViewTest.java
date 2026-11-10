//? if <26.2 {
package net.syncmaterial.syncmaterial.adapter.litematica;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialStateViewTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exposesOnlyTheSuppliedStateWithoutContainersOrChunks() {
        BlockState state = Blocks.STONE.defaultBlockState();
        var view = new MaterialStateView(state);
        assertSame(state, view.getBlockState(BlockPos.ZERO));
        assertTrue(view.getBlockState(BlockPos.ZERO.above()).isAir());
        assertSame(state.getFluidState(), view.getFluidState(BlockPos.ZERO));
        assertNull(view.getBlockEntity(BlockPos.ZERO));
        assertFalse(view.hasChunk(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> view.getChunk(0, 0, ChunkStatus.FULL, true));
        assertThrows(UnsupportedOperationException.class, view::getBiomeManager);
        assertThrows(UnsupportedOperationException.class, view::dimensionType);
        assertSame(BuiltInRegistries.ITEM, view.registryAccess().lookupOrThrow(Registries.ITEM));
    }

    @Test
    void pickStackCanReadTheSchematicStateButNotARealWorld() {
        var id = ResourceKey.create(Registries.BLOCK, ResourceLocation.parse("syncmaterial:test_pick_context"));
        Block block = new Block(BlockBehaviour.Properties.of().setId(id)) {
            @Override
            protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
                assertNotNull(level);
                assertEquals(BlockPos.ZERO, pos);
                assertSame(state, level.getBlockState(pos));
                assertNull(level.getBlockEntity(pos));
                assertFalse(includeData);
                return new ItemStack(Items.STONE);
            }
        };
        var state = block.defaultBlockState();
        assertTrue(state.getCloneItemStack(new MaterialStateView(state), BlockPos.ZERO, false).is(Items.STONE));
    }

    @Test
    void stemsResolveTheirSeedFromTheNativeRegistry() {
        assertEquals("minecraft:pumpkin_seeds", MaterialResolver.resolve(Blocks.PUMPKIN_STEM.defaultBlockState(), 1).get(0).itemId());
        assertEquals("minecraft:melon_seeds", MaterialResolver.resolve(Blocks.ATTACHED_MELON_STEM.defaultBlockState(), 1).get(0).itemId());
    }
}
//?}
