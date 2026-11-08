package net.syncmaterial.syncmaterial.gametest.foundation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry;

public class FoundationServerGameTest {
    @GameTest(structure = "empty")
    public void runtimeUsesOnlyTheNewDatabase(GameTestHelper helper) {
        var runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        helper.assertTrue(runtime != null && runtime.isOpen(), Component.literal("新 runtime 应已初始化"));
        helper.assertTrue(SyncMaterial.getSharedDatabase() == null, Component.literal("新后端不能打开旧数据库"));
        helper.assertTrue(SyncMaterial.getServerStagingAreaManager() == null, Component.literal("首批不能启用旧扫描器"));
        helper.succeed();
    }

    @GameTest(structure = "empty")
    public void materialClaimLoopPreservesIdentityOnUpdate(GameTestHelper helper) {
        var runtime = ServerRuntimeRegistry.get(helper.getLevel().getServer());
        String id = "foundation-" + UUID.randomUUID();
        PlayerIdentity owner = new PlayerIdentity(UUID.randomUUID(), "Owner");
        PlayerIdentity participant = new PlayerIdentity(UUID.randomUUID(), "Participant");
        var before = new SchematicMetadata(id, "Before", Path.of("before.litematic"), "old", owner);
        try {
            runtime.store().ingest(before, List.of(new MaterialCount("minecraft:stone", 64))).join();
            int materialId = runtime.store().findProject(id).join().orElseThrow().materials().get(0).id();
            helper.assertTrue(runtime.store().join(id, materialId, participant).join(), Component.literal("认领应成功"));
            runtime.connected(participant.uuid());
            runtime.reportInventory(id, materialId, participant, 32).join();
            helper.assertTrue(runtime.inventoryCount(participant.uuid(), id, materialId) == 32, Component.literal("在线持有量应可读取"));
            runtime.store().ingest(new SchematicMetadata(id, "After", Path.of("after.litematic"), "new", owner),
                    List.of(new MaterialCount("minecraft:stone", 128))).join();
            var updated = runtime.store().snapshot(id).join().orElseThrow();
            helper.assertTrue(updated.project().materials().get(0).id() == materialId, Component.literal("更新不能改变保留物品的 ID"));
            helper.assertTrue(updated.claims().size() == 1, Component.literal("更新不能删除保留物品的认领"));
            runtime.disconnected(participant.uuid());
            helper.assertTrue(runtime.inventoryCount(participant.uuid(), id, materialId) == 0, Component.literal("离线立即不计库存"));
            helper.assertTrue(runtime.leave(id, materialId, participant).join(), Component.literal("退出应成功"));
            helper.assertTrue(runtime.store().claims(id).join().isEmpty(), Component.literal("退出后认领应为空"));
        } finally {
            runtime.store().removeProject(id).join();
        }
        helper.succeed();
    }
}
