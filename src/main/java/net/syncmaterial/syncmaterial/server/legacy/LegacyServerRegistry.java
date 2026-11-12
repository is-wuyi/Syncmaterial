package net.syncmaterial.syncmaterial.server.legacy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

public final class LegacyServerRegistry {
    private static final Map<MinecraftServer, LegacyServerContext> CONTEXTS = new ConcurrentHashMap<>();

    private LegacyServerRegistry() {}

    public static LegacyServerContext get(MinecraftServer server) {
        return server == null ? null : CONTEXTS.get(server);
    }

    public static void install(LegacyServerContext context) {
        context.install();
    }

    static void installOpen(LegacyServerContext context) {
        LegacyServerContext existing = CONTEXTS.putIfAbsent(context.server(), context);
        if (existing == context) return;
        if (existing != null) {
            context.close();
            throw new IllegalStateException("A legacy context is already installed for this server");
        }
        net.syncmaterial.syncmaterial.network.ProtocolHandshake.registerServer(context.server());
    }

    public static LegacyServerContext onlyOpenContext() {
        LegacyServerContext result = null;
        for (LegacyServerContext context : CONTEXTS.values()) {
            if (!context.isOpen()) continue;
            if (result != null) return null;
            result = context;
        }
        return result;
    }

    public static void stop(MinecraftServer server) {
        LegacyServerContext context = get(server);
        if (context != null) context.stop();
    }

    public static void close(MinecraftServer server) {
        LegacyServerContext context = get(server);
        if (context == null) return;
        context.stop();
        CONTEXTS.remove(server, context);
        context.close();
    }
}
