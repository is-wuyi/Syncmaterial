package net.syncmaterial.syncmaterial.mixin;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicUploadListener;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to register schematic upload listener when syncmatica initializes.
 * Uses string-based class names to avoid compile-time dependencies.
 */
@Mixin(targets = "ch.endte.syncmatica.litematica.LitematicManager", remap = false)
public class SyncmaticaIntegrationMixin {

    @Inject(method = "setContext", at = @At("TAIL"), remap = false)
    private void onSyncmaticaInit(Object context, CallbackInfo ci) {
        try {
            SyncMaterial.LOGGER.info("开始初始化SyncMaterial服务端组件...");

            // Initialize our components
            SchematicDatabase database = new SchematicDatabase();
            database.initialize();
            SyncMaterial.LOGGER.info("数据库初始化完成");

            net.syncmaterial.syncmaterial.server.DatabaseQueryService queryService =
                new net.syncmaterial.syncmaterial.server.DatabaseQueryService(database);

            LitematicaParser parser = new DefaultLitematicaParser(null); // TODO: proper thread pool
            SchematicUploadListener listener = new SchematicUploadListener(database, queryService, parser);

            // Register with syncmatica using reflection
            Object syncManager = context.getClass().getMethod("getSyncmaticManager").invoke(context);
            if (syncManager != null) {
                SyncMaterial.LOGGER.info("找到Syncmatica syncManager，尝试注册监听器...");
                syncManager.getClass().getMethod("addServerPlacementConsumer", java.util.function.Consumer.class)
                    .invoke(syncManager, listener);

                SyncMaterial.LOGGER.info("SyncMaterial原理图上传监听器已注册到Syncmatica");
            } else {
                SyncMaterial.LOGGER.error("未找到Syncmatica syncManager");
            }

        } catch (Exception e) {
            SyncMaterial.LOGGER.error("注册SyncMaterial监听器失败", e);
        }
    }
}