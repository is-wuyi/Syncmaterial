package net.syncmaterial.syncmaterial.gametest.foundation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialResolver;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Client-only golden oracle: compares {@link MaterialResolver} with the actual
 * Litematica 0.28.5 material-list implementation loaded at runtime.
 *
 * <p>Litematica is an optional compile-time/runtime dependency of the test
 * environment only. This class must never be referenced by production code or
 * by the dedicated-server path.</p>
 */
public class MaterialResolverOracleClientGameTest implements FabricClientGameTest {
    private static final long OCCURRENCES = 3L;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            // Exercises both MaterialCache#getRequiredBuildItemForState and
            // MaterialCache#getItems via MaterialListUtils#getMaterialList.
            fi.dy.masa.litematica.materials.MaterialCache.getInstance().clearCache();

            assertRule(Blocks.STONE.defaultBlockState());
            assertRule(Blocks.AIR.defaultBlockState());
            assertRule(Blocks.CAVE_AIR.defaultBlockState());
            assertRule(Blocks.VOID_AIR.defaultBlockState());

            assertRule(Blocks.FARMLAND.defaultBlockState());
            assertRule(Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState());
            assertRule(Blocks.RED_MUSHROOM_BLOCK.defaultBlockState());
            assertRule(Blocks.PISTON_HEAD.defaultBlockState());
            assertRule(Blocks.MOVING_PISTON.defaultBlockState());
            assertRule(Blocks.NETHER_PORTAL.defaultBlockState());
            assertRule(Blocks.END_PORTAL.defaultBlockState());
            assertRule(Blocks.END_GATEWAY.defaultBlockState());

            assertRule(Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            assertRule(Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            assertRule(Blocks.BED.red().defaultBlockState()
                    .setValue(BedBlock.PART, BedPart.FOOT));
            assertRule(Blocks.BED.red().defaultBlockState()
                    .setValue(BedBlock.PART, BedPart.HEAD));
            assertRule(Blocks.TALL_GRASS.defaultBlockState()
                    .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
            assertRule(Blocks.TALL_GRASS.defaultBlockState()
                    .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

            assertRule(Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.DOUBLE));
            assertRule(Blocks.SNOW.defaultBlockState()
                    .setValue(SnowLayerBlock.LAYERS, 5));
            assertRule(Blocks.TURTLE_EGG.defaultBlockState()
                    .setValue(TurtleEggBlock.EGGS, 3));
            assertRule(Blocks.SEA_PICKLE.defaultBlockState()
                    .setValue(SeaPickleBlock.PICKLES, 4));
            assertRule(Blocks.CANDLE.defaultBlockState()
                    .setValue(CandleBlock.CANDLES, 4));
            assertRule(Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true));

            BlockState twoFaces = Blocks.GLOW_LICHEN.defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true)
                    .setValue(MultifaceBlock.getFaceProperty(Direction.UP), true);
            assertRule(twoFaces);

            assertRule(Blocks.WATER.defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 0));
            assertRule(Blocks.WATER.defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 1));
            assertRule(Blocks.LAVA.defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 0));
            assertRule(Blocks.LAVA.defaultBlockState()
                    .setValue(LiquidBlock.LEVEL, 1));

            assertRule(Blocks.FLOWER_POT.defaultBlockState());
            assertRule(Blocks.POTTED_POPPY.defaultBlockState());
            assertRule(Blocks.CAULDRON.defaultBlockState());
            assertRule(Blocks.LAVA_CAULDRON.defaultBlockState());
            assertRule(Blocks.POWDER_SNOW_CAULDRON.defaultBlockState());
            assertRule(Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 1));
            assertRule(Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 2));
            assertRule(Blocks.WATER_CAULDRON.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));

            assertRule(Blocks.OAK_SIGN.defaultBlockState());
            assertRule(Blocks.OAK_WALL_SIGN.defaultBlockState());
            assertRule(Blocks.OAK_HANGING_SIGN.defaultBlockState());
            assertRule(Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState());
        });
    }

    private static void assertRule(BlockState state) {
        Map<String, Long> expected = expectedFromLitematica(state);
        Map<String, Long> actual = new HashMap<>();
        for (MaterialCount material : MaterialResolver.resolve(state, OCCURRENCES)) {
            actual.merge(material.itemId(), material.count(), Long::sum);
        }
        if (!expected.equals(actual)) {
            throw new AssertionError("Material rule mismatch for " + state
                    + " (occurrences=" + OCCURRENCES + ")"
                    + "\n  Litematica=" + expected
                    + "\n  resolver  =" + actual);
        }
    }

    /**
     * Runs the same public material-list entry point Litematica's GUI uses for
     * one state, then aggregates by item registry id (the foundation store's
     * key) so component-only grouping differences do not affect the oracle.
     */
    private static Map<String, Long> expectedFromLitematica(BlockState state) {
        Object2IntOpenHashMap<BlockState> required = new Object2IntOpenHashMap<>();
        required.put(state, (int) OCCURRENCES);
        List<fi.dy.masa.litematica.materials.MaterialListEntry> entries =
                fi.dy.masa.litematica.materials.MaterialListUtils.getMaterialList(
                        required,
                        new Object2IntOpenHashMap<>(),
                        new Object2IntOpenHashMap<>(),
                        null);
        Map<String, Long> counts = new HashMap<>();
        for (var entry : entries) {
            String itemId = BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString();
            counts.merge(itemId, (long) entry.getCountTotal(), Long::sum);
        }
        return counts;
    }
}
