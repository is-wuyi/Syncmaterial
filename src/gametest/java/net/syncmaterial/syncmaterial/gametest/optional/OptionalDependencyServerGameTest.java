package net.syncmaterial.syncmaterial.gametest.optional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.adapter.litematica.LitematicMaterialDecoder;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.runtime.BackendMode;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

/**
 * Dedicated-server acceptance test for a runtime where every optional dependency
 * (MaLiLib, Litematica, Syncmatica) is physically absent.
 *
 * <p>The external runner strips those jars from the classpath and additionally
 * disables them with {@code fabric.debug.disableModIds}. This class still asserts
 * the absence explicitly: if an optional mod is ever loaded, the test must fail
 * instead of silently testing the wrong environment.</p>
 *
 * <p>Only Minecraft, Fabric Loader and SyncMaterial's own classes are referenced.
 * The .litematic fixture is written with native NBT so decoding does not require
 * any optional mod class.</p>
 */
public class OptionalDependencyServerGameTest {
    private static final List<String> OPTIONAL_MODS = List.of("malilib", "litematica", "syncmatica");
    private static final String STONE_ITEM_ID = "minecraft:stone";

    @GameTest(structure = "empty")
    public void optional_dependency_server_game_test_uses_own_backend(GameTestHelper helper) {
        assertOptionalModsMissing(helper);

        var server = helper.getLevel().getServer();
        if (BackendMode.isFoundationEnabled()) {
            ServerRuntime runtime = ServerRuntimeRegistry.get(server);
            helper.assertTrue(runtime != null, Component.literal("foundation 模式必须安装新 runtime"));
            helper.assertTrue(runtime.isOpen(), Component.literal("新 runtime 必须处于打开状态"));
            helper.assertTrue(SyncMaterial.getSharedDatabase() == null,
                    Component.literal("foundation 模式不得打开旧数据库"));
        } else {
            var database = SyncMaterial.getSharedDatabase();
            helper.assertTrue(database != null, Component.literal("legacy 模式必须初始化旧数据库"));
            helper.assertTrue(ServerRuntimeRegistry.get(server) == null,
                    Component.literal("legacy 模式不得安装新 runtime"));
            try (var result = database.executeQuery("SELECT 1")) {
                helper.assertTrue(result.next() && result.getInt(1) == 1,
                        Component.literal("legacy 数据库必须可查询"));
            } catch (SQLException error) {
                throw new AssertionError("legacy 数据库不可查询", error);
            }
        }

        helper.succeed();
    }

    @GameTest(structure = "empty")
    public void optional_dependency_server_game_test_decodes_real_litematic(GameTestHelper helper) {
        assertOptionalModsMissing(helper);

        var server = helper.getLevel().getServer();
        String projectId = "optional-dependency-" + UUID.randomUUID();
        ServerRuntime runtime = BackendMode.isFoundationEnabled()
                ? ServerRuntimeRegistry.get(server) : null;
        Path schematicFile = null;
        try {
            schematicFile = Files.createTempFile("syncmaterial-optional-", ".litematic");
            CompoundTag root = stoneLitematic();
            helper.assertTrue(root.getIntOr("MinecraftDataVersion", -1) == currentDataVersion(),
                    Component.literal("合成 NBT 必须声明当前 DataVersion"));
            NbtIo.writeCompressed(root, schematicFile);

            var decoded = new LitematicMaterialDecoder().decode(schematicFile);
            long decodedStone = decoded.stream()
                    .filter(material -> STONE_ITEM_ID.equals(material.itemId()))
                    .mapToLong(MaterialCount::count)
                    .sum();
            helper.assertTrue(decodedStone == 4,
                    Component.literal("独立解码器应数出 4 个 stone，实际 " + decodedStone
                            + "（材料: " + decoded + "）"));

            if (BackendMode.isFoundationEnabled()) {
                helper.assertTrue(runtime != null && runtime.isOpen(),
                        Component.literal("foundation 模式必须安装可用的新 runtime"));
                var owner = new PlayerIdentity(UUID.randomUUID(), "OptionalDependencyTest");
                runtime.ingest(new SchematicMetadata(projectId, "optional", schematicFile,
                        "optional-" + UUID.randomUUID(), owner)).join();
                var stored = runtime.store().findProject(projectId).join().orElseThrow();
                long storedStone = stored.materials().stream()
                        .filter(material -> STONE_ITEM_ID.equals(material.itemId()))
                        .mapToLong(material -> material.required())
                        .sum();
                helper.assertTrue(storedStone == 4,
                        Component.literal("runtime.ingest 应把 4 个 stone 写入新库，实际 " + storedStone));
            }
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError("真实 .litematic 解析验收失败", failure);
        } finally {
            if (runtime != null) runtime.removeProject(projectId).join();
            if (schematicFile != null) {
                try {
                    Files.deleteIfExists(schematicFile);
                } catch (IOException failure) {
                    throw new AssertionError("无法清理临时 .litematic 文件", failure);
                }
            }
        }

        helper.succeed();
    }

    private static void assertOptionalModsMissing(GameTestHelper helper) {
        for (String modId : OPTIONAL_MODS) {
            helper.assertTrue(!FabricLoader.getInstance().isModLoaded(modId),
                    Component.literal("缺可选依赖验收环境不得加载 " + modId));
        }
    }

    /**
     * 4 格全 stone 的区域。调色板只有一项时 Litematica 仍按 2 bit/格打包，
     * 因此 4 格 = 8 bit = 1 个全零 long。
     */
    private static CompoundTag stoneLitematic() {
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));

        CompoundTag size = new CompoundTag();
        size.putInt("x", 4);
        size.putInt("y", 1);
        size.putInt("z", 1);

        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);

        CompoundTag region = new CompoundTag();
        region.put("Size", size);
        region.put("Position", position);
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", new LongArrayTag(new long[] {0L}));

        CompoundTag regions = new CompoundTag();
        regions.put("main", region);

        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", currentDataVersion());
        root.putInt("Version", 7);
        root.put("Regions", regions);
        return root;
    }

    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }
}
