package net.syncmaterial.syncmaterial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shared module boundary: pure Java 21, no Minecraft/Fabric/MaLiLib/
 * Litematica classes on the compile or test runtime classpath.
 */
class SharedIsolationTest {
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "net.minecraft",
            "net.fabricmc",
            "fi.dy.masa",
            "net.syncmaterial.syncmaterial.SyncMaterial");

    @Test
    void sharedSourcesDoNotReferencePlatformClasses() throws Exception {
        Path root = sourceRoot();
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertTrue(!sources.isEmpty(), "shared main sources must be present at " + root);
        for (Path source : sources) {
            String text = Files.readString(source);
            for (String marker : FORBIDDEN_MARKERS) {
                assertTrue(!text.contains(marker),
                        source + " must not reference platform marker " + marker);
            }
        }
    }

    @Test
    void platformClassesAreAbsentFromTheRuntimeClasspath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("net.minecraft.SharedConstants"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("net.fabricmc.loader.api.FabricLoader"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("fi.dy.masa.litematica.Litematica"));
    }

    @Test
    void runsOnJava21OrNewer() {
        assertTrue(Runtime.version().feature() >= 21,
                "shared module requires Java 21+, running " + Runtime.version());
    }

    private static Path sourceRoot() {
        for (Path candidate : List.of(Path.of("src/main/java"), Path.of("shared/src/main/java"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate shared main sources from " + Path.of(".").toAbsolutePath());
    }
}
