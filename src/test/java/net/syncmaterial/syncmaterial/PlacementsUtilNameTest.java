package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.fabricmc.loader.api.FabricLoader;
import net.syncmaterial.syncmaterial.server.PlacementsUtil;
import net.syncmaterial.syncmaterial.server.SchematicFolderWatcher;

/**
 * 原理图显示名称解析测试：静态缓存三态（命中/文件穿透/不可用回退）。
 */
public class PlacementsUtilNameTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SchematicFolderWatcher.placementNames.clear();
    }

    @Test
    void cacheHit_returnsImmediatelyWithoutFileAccess() {
        SchematicFolderWatcher.placementNames.put("cached-id", "缓存的名字");

        assertEquals("缓存的名字", PlacementsUtil.getDisplayName("cached-id"));
    }

    @Test
    void fabricUnavailable_missingFile_fallsBackToId() {
        try (MockedStatic<FabricLoader> loaderMock = mockStatic(FabricLoader.class)) {
            // FabricLoader 不可用（如部分测试环境）→ 回退到默认路径 → 文件不存在 → 返回 ID
            loaderMock.when(FabricLoader::getInstance).thenThrow(new RuntimeException("loader unavailable"));

            assertEquals("unknown-id", PlacementsUtil.getDisplayName("unknown-id"));
        }
    }

    @Test
    void readsFromFileAndPopulatesCache() throws Exception {
        Path syncmaticaDir = Files.createDirectories(tempDir.resolve("syncmatica"));
        Files.writeString(syncmaticaDir.resolve("placements.json"), """
            {"placements": [
              {"id": "file-id", "display_name": "文件里的名字"}
            ]}
            """);

        try (MockedStatic<FabricLoader> loaderMock = mockStatic(FabricLoader.class)) {
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.getConfigDir()).thenReturn(tempDir);
            loaderMock.when(FabricLoader::getInstance).thenReturn(loader);

            assertEquals("文件里的名字", PlacementsUtil.getDisplayName("file-id"));
        }

        // 删掉文件后仍能取到 → 证明已缓存，不再依赖文件
        Files.delete(syncmaticaDir.resolve("placements.json"));
        assertEquals("文件里的名字", PlacementsUtil.getDisplayName("file-id"));
    }

    @Test
    void idNotInFile_returnsIdAndCachesScannedEntries() throws Exception {
        Path syncmaticaDir = Files.createDirectories(tempDir.resolve("syncmatica"));
        Files.writeString(syncmaticaDir.resolve("placements.json"),
            "{\"placements\": [{\"id\": \"别的\", \"display_name\": \"名字\"}]}");

        try (MockedStatic<FabricLoader> loaderMock = mockStatic(FabricLoader.class)) {
            FabricLoader loader = mock(FabricLoader.class);
            when(loader.getConfigDir()).thenReturn(tempDir);
            loaderMock.when(FabricLoader::getInstance).thenReturn(loader);

            // 未命中会遍历整个文件 → 途经条目顺带进入缓存
            assertEquals("missing-id", PlacementsUtil.getDisplayName("missing-id"));
        }
        assertEquals("名字", SchematicFolderWatcher.placementNames.get("别的"),
            "全量遍历时途经的条目应被缓存");
    }
}
