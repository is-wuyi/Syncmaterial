package net.syncmaterial.syncmaterial.server.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.SchematicMetadata;
import net.syncmaterial.syncmaterial.server.runtime.ServerRuntime;
import net.syncmaterial.syncmaterial.server.runtime.LifecycleHook;
//? if >=26.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

public final class SyncmaticaDiscoveryAdapter implements Consumer<Object>, LifecycleHook {
    private ServerRuntime runtime;
    private Object manager;
    private Path root;

    @Override
    public synchronized void attach(ServerRuntime runtime) {
        if (this.runtime != null) throw new IllegalStateException("Discovery hook is already attached");
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    @Override
    public void tick() {
        register();
    }

    public synchronized void register() {
        if (manager != null || runtime == null || !runtime.isOpen() || !FabricLoader.getInstance().isModLoaded("syncmatica")) return;
        try {
            Class<?> entrypoint = Class.forName("ch.endte.syncmatica.Syncmatica");
            Class<?> contextType = Class.forName("ch.endte.syncmatica.Context");
            Object key = entrypoint.getField("SERVER_CONTEXT").get(null);
            //? if >=26.2 {
            Object context = entrypoint.getMethod("getContext", Identifier.class).invoke(null, key);
            //?} else {
            /*Object context = entrypoint.getMethod("getContext", ResourceLocation.class).invoke(null, key);
            *///?}
            if (context == null || !Boolean.TRUE.equals(contextType.getMethod("isServer").invoke(context))) return;
            Object candidate = contextType.getMethod("getSyncmaticManager").invoke(context);
            if (candidate == null) return;
            root = ((Path) contextType.getMethod("getLitematicFolder").invoke(context)).toAbsolutePath().normalize();
            manager = candidate;
            candidate.getClass().getMethod("addServerPlacementConsumer", Consumer.class).invoke(candidate, this);
            SyncMaterial.LOGGER.info("实验后端已接入 Syncmatica 服务端发现事件");
        } catch (ReflectiveOperationException error) {
            manager = null;
            root = null;
            SyncMaterial.LOGGER.debug("Syncmatica 服务端上下文尚未就绪，文件发现继续工作", error);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (manager != null) {
                manager.getClass().getMethod("removeServerPlacementConsumer", Consumer.class).invoke(manager, this);
            }
        } catch (ReflectiveOperationException error) {
            SyncMaterial.LOGGER.warn("停止 Syncmatica 发现监听失败", error);
        } finally {
            manager = null;
            root = null;
            runtime = null;
        }
    }

    @Override
    public void accept(Object placement) {
        Object currentManager;
        Path currentRoot;
        ServerRuntime currentRuntime;
        synchronized (this) {
            currentManager = manager;
            currentRoot = root;
            currentRuntime = runtime;
        }
        if (placement == null || currentManager == null || currentRoot == null || currentRuntime == null || !currentRuntime.isOpen()) return;
        try {
            Class<?> type = placement.getClass();
            Object placementId = type.getMethod("getId").invoke(placement);
            UUID id = placementId instanceof UUID value ? value : UUID.fromString(placementId.toString());
            Object active = currentManager.getClass().getMethod("getPlacement", UUID.class).invoke(currentManager, id);
            if (active == null) {
                currentRuntime.removeProject(id.toString());
                return;
            }
            String hash = type.getMethod("getHash").invoke(placement).toString();
            UUID.fromString(hash);
            Path path = currentRoot.resolve(hash + ".litematic");
            if (!Files.isRegularFile(path)) return;
            Path realFile = path.toRealPath();
            if (!realFile.startsWith(currentRoot.toRealPath())) throw new IllegalArgumentException("Schematic file is outside its root");
            String name = hash + ".litematic";
            try {
                Object value = type.getMethod("getName").invoke(placement);
                if (value != null) name = value.toString();
            } catch (ReflectiveOperationException ignored) {
                // Older Syncmatica versions display the uploaded file name.
            }
            Object owner = type.getMethod("getOwner").invoke(placement);
            String ownerName = owner == null ? "unknown" : owner.getClass().getMethod("getName").invoke(owner).toString();
            var metadata = new SchematicMetadata(id.toString(), name, realFile, hash, new PlayerIdentity(null, ownerName));
            currentRuntime.ingest(metadata).whenComplete((ignored, error) -> {
                if (error != null) SyncMaterial.LOGGER.warn("Syncmatica 发现的原理图导入失败: {}", id, error);
            });
        } catch (Exception error) {
            SyncMaterial.LOGGER.warn("读取 Syncmatica 原理图元数据失败", error);
        }
    }
}
