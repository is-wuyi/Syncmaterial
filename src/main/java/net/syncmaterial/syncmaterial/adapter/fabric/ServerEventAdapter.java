package net.syncmaterial.syncmaterial.adapter.fabric;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerEventAdapter {
    private ServerEventAdapter() {}

    public static void onChunkLoaded(BiConsumer<ServerLevel, LevelChunk> listener) {
        //? if >=26.2 {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) -> listener.accept(level, chunk));
        //?} else {
        /*ServerChunkEvents.CHUNK_LOAD.register(listener::accept);
        *///?}
    }

    public static void onChunkUnloaded(BiConsumer<ServerLevel, LevelChunk> listener) {
        //? if >=26.2 {
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> listener.accept(level, chunk));
        //?} else {
        /*ServerChunkEvents.CHUNK_UNLOAD.register(listener::accept);
        *///?}
    }
}
