package net.syncmaterial.syncmaterial.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.engine.LitematicaParser;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SchematicFolderWatcher {
    private final Path syncamaticaFolder;
    private final Path placementsFile;
    private final Path syncmaticsRootFolder;
    // volatile：主线程 start()/stop() 写入，watch 线程读取
    private volatile WatchService watchService;
    private final ExecutorService watchExecutor;
    private final ExecutorService parseExecutor;
    private final SchematicDatabase database;
    private final DatabaseQueryService queryService;
    private final LitematicaParser parser;
    // volatile：主线程 setServer() 写入，watch 线程在广播时读取
    private volatile MinecraftServer server;
    private final Gson gson = new Gson();
    private final Set<String> processedHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, String> hashToSchematicId = new ConcurrentHashMap<>();
    public static final Map<String, String> placementNames = new ConcurrentHashMap<>();

    public SchematicFolderWatcher(Path syncamaticaFolder, Path syncmaticsRootFolder,
                                   SchematicDatabase database, 
                                   DatabaseQueryService queryService, LitematicaParser parser) {
        this.syncamaticaFolder = syncamaticaFolder;
        this.placementsFile = syncamaticaFolder.resolve("placements.json");
        this.syncmaticsRootFolder = syncmaticsRootFolder;
        this.database = database;
        this.queryService = queryService;
        this.parser = parser;
        // daemon 线程：避免 stop() 后残留线程阻止 JVM 退出（测试环境/服务器关服）
        this.watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "syncmaterial-watch");
            t.setDaemon(true);
            return t;
        });
        this.parseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "syncmaterial-parse");
            t.setDaemon(true);
            return t;
        });
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void start() {
        try {
            if (!Files.exists(syncamaticaFolder)) {
                Files.createDirectories(syncamaticaFolder);
                SyncMaterial.LOGGER.info("创建配置目录: {}", syncamaticaFolder);
            }
            if (!Files.exists(syncmaticsRootFolder)) {
                Files.createDirectories(syncmaticsRootFolder);
                SyncMaterial.LOGGER.info("创建原理图目录: {}", syncmaticsRootFolder);
            }

            // 先扫描现有的 placements
            scanExistingPlacements();

            this.watchService = FileSystems.getDefault().newWatchService();
            syncamaticaFolder.register(watchService, 
                StandardWatchEventKinds.ENTRY_MODIFY);

            watchExecutor.submit(this::watchLoop);
            SyncMaterial.LOGGER.info("原理图监控已启动: {}", syncamaticaFolder);
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("启动原理图监控失败", e);
        }
    }

    private void scanExistingPlacements() {
        try {
            if (Files.exists(placementsFile)) {
                processPlacementsJson();
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("扫描现有 placements 失败", e);
        }
    }

    private void watchLoop() {
        while ( true) {
            try {
                WatchKey key = watchService.take();
                boolean needsProcess = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        SyncMaterial.LOGGER.info("检测到 OVERFLOW 事件");
                        continue;
                    }

                    Path filename = (Path) event.context();
                    String filenameStr = filename.toString();
                    SyncMaterial.LOGGER.info("检测到文件变化: {} (kind: {})", filenameStr, kind);
                    
                    // placements.json.new 是 syncmatica 写入的临时文件
                    if (filenameStr.equals("placements.json") || filenameStr.equals("placements.json.new")) {
                        needsProcess = true;
                    }
                }
                // 去抖：一批 pollEvents 只触发一次处理
                if (needsProcess) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    processPlacementsJson();
                }
                key.reset();
            } catch (InterruptedException e) {
                SyncMaterial.LOGGER.info("原理图监控线程被中断");
                break;
            } catch (ClosedWatchServiceException e) {
                // stop() 关闭了 WatchService，正常退出循环
                SyncMaterial.LOGGER.info("原理图监控服务已关闭");
                break;
            } catch (Exception e) {
                SyncMaterial.LOGGER.error("监控循环出错", e);
            }
        }
    }

    private synchronized void processPlacementsJson() {
        SyncMaterial.LOGGER.debug("开始处理 placements.json...");

        // 优先读取 placements.json，如果不存在则读取 placements.json.new
        Path actualFile = Files.exists(placementsFile) ? placementsFile : syncamaticaFolder.resolve("placements.json.new");
        
        if (!Files.exists(actualFile)) {
            SyncMaterial.LOGGER.info("placements.json 文件不存在");
            return;
        }

        try {
            String content = Files.readString(actualFile);
            JsonObject root = gson.fromJson(content, JsonObject.class);
            JsonArray placements = root.getAsJsonArray("placements");

            SyncMaterial.LOGGER.debug("placements 数组大小: {}", placements.size());

            Set<String> currentHashes = new HashSet<>();

            for (var element : placements) {
                JsonObject p = element.getAsJsonObject();
                String id = p.get("id").getAsString();
                String hash = p.get("hash").getAsString();
                String displayName = p.has("display_name") ? p.get("display_name").getAsString() : "unknown";
                String fileName = p.has("file_name") ? p.get("file_name").getAsString() : "";
                
                JsonObject ownerObj = p.getAsJsonObject("owner");
                String ownerName = "unknown";
                if (ownerObj != null && ownerObj.has("name")) {
                    ownerName = ownerObj.get("name").getAsString();
                }

                currentHashes.add(hash);
                placementNames.put(id, displayName);
                hashToSchematicId.put(hash, id);
                SyncMaterial.LOGGER.debug("发现 placement: id={}, hash={}, name={}", id, hash, displayName);

                // 检查数据库中是否已存在该原理图的材料记录
                boolean existsInDb = queryService.schematicExists(id);

                if (existsInDb) {
                    // 检查文件是否已更新（hash 变化）
                    String storedHash = getStoredHash(id);
                    if (storedHash != null && !storedHash.equals(hash)) {
                        SyncMaterial.LOGGER.info("原理图文件已更新: {} (hash: {} → {})", displayName, storedHash, hash);
                        deleteSchematicRecords(id);
                        // 继续下方的解析流程
                    } else {
                        processedHashes.add(hash);
                        continue;
                    }
                }

                // 查找实际的 litematic 文件
                Path litematicFile = findLitematicFile(hash);
                SyncMaterial.LOGGER.debug("findLitematicFile 结果: {} for hash {}", litematicFile, hash);
                if (litematicFile != null) {
                    processNewSchematic(id, hash, displayName, ownerName, litematicFile);
                } else {
                    SyncMaterial.LOGGER.warn("未找到 hash={} 对应的 litematic 文件", hash);
                }
            }

            // 检测被删除的原理图并从数据库中删除
            SyncMaterial.LOGGER.debug("processedHashes: {}, currentHashes: {}", processedHashes, currentHashes);
            
            Set<String> removedHashes = new HashSet<>(processedHashes);
            removedHashes.removeAll(currentHashes);
            
            SyncMaterial.LOGGER.debug("removedHashes: {}", removedHashes);
            
            for (String removedHash : removedHashes) {
                String schematicId = hashToSchematicId.get(removedHash);
                if (schematicId != null) {
                    try {
                        database.deleteSchematicRecords(schematicId);
                    } catch (Exception e) {
                        SyncMaterial.LOGGER.error("删除原理图记录失败: {}", schematicId, e);
                    }
                    SyncMaterial.LOGGER.info("已删除原理图材料记录: {} (hash: {})", schematicId, removedHash);

                    // 通知所有客户端清理对应的备货区渲染数据
                    notifyClientsStagingAreaRemoved(schematicId);
                    placementNames.remove(schematicId);
                } else {
                    SyncMaterial.LOGGER.warn("未找到 hash={} 对应的 schematicId，跳过清理", removedHash);
                }
                hashToSchematicId.remove(removedHash);
                processedHashes.remove(removedHash);
            }

        } catch (Exception e) {
            SyncMaterial.LOGGER.error("解析 placements.json 失败", e);
        }
    }

    private String getStoredHash(String schematicId) {
        try (var rs = database.executeQuery("SELECT file_hash FROM schematics WHERE id = ?", schematicId)) {
            if (rs.next()) {
                String h = rs.getString("file_hash");
                return (h != null && !h.isEmpty()) ? h : null;
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.warn("获取原理图 hash 失败: {}", schematicId);
        }
        return null;
    }

    private void deleteSchematicRecords(String schematicId) {
        try {
            database.deleteSchematicRecords(schematicId);
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("删除旧原理图记录失败: {}", schematicId, e);
        }
    }

    private Path findLitematicFile(String hash) {
        try (var dir = Files.list(syncmaticsRootFolder)) {
            for (Path file : dir.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(hash) && name.endsWith(".litematic")) {
                    return file;
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("查找 litematic 文件失败: {}", hash, e);
        }
        return null;
    }

    private void processNewSchematic(String id, String hash, String displayName, String owner, Path filePath) {
        SyncMaterial.LOGGER.debug("processNewSchematic 被调用: id={}, hash={}", id, hash);
        
        // processedHashes 快速去重，避免重复解析同一文件。
        // Set.add 的返回值本身是原子的存在性判断，无需 contains + add 两步
        if (!processedHashes.add(hash)) {
            SyncMaterial.LOGGER.debug("hash 已在处理队列中，跳过: {}", hash);
            return;
        }
        hashToSchematicId.put(hash, id);

        SyncMaterial.LOGGER.debug("准备提交异步任务...");
        parseExecutor.submit(() -> {
            SyncMaterial.LOGGER.debug("异步任务开始执行: {}", id);
            try {
                // 在异步任务中再次检查数据库，防止并发重复插入
                if (queryService.schematicExists(id)) {
                    SyncMaterial.LOGGER.debug("原理图已存在于数据库，跳过: {}", id);
                    return;
                }
                
                SyncMaterial.LOGGER.debug("检测到新原理图: {} (hash: {})", displayName, hash);
                var materials = parser.parseAsync(filePath.toString()).join();
                SyncMaterial.LOGGER.debug("解析完成, 材料数量: {}", materials.size());

                database.executeUpdate(
                    "INSERT OR IGNORE INTO schematics (id, name, file_path, uploaded_by, file_hash) VALUES (?, ?, ?, ?, ?)",
                    id,
                    displayName,
                    filePath.toString(),
                    owner,
                    hash
                );

                // 聚合相同材料
                Map<String, Long> aggregatedMaterials = new java.util.HashMap<>();
                for (var entry : materials) {
                    if (entry.getStack().isEmpty()) {
                        continue;
                    }
                    // 安全：注册表在游戏启动后冻结为只读，可在任意线程读取
                    String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).toString();
                    long count = entry.getCountTotal();
                    aggregatedMaterials.merge(itemId, count, Long::sum);
                }

                // 存储聚合后的材料：清空旧记录与写入新记录必须原子完成，
                // 否则中途失败会留下残缺的材料清单（且 hash 已入库导致不再重试）。
                // 不复用 database.deleteSchematicRecords()：它自带 begin/commit，
                // 嵌套调用会提前提交外层事务并使 rollback 失效。
                database.beginTransaction();
                try {
                    database.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", id);

                    for (var entry : aggregatedMaterials.entrySet()) {
                        String itemId = entry.getKey();
                        long count = entry.getValue();
                        int countInt = (int) Math.min(count, Integer.MAX_VALUE);

                        // 快照语义：材料清单是"该原理图需要多少"的声明，重复写入应覆盖而非累加
                        database.executeUpdate(
                            "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?) " +
                            "ON CONFLICT(schematic_id, item_id) DO UPDATE SET count = excluded.count",
                            id, itemId, countInt
                        );
                    }
                    database.commitTransaction();
                } catch (Exception e) {
                    database.rollbackTransaction();
                    throw e;
                }

                SyncMaterial.LOGGER.info("原理图处理完成: {} ({} 项材料)", displayName, aggregatedMaterials.size());
            } catch (Throwable t) {
                SyncMaterial.LOGGER.error("处理原理图失败: {}", displayName, t);
                // 解析失败必须撤销 hash 标记，否则该原理图直到服务端重启前都不会再被尝试解析
                processedHashes.remove(hash);
            }
        });
    }

    /**
     * 向所有在线客户端广播备货区移除通知（确保在主线程发送网络包）
     */
    private void notifyClientsStagingAreaRemoved(String schematicId) {
        // 取局部变量：避免 null 检查与后续使用之间字段被并发改写（TOCTOU）
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        // 在后台线程调用，必须切到主线程发送网络包
        srv.execute(() -> {
            try {
                var packet = new net.syncmaterial.syncmaterial.network.StagingAreaConfigResponseS2CPacket(
                    "SCHEMATIC_DELETED", schematicId, "", true, "", java.util.List.of());
                for (var player : srv.getPlayerList().getPlayers()) {
                    // 没握手过说明对方没装本 mod（或版本被拒），不必白发
                    if (!net.syncmaterial.syncmaterial.network.ProtocolHandshake.hasHandshaked(player)) {
                        continue;
                    }
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, packet);
                }
                SyncMaterial.LOGGER.info("已通知客户端清理备货区渲染: {}", schematicId);
            } catch (Exception e) {
                SyncMaterial.LOGGER.error("通知客户端失败: {}", schematicId, e);
            }
        });
    }

    public void stop() {
        try {
            WatchService ws = this.watchService;
            if (ws != null) {
                ws.close();
            }
            watchExecutor.shutdownNow();
            parseExecutor.shutdownNow();
            
            // 等待任务完成
            if (!watchExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                SyncMaterial.LOGGER.warn("watchExecutor 未能在 5 秒内完成");
            }
            if (!parseExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                SyncMaterial.LOGGER.warn("parseExecutor 未能在 5 秒内完成");
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("停止监控失败", e);
        }
    }
}
