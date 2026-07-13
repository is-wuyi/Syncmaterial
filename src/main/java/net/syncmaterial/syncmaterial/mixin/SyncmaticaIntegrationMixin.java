package net.syncmaterial.syncmaterial.mixin;

import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicUploadListener;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
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
            SyncMaterial.LOGGER.info("检测到 Syncmatica 初始化，注册原理图上传监听器...");

            // 使用 SyncMaterial 中的单例数据库实例，避免重复初始化
            net.syncmaterial.syncmaterial.server.DatabaseQueryService queryService =
                SyncMaterial.getSharedQueryService();

            if (queryService == null) {
                SyncMaterial.LOGGER.warn("DatabaseQueryService 尚未初始化，跳过监听器注册");
                return;
            }

            LitematicaParser parser = SyncMaterial.getSharedParser();
            SchematicDatabase database = SyncMaterial.getSharedDatabase();

            if (database == null || parser == null) {
                SyncMaterial.LOGGER.warn("数据库或解析器尚未初始化，跳过监听器注册");
                return;
            }

            // Register with syncmatica using reflection
            Object syncManager = context.getClass().getMethod("getSyncmaticManager").invoke(context);
            if (syncManager != null) {
                SyncMaterial.LOGGER.info("找到Syncmatica syncManager，尝试注册监听器...");
                SchematicUploadListener listener = new SchematicUploadListener(database, queryService, parser);
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
