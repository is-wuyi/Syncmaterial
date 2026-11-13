package net.syncmaterial.syncmaterial.adapter.minecraft;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/** Takes online facts on the server thread; saved user-cache reading runs off the game thread. */
public final class MinecraftPlayerDirectory {
    private static final long MAX_CACHE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CACHE_ENTRIES = 65_536;

    private MinecraftPlayerDirectory() {}

    public static List<PlayerIdentity> online(MinecraftServer server) {
        //? if >=26.2 {
        if (server.getPlayerList() == null) return List.of();
        //?}
        List<PlayerIdentity> players = new ArrayList<>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.connection != null) {
                players.add(new PlayerIdentity(player.getUUID(), player.getName().getString()));
            }
        }
        return List.copyOf(players);
    }

    /** The saved cache has the same name/uuid JSON fields on all three supported nodes. */
    public static List<PlayerIdentity> cached(Path file) {
        try {
            return readCache(file);
        } catch (IOException | RuntimeException failure) {
            SyncMaterial.LOGGER.warn("读取已知玩家缓存失败，仍保留在线与数据库玩家目录", failure);
            return List.of();
        }
    }

    static List<PlayerIdentity> readCache(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        if (Files.size(file) > MAX_CACHE_BYTES) throw new IOException("Player cache exceeds size limit");
        List<PlayerIdentity> identities = new ArrayList<>();
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file))) {
            reader.beginArray();
            int entries = 0;
            while (reader.hasNext()) {
                if (++entries > MAX_CACHE_ENTRIES) throw new IOException("Player cache exceeds entry limit");
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue();
                    continue;
                }
                reader.beginObject();
                String name = null;
                String uuidText = null;
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    if ((field.equals("name") || field.equals("uuid")) && reader.peek() == JsonToken.STRING) {
                        String value = reader.nextString();
                        if (field.equals("name")) name = value;
                        else uuidText = value;
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (name == null || name.isBlank() || name.length() > TeamLimits.PLAYER_NAME_LENGTH) continue;
                UUID uuid = null;
                if (uuidText != null) {
                    try {
                        uuid = UUID.fromString(uuidText);
                    } catch (IllegalArgumentException ignored) {
                        // Keep a useful unresolved name, never invent an offline UUID.
                    }
                }
                identities.add(new PlayerIdentity(uuid, name));
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing player cache data");
        }
        return List.copyOf(identities);
    }
}
