package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.core.ProjectViewScope;

/**
 * Shared, Minecraft-free view of one protocol-5 S2C envelope as seen by the client
 * assembler. The platform payload record implements this interface; the assembler can be
 * tested and used without depending on Minecraft packet classes.
 */
public interface ProjectViewPart {
    /** True only for a {@code PROGRESS_PART} result. */
    boolean isProgressPart();

    ProjectViewScope scope();

    long viewId();

    long generation();

    /** Monotonic publication sequence of the snapshot this part belongs to. */
    long sequence();

    long catalogRevision();

    long inventoryRevision();

    String snapshotId();

    int partIndex();

    int partCount();

    int totalBytes();

    /** Must return a defensive copy; assemblers and GUIs never receive mutable payload bytes. */
    byte[] fragment();
}
