package net.syncmaterial.syncmaterial.server.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public final class ServerRuntimeRegistry {
    private static final Map<MinecraftServer, ServerRuntime> RUNTIMES = new ConcurrentHashMap<>();

    private ServerRuntimeRegistry() {}

    public static ServerRuntime get(MinecraftServer server) {
        return RUNTIMES.get(server);
    }

    public static void install(MinecraftServer server, ServerRuntime runtime) {
        if (RUNTIMES.putIfAbsent(server, runtime) != null) {
            runtime.close();
            throw new IllegalStateException("A runtime is already installed for this server");
        }
    }

    public static void close(MinecraftServer server) {
        ServerRuntime runtime = RUNTIMES.remove(server);
        if (runtime != null) runtime.close();
    }
}
