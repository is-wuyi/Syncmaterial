package net.syncmaterial.syncmaterial.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.server.legacy.LegacySchematicIngest;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/**
 * Syncmatica 上传事件监听器（只负责旧后端）。
 *
 * <p>事件线程上只做一次同步反射，捕获不可变元数据后交给
 * {@link LegacySchematicIngest}；不再自行创建 common-pool 任务，也不再查找全局
 * manager/database。是否真实存在、是否仍由 Syncmatica manager 管理由服务端发现
 * 适配器在调用 {@link #accept(Object)} 前判断；本类不注册/注销 consumer。</p>
 */
public final class SchematicUploadListener implements Consumer<Object>, AutoCloseable {

    private final LegacyServerContext owner;
    private final LegacySchematicIngest ingest;
    private final Set<LegacySchematicIngest.Job> jobs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SchematicUploadListener(LegacyServerContext owner) {
        this(owner, owner.ingest());
    }

    SchematicUploadListener(LegacyServerContext owner, LegacySchematicIngest ingest) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    @Override
    public void accept(Object placement) {
        onSchematicUploaded(placement);
    }

    /**
     * 处理一次上传事件。方法本身只做同步元数据捕获和排队，解析与入库在
     * {@link LegacySchematicIngest} 的串行线程上完成。
     */
    public void onSchematicUploaded(Object placement) {
        if (placement == null || closed.get() || !owner.isCurrent()) return;

        LegacySchematicIngest.Metadata metadata;
        try {
            metadata = capture(placement);
        } catch (ReflectiveOperationException | RuntimeException error) {
            SyncMaterial.LOGGER.error("读取 Syncmatica 上传元数据失败", error);
            return;
        }
        if (metadata == null) return;

        try {
            if (!Files.isRegularFile(metadata.file())) {
                SyncMaterial.LOGGER.error("原理图文件不存在: {}", metadata.file());
                return;
            }
            if (closed.get() || !owner.isCurrent()) return;

            SyncMaterial.LOGGER.info("检测到原理图上传: {} (文件: {}, 上传者: {})",
                    metadata.id(), metadata.file(), metadata.uploader());
            owner.setPlacementName(metadata.id(), metadata.name());
            if (closed.get() || !owner.isCurrent()) return;

            LegacySchematicIngest.Job job = ingest.submit(metadata, this);
            if (job == null) return;
            if (closed.get()) {
                job.release(this);
                return;
            }
            jobs.add(job);
            if (closed.get()) {
                job.release(this);
                jobs.remove(job);
                return;
            }
            job.completion().whenComplete((ignored, error) -> jobs.remove(job));
        } catch (RuntimeException error) {
            SyncMaterial.LOGGER.error("排队原理图上传失败: {}", metadata.id(), error);
        }
    }

    private LegacySchematicIngest.Metadata capture(Object placement)
            throws ReflectiveOperationException {
        Class<?> type = placement.getClass();
        Object rawId = type.getMethod("getId").invoke(placement);
        if (rawId == null) return null;
        String id = rawId.toString();
        if (id.isEmpty()) return null;

        Object rawFile = type.getMethod("getFile").invoke(placement);
        if (!(rawFile instanceof Path file)) {
            throw new IllegalArgumentException("placement.getFile() 未返回 Path");
        }

        String name = readName(type, placement, file);
        String uploader = readUploader(type, placement);
        String hash = readHash(type, placement);
        return new LegacySchematicIngest.Metadata(
                id, name, file.toAbsolutePath().normalize(), hash, uploader,
                LegacySchematicIngest.Source.UPLOAD, false);
    }

    private static String readName(Class<?> type, Object placement, Path file) {
        try {
            Object rawName = type.getMethod("getName").invoke(placement);
            if (rawName != null && !rawName.toString().isEmpty()) return rawName.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 老版本/测试对象可能没有 getName：退化为文件名。
        }
        Path fileName = file.getFileName();
        return fileName == null ? "unknown" : fileName.toString();
    }

    private static String readUploader(Class<?> type, Object placement) {
        try {
            Object owner = type.getMethod("getOwner").invoke(placement);
            if (owner == null) return "unknown";
            Object name = owner.getClass().getMethod("getName").invoke(owner);
            return name == null ? "unknown" : name.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "unknown";
        }
    }

    private static String readHash(Class<?> type, Object placement) {
        try {
            Object hash = type.getMethod("getHash").invoke(placement);
            return hash == null ? "" : hash.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 与旧行为一致：没有 getHash 的实现留空，文件监听会按空 hash 语义跳过更新检测。
            return "";
        }
    }

    /**
     * 取消本监听器提交的导入；不等待解析线程。主线程发现适配器负责注销 consumer。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (LegacySchematicIngest.Job job : jobs) job.release(this);
        jobs.clear();
    }
}
