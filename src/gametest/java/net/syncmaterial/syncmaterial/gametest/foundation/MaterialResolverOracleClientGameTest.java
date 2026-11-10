/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.syncmaterial.gametest.foundation;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.syncmaterial.shared.litematic.MaterialRuleCases;
import net.syncmaterial.syncmaterial.adapter.litematica.MaterialResolver;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Client-only golden oracle: compares {@link MaterialResolver} with the actual
 * Litematica material-list implementation loaded at runtime.
 *
 * <p>The corpus lives in {@link MaterialRuleCases} as neutral block-state
 * descriptors, so the same list runs against Litematica 0.23.7 on 1.21.x and
 * 0.28.5 on 26.2. Litematica is an optional compile-time/runtime dependency of
 * the test environment only. This class must never be referenced by production
 * code or by the dedicated-server path.</p>
 */
public class MaterialResolverOracleClientGameTest implements FabricClientGameTest {
    private static final long OCCURRENCES = 3L;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            context.waitTicks(20);
            context.runOnClient(client -> {
                fi.dy.masa.litematica.materials.MaterialCache.getInstance().clearCache();
                for (String descriptor : MaterialRuleCases.BLOCK_STATE_DESCRIPTORS) {
                    assertRule(parseDescriptor(descriptor));
                }
            });
        }
    }

    private static BlockState parseDescriptor(String descriptor) {
        try {
            StringReader reader = new StringReader(descriptor);
            BlockStateParser.BlockResult result =
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false);
            if (reader.canRead()) {
                throw new AssertionError("Trailing input in oracle descriptor: " + descriptor);
            }
            return result.blockState();
        } catch (CommandSyntaxException error) {
            throw new AssertionError("Invalid oracle descriptor: " + descriptor, error);
        }
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
