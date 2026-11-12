package net.syncmaterial.syncmaterial.server.legacy;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.SchematicUploadListener;

public final class LegacySyncmaticaDiscovery implements AutoCloseable {
    private final LegacyServerContext owner;
    private final ContextSource source;
    private volatile Object context;
    private volatile Object manager;
    private Consumer<Object> consumer;
    private SchematicUploadListener listener;
    private volatile boolean closed;

    public LegacySyncmaticaDiscovery(LegacyServerContext owner) {
        this(owner, LegacySyncmaticaDiscovery::serverContext);
    }

    LegacySyncmaticaDiscovery(LegacyServerContext owner, ContextSource source) {
        this.owner = owner;
        this.source = source;
    }

    public synchronized void tick() {
        if (closed || !owner.isCurrent()) return;
        try {
            Object candidate = source.get();
            if (candidate == context) return;
            detach();
            if (candidate == null || !Boolean.TRUE.equals(candidate.getClass().getMethod("isServer").invoke(candidate))) return;
            Object config = candidate.getClass().getMethod("getConfigFolder").invoke(candidate);
            if (!(config instanceof Path path)
                    || !path.toAbsolutePath().normalize().equals(owner.worldRoot().resolve("syncmatica"))) return;
            Object target = candidate.getClass().getMethod("getSyncmaticManager").invoke(candidate);
            if (target == null) return;
            SchematicUploadListener upload = new SchematicUploadListener(owner);
            Consumer<Object> callback = placement -> accept(candidate, target, upload, placement);
            try {
                target.getClass().getMethod("addServerPlacementConsumer", Consumer.class).invoke(target, callback);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                upload.close();
                throw failure;
            }
            context = candidate;
            manager = target;
            listener = upload;
            consumer = callback;
            SyncMaterial.LOGGER.info("旧后端已接入所属世界的 Syncmatica 服务端发现事件");
        } catch (ReflectiveOperationException error) {
            SyncMaterial.LOGGER.debug("Syncmatica 服务端上下文尚未就绪，文件发现继续工作", error);
        }
    }

    private void accept(Object origin, Object originManager, SchematicUploadListener upload, Object placement) {
        if (closed || !owner.isCurrent() || context != origin || manager != originManager || placement == null) return;
        try {
            if (source.get() != origin) return;
            Object rawId = placement.getClass().getMethod("getId").invoke(placement);
            UUID id = rawId instanceof UUID value ? value : UUID.fromString(rawId.toString());
            Object active = originManager.getClass().getMethod("getPlacement", UUID.class).invoke(originManager, id);
            if (active == null) {
                owner.ingest().remove(id.toString());
            } else {
                upload.accept(active);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            SyncMaterial.LOGGER.warn("读取 Syncmatica 服务端 placement 失败", error);
        }
    }

    private void detach() {
        Object previousManager = manager;
        Consumer<Object> previousConsumer = consumer;
        SchematicUploadListener previousListener = listener;
        context = null;
        manager = null;
        consumer = null;
        listener = null;
        if (previousListener != null) previousListener.close();
        if (previousManager == null || previousConsumer == null) return;
        try {
            previousManager.getClass().getMethod("removeServerPlacementConsumer", Consumer.class)
                    .invoke(previousManager, previousConsumer);
        } catch (ReflectiveOperationException error) {
            SyncMaterial.LOGGER.warn("注销 Syncmatica 服务端发现监听失败", error);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        detach();
    }

    private static Object serverContext() throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("syncmatica")) return null;
        Class<?> entrypoint = Class.forName("ch.endte.syncmatica.Syncmatica");
        Object key = entrypoint.getField("SERVER_CONTEXT").get(null);
        return entrypoint.getMethod("getContext", key.getClass()).invoke(null, key);
    }

    @FunctionalInterface
    interface ContextSource {
        Object get() throws ReflectiveOperationException;
    }
}
