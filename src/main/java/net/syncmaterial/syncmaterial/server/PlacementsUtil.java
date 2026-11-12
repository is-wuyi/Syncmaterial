package net.syncmaterial.syncmaterial.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

public class PlacementsUtil
{
    /**
     * 从 placements.json 内容中解析 id → displayName 的映射。
     * 纯函数，不依赖文件系统，可直接 JUnit 测试。
     */
    public static Map<String, String> parsePlacementNames(String jsonContent) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonArray placements = root.getAsJsonArray("placements");
            if (placements != null) {
                for (var element : placements) {
                    JsonObject p = element.getAsJsonObject();
                    String id = p.get("id").getAsString();
                    String displayName = p.has("display_name") ? p.get("display_name").getAsString() : "unknown";
                    result.put(id, displayName);
                }
            }
        } catch (Exception e) {
            SyncMaterial.LOGGER.error("解析 placements JSON 失败", e);
        }
        return result;
    }

    /** 生产入口：owner 缓存 → owner 世界目录 → owner 数据库，不读写全局状态。 */
    public static String getDisplayName(LegacyServerContext owner, String schematicId)
    {
        return resolve(owner, schematicId, true);
    }

    /** 旧无上下文兼容读取：仅唯一打开上下文时解析，只读且不回填缓存。 */
    public static String getDisplayName(String schematicId)
    {
        LegacyServerContext owner = LegacyServerRegistry.onlyOpenContext();
        return resolve(owner, schematicId, false);
    }

    private static String resolve(LegacyServerContext owner, String schematicId, boolean cache)
    {
        if (schematicId == null || owner == null || !owner.isCurrent())
        {
            return schematicId;
        }

        String name = owner.placementNames().get(schematicId);
        if (name != null)
        {
            return name;
        }

        Map<String, String> scanned = readPlacementNames(owner.worldRoot());
        if (cache)
        {
            // 与旧行为一致：未命中时顺带缓存整份 placements 扫描结果，
            // 但缓存落在该上下文自己的 owner 名称表里。
            for (var entry : scanned.entrySet())
            {
                owner.setPlacementName(entry.getKey(), entry.getValue());
            }
        }

        String fromFile = scanned.get(schematicId);
        if (fromFile != null)
        {
            return fromFile;
        }

        String fromDatabase = lookupDatabaseName(owner, schematicId);
        return fromDatabase != null ? fromDatabase : schematicId;
    }

    private static Map<String, String> readPlacementNames(Path worldRoot)
    {
        if (worldRoot == null)
        {
            return Map.of();
        }

        Path syncmaticaDir = worldRoot.resolve("syncmatica");
        Path placementsFile = syncmaticaDir.resolve("placements.json");
        if (!Files.exists(placementsFile))
        {
            placementsFile = syncmaticaDir.resolve("placements.json.new");
        }
        if (!Files.exists(placementsFile))
        {
            return Map.of();
        }

        try
        {
            return parsePlacementNames(Files.readString(placementsFile));
        }
        catch (Exception e)
        {
            SyncMaterial.LOGGER.error("读取 placements.json 失败", e);
            return Map.of();
        }
    }

    private static String lookupDatabaseName(LegacyServerContext owner, String schematicId)
    {
        try
        {
            var database = owner.database();
            if (database == null)
            {
                return null;
            }
            try (var rs = database.executeQuery("SELECT name FROM schematics WHERE id = ?", schematicId))
            {
                if (rs.next())
                {
                    String name = rs.getString("name");
                    return name != null && !name.isBlank() ? name : null;
                }
            }
        }
        catch (java.sql.SQLException ignored)
        {
            // 名称仅用于展示；库不可用或没有记录时回退到原理图 id
        }
        return null;
    }
}
