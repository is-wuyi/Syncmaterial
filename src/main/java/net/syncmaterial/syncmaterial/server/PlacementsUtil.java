package net.syncmaterial.syncmaterial.server;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.syncmaterial.syncmaterial.SyncMaterial;

public class PlacementsUtil
{
    public static String getDisplayName(String schematicId)
    {
        String name = SchematicFolderWatcher.placementNames.get(schematicId);
        if (name != null)
        {
            return name;
        }

        // 尝试使用 Fabric Loader 获取配置目录
        Path configDir;
        try
        {
            configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("syncmatica");
        }
        catch (Exception e)
        {
            // 如果 Fabric API 不可用，使用默认路径
            configDir = Path.of(System.getProperty("user.home"), ".minecraft", "config", "syncmatica");
        }

        Path placementsFile = configDir.resolve("placements.json");

        if (!Files.exists(placementsFile))
        {
            placementsFile = configDir.resolve("placements.json.new");
        }

        if (!Files.exists(placementsFile))
        {
            return schematicId;
        }

        try
        {
            JsonArray placements = JsonParser.parseReader(Files.newBufferedReader(placementsFile))
                    .getAsJsonObject().getAsJsonArray("placements");

            if (placements != null)
            {
                for (var element : placements)
                {
                    JsonObject placement = element.getAsJsonObject();
                    String id = placement.get("id").getAsString();
                    String displayName = placement.get("display_name").getAsString();
                    SchematicFolderWatcher.placementNames.put(id, displayName);

                    if (id.equals(schematicId))
                    {
                        return displayName;
                    }
                }
            }
        }
        catch (Exception e)
        {
            SyncMaterial.LOGGER.error("读取 placements.json 失败", e);
        }

        return schematicId;
    }
}