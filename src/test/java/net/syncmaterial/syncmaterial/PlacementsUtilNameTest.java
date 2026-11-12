package net.syncmaterial.syncmaterial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.syncmaterial.syncmaterial.server.PlacementsUtil;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerRegistry;

/** 名称解析：上下文缓存、世界目录/数据库回退与无唯一上下文歧义。 */
public class PlacementsUtilNameTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        // 上下文缓存由 mock 持有，测试间没有全局静态状态需要清理。
    }

    @Test
    void cacheHit_returnsImmediatelyWithoutFileAccess() {
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenReturn(Map.of("cached-id", "缓存的名字"));

        assertEquals("缓存的名字", PlacementsUtil.getDisplayName(owner, "cached-id"));
        verify(owner, never()).database();
    }

    @Test
    void missingFileAndDatabase_fallsBackToId() {
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenReturn(Map.of());
        when(owner.worldRoot()).thenReturn(tempDir);
        when(owner.database()).thenReturn(null);

        assertEquals("unknown-id", PlacementsUtil.getDisplayName(owner, "unknown-id"));
    }

    @Test
    void readsFromOwnerWorldAndPopulatesOwnerCache() throws Exception {
        Path syncmaticaDir = Files.createDirectories(tempDir.resolve("syncmatica"));
        Files.writeString(syncmaticaDir.resolve("placements.json"), """
            {"placements": [
              {"id": "file-id", "display_name": "文件里的名字"}
            ]}
            """);

        Map<String, String> ownerCache = new ConcurrentHashMap<>();
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.worldRoot()).thenReturn(tempDir);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenAnswer(inv -> Map.copyOf(ownerCache));
        doAnswer(inv -> {
            ownerCache.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(owner).setPlacementName(any(), any());

        assertEquals("文件里的名字", PlacementsUtil.getDisplayName(owner, "file-id"));
        assertEquals("文件里的名字", ownerCache.get("file-id"), "扫描结果应写入该上下文自己的缓存");

        // 删掉文件后仍能取到 → 上下文缓存生效，不再依赖文件
        Files.delete(syncmaticaDir.resolve("placements.json"));
        assertEquals("文件里的名字", PlacementsUtil.getDisplayName(owner, "file-id"));
    }

    @Test
    void idNotInFile_returnsIdAndCachesScannedEntries() throws Exception {
        Path syncmaticaDir = Files.createDirectories(tempDir.resolve("syncmatica"));
        Files.writeString(syncmaticaDir.resolve("placements.json"),
            "{\"placements\": [{\"id\": \"别的\", \"display_name\": \"名字\"}]}");

        Map<String, String> ownerCache = new ConcurrentHashMap<>();
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.worldRoot()).thenReturn(tempDir);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenAnswer(inv -> Map.copyOf(ownerCache));
        doAnswer(inv -> {
            ownerCache.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(owner).setPlacementName(any(), any());

        assertEquals("missing-id", PlacementsUtil.getDisplayName(owner, "missing-id"));
        assertEquals("名字", ownerCache.get("别的"), "全量遍历时途经的条目应被缓存");
    }

    @Test
    void databaseFallback_usedWhenWorldHasNoPlacement() throws Exception {
        SchematicDatabase database = mock(SchematicDatabase.class);
        SchematicDatabase.QueryResult resultSet = mock(SchematicDatabase.QueryResult.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("name")).thenReturn("数据库里的名字");
        when(database.executeQuery(anyString(), eq("db-id"))).thenReturn(resultSet);

        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenReturn(Map.of());
        when(owner.worldRoot()).thenReturn(tempDir);
        when(owner.database()).thenReturn(database);

        assertEquals("数据库里的名字", PlacementsUtil.getDisplayName(owner, "db-id"));
    }

    @Test
    void oldNoServerGetter_noUniqueContext_returnsIdWithoutWrites() {
        try (MockedStatic<LegacyServerRegistry> registry = mockStatic(LegacyServerRegistry.class)) {
            registry.when(LegacyServerRegistry::onlyOpenContext).thenReturn(null);

            assertEquals("ambiguous-id", PlacementsUtil.getDisplayName("ambiguous-id"));
        }
    }

    @Test
    void oldNoServerGetter_uniqueContextIsReadOnly() throws Exception {
        Path syncmaticaDir = Files.createDirectories(tempDir.resolve("syncmatica"));
        Files.writeString(syncmaticaDir.resolve("placements.json"),
            "{\"placements\": [{\"id\": \"file-id\", \"display_name\": \"文件里的名字\"}]}");

        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.placementNames()).thenReturn(Map.of());
        when(owner.worldRoot()).thenReturn(tempDir);

        try (MockedStatic<LegacyServerRegistry> registry = mockStatic(LegacyServerRegistry.class)) {
            registry.when(LegacyServerRegistry::onlyOpenContext).thenReturn(owner);

            assertEquals("文件里的名字", PlacementsUtil.getDisplayName("file-id"));
        }

        // 兼容读取不写 owner 缓存，更不写任何全局/客户端 config 状态
        verify(owner, never()).setPlacementName(any(), any());
    }

    @Test
    void retiredContext_returnsIdWithoutFileOrDatabaseRead() {
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(false);

        assertEquals("retired-id", PlacementsUtil.getDisplayName(owner, "retired-id"));
        verify(owner, never()).placementNames();
        verify(owner, never()).worldRoot();
        verify(owner, never()).database();
        verify(owner, never()).setPlacementName(any(), any());
    }

    @Test
    void nullOwner_returnsId() {
        assertEquals("id", PlacementsUtil.getDisplayName((LegacyServerContext) null, "id"));
    }
}
