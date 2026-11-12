package net.syncmaterial.syncmaterial;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.syncmaterial.syncmaterial.adapter.fabric.ServerEventAdapter;
import net.syncmaterial.syncmaterial.adapter.minecraft.FoundationAreaEvents;
import net.syncmaterial.syncmaterial.adapter.minecraft.MinecraftAreaSnapshotSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.syncmaterial.syncmaterial.api.MaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncMaterial implements ModInitializer {
    public static final String MOD_ID = "syncmaterial";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("SyncMaterial 初始化中...");

        ModNetworkHandler.registerPayloadTypes();
        if (net.syncmaterial.syncmaterial.server.runtime.BackendMode.isFoundationEnabled()) {
            registerFoundationRuntime();
            return;
        }

        ModNetworkHandler.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                LegacyServerContext context = LegacyServerContext.open(server,
                        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                        FabricLoader.getInstance().getGameDir());
                LegacyServerRegistry.install(context);
                LOGGER.info("SyncMaterial 服务端组件初始化完成: {}", context.epoch());
            } catch (RuntimeException error) {
                LOGGER.error("SyncMaterial 服务端组件初始化失败", error);
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                    throw new IllegalStateException("Failed to initialize SyncMaterial server components", error);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LegacyServerContext context = LegacyServerRegistry.get(server);
            if (context != null) context.start();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LegacyServerContext context = LegacyServerRegistry.get(server);
            if (context != null) context.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ModNetworkHandler.onPlayerDisconnect(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(LegacyServerRegistry::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(LegacyServerRegistry::close);
        ServerEventAdapter.onChunkLoaded((world, chunk) -> {
            LegacyServerContext context = LegacyServerRegistry.get(world.getServer());
            if (context != null) context.execute(() -> context.staging().scanChunkForContainerAreas(chunk, world));
        });

        LOGGER.info("SyncMaterial 初始化完成！");
    }

    private void registerFoundationRuntime() {
        ModNetworkHandler.register();
        ServerEventAdapter.onChunkLoaded(FoundationAreaEvents::chunkLoaded);
        ServerEventAdapter.onChunkUnloaded(FoundationAreaEvents::chunkUnloaded);
        ServerTickEvents.END_SERVER_TICK.register(FoundationAreaEvents::drain);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            java.nio.file.Path gameRoot = FabricLoader.getInstance().getGameDir();
            try {
                var runtime = net.syncmaterial.syncmaterial.server.runtime.ServerRuntime.open(worldRoot, gameRoot,
                        new net.syncmaterial.syncmaterial.adapter.litematica.LitematicMaterialDecoder(),
                        projectId -> net.syncmaterial.syncmaterial.network.FoundationNetworkAdapter.broadcast(server, projectId),
                        new net.syncmaterial.syncmaterial.server.ingest.SyncmaticaDiscoveryAdapter());
                net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry.install(server, runtime);
                net.syncmaterial.syncmaterial.network.ProtocolHandshake.registerServer(server);
                // Bind the passive source before the first tick. It never loads a chunk:
                // only Fabric's already-loaded cache and getChunkNow read existing state.
                runtime.areas().attachSource(new MinecraftAreaSnapshotSource(server));
                LOGGER.info("实验后端已启用，使用独立新库: {}", worldRoot.resolve("syncmaterial/runtime-v2.sqlite"));
            } catch (Exception error) {
                throw new IllegalStateException("实验后端初始化失败，不会回退写入旧数据库", error);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var runtime = net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry.get(server);
            if (runtime != null) {
                runtime.startDiscovery(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                        FabricLoader.getInstance().getGameDir().resolve("syncmatics"));
                // Re-attach after world setup: the source now reports every chunk that the
                // initial load already produced, without triggering active loading.
                runtime.areas().attachSource(new MinecraftAreaSnapshotSource(server));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var runtime = net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry.get(server);
            if (runtime != null) runtime.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ModNetworkHandler.onPlayerDisconnect(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            net.syncmaterial.syncmaterial.network.ProtocolHandshake.clear(server);
            FoundationAreaEvents.clear(server);
            net.syncmaterial.syncmaterial.server.runtime.ServerRuntimeRegistry.close(server);
        });
    }

    /**
     * 获取材料统计引擎的公开 API。
     */
    public static MaterialStatisticsEngine getStatisticsEngine() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.engine();
    }

    public static StagingAreaManager getServerStagingAreaManager() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.staging();
    }

    /**
     * 获取共享数据库实例。
     */
    public static SchematicDatabase getSharedDatabase() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.database();
    }

    /**
     * 获取共享数据库查询服务实例。
     */
    public static DatabaseQueryService getSharedQueryService() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.queryService();
    }

    /**
     * 获取共享协作管理器实例。
     */
    public static CollaborationManager getSharedCollaborationManager() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.collaboration();
    }

    /**
     * 获取共享解析器实例。
     */
    public static LitematicaParser getSharedParser() {
        LegacyServerContext context = LegacyServerRegistry.onlyOpenContext();
        return context == null ? null : context.parser();
    }

    /**
     * 本 mod 的版本号，从 Fabric 元数据读取，避免与 gradle.properties 里的版本号双份维护。
     * 仅用于握手时展示给用户，任何判断逻辑都应依赖 ProtocolVersion。
     */
    public static String getModVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
