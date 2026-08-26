//? if >=26 {
package net.syncmaterial.syncmaterial;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.syncmaterial.syncmaterial.api.MaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.DefaultMaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicFolderWatcher;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncMaterial implements ModInitializer {
    public static final String MOD_ID = "syncmaterial";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MaterialStatisticsEngine statisticsEngine;
    private static SchematicDatabase sharedDatabase;
    private static DatabaseQueryService sharedQueryService;
    private static LitematicaParser sharedParser;
    private static CollaborationManager sharedCollaborationManager;
    private static StagingAreaManager sharedStagingAreaManager;

    @Override
    public void onInitialize() {
        LOGGER.info("SyncMaterial 初始化中...");

        ModNetworkHandler.registerPayloadTypes();

        statisticsEngine = new DefaultMaterialStatisticsEngine();

        // 2. 注册服务端启动事件，用于初始化数据库
        // 注意：这个事件在单人游戏（集成服务器）中也会触发
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("SyncMaterial 服务端组件初始化中...");

            try {
                // 初始化数据库服务
                sharedDatabase = new SchematicDatabase();
                sharedDatabase.initialize();

                sharedQueryService = new DatabaseQueryService(sharedDatabase);
                sharedParser = new DefaultLitematicaParser(new ParsingThreadPool());
                sharedCollaborationManager = new CollaborationManager(sharedDatabase);
                sharedStagingAreaManager = new StagingAreaManager(sharedDatabase);
                sharedStagingAreaManager.setServer(server);
                sharedCollaborationManager.setStagingAreaManager(sharedStagingAreaManager);
                sharedCollaborationManager.loadAllInventories();

                ModNetworkHandler.initializeServices(sharedQueryService, sharedCollaborationManager);
                ModNetworkHandler.register();

                final int[] tickCounter = {0};
                ServerTickEvents.END_SERVER_TICK.register(s -> {
                    tickCounter[0]++;
                    if (tickCounter[0] >= 4) {
                        tickCounter[0] = 0;
                        if (sharedStagingAreaManager != null) {
                            sharedStagingAreaManager.processDirtyContainers();
                        }
                    }
                });

                LOGGER.info("SyncMaterial 服务端组件初始化完成！");
            } catch (Exception e) {
                LOGGER.error("SyncMaterial 服务端组件初始化失败", e);
                // 在单人游戏中，我们不应该崩溃游戏
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                    throw new RuntimeException("Failed to initialize SyncMaterial server components", e);
                }
            }
        });

        // 3. 初始数据推送不在 JOIN 时进行。
        // 玩家刚进服时服务端还不知道对方装没装本 mod、是什么协议版本，
        // 盲推会给原版客户端白发一堆读不懂的包。改由客户端握手触发，
        // 见 ModNetworkHandler.registerHandshakeReceiver。

        // 4. 注册玩家断开连接事件，清理订阅
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            net.syncmaterial.syncmaterial.network.ModNetworkHandler.onPlayerDisconnect(handler.player);
        });

        // 5. 注册服务器关闭事件，释放资源
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("SyncMaterial 服务端组件正在关闭...");
            try {
                if (sharedCollaborationManager != null) {
                    sharedCollaborationManager = null;
                }
                if (sharedStagingAreaManager != null) {
                    sharedStagingAreaManager = null;
                }
                if (sharedQueryService != null) {
                    sharedQueryService = null;
                }
                if (sharedParser != null) {
                    sharedParser = null;
                }
                if (sharedDatabase != null) {
                    sharedDatabase.close();
                    sharedDatabase = null;
                }
                LOGGER.info("SyncMaterial 服务端组件已关闭");
            } catch (Exception e) {
                LOGGER.error("关闭 SyncMaterial 服务端组件失败", e);
            }
        });

        // 6. 服务端启动完成后，监控 placements.json
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                // 服务端根目录 (FabricLoader.getInstance().getGameDir())
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                
                // placements.json 在 world/syncmatica/ 目录
                java.nio.file.Path worldPath = server.getSavePath(net.minecraft.server.level.ServerLevel.ROOT);
                java.nio.file.Path syncamaticaFolder = worldPath.resolve("syncmatica");
                
                // 原理图文件在服务端根目录的 /syncmatics/ 文件夹
                java.nio.file.Path syncmaticsRootFolder = gameDir.resolve("syncmatics");

                SchematicFolderWatcher watcher = new SchematicFolderWatcher(
                    syncamaticaFolder, syncmaticsRootFolder, sharedDatabase, sharedQueryService, sharedParser);
                watcher.setServer(server);
                watcher.start();

                LOGGER.info("原理图监控已启动 (placements: {}, files: {})", syncamaticaFolder, syncmaticsRootFolder);

                // 延迟 5 秒后全量扫描所有备货区，确保 watcher 已完成初始解析
                server.execute(() -> {
                    // 延迟到下一个 tick 再用 scheduledExecutor
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                        server.execute(() -> {
                            if (sharedStagingAreaManager != null) {
                                int count = 0;
                                for (var entry : SchematicFolderWatcher.placementNames.entrySet()) {
                                    String schematicId = entry.getKey();
                                    var areas = sharedStagingAreaManager.getStagingAreas(schematicId);
                                    for (var area : areas) {
                                        sharedStagingAreaManager.rescanStagingArea(area.id());
                                        count++;
                                    }
                                }
                                LOGGER.info("启动时全量扫描完成: {} 个备货区", count);

                                // Phase 5: 加载全局仓库并扫描
                                sharedStagingAreaManager.loadWarehousesFromDb();
                                for (var wh : sharedStagingAreaManager.getAllWarehouses()) {
                                    sharedStagingAreaManager.rescanWarehouseAndMarkChunks(wh.id());
                                }
                                LOGGER.info("启动时全局仓库加载完成: {} 个仓库", sharedStagingAreaManager.getAllWarehouses().size());
                            }
                        });
                    });
                });
            } catch (Exception e) {
                LOGGER.error("启动原理图监控失败", e);
            }
        });

        // Phase 5: 区块加载时扫描仓库/备货区箱子
        ServerChunkEvents.CHUNK_LOAD.register((serverWorld, chunk) -> {
            if (sharedStagingAreaManager == null) return;
            MinecraftServer srv = serverWorld.getServer();
            srv.execute(() -> {
                sharedStagingAreaManager.scanChunkForContainerAreas(chunk, serverWorld);
            });
        });

        LOGGER.info("SyncMaterial 初始化完成！");
    }

    /**
     * 获取材料统计引擎的公开 API。
     */
    public static MaterialStatisticsEngine getStatisticsEngine() {
        return statisticsEngine;
    }

    public static StagingAreaManager getServerStagingAreaManager() {
        return sharedStagingAreaManager;
    }

    /**
     * 获取共享数据库实例。
     */
    public static SchematicDatabase getSharedDatabase() {
        return sharedDatabase;
    }

    /**
     * 获取共享数据库查询服务实例。
     */
    public static DatabaseQueryService getSharedQueryService() {
        return sharedQueryService;
    }

    /**
     * 获取共享解析器实例。
     */
    public static LitematicaParser getSharedParser() {
        return sharedParser;
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
//?} else {
package net.syncmaterial.syncmaterial;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.syncmaterial.syncmaterial.api.MaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.DefaultMaterialStatisticsEngine;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;
import net.syncmaterial.syncmaterial.engine.impl.DefaultLitematicaParser;
import net.syncmaterial.syncmaterial.engine.internal.ParsingThreadPool;
import net.syncmaterial.syncmaterial.network.ModNetworkHandler;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.SchematicFolderWatcher;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncMaterial implements ModInitializer {
    public static final String MOD_ID = "syncmaterial";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MaterialStatisticsEngine statisticsEngine;
    private static SchematicDatabase sharedDatabase;
    private static DatabaseQueryService sharedQueryService;
    private static LitematicaParser sharedParser;
    private static CollaborationManager sharedCollaborationManager;
    private static StagingAreaManager sharedStagingAreaManager;

    @Override
    public void onInitialize() {
        LOGGER.info("SyncMaterial 初始化中...");

        ModNetworkHandler.registerPayloadTypes();

        statisticsEngine = new DefaultMaterialStatisticsEngine();

        // 2. 注册服务端启动事件，用于初始化数据库
        // 注意：这个事件在单人游戏（集成服务器）中也会触发
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("SyncMaterial 服务端组件初始化中...");

            try {
                // 初始化数据库服务
                sharedDatabase = new SchematicDatabase();
                sharedDatabase.initialize();

                sharedQueryService = new DatabaseQueryService(sharedDatabase);
                sharedParser = new DefaultLitematicaParser(new ParsingThreadPool());
                sharedCollaborationManager = new CollaborationManager(sharedDatabase);
                sharedStagingAreaManager = new StagingAreaManager(sharedDatabase);
                sharedStagingAreaManager.setServer(server);
                sharedCollaborationManager.setStagingAreaManager(sharedStagingAreaManager);
                sharedCollaborationManager.loadAllInventories();

                ModNetworkHandler.initializeServices(sharedQueryService, sharedCollaborationManager);
                ModNetworkHandler.register();

                final int[] tickCounter = {0};
                ServerTickEvents.END_SERVER_TICK.register(s -> {
                    tickCounter[0]++;
                    if (tickCounter[0] >= 4) {
                        tickCounter[0] = 0;
                        if (sharedStagingAreaManager != null) {
                            sharedStagingAreaManager.processDirtyContainers();
                        }
                    }
                });

                LOGGER.info("SyncMaterial 服务端组件初始化完成！");
            } catch (Exception e) {
                LOGGER.error("SyncMaterial 服务端组件初始化失败", e);
                // 在单人游戏中，我们不应该崩溃游戏
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                    throw new RuntimeException("Failed to initialize SyncMaterial server components", e);
                }
            }
        });

        // 3. 初始数据推送不在 JOIN 时进行。
        // 玩家刚进服时服务端还不知道对方装没装本 mod、是什么协议版本，
        // 盲推会给原版客户端白发一堆读不懂的包。改由客户端握手触发，
        // 见 ModNetworkHandler.registerHandshakeReceiver。

        // 4. 注册玩家断开连接事件，清理订阅
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            net.syncmaterial.syncmaterial.network.ModNetworkHandler.onPlayerDisconnect(handler.player);
        });

        // 5. 注册服务器关闭事件，释放资源
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("SyncMaterial 服务端组件正在关闭...");
            try {
                if (sharedCollaborationManager != null) {
                    sharedCollaborationManager = null;
                }
                if (sharedStagingAreaManager != null) {
                    sharedStagingAreaManager = null;
                }
                if (sharedQueryService != null) {
                    sharedQueryService = null;
                }
                if (sharedParser != null) {
                    sharedParser = null;
                }
                if (sharedDatabase != null) {
                    sharedDatabase.close();
                    sharedDatabase = null;
                }
                LOGGER.info("SyncMaterial 服务端组件已关闭");
            } catch (Exception e) {
                LOGGER.error("关闭 SyncMaterial 服务端组件失败", e);
            }
        });

        // 6. 服务端启动完成后，监控 placements.json
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                // 服务端根目录 (FabricLoader.getInstance().getGameDir())
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                
                // placements.json 在 world/syncmatica/ 目录
                java.nio.file.Path worldPath = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
                java.nio.file.Path syncamaticaFolder = worldPath.resolve("syncmatica");
                
                // 原理图文件在服务端根目录的 /syncmatics/ 文件夹
                java.nio.file.Path syncmaticsRootFolder = gameDir.resolve("syncmatics");

                SchematicFolderWatcher watcher = new SchematicFolderWatcher(
                    syncamaticaFolder, syncmaticsRootFolder, sharedDatabase, sharedQueryService, sharedParser);
                watcher.setServer(server);
                watcher.start();

                LOGGER.info("原理图监控已启动 (placements: {}, files: {})", syncamaticaFolder, syncmaticsRootFolder);

                // 延迟 5 秒后全量扫描所有备货区，确保 watcher 已完成初始解析
                server.execute(() -> {
                    // 延迟到下一个 tick 再用 scheduledExecutor
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                        server.execute(() -> {
                            if (sharedStagingAreaManager != null) {
                                int count = 0;
                                for (var entry : SchematicFolderWatcher.placementNames.entrySet()) {
                                    String schematicId = entry.getKey();
                                    var areas = sharedStagingAreaManager.getStagingAreas(schematicId);
                                    for (var area : areas) {
                                        sharedStagingAreaManager.rescanStagingArea(area.id());
                                        count++;
                                    }
                                }
                                LOGGER.info("启动时全量扫描完成: {} 个备货区", count);

                                // Phase 5: 加载全局仓库并扫描
                                sharedStagingAreaManager.loadWarehousesFromDb();
                                for (var wh : sharedStagingAreaManager.getAllWarehouses()) {
                                    sharedStagingAreaManager.rescanWarehouseAndMarkChunks(wh.id());
                                }
                                LOGGER.info("启动时全局仓库加载完成: {} 个仓库", sharedStagingAreaManager.getAllWarehouses().size());
                            }
                        });
                    });
                });
            } catch (Exception e) {
                LOGGER.error("启动原理图监控失败", e);
            }
        });

        // Phase 5: 区块加载时扫描仓库/备货区箱子
        ServerChunkEvents.CHUNK_LOAD.register((serverWorld, chunk) -> {
            if (sharedStagingAreaManager == null) return;
            MinecraftServer srv = serverWorld.getServer();
            srv.execute(() -> {
                sharedStagingAreaManager.scanChunkForInventoryAreas(chunk, serverWorld);
            });
        });

        LOGGER.info("SyncMaterial 初始化完成！");
    }

    /**
     * 获取材料统计引擎的公开 API。
     */
    public static MaterialStatisticsEngine getStatisticsEngine() {
        return statisticsEngine;
    }

    public static StagingAreaManager getServerStagingAreaManager() {
        return sharedStagingAreaManager;
    }

    /**
     * 获取共享数据库实例。
     */
    public static SchematicDatabase getSharedDatabase() {
        return sharedDatabase;
    }

    /**
     * 获取共享数据库查询服务实例。
     */
    public static DatabaseQueryService getSharedQueryService() {
        return sharedQueryService;
    }

    /**
     * 获取共享解析器实例。
     */
    public static LitematicaParser getSharedParser() {
        return sharedParser;
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
//?}
