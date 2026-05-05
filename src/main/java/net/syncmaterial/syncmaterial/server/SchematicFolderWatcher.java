package net.syncmaterial.syncmaterial.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    private WatchService watchService;
    private final ExecutorService watchExecutor;
    private final ExecutorService parseExecutor;
    private final SchematicDatabase database;
    private final DatabaseQueryService queryService;
    private final LitematicaParser parser;
    private final Gson gson = new Gson();
    private final Set<String> processedHashes = ConcurrentHashMap.newKeySet();
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
        this.watchExecutor = Executors.newSingleThreadExecutor();
        this.parseExecutor = Executors.newSingleThreadExecutor();
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
                        processPlacementsJson();
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                SyncMaterial.LOGGER.info("原理图监控线程被中断");
                break;
            } catch (Exception e) {
                SyncMaterial.LOGGER.error("监控循环出错", e);
            }
        }
    }

    private void processPlacementsJson() {
        SyncMaterial.LOGGER.info("开始处理 placements.json...");
        
        // 等待文件写入完成
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            return;
        }

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

            SyncMaterial.LOGGER.info("placements 数组大小: {}", placements.size());

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
                SyncMaterial.LOGGER.info("发现 placement: id={}, hash={}, name={}", id, hash, displayName);

                // 检查数据库中是否已存在该原理图的材料记录
                boolean existsInDb = queryService.schematicExists(id);
                
                // 如果数据库中已有记录，跳过
                if (existsInDb) {
                    processedHashes.add(hash);  // 添加到已处理集合
                    continue;
                }

                // 查找实际的 litematic 文件
                Path litematicFile = findLitematicFile(hash);
                SyncMaterial.LOGGER.info("findLitematicFile 结果: {} for hash {}", litematicFile, hash);
                if (litematicFile != null) {
                    processNewSchematic(id, hash, displayName, ownerName, litematicFile);
                } else {
                    SyncMaterial.LOGGER.warn("未找到 hash={} 对应的 litematic 文件", hash);
                }
            }

            // 检测被删除的原理图并从数据库中删除
            SyncMaterial.LOGGER.info("processedHashes: {}, currentHashes: {}", processedHashes, currentHashes);
            
            Set<String> removedHashes = new HashSet<>(processedHashes);
            removedHashes.removeAll(currentHashes);
            
            SyncMaterial.LOGGER.info("removedHashes: {}", removedHashes);
            
            for (String removedHash : removedHashes) {
                // 根据 hash 查找对应的 schematic id
                try (var results = database.executeQuery(
                    "SELECT id FROM schematics WHERE file_path LIKE ?",
                    "%" + removedHash + "%"
                )) {
                    if (results.next()) {
                        String schematicId = results.getString("id");
                        database.executeUpdate("DELETE FROM material_entries WHERE schematic_id = ?", schematicId);
                        database.executeUpdate("DELETE FROM schematics WHERE id = ?", schematicId);
                        SyncMaterial.LOGGER.info("已删除原理图材料记录: {} (hash: {})", schematicId, removedHash);
                    }
                }
                processedHashes.remove(removedHash);
            }

        } catch (Exception e) {
            SyncMaterial.LOGGER.error("解析 placements.json 失败", e);
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
        SyncMaterial.LOGGER.info("processNewSchematic 被调用: id={}, hash={}", id, hash);
        
        // 先检查是否已存在，只有确认不存在时才添加到 processedHashes
        boolean exists;
        try {
            exists = queryService.schematicExists(id);
            SyncMaterial.LOGGER.info("schematicExists 结果: {}", exists);
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("schematicExists 调用失败: {} - {}", id, e.getMessage(), e);
            return;
        }
        
        if (exists) {
            SyncMaterial.LOGGER.info("原理图已存在，跳过: {}", id);
            processedHashes.add(hash);
            return;
        }

        SyncMaterial.LOGGER.info("开始解析并存储新原理图: {}", id);
        processedHashes.add(hash);

        SyncMaterial.LOGGER.info("准备提交异步任务...");
        parseExecutor.submit(() -> {
            SyncMaterial.LOGGER.info("异步任务开始执行: {}", id);
            try {
                SyncMaterial.LOGGER.info("检测到新原理图: {} (hash: {})", displayName, hash);
                var materials = parser.parseAsync(filePath.toString()).join();
                SyncMaterial.LOGGER.info("解析完成, 材料数量: {}", materials.size());

                database.executeUpdate(
                    "INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                    id,
                    displayName,
                    filePath.toString(),
                    owner
                );

                // 聚合相同材料
                Map<String, Long> aggregatedMaterials = new java.util.HashMap<>();
                for (var entry : materials) {
                    if (entry.getStack().isEmpty()) {
                        continue;
                    }
                    String itemId = net.minecraft.registry.Registries.ITEM.getId(entry.getStack().getItem()).toString();
                    long count = entry.getCountTotal();
                    aggregatedMaterials.merge(itemId, count, Long::sum);
                }

                // 存储聚合后的材料
                for (var entry : aggregatedMaterials.entrySet()) {
                    String itemId = entry.getKey();
                    long count = entry.getValue();
                    int countInt = (int) Math.min(count, Integer.MAX_VALUE);

                    database.executeUpdate(
                        "INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                        id, itemId, countInt
                    );
                }

                SyncMaterial.LOGGER.info("原理图处理完成: {} ({} 项材料)", displayName, aggregatedMaterials.size());
            } catch (Throwable t) {
                SyncMaterial.LOGGER.error("处理原理图失败: {} - Exception: {}, StackTrace: {}", 
                    displayName, t.getMessage(), t.getClass().getName());
                t.printStackTrace();
            }
        });
    }

    public void stop() {
        try {
            watchService.close();
            watchExecutor.shutdown();
            parseExecutor.shutdown();
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("停止监控失败", e);
        }
    }
}